package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.infrastructure.session.CredentialType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.PtyCapableChannelSession;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * Real outbound SSH shell-session core.
 *
 * <p>This is a genuine Apache MINA SSHD client, not a stub. It connects to a
 * <em>remote</em> host, verifies the server key strictly through
 * {@link StrictHostKeyVerifier} (backed by the existing domain
 * {@code HostKeyTrustPolicy} and a caller-provided
 * {@link FirstHostKeyTrustCallback}), authenticates using credentials sourced
 * <em>only</em> as {@code char[]}/{@code byte[]} from a
 * {@link SshCredentialProvider}, opens a PTY shell or exec channel, streams
 * stdin/stdout, resizes the PTY, and reconnects with a bounded
 * {@link SshReconnectPolicy}.
 * Sensitive credential buffers are zeroed immediately after authentication.</p>
 *
 * <h2>Documented limitations</h2>
 * <ul>
 *   <li><b>No guest-side SSH server.</b> Ubuntu Base does not ship an SSH server.
 *       This bridge is a client to user-chosen <em>remote</em> hosts only; it
 *       never starts a server. A guest SSH endpoint would require a curated
 *       rootfs that explicitly installs and configures Dropbear/OpenSSH
 *       (ADR-0007), which is out of scope for this slice.</li>
 *   <li><b>Android one-time init required.</b> MINA SSHD is not officially tested
 *       on Android. The presentation layer must call
 *       {@link SshSecurityInitializer#initialize(android.content.Context)} once
 *       from {@code Application.onCreate} before any session starts, to register
 *       the Bouncy Castle provider and resolve the missing {@code user.home}/
 *       {@code user.dir} system properties. Without it, key loading and
 *       provider selection can fail on Android.</li>
 *   <li><b>Password identity is a {@code String} inside MINA.</b> MINA's
 *       {@code ClientSession.addPasswordIdentity} accepts a {@code String}, so
 *       the {@code char[]} from the vault is converted at the last moment and
 *       the {@code char[]} is zeroed; the short-lived {@code String} cannot be
 *       zeroed (immutable). This is the practical minimum exposure.</li>
 *   <li><b>No passphrase-protected private keys.</b> The key loader is invoked
 *       with a null {@link FilePasswordProvider}; encrypted private keys are not
 *       supported in this slice. A future slice can route a passphrase through
 *       the vault adapter.</li>
 *   <li><b>Exec channel for command tabs.</b> A non-null {@code command} to
 *       {@link #start} opens {@code ChannelExec} instead of
 *       {@code ChannelShell}; both are {@code PtyCapableChannelSession}, so
 *       stdin/stdout streaming and window-change are identical. The command
 *       string is passed verbatim to the remote {@code sshd} — never parsed
 *       or executed on the host — and the exec request carries no extra
 *       environment (ADR-0054).</li>
 *   <li><b>slf4j backend.</b> MINA uses slf4j-api; debug builds route it to
 *       logcat through {@code org.slf4j.impl.StaticLoggerBinder} under
 *       {@code app/src/debug}; release builds keep slf4j unbound (no-op).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * The bridge owns a daemon executor for the session lifecycle, a second daemon
 * executor that serializes channel writes/resizes/closes, and two pump threads
 * per session for stdout/stderr. All {@link SshSessionListener} callbacks
 * are delivered on the lifecycle thread (except output frames, which arrive on
 * the pump threads). Listeners must not block.
 *
 * <p>{@link #write(byte[])}, {@link #resize(int, int)}, and {@link #close()}
 * may be invoked from any thread, including the Android main thread (WebView
 * {@code WebMessagePort} callbacks arrive there). Android StrictMode rejects
 * network writes on the main thread with {@code NetworkOnMainThreadException},
 * which MINA surfaces as a session-fatal {@code handleWriteCycleFailure}; all
 * channel I/O is therefore marshalled onto the internal IO executor.</p>
 */
public final class SshClientBridge {

    private final SshCredentialProvider credentials;
    private final HostKeyTrustStore trustStore;
    private final FirstHostKeyTrustCallback firstTrustCallback;
    private final SshReconnectPolicy reconnectPolicy;
    private final LongSupplier clock;

    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ssh-bridge-lifecycle");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ssh-bridge-io");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean hostKeyRejected = new AtomicBoolean();
    private final AtomicReference<PtyCapableChannelSession> activeChannel = new AtomicReference<>();
    private volatile SshSessionConfig config;
    private volatile String command;
    private volatile SshSessionListener listener;

    /**
     * @param credentials       source of passwords/private keys (vault adapter)
     * @param trustStore        persistence for host-key trust decisions
     * @param firstTrustCallback explicit first-contact trust decision
     * @param reconnectPolicy   bounded reconnect policy
     * @param clock             source of {@code now} in epoch millis
     */
    public SshClientBridge(
            SshCredentialProvider credentials,
            HostKeyTrustStore trustStore,
            FirstHostKeyTrustCallback firstTrustCallback,
            SshReconnectPolicy reconnectPolicy,
            LongSupplier clock) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        if (trustStore == null) {
            throw new IllegalArgumentException("trustStore must not be null");
        }
        if (firstTrustCallback == null) {
            throw new IllegalArgumentException("firstTrustCallback must not be null");
        }
        if (reconnectPolicy == null) {
            throw new IllegalArgumentException("reconnectPolicy must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.credentials = credentials;
        this.trustStore = trustStore;
        this.firstTrustCallback = firstTrustCallback;
        this.reconnectPolicy = reconnectPolicy;
        this.clock = clock;
    }

    /**
     * Begin a session. Runs asynchronously; returns immediately. The listener
     * receives state transitions and streamed output. Calling start twice
     * without {@link #close()} is not allowed.
     *
     * @param command {@code null} opens an interactive shell channel; a
     *                non-null command opens an exec channel with a PTY that
     *                runs the command on the remote side, and every bounded
     *                reconnect re-runs it. The command is opaque here — it is
     *                never parsed or executed on the host (ADR-0054).
     */
    public void start(SshSessionConfig sessionConfig, String command, SshSessionListener sessionListener) {
        if (sessionConfig == null) {
            throw new IllegalArgumentException("sessionConfig must not be null");
        }
        if (sessionListener == null) {
            throw new IllegalArgumentException("sessionListener must not be null");
        }
        if (closed.get()) {
            throw new IllegalStateException("bridge is closed");
        }
        this.config = sessionConfig;
        this.command = command;
        this.listener = sessionListener;
        lifecycle.submit(this::runSession);
    }

    /**
     * Send bytes to the remote shell stdin. Thread-safe. Silently drops data if
     * no channel is currently open.
     */
    public void write(byte[] data) {
        if (data == null) {
            return;
        }
        write(data, 0, data.length);
    }

    /**
     * Send bytes to the remote shell stdin. Thread-safe; marshalled onto the IO
     * executor so it is safe to call from the Android main thread. Silently
     * drops data if no channel is currently open.
     */
    public void write(byte[] data, int offset, int length) {
        if (data == null || closed.get()) {
            return;
        }
        // The caller may reuse its buffer; snapshot for async delivery.
        byte[] frame = java.util.Arrays.copyOfRange(data, offset, offset + length);
        try {
            io.execute(() -> writeOnIo(frame));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Bridge is closing; drop the frame.
        }
    }

    /**
     * Resize the remote PTY. Safe to call from any thread; marshalled onto the
     * IO executor. Ignored if no open channel.
     */
    public void resize(int cols, int rows) {
        if (closed.get()) {
            return;
        }
        try {
            io.execute(() -> resizeOnIo(cols, rows));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Bridge is closing; drop the resize.
        }
    }

    private void writeOnIo(byte[] frame) {
        PtyCapableChannelSession channel = activeChannel.get();
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            OutputStream stdin = channel.getInvertedIn();
            synchronized (channel) {
                stdin.write(frame);
                stdin.flush();
            }
        } catch (Exception ignored) {
            // A broken stdin stream is surfaced by the stdout pump as a drop.
        }
    }

    private void resizeOnIo(int cols, int rows) {
        PtyCapableChannelSession channel = activeChannel.get();
        if (channel == null || !channel.isOpen()) {
            return;
        }
        try {
            channel.sendWindowChange(cols, rows);
        } catch (Exception ignored) {
            // Best-effort; a dead channel is reconciled by the pump.
        }
    }

    /**
     * Gracefully stop the session and release resources. Idempotent. Safe to
     * call from the Android main thread: the channel close is marshalled onto
     * the IO executor so no socket write happens on the caller's thread.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PtyCapableChannelSession channel = activeChannel.getAndSet(null);
        try {
            io.execute(() -> closeQuietly(channel));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            closeQuietly(channel);
        }
        io.shutdown();
        lifecycle.shutdownNow();
        try {
            lifecycle.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        SshSessionListener l = listener;
        if (l != null) {
            l.onState(SshSessionState.CLOSED, "closed by caller");
            l.onClosed("closed by caller");
        }
    }

    private void runSession() {
        SshSessionConfig cfg = config;
        SshSessionListener l = listener;
        int attempt = 0;
        while (!closed.get()) {
            attempt++;
            try {
                connectAndStream(cfg, l);
                // Clean remote close: terminal, do not reconnect.
                if (!closed.get()) {
                    l.onState(SshSessionState.CLOSED, "remote closed the session");
                    l.onClosed("remote closed the session");
                }
                return;
            } catch (Exception e) {
                if (closed.get()) {
                    return;
                }
                if (hostKeyRejected.get()) {
                    // Strict host-key rejection is terminal: never retry.
                    l.onState(SshSessionState.FAILED, describe(e));
                    l.onClosed(describe(e));
                    return;
                }
                if (reconnectPolicy.shouldRetry(attempt)) {
                    l.onState(SshSessionState.RECONNECTING, describe(e));
                    sleep(reconnectPolicy.delayForAttempt(attempt));
                    continue;
                }
                l.onState(SshSessionState.FAILED, describe(e));
                l.onClosed(describe(e));
                return;
            }
        }
    }

    private void connectAndStream(SshSessionConfig cfg, SshSessionListener l) throws Exception {
        hostKeyRejected.set(false);
        SshClient client = createClient();
        if (cfg.getKeepAliveIntervalSeconds() > 0) {
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(
                    client, Duration.ofSeconds(cfg.getKeepAliveIntervalSeconds()));
        }
        CoreModuleProperties.CHANNEL_OPEN_TIMEOUT.set(
                client, Duration.ofMillis(cfg.getChannelOpenTimeoutMillis()));
        client.setServerKeyVerifier(new StrictHostKeyVerifier(
                trustStore, firstTrustCallback, cfg.hostKeyScope(), clock,
                reason -> hostKeyRejected.set(true)));
        client.start();
        try {
            l.onState(SshSessionState.CONNECTING, "connecting to " + cfg.getHost() + ":" + cfg.getPort());
            ConnectFuture connect = client.connect(cfg.getUsername(), cfg.getHost(), cfg.getPort());
            connect.verify(cfg.getConnectTimeoutMillis());
            ClientSession session = connect.getSession();
            try {
                l.onState(SshSessionState.AUTHENTICATING, "authenticating");
                authenticate(session, cfg);
                session.auth().verify(cfg.getAuthTimeoutMillis());

                PtyChannelConfiguration pty = new PtyChannelConfiguration();
                pty.setPtyType(cfg.getTerminalType());
                pty.setPtyColumns(cfg.getInitialCols());
                pty.setPtyLines(cfg.getInitialRows());
                // A command tab opens an exec channel with the same PTY as the
                // shell channel; the command goes to the remote verbatim and
                // each reconnect attempt re-runs it (ADR-0054).
                PtyCapableChannelSession channel = command == null
                        ? session.createShellChannel(pty, Collections.emptyMap())
                        : session.createExecChannel(command, pty, Collections.emptyMap());
                if (command != null) {
                    // The exec constructor copies the PTY *configuration* but
                    // hard-codes the use-pty flag to false (it is that
                    // constructor's first argument), so the request has to be
                    // armed explicitly. Without it the guest runs the command
                    // with no TTY at all: an interactive command such as
                    // `docker exec -it … bash` or `top` then fails and exits
                    // immediately, which is exactly what the S10e showed (the
                    // guest sshd logged "Starting session: command" and the
                    // session closed two seconds later with no output).
                    channel.setUsePty(true);
                }
                channel.open().verify(cfg.getChannelOpenTimeoutMillis());
                activeChannel.set(channel);
                l.onState(SshSessionState.RUNNING,
                        command == null ? "shell open" : "command channel open");
                streamUntilClosed(channel, l);
            } finally {
                activeChannel.set(null);
                closeQuietly(session);
            }
        } finally {
            client.close();
        }
    }

    private void authenticate(ClientSession session, SshSessionConfig cfg) throws Exception {
        CredentialType type = credentials.typeOf(cfg.getCredentialId());
        if (type == null) {
            throw new IllegalStateException("no credential stored for " + cfg.getCredentialId());
        }
        if (type == CredentialType.PASSWORD) {
            char[] password = credentials.password(cfg.getCredentialId());
            if (password == null) {
                throw new IllegalStateException("password credential missing for " + cfg.getCredentialId());
            }
            try {
                // MINA accepts a String; convert at the last moment.
                session.addPasswordIdentity(new String(password));
            } finally {
                zero(password);
            }
        } else if (type == CredentialType.PRIVATE_KEY) {
            byte[] keyBytes = credentials.privateKey(cfg.getCredentialId());
            if (keyBytes == null) {
                throw new IllegalStateException("private key missing for " + cfg.getCredentialId());
            }
            try {
                KeyPair keyPair = loadKeyPair(cfg.getCredentialId(), keyBytes);
                session.addPublicKeyIdentity(keyPair);
            } finally {
                zero(keyBytes);
            }
        } else {
            throw new IllegalStateException("unsupported credential type: " + type);
        }
    }

    private KeyPair loadKeyPair(String credentialId, byte[] keyBytes) throws Exception {
        NamedResource resource = () -> credentialId;
        try (InputStream in = new ByteArrayInputStream(keyBytes)) {
            Iterable<KeyPair> pairs = SecurityUtils.loadKeyPairIdentities(
                    (SessionContext) null, resource, in, null);
            if (pairs == null || !pairs.iterator().hasNext()) {
                throw new IllegalStateException("could not parse private key for " + credentialId);
            }
            return pairs.iterator().next();
        }
    }

    private void streamUntilClosed(PtyCapableChannelSession channel, SshSessionListener l) throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Exception> error = new AtomicReference<>();
        pump("ssh-stdout", channel.getInvertedOut(), l::onStdout, done, error);
        pump("ssh-stderr", channel.getInvertedErr(), l::onStderr, done, error);
        done.await();
        if (error.get() != null) {
            throw error.get();
        }
    }

    private void pump(
            String name,
            InputStream in,
            OutputSink sink,
            CountDownLatch done,
            AtomicReference<Exception> error) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read > 0) {
                        byte[] copy = new byte[read];
                        System.arraycopy(buffer, 0, copy, 0, read);
                        sink.deliver(copy, read);
                    }
                }
            } catch (IOException e) {
                error.compareAndSet(null, e);
            } catch (Exception e) {
                error.compareAndSet(null, new IOException(name + " pump failed", e));
            } finally {
                done.countDown();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private interface OutputSink {
        void deliver(byte[] data, int len);
    }

    private void sleep(long millis) {
        try {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    private static void closeQuietly(Object closeable) {
        if (closeable instanceof AutoCloseable) {
            try {
                ((AutoCloseable) closeable).close();
            } catch (Exception ignored) {
                // Best-effort cleanup during teardown.
            }
        }
    }

    private static void zero(char[] data) {
        if (data != null) {
            for (int i = 0; i < data.length; i++) {
                data[i] = '\0';
            }
        }
    }

    private static void zero(byte[] data) {
        if (data != null) {
            for (int i = 0; i < data.length; i++) {
                data[i] = 0;
            }
        }
    }

    /** Creates a fresh default MINA client. Overridable shape kept via factory. */
    private SshClient createClient() {
        return SshClient.setUpDefaultClient();
    }
}
