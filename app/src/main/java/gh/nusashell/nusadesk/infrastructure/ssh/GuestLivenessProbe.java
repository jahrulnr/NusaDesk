package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * One bounded guest liveness check over the app's own pinned SSH wiring
 * (ADR-0062).
 *
 * <p>Each call opens one short SSH session to the fixed loopback endpoint —
 * the same {@link LocalSshSessionFactory} configuration, credential, and
 * pinned host key the terminal uses — and runs {@link #PROBE_COMMAND} as an
 * exec command. The command is deliberately one that must spawn a child in
 * the guest: a session whose process table wedged (the {@code link2symlink}
 * fork hang) still answers SSH heartbeats and keeps the daemon alive, so the
 * only honest liveness signal is "can the guest still run something".</p>
 *
 * <p>Success means the channel opened and the remote side closed it cleanly
 * inside the caller's bound. Anything else — connect failure, auth failure,
 * a channel that never opens, or a command that never returns — is a miss.
 * A fresh bridge backs every probe, so a wedged probe can never hold a
 * channel the next tick needs.</p>
 *
 * <p>{@link #probeOnce} blocks; callers run it on a background executor,
 * never on the service's main thread. While healthy it is silent: the
 * listener ignores output and no state is logged per tick.</p>
 */
public final class GuestLivenessProbe {

    /** Command run in the guest. {@code true} forks and exits, cheap and silent. */
    private static final String PROBE_COMMAND = "/bin/true";

    /** One try per probe: the outer cadence is the retry loop, not the bridge. */
    private static final SshReconnectPolicy SINGLE_ATTEMPT = new SshReconnectPolicy(1, 0L, 0L);

    private static final int PROBE_COLS = 80;
    private static final int PROBE_ROWS = 24;

    private final SshCredentialProvider credentials;
    private final HostKeyTrustStore trustStore;
    private final LongSupplier clock;

    /**
     * @param credentials vault-backed credential provider (as the terminal uses)
     * @param trustStore  pinned host-key trust store
     * @param clock       source of {@code now} for the bridge's trust verifier
     */
    public GuestLivenessProbe(
            SshCredentialProvider credentials,
            HostKeyTrustStore trustStore,
            LongSupplier clock) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        if (trustStore == null) {
            throw new IllegalArgumentException("trustStore must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.credentials = credentials;
        this.trustStore = trustStore;
        this.clock = clock;
    }

    /**
     * Run one probe and wait up to {@code timeoutMillis} for it to finish.
     *
     * <p>Must be called from a background thread. Returns {@code true} only
     * when the probe command completed within the bound; on timeout the
     * session is torn down and the result is a miss.</p>
     */
    public boolean probeOnce(long timeoutMillis) {
        SshSessionConfig config = LocalSshSessionFactory.create(PROBE_COLS, PROBE_ROWS);
        SshClientBridge bridge = new SshClientBridge(
                credentials, trustStore, LocalSshSessionFactory.pinnedHostKeyOnly(),
                SINGLE_ATTEMPT, clock);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<SshSessionState> lastState = new AtomicReference<>(SshSessionState.CONNECTING);
        bridge.start(config, PROBE_COMMAND, new SshSessionListener() {
            @Override
            public void onState(SshSessionState state, String detail) {
                lastState.set(state);
            }

            @Override
            public void onStdout(byte[] data, int len) {
            }

            @Override
            public void onStderr(byte[] data, int len) {
            }

            @Override
            public void onClosed(String reason) {
                done.countDown();
            }
        });
        boolean completed;
        try {
            completed = done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        boolean alive = completed && lastState.get() == SshSessionState.CLOSED;
        bridge.close();
        return alive;
    }
}
