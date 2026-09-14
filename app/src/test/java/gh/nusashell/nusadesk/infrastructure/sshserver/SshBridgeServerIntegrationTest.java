package gh.nusashell.nusadesk.infrastructure.sshserver;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real integration test for {@link SshBridgeServer}: spins up the loopback MINA
 * SSHD bridge and exercises it with a genuine in-process Apache MINA SSHD client
 * against a fake {@link GuestShellLauncher}.
 *
 * <p>Asserts the security and lifecycle contract: loopback-only bind, readiness
 * frame published only after bind+health, opaque-token authentication (never
 * no-auth, wrong token rejected, no-credential rejected), host-key generation
 * and persistence across restart, readable curated-shell stdout, refusal of
 * arbitrary exec command input, and bounded shutdown that closes the endpoint.
 *
 * <p>The fake guest shell launcher stands in for the future PRoot adapter; it
 * runs a fixed curated echo shell and never accepts a command string. The
 * embedded MINA client is a test fixture, not shipped.
 */
public class SshBridgeServerIntegrationTest {

    private static final String TOKEN = "per-install-opaque-token";
    private static final String APP_ID = "ssh-bridge";
    private static final String APP_VERSION = "0.1.0";
    private static final String SESSION_ID = "sess-test";
    private static final long TIMEOUT_MS = 20_000L;

    private RecordingHostKeyStore hostKeyStore;
    private SshBridgeServer server;

