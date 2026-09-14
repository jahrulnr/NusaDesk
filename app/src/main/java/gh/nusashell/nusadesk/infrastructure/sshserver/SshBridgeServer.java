package gh.nusashell.nusadesk.infrastructure.sshserver;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Map;
import java.util.function.LongSupplier;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;

/**
 * Real local SSH bridge server: an Apache MINA SSHD server bound to loopback
 * only, requiring an opaque per-install credential, presenting a persisted
 * host key, and routing authenticated shell channels to the fixed curated guest
 * shell through an injected {@link GuestShellLauncher}.
 *
 * <p>This is the <em>server-side bridge</em>. It does not assume Ubuntu Base
 * ships an SSH server (ADR-0007); the Android host owns the SSH endpoint and
 * the guest only provides the shell via the PRoot adapter. The bridge never
 * runs arbitrary host {@link ProcessBuilder} command strings: SSH exec channels
 * (which carry an arbitrary client command) are refused, and only the fixed
 * curated shell is reachable through {@link GuestShellLauncher}.</p>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #start(SshBridgeStartRequest)} loads (or generates and persists)
 *       the host key, fetches the opaque credential (refusing to start if it is
 *       absent &mdash; never no-auth), binds {@code 127.0.0.1:0}, runs the
 *       health probe, and only then publishes a machine-readable
 *       {@link ReadinessFrame} with the concrete loopback endpoint.</li>
 *   <li>{@link #stop(long)} tears down the server and active shells within a
 *       bounded grace and zeroes the cached credential.</li>
 *   <li>{@link #endpoint()} exposes the published loopback endpoint, or
 *       {@code null} when not running.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>Loopback only ({@code 127.0.0.1}, port {@code 0}); the bound endpoint is
 *       a {@link RuntimePort}, which rejects non-loopback hosts.</li>
 *   <li>Never no-auth: a missing/empty credential fails start; every connection
 *       must present a token compared in constant time.</li>
 *   <li>Host key loaded/saved only through {@link SshServerHostKeyStore}; a
 *       fresh key is generated on first start and reused thereafter.</li>
 *   <li>Exec channels are refused; only the curated shell channel is routed to
 *       the launcher.</li>
 *   <li>No secret is logged: the credential is never placed in a log, error
 *       message, or readiness frame.</li>
 * </ul>
 *
 * <p>The class carries no Android imports so it is unit-testable on a plain
 * JVM with a real in-process MINA client and a fake guest shell launcher.</p>
 */
public final class SshBridgeServer {

    /** Loopback host the bridge binds. Reachability-only, not authentication. */
    public static final String BIND_HOST = "127.0.0.1";

    /** Bind port zero so the OS assigns an ephemeral port; the bound port is read after start. */
    public static final int BIND_PORT = 0;

    /** RSA host key size generated on first start. */
    static final int HOST_KEY_BITS = 2048;

    private final SshBridgeCredential credential;
    private final SshServerHostKeyStore hostKeyStore;
    private final GuestShellLauncher guestShellLauncher;
    private final SshBridgeHealthProbe healthProbe;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private SshServer server;
    private char[] token;
    private SshBridgeState state = SshBridgeState.STOPPED;
    private RuntimePort endpoint;
    private ReadinessFrame lastFrame;
    private long shellDestroyGraceMillis;

