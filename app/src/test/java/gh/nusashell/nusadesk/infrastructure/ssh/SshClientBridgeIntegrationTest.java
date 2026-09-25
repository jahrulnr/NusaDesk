package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.HostKeyTrust;
import gh.nusashell.nusadesk.infrastructure.session.CredentialType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real integration test: spins up an in-process Apache MINA SSHD server and
 * connects to it through {@link SshClientBridge}. This is a genuine end-to-end
 * exercise of connect, strict host-key first-trust, password auth, shell
 * channel open, stdin/stdout streaming, and graceful close. The embedded
 * server is a test fixture standing in for a remote host — it is NOT a guest
 * SSH server and is not shipped.
 */
public class SshClientBridgeIntegrationTest {

    private static final String USER = "test";
    private static final String PASSWORD = "secret";

    private SshServer server;
    private int port;

    @BeforeClass
    public static void registerBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Before
    public void startServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair hostKey = generator.generateKeyPair();

        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        server.setPasswordAuthenticator(new PasswordAuthenticator() {
            @Override
            public boolean authenticate(String username, String password, ServerSession session) {
                return USER.equals(username) && PASSWORD.equals(password);
            }
        });
        server.setShellFactory(channel -> new EchoCommand());
        server.start();
        port = server.getPort();
    }

    @After
    public void stopServer() throws Exception {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    public void connectsAuthenticatesStreamsAndCloses() throws Exception {
        InMemoryTrustStore trustStore = new InMemoryTrustStore();
        SshCredentialProvider credentials = new FixedPasswordCredentialProvider(PASSWORD);
        CapturingListener listener = new CapturingListener();

        SshClientBridge bridge = new SshClientBridge(
                credentials, trustStore, (host, fp) -> true,
                new SshReconnectPolicy(1, 0L, 0L), System::currentTimeMillis);

        SshSessionConfig config = new SshSessionConfig(
                "127.0.0.1", port, USER, "cred",
                10_000, 10_000, 10_000, 0,
                SshSessionConfig.DEFAULT_TERMINAL_TYPE, 80, 24);

        bridge.start(config, null, listener);
        try {
            assertTrue("did not reach RUNNING",
                    listener.runningLatch.await(15, TimeUnit.SECONDS));
            assertEquals(SshSessionState.RUNNING, listener.lastState.get());

            bridge.write("hello\n".getBytes(StandardCharsets.UTF_8));

            String stdout = listener.awaitOutputContaining("echo: hello", 15);
            assertTrue("expected echoed output, got: " + stdout,
                    stdout.contains("echo: hello"));
        } finally {
            bridge.close();
        }
        assertTrue("did not reach CLOSED",
                listener.closedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void mismatchedHostKeyRefusesConnectionWithoutRetry() throws Exception {
        InMemoryTrustStore trustStore = new InMemoryTrustStore();
        // Pre-trust a different key's fingerprint so the real server key mismatches.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair otherKey = generator.generateKeyPair();
        HostKeyFingerprint otherFp = SshHostKeyFingerprintCodec.toFingerprint(otherKey.getPublic());
        String scope = "127.0.0.1:" + port;
        trustStore.save(new HostKeyRecord(scope, otherFp, HostKeyTrust.VERIFIED, 1L));

        AtomicReference<Boolean> callbackInvoked = new AtomicReference<>();
        SshCredentialProvider credentials = new FixedPasswordCredentialProvider(PASSWORD);
        CapturingListener listener = new CapturingListener();

        SshClientBridge bridge = new SshClientBridge(
                credentials, trustStore, (host, fp) -> {
                    callbackInvoked.set(true);
                    return true;
                },
                new SshReconnectPolicy(5, 0L, 0L), System::currentTimeMillis);

        SshSessionConfig config = new SshSessionConfig(
                "127.0.0.1", port, USER, "cred",
                5_000, 5_000, 5_000, 0,
                SshSessionConfig.DEFAULT_TERMINAL_TYPE, 80, 24);

        bridge.start(config, null, listener);
        try {
            assertTrue("did not reach FAILED on host-key mismatch",
                    listener.failedLatch.await(15, TimeUnit.SECONDS));
        } finally {
            bridge.close();
        }
        assertEquals("first-trust callback must not be consulted on mismatch",
                null, callbackInvoked.get());
    }

    @Test
    public void execChannelRunsTheCommandOnAPty() throws Exception {
        // The exec-channel path behind a command tab (ADR-0054): the command
        // string goes to the server verbatim, output streams back, and the
        // command exiting ends the session with a clean close.
        server.setCommandFactory((channel, command) -> new ExecProbeCommand(command));

        InMemoryTrustStore trustStore = new InMemoryTrustStore();
        SshCredentialProvider credentials = new FixedPasswordCredentialProvider(PASSWORD);
        CapturingListener listener = new CapturingListener();

        SshClientBridge bridge = new SshClientBridge(
                credentials, trustStore, (host, fp) -> true,
                new SshReconnectPolicy(1, 0L, 0L), System::currentTimeMillis);

        SshSessionConfig config = new SshSessionConfig(
                "127.0.0.1", port, USER, "cred",
                10_000, 10_000, 10_000, 0,
                SshSessionConfig.DEFAULT_TERMINAL_TYPE, 80, 24);

        bridge.start(config, "uptime", listener);
        try {
            assertTrue("exec session did not reach RUNNING",
                    listener.runningLatch.await(15, TimeUnit.SECONDS));
            String stdout = listener.awaitOutputContaining("ran: uptime", 15);
            assertTrue("expected the command's output, got: " + stdout,
                    stdout.contains("ran: uptime"));
            // ssh -t semantics: the exec request must carry a PTY. The embedded
            // server only learns TERM from a pty-req, so this is the exact
            // client behavior a command tab depends on — without it the guest
            // runs the command with no TTY and interactive commands die
            // instantly (the device defect this test now locks down).
            assertTrue("the exec channel must request a PTY, got: " + stdout,
                    stdout.contains("pty: " + SshSessionConfig.DEFAULT_TERMINAL_TYPE));
            assertTrue("exec session did not close when the command exited",
                    listener.closedLatch.await(15, TimeUnit.SECONDS));
        } finally {
            bridge.close();
        }
    }

    /** Minimal exec command: reports the command string, then exits 0. */
    private static final class ExecProbeCommand implements Command {
        private final String command;
        private OutputStream out;
        private ExitCallback callback;
        private Thread runner;

        ExecProbeCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {
            // unused: the probe never reads stdin
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            // unused
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            // MINA's server registers TERM only when the client sent a pty-req,
            // so this is the probe's pty evidence.
            String term = env.getEnv().get(Environment.ENV_TERM);
            String pty = term == null || term.isEmpty() ? "pty: none" : "pty: " + term;
            runner = new Thread(() -> {
                try {
                    out.write(("ran: " + command + "\r\n" + pty + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                    // channel closed mid-write
                } finally {
                    ExitCallback cb = callback;
                    if (cb != null) {
                        cb.onExit(0);
                    }
                }
            }, "exec-probe");
            runner.setDaemon(true);
            runner.start();
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            if (runner != null) {
                runner.interrupt();
            }
        }
    }

    /** Minimal echo shell: echoes each input line prefixed with "echo: ". */
    private static final class EchoCommand implements Command {
        private InputStream in;
        private OutputStream out;
        private ExitCallback callback;
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
            // unused
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            runner = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                     PrintStream printer = new PrintStream(out, true, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        printer.print("echo: " + line + "\r\n");
                        printer.flush();
                    }
                } catch (IOException ignored) {
                    // channel closed
                } finally {
                    ExitCallback cb = callback;
                    if (cb != null) {
                        cb.onExit(0);
                    }
                }
            }, "echo-shell");
            runner.setDaemon(true);
            runner.start();
        }

        @Override
        public void destroy(ChannelSession channel) throws Exception {
            if (runner != null) {
                runner.interrupt();
            }
        }
    }

    private static final class InMemoryTrustStore implements HostKeyTrustStore {
        final Map<String, HostKeyRecord> records = new HashMap<>();

        @Override
        public HostKeyRecord load(String host) {
            return records.get(host);
        }

        @Override
        public void save(HostKeyRecord record) {
            records.put(record.getHost(), record);
        }

        @Override
        public void clear(String host) {
            records.remove(host);
        }
    }

    private static final class FixedPasswordCredentialProvider implements SshCredentialProvider {
        private final String password;

        FixedPasswordCredentialProvider(String password) {
            this.password = password;
        }

        @Override
        public char[] password(String credentialId) {
            return password.toCharArray();
        }

        @Override
        public byte[] privateKey(String credentialId) {
            return null;
        }

        @Override
        public CredentialType typeOf(String credentialId) {
            return CredentialType.PASSWORD;
        }

        @Override
        public boolean exists(String credentialId) {
            return true;
        }
    }

    private static final class CapturingListener implements SshSessionListener {
        final CountDownLatch runningLatch = new CountDownLatch(1);
        final CountDownLatch failedLatch = new CountDownLatch(1);
        final CountDownLatch closedLatch = new CountDownLatch(1);
        final AtomicReference<SshSessionState> lastState = new AtomicReference<>();
        private final StringBuilder stdout = new StringBuilder();

        @Override
        public void onState(SshSessionState state, String detail) {
            lastState.set(state);
            if (state == SshSessionState.RUNNING) {
                runningLatch.countDown();
            } else if (state == SshSessionState.FAILED) {
                failedLatch.countDown();
            }
        }

        @Override
        public void onStdout(byte[] data, int len) {
            synchronized (stdout) {
                stdout.append(new String(data, 0, len, StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onStderr(byte[] data, int len) {
            synchronized (stdout) {
                stdout.append(new String(data, 0, len, StandardCharsets.UTF_8));
            }
        }

        @Override
        public void onClosed(String reason) {
            closedLatch.countDown();
        }

        String awaitOutputContaining(String needle, int timeoutSeconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                synchronized (stdout) {
                    if (stdout.indexOf(needle) >= 0) {
                        return stdout.toString();
                    }
                }
                Thread.sleep(50);
            }
            synchronized (stdout) {
                return stdout.toString();
            }
        }
    }
}