    @BeforeClass
    public static void registerBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Before
    public void setUp() {
        hostKeyStore = new RecordingHostKeyStore();
        server = newServer(hostKeyStore, new FakeGuestShellLauncher(), new JdkTcpHealthProbe());
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(1_000L);
        }
    }

    private SshBridgeServer newServer(SshServerHostKeyStore store, GuestShellLauncher launcher,
                                      SshBridgeHealthProbe probe) {
        return new SshBridgeServer(
                new FixedCredential(TOKEN),
                store,
                launcher,
                probe,
                new FixedClock(1_700_000_000_000L));
    }

    private SshBridgeStartRequest startRequest() {
        return new SshBridgeStartRequest(APP_ID, APP_VERSION, SESSION_ID, 5_000L, 1_000L);
    }

    @Test
    public void startBindsLoopbackAndPublishesHealthyReadinessFrame() {
        SshBridgeStartResult result = server.start(startRequest());
        assertTrue("start should be ready, state=" + result.getState(), result.isReady());
        assertEquals(SshBridgeState.RUNNING, result.getState());

        ReadinessFrame frame = result.getReadinessFrame();
        assertNotNull("readiness frame must be published", frame);
        assertEquals(ReadinessFrame.SUPPORTED_SCHEMA, frame.getSchemaVersion());
        assertEquals(APP_ID, frame.getAppId());
        assertEquals(APP_VERSION, frame.getAppVersion());
        assertEquals(SESSION_ID, frame.getSessionId());
        assertEquals(ReadinessHealth.HEALTHY, frame.getHealth());

        RuntimePort endpoint = result.getEndpoint();
        assertNotNull(endpoint);
        assertEquals("127.0.0.1", endpoint.getHost());
        assertTrue("port must be a concrete ephemeral port, got " + endpoint.getPort(),
                endpoint.getPort() > 0);

        assertEquals(endpoint, server.endpoint());
        assertTrue(server.isRunning());
    }

    @Test
    public void refusesToStartWithoutCredential() {
        SshBridgeServer noCred = new SshBridgeServer(
                new FixedCredential(""),
                hostKeyStore,
                new FakeGuestShellLauncher(),
                new JdkTcpHealthProbe(),
                new FixedClock(1L));
        SshBridgeStartResult result = noCred.start(startRequest());
        assertEquals(SshBridgeState.FAILED, result.getState());
        assertFalse(result.isReady());
        assertNull(result.getEndpoint());
        assertFalse(noCred.isRunning());
    }

    @Test
    public void wrongTokenAndNoCredentialAreRejected() throws Exception {
        server.start(startRequest());
        int port = server.endpoint().getPort();

        // Wrong token must be rejected.
        try (ClientHolder holder = connect(port, "wrong-token")) {
            fail("wrong token must be rejected");
        } catch (Exception expected) {
            // expected: authentication rejected
        }

        // No credential at all must be rejected (never no-auth).
        try (ClientHolder holder = connect(port, null)) {
            fail("no credential must be rejected");
        } catch (Exception expected) {
            // expected: no acceptable auth method
        }
    }

    @Test
    public void correctTokenAuthenticatesAndStreamsCuratedShell() throws Exception {
        server.start(startRequest());
        int port = server.endpoint().getPort();

        try (ClientHolder holder = connect(port, TOKEN)) {
            ChannelShell shell = holder.session.createShellChannel();
            shell.open().verify(TIMEOUT_MS);

            StreamCapture stdout = StreamCapture.start(shell.getInvertedOut());
            OutputStream stdin = shell.getInvertedIn();

            assertTrue("expected curated-shell banner, got: " + stdout.awaitContains(
                    "linuxwrapper:curated-shell ready", 10),
                    stdout.contains("linuxwrapper:curated-shell ready"));

            stdin.write("hello\n".getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            assertTrue("expected echoed stdout, got: " + stdout.awaitContains("echo: hello", 10),
                    stdout.contains("echo: hello"));

            stdin.close();
            shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TIMEOUT_MS);
        }
    }

    @Test
    public void arbitraryExecCommandIsRefused() throws Exception {
        server.start(startRequest());
        int port = server.endpoint().getPort();

        try (ClientHolder holder = connect(port, TOKEN)) {
            ChannelExec exec = holder.session.createExecChannel("rm -rf /");
            exec.open().verify(TIMEOUT_MS);

            StreamCapture stderr = StreamCapture.start(exec.getInvertedErr());
            Set<ClientChannelEvent> events = exec.waitFor(
                    EnumSet.of(ClientChannelEvent.EXIT_STATUS, ClientChannelEvent.CLOSED),
                    TIMEOUT_MS);

            assertTrue("exec channel should close or report exit, events=" + events,
                    events.contains(ClientChannelEvent.CLOSED)
                            || events.contains(ClientChannelEvent.EXIT_STATUS));
            Integer exit = exec.getExitStatus();
            assertNotNull("exec must report an exit status", exit);
            assertEquals("arbitrary exec must be refused with non-zero exit", 1, (int) exit);
            assertTrue("expected refusal message, got: " + stderr.awaitContains("refused", 10),
                    stderr.contains("refused"));
        }
    }

    @Test
    public void startWhileRunningWithSameSessionIdIsIdempotent() {
        SshBridgeStartResult first = server.start(startRequest());
        assertTrue(first.isReady());

        SshBridgeStartResult repeat = server.start(startRequest());
        assertTrue(repeat.isReady());
        assertSame("same session id must re-deliver the same frame",
                first.getReadinessFrame(), repeat.getReadinessFrame());
    }

    @Test
    public void startWhileRunningWithNewSessionIdRebindsFresh() {
        SshBridgeStartResult first = server.start(startRequest());
        assertTrue(first.isReady());
        int stalePort = server.endpoint().getPort();

        // A new session must never be handed the previous session's frame:
        // the host drops mismatched frames, so serving the stale frame would
        // wedge the new session in STARTING while the port stayed bound.
        SshBridgeStartResult second = server.start(new SshBridgeStartRequest(
                APP_ID, APP_VERSION, "sess-new", 5_000L, 1_000L));
        assertTrue("new session start must be ready", second.isReady());
        assertEquals("sess-new", second.getReadinessFrame().getSessionId());
        assertTrue("new session must publish a fresh endpoint",
                second.getEndpoint().getPort() > 0);
    }

    @Test
    public void hostKeyIsGeneratedOnceAndReusedAcrossRestart() throws Exception {
        SshBridgeStartResult first = server.start(startRequest());
        assertTrue(first.isReady());
        int port1 = server.endpoint().getPort();

        HostKeyFingerprint f1;
        try (ClientHolder holder = connect(port1, TOKEN)) {
            f1 = holder.recordedFingerprint();
        }
        assertNotNull("client must observe a server host key", f1);

        server.stop(1_000L);
        assertNull(server.endpoint());

        // Restart with the SAME store: the host key must be reused, not regenerated.
        SshBridgeStartResult second = server.start(startRequest());
        assertTrue(second.isReady());
        int port2 = server.endpoint().getPort();

        HostKeyFingerprint f2;
        try (ClientHolder holder = connect(port2, TOKEN)) {
            f2 = holder.recordedFingerprint();
        }
        assertEquals("host key must be persisted and reused across restart", f1, f2);
        assertEquals("host key must be generated exactly once", 1, hostKeyStore.saveCount);
    }

    @Test
    public void freshStoreGeneratesADifferentHostKey() throws Exception {
        server.start(startRequest());
        int port1 = server.endpoint().getPort();
        HostKeyFingerprint f1;
        try (ClientHolder holder = connect(port1, TOKEN)) {
            f1 = holder.recordedFingerprint();
        }
        server.stop(1_000L);

        // A brand-new store produces a brand-new key.
        RecordingHostKeyStore freshStore = new RecordingHostKeyStore();
        SshBridgeServer other = newServer(freshStore, new FakeGuestShellLauncher(), new JdkTcpHealthProbe());
        try {
            other.start(startRequest());
            int port2 = other.endpoint().getPort();
            HostKeyFingerprint f2;
            try (ClientHolder holder = connect(port2, TOKEN)) {
                f2 = holder.recordedFingerprint();
            }
            assertNotEquals("a fresh store must generate a different host key", f1, f2);
        } finally {
            other.stop(1_000L);
        }
    }

    @Test
    public void stopClosesEndpointAndRejectsNewConnections() throws Exception {
        server.start(startRequest());
        int port = server.endpoint().getPort();
        assertTrue(server.isRunning());

        server.stop(1_000L);

        assertFalse(server.isRunning());
        assertNull(server.endpoint());
        assertEquals(SshBridgeState.STOPPED, server.state());

        // A new connection to the former port must fail after stop.
        try (ClientHolder holder = connect(port, TOKEN)) {
            fail("connection must fail after stop");
        } catch (Exception expected) {
            // expected: endpoint no longer accepting connections
        }
    }

    @Test
    public void failedHealthCheckReportsFailedWithoutRunning() {
        SshBridgeServer sick = new SshBridgeServer(
                new FixedCredential(TOKEN),
                hostKeyStore,
                new FakeGuestShellLauncher(),
                (host, port, timeout) -> false,
                new FixedClock(1L));
        SshBridgeStartResult result = sick.start(startRequest());
        assertEquals(SshBridgeState.FAILED, result.getState());
        assertFalse(sick.isRunning());
        assertNull(sick.endpoint());
    }

    // ----- helpers -----------------------------------------------------

    private ClientHolder connect(int port, String password) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        AtomicReference<java.security.PublicKey> recordedKey = new AtomicReference<>();
        client.setServerKeyVerifier((session, remoteAddress, serverKey) -> {
            recordedKey.set(serverKey);
            return true;
        });
        client.start();
        ConnectFuture connect = client.connect("anyuser", "127.0.0.1", port);
        connect.verify(TIMEOUT_MS);
        ClientSession session = connect.getSession();
        try {
            if (password != null) {
                session.addPasswordIdentity(password);
            }
            session.auth().verify(TIMEOUT_MS);
        } catch (Exception e) {
            session.close(false);
            client.close();
            throw e;
        }
        return new ClientHolder(client, session, recordedKey);
    }

    private static final class ClientHolder implements AutoCloseable {
        final SshClient client;
        final ClientSession session;
        private final AtomicReference<java.security.PublicKey> recordedKey;

        ClientHolder(SshClient client, ClientSession session,
                     AtomicReference<java.security.PublicKey> recordedKey) {
            this.client = client;
            this.session = session;
            this.recordedKey = recordedKey;
        }

        HostKeyFingerprint recordedFingerprint() {
            java.security.PublicKey key = recordedKey.get();
            assertNotNull("no server key was recorded", key);
            return HostKeyFingerprintCodec.toFingerprint(key);
        }

        @Override
        public void close() throws Exception {
            try {
                session.close(false);
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                client.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private static final class StreamCapture {
        private final StringBuilder buffer = new StringBuilder();
        private final Thread pump;
        private final CountDownLatch done = new CountDownLatch(1);

        private StreamCapture(InputStream in) {
            pump = new Thread(() -> {
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = in.read(buf)) != -1) {
                        if (n > 0) {
                            synchronized (buffer) {
                                buffer.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // stream closed
                } finally {
                    done.countDown();
                }
            }, "stream-capture");
            pump.setDaemon(true);
        }

        static StreamCapture start(InputStream in) {
            StreamCapture capture = new StreamCapture(in);
            capture.pump.start();
            return capture;
        }

        boolean contains(String needle) {
            synchronized (buffer) {
                return buffer.indexOf(needle) >= 0;
            }
        }

        String awaitContains(String needle, int timeoutSeconds) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                synchronized (buffer) {
                    if (buffer.indexOf(needle) >= 0) {
                        return buffer.toString();
                    }
                }
                Thread.sleep(50);
            }
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }

    private static final class FixedCredential implements SshBridgeCredential {
        private final String token;

        FixedCredential(String token) {
            this.token = token;
        }

        @Override
        public char[] token() {
            return token == null ? null : token.toCharArray();
        }
    }

    private static final class FixedClock implements LongSupplier {
        private final long value;

        FixedClock(long value) {
            this.value = value;
        }

        @Override
        public long getAsLong() {
            return value;
        }
    }

    private static final class RecordingHostKeyStore implements SshServerHostKeyStore {
        final Map<String, KeyPair> store = new HashMap<>();
        int saveCount;
        int loadCount;

        @Override
        public KeyPair load() {
            loadCount++;
            return store.get("default");
        }

        @Override
        public void save(KeyPair keyPair) {
            saveCount++;
            store.put("default", keyPair);
        }
    }

    /**
     * Fake guest shell launcher: a fixed curated echo shell. It writes a
     * banner proving the curated shell ran, echoes each input line, and never
     * accepts a command string. Stands in for the future PRoot adapter.
     */
    private static final class FakeGuestShellLauncher implements GuestShellLauncher {
        @Override
        public GuestShellHandle launchShell(InputStream stdin, OutputStream stdout,
                                             OutputStream stderr, PtySize ptySize)
                throws GuestShellLaunchException {
            try {
                stdout.write("linuxwrapper:curated-shell ready\r\n".getBytes(StandardCharsets.UTF_8));
                stdout.flush();
            } catch (IOException e) {
                throw new GuestShellLaunchException("could not write banner", e);
            }
            CountDownLatch done = new CountDownLatch(1);
            Thread runner = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stdin, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.write(("echo: " + line + "\r\n").getBytes(StandardCharsets.UTF_8));
                        stdout.flush();
                    }
                } catch (IOException ignored) {
                    // stdin closed: shell exits
                } finally {
                    done.countDown();
                }
            }, "fake-guest-shell");
            runner.setDaemon(true);
            runner.start();
            return new GuestShellHandle() {
                @Override
                public void resize(int cols, int rows) {
                    // best-effort, ignored by the fake
                }

                @Override
                public int waitFor() throws InterruptedException {
                    done.await();
                    return 0;
                }

                @Override
                public void destroy(long gracefulMillis) {
                    runner.interrupt();
                    try {
                        stdin.close();
                    } catch (IOException ignored) {
                        // best-effort
                    }
                }
            };
        }
    }

    // Reuses the existing fingerprint codec so recorded keys compare equal to
    // the domain HostKeyFingerprint format used by the trust store.
    private static final class HostKeyFingerprintCodec {
        static HostKeyFingerprint toFingerprint(java.security.PublicKey key) {
            String print = org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key);
            assertNotNull("fingerprint must not be blank", print);
            assertFalse("fingerprint must not be blank", print.trim().isEmpty());
            return new HostKeyFingerprint(print);
        }
    }

}