    /**
     * @param credential       source of the opaque per-install token (never no-auth)
     * @param hostKeyStore     persistence for the server host key
     * @param guestShellLauncher port that runs the fixed curated guest shell
     * @param healthProbe      confirms the bound port is accepting connections
     * @param clock            source of {@code now} in epoch millis for the readiness frame
     */
    public SshBridgeServer(
            SshBridgeCredential credential,
            SshServerHostKeyStore hostKeyStore,
            GuestShellLauncher guestShellLauncher,
            SshBridgeHealthProbe healthProbe,
            LongSupplier clock) {
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        if (hostKeyStore == null) {
            throw new IllegalArgumentException("hostKeyStore must not be null");
        }
        if (guestShellLauncher == null) {
            throw new IllegalArgumentException("guestShellLauncher must not be null");
        }
        if (healthProbe == null) {
            throw new IllegalArgumentException("healthProbe must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.credential = credential;
        this.hostKeyStore = hostKeyStore;
        this.guestShellLauncher = guestShellLauncher;
        this.healthProbe = healthProbe;
        this.clock = clock;
    }

    /**
     * Start the bridge: bind loopback, verify health, publish readiness.
     *
     * @param request start parameters; never {@code null}
     * @return the result, carrying the readiness frame on success
     */
    public SshBridgeStartResult start(SshBridgeStartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        synchronized (lock) {
            if (state == SshBridgeState.RUNNING) {
                if (lastFrame != null
                        && lastFrame.getSessionId().equals(request.getSessionId())) {
                    // Idempotent re-delivery for the same session only.
                    return new SshBridgeStartResult(
                            SshBridgeState.RUNNING, "already running", lastFrame);
                }
                // The bound bridge belongs to an earlier session. Serving its
                // stale frame would be silently dropped by the host (session id
                // mismatch) while the port stayed bound — a wedged state. A new
                // session gets a fresh bind and a fresh readiness frame.
                stopServerQuietly(server);
                server = null;
                zeroToken();
                endpoint = null;
                lastFrame = null;
            }
            if (state == SshBridgeState.STARTING) {
                return new SshBridgeStartResult(SshBridgeState.STARTING, "already starting", null);
            }

            state = SshBridgeState.STARTING;
            try {
                char[] t = credential.token();
                if (t == null || t.length == 0) {
                    state = SshBridgeState.FAILED;
                    return new SshBridgeStartResult(SshBridgeState.FAILED, "credential is absent", null);
                }
                this.token = t;
                this.shellDestroyGraceMillis = request.getShellDestroyGraceMillis();

                KeyPair keyPair = hostKeyStore.load();
                if (keyPair == null) {
                    keyPair = generateHostKey();
                    hostKeyStore.save(keyPair);
                }

                SshServer s = SshServer.setUpDefaultServer();
                s.setHost(BIND_HOST);
                s.setPort(BIND_PORT);
                s.setKeyPairProvider(KeyPairProvider.wrap(keyPair));
                s.setPasswordAuthenticator(new TokenAuthenticator(t));
                // Explicitly reject publickey auth so the only accepted auth is the
                // opaque token. MINA's default publickey authenticator would otherwise
                // accept any key, which would defeat the never-no-auth requirement.
                s.setPublickeyAuthenticator(RejectAllPublickeyAuthenticator.INSTANCE);
                s.setShellFactory(channel -> new GuestShellCommand());
                s.setCommandFactory((channel, command) -> new RefusedExecCommand());
                s.start();
                this.server = s;

                int port = s.getPort();
                if (port <= 0) {
                    stopServerQuietly(s);
                    this.server = null;
                    zeroToken();
                    state = SshBridgeState.FAILED;
                    return new SshBridgeStartResult(SshBridgeState.FAILED, "bind did not produce a port", null);
                }

                boolean healthy = healthProbe.isHealthy(BIND_HOST, port, request.getHealthTimeoutMillis());
                if (!healthy) {
                    stopServerQuietly(s);
                    this.server = null;
                    zeroToken();
                    state = SshBridgeState.FAILED;
                    return new SshBridgeStartResult(SshBridgeState.FAILED, "health check failed", null);
                }

                RuntimePort ep = new RuntimePort(BIND_HOST, port);
                ReadinessFrame frame = new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        request.getAppId(),
                        request.getAppVersion(),
                        request.getSessionId(),
                        ep,
                        ReadinessHealth.HEALTHY,
                        clock.getAsLong());
                this.endpoint = ep;
                this.lastFrame = frame;
                this.state = SshBridgeState.RUNNING;
                return new SshBridgeStartResult(SshBridgeState.RUNNING, "ready", frame);
            } catch (Exception e) {
                if (server != null) {
                    stopServerQuietly(server);
                    server = null;
                }
                zeroToken();
                state = SshBridgeState.FAILED;
                return new SshBridgeStartResult(SshBridgeState.FAILED, describe(e), null);
            }
        }
    }

    /**
     * Stop the bridge and tear down active shells. Bounded: the MINA server is
     * stopped immediately and active guest shells are destroyed within the
     * configured grace. Idempotent.
     *
     * @param timeoutMillis reserved for future graceful-stop bounding; the
     *                      current implementation uses MINA's immediate stop
     */
    public void stop(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        synchronized (lock) {
            if (state == SshBridgeState.STOPPED || state == SshBridgeState.FAILED) {
                return;
            }
            state = SshBridgeState.STOPPING;
            SshServer s = server;
            if (s != null) {
                stopServerQuietly(s);
                server = null;
            }
            zeroToken();
            endpoint = null;
            lastFrame = null;
            state = SshBridgeState.STOPPED;
        }
    }

    /** Current bridge state. */
    public SshBridgeState state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Whether the bridge is bound, healthy, and accepting connections. */
    public boolean isRunning() {
        synchronized (lock) {
            return state == SshBridgeState.RUNNING;
        }
    }

    /**
     * @return the published loopback endpoint, or {@code null} when not running.
     */
    public RuntimePort endpoint() {
        synchronized (lock) {
            return endpoint;
        }
    }

    private void stopServerQuietly(SshServer s) {
        try {
            s.stop(true);
        } catch (Exception ignored) {
            // Best-effort teardown during stop.
        }
    }

    private KeyPair generateHostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(HOST_KEY_BITS);
        return generator.generateKeyPair();
    }

    private void zeroToken() {
        char[] t = token;
        token = null;
        if (t != null) {
            Arrays.fill(t, '\0');
        }
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    /**
     * Compares a client-supplied password to the opaque token in constant time.
     * Never logs either value.
     */
    private static boolean constantTimeEquals(String a, char[] b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < b.length; i++) {
            diff |= a.charAt(i) ^ b[i];
        }
        return diff == 0;
    }

    private static PtySize ptySizeFrom(Environment env) {
        Map<String, String> vars = env.getEnv();
        int cols = parseInt(vars.get(Environment.ENV_COLUMNS), PtySize.DEFAULT_COLS);
        int rows = parseInt(vars.get(Environment.ENV_LINES), PtySize.DEFAULT_ROWS);
        try {
            return new PtySize(cols, rows);
        } catch (IllegalArgumentException e) {
            return PtySize.defaults();
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Password authenticator comparing the client password to the opaque token. */
    private final class TokenAuthenticator implements PasswordAuthenticator {
        private final char[] expected;

        TokenAuthenticator(char[] expected) {
            this.expected = expected;
        }

        @Override
        public boolean authenticate(String username, String password, ServerSession session) {
            return constantTimeEquals(password, expected);
        }
    }

    /**
     * MINA {@link Command} that bridges an authenticated shell channel to the
     * fixed curated guest shell via {@link GuestShellLauncher}. Runs the guest
     * shell on a daemon thread and signals exit through the callback. On
     * destroy it tears the guest shell down within the configured grace.
     */
    private final class GuestShellCommand implements Command {
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private volatile GuestShellHandle handle;
        private Thread runner;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            PtySize ptySize = ptySizeFrom(env);
            runner = new Thread(() -> {
                try {
                    GuestShellHandle h = guestShellLauncher.launchShell(in, out, err, ptySize);
                    handle = h;
                    int code = h.waitFor();
                    java.util.logging.Logger.getLogger("SshBridgeServer").info(
                            "guest shell exited code=" + code);
                    ExitCallback cb = callback;
                    if (cb != null) {
                        cb.onExit(code);
                    }
                } catch (GuestShellLaunchException e) {
                    ExitCallback cb = callback;
                    if (cb != null) {
                        cb.onExit(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    ExitCallback cb = callback;
                    if (cb != null) {
                        cb.onExit(1);
                    }
                } catch (Exception e) {
                    ExitCallback cb = callback;
                    if (cb != null) {
                        cb.onExit(1);
                    }
                }
            }, "ssh-bridge-guest-shell");
            runner.setDaemon(true);
            runner.start();
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            java.util.logging.Logger.getLogger("SshBridgeServer").info(
                    "guest shell destroy() invoked");
            GuestShellHandle h = handle;
            if (h != null) {
                try {
                    h.destroy(shellDestroyGraceMillis);
                } catch (Exception ignored) {
                    // Best-effort teardown during channel close.
                }
            }
            Thread r = runner;
            if (r != null) {
                r.interrupt();
            }
        }
    }

    /**
     * MINA {@link Command} that refuses every exec channel. Exec channels carry
     * an arbitrary client command string; the bridge must not run arbitrary
     * host commands. The command writes a fixed, non-secret error to stderr and
     * exits non-zero so the refusal is observable to the client.
     */
    private static final class RefusedExecCommand implements Command {
        private OutputStream err;
        private ExitCallback callback;

        @Override
        public void setInputStream(InputStream in) {
            // unused
        }

        @Override
        public void setOutputStream(OutputStream out) {
            // unused
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            try {
                if (err != null) {
                    err.write("exec channel refused: arbitrary commands are not permitted\r\n"
                            .getBytes(StandardCharsets.UTF_8));
                    err.flush();
                }
            } catch (IOException ignored) {
                // The refusal must still signal exit even if stderr is closed.
            }
            ExitCallback cb = callback;
            if (cb != null) {
                cb.onExit(1, "exec refused");
            }
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            // nothing to tear down
        }
    }
}
