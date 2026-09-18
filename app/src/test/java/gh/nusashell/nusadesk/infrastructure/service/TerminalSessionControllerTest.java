package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.application.terminal.TerminalOutputListener;
import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionConfig;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransport;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransportFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link TerminalSessionController} with a fake transport so the whole
 * session lifecycle — open on runtime RUNNING, drop, explicit reconnect,
 * stale-callback rejection — is exercised without a real SSH client (ADR-0033).
 */
public class TerminalSessionControllerTest {

    private static final String APP_ID = "ubuntu-base-arm64";
    private static final String VERSION = "0.1.0";
    private static final String SESSION = "sess-1";

    private List<FakeTransport> created;
    private List<TerminalSessionStatus> published;
    private TerminalSessionController controller;

    @Before
    public void setUp() {
        created = new ArrayList<>();
        published = new ArrayList<>();
        TerminalTransportFactory factory = () -> {
            FakeTransport transport = new FakeTransport();
            created.add(transport);
            return transport;
        };
        // A direct executor keeps every transition synchronous, matching the
        // production main-thread executor's serialization guarantees.
        controller = new TerminalSessionController(factory, published::add, Runnable::run);
    }

    @Test
    public void initialStatusIsNotStarted() {
        assertEquals(TerminalSessionState.NOT_STARTED, controller.status().getState());
        assertTrue(created.isEmpty());
    }

    @Test
    public void runtimeRunningOpensATransportAndPublishesConnecting() {
        controller.onRuntimeStatus(running(SESSION));

        assertEquals(1, created.size());
        assertEquals(1, created.get(0).startCount);
        assertEquals(TerminalSessionState.CONNECTING, last().getState());
        // The fixed local endpoint must stay the only production target (ADR-0013).
        SshSessionConfig config = created.get(0).config;
        assertEquals("127.0.0.1", config.getHost());
        assertEquals(22022, config.getPort());
    }

    @Test
    public void bridgeRunningPublishesRunning() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onState(SshSessionState.RUNNING, "shell open");

        assertEquals(TerminalSessionState.RUNNING, last().getState());
    }

    @Test
    public void bridgeAuthenticatingStaysConnecting() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onState(SshSessionState.AUTHENTICATING, "authenticating");

        assertEquals(TerminalSessionState.CONNECTING, last().getState());
    }

    @Test
    public void bridgeReconnectingPublishesReconnecting() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onState(SshSessionState.RUNNING, "shell open");
        created.get(0).listener.onState(SshSessionState.RECONNECTING, "dropped");

        assertEquals(TerminalSessionState.RECONNECTING, last().getState());
    }

    @Test
    public void cleanClosePublishesDropped() {
        controller.onRuntimeStatus(running(SESSION));
        FakeTransport transport = created.get(0);
        transport.listener.onState(SshSessionState.RUNNING, "shell open");

        transport.listener.onState(SshSessionState.CLOSED, "remote closed the session");
        transport.listener.onClosed("remote closed the session");

        assertEquals(TerminalSessionState.DROPPED, last().getState());
        assertEquals("remote closed the session", last().getDetail());
    }

    @Test
    public void failurePublishesFailedAndKeepsItsReason() {
        controller.onRuntimeStatus(running(SESSION));
        FakeTransport transport = created.get(0);

        transport.listener.onState(SshSessionState.FAILED, "auth refused");
        transport.listener.onClosed("auth refused");

        assertEquals(TerminalSessionState.FAILED, last().getState());
        assertEquals("auth refused", last().getDetail());
    }

    @Test
    public void sameRunningSessionDoesNotOpenASecondTransport() {
        controller.onRuntimeStatus(running(SESSION));
        controller.onRuntimeStatus(running(SESSION));

        assertEquals("republishing RUNNING must not re-attach", 1, created.size());
    }

    @Test
    public void droppedShellStaysDroppedOnRuntimeRepublish() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onClosed("remote closed the session");
        assertEquals(TerminalSessionState.DROPPED, last().getState());

        // The runtime is still the same session; the dropped shell needs the
        // explicit reconnect, never a silent re-attach.
        controller.onRuntimeStatus(running(SESSION));

        assertEquals(1, created.size());
        assertEquals(TerminalSessionState.DROPPED, last().getState());
    }

    @Test
    public void newRuntimeSessionReplacesTheTransport() {
        controller.onRuntimeStatus(running(SESSION));
        FakeTransport first = created.get(0);

        controller.onRuntimeStatus(running("sess-2"));

        assertEquals(1, first.closeCount);
        assertEquals(2, created.size());
        assertEquals(TerminalSessionState.CONNECTING, last().getState());
    }

    @Test
    public void runtimeNotRunningClosesTheSessionAndPublishesNotStarted() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onState(SshSessionState.RUNNING, "shell open");

        controller.onRuntimeStatus(notRunning(SessionState.STOPPING));

        assertEquals(1, created.get(0).closeCount);
        assertEquals(TerminalSessionState.NOT_STARTED, last().getState());
    }

    @Test
    public void missingRuntimeKeepsTerminalNotStarted() {
        controller.onRuntimeStatus(new HostRuntimeStatus(null, true, "ubuntu-base-arm64/ssh"));

        assertTrue(created.isEmpty());
        assertEquals(TerminalSessionState.NOT_STARTED, controller.status().getState());
    }

    @Test
    public void reconnectWithoutRuntimeIsANoOp() {
        controller.reconnect();

        assertTrue(created.isEmpty());
        assertEquals(TerminalSessionState.NOT_STARTED, controller.status().getState());
    }

    @Test
    public void reconnectAfterDropOpensAFreshTransport() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onClosed("remote closed the session");
        assertEquals(TerminalSessionState.DROPPED, last().getState());

        controller.reconnect();

        // The dropped transport already ended on its own; reconnect must not
        // close it a second time, only start the fresh one.
        assertEquals(0, created.get(0).closeCount);
        assertEquals(2, created.size());
        assertEquals(TerminalSessionState.CONNECTING, last().getState());
    }

    @Test
    public void staleTransportCallbacksAreIgnored() {
        controller.onRuntimeStatus(running(SESSION));
        FakeTransport stale = created.get(0);
        controller.reconnect();
        assertEquals(2, created.size());

        // The retired transport still delivers its late callbacks; they must
        // not move the state owned by the fresh session.
        stale.listener.onState(SshSessionState.RUNNING, "stale shell");
        stale.listener.onClosed("stale close");

        assertEquals(TerminalSessionState.CONNECTING, last().getState());
    }

    @Test
    public void writeAndResizeReachTheCurrentTransportOnly() {
        controller.onRuntimeStatus(running(SESSION));
        FakeTransport transport = created.get(0);

        byte[] frame = "ls\n".getBytes(StandardCharsets.UTF_8);
        controller.write(frame);
        controller.resize(120, 40);

        assertEquals(1, transport.writes.size());
        assertArrayEquals(frame, transport.writes.get(0));
        assertArrayEquals(new int[]{120, 40}, transport.lastResize);
    }

    @Test
    public void writeAfterDropIsSilentlyDropped() {
        controller.onRuntimeStatus(running(SESSION));
        created.get(0).listener.onClosed("remote closed the session");

        controller.write("x".getBytes(StandardCharsets.UTF_8));

        assertTrue(created.get(0).writes.isEmpty());
    }

    @Test
    public void reportedSizeIsReusedForTheNextSession() {
        controller.onRuntimeStatus(running(SESSION));
        controller.resize(120, 40);
        controller.reconnect();

        SshSessionConfig reopened = created.get(1).config;
        assertEquals(120, reopened.getInitialCols());
        assertEquals(40, reopened.getInitialRows());
    }

    @Test
    public void outputFlowsToTheRegisteredListenerOnly() {
        controller.onRuntimeStatus(running(SESSION));
        List<String> received = new ArrayList<>();
        controller.setOutputListener(new TerminalOutputListener() {
            @Override
            public void onStdout(byte[] data, int len) {
                received.add(new String(data, 0, len, StandardCharsets.UTF_8));
            }

            @Override
            public void onStderr(byte[] data, int len) {
                received.add("err:" + new String(data, 0, len, StandardCharsets.UTF_8));
            }
        });

        SshSessionListener bridge = created.get(0).listener;
        bridge.onStdout("hello".getBytes(StandardCharsets.UTF_8), 5);
        bridge.onStderr("oops".getBytes(StandardCharsets.UTF_8), 4);

        assertEquals(2, received.size());
        assertEquals("hello", received.get(0));
        assertEquals("err:oops", received.get(1));

        // A detached surface must stop receiving bytes.
        controller.setOutputListener(null);
        bridge.onStdout("late".getBytes(StandardCharsets.UTF_8), 4);
        assertEquals(2, received.size());
    }

    @Test
    public void closeReleasesTheTransportAndPublishesNotStarted() {
        controller.onRuntimeStatus(running(SESSION));
        controller.close();

        assertEquals(1, created.get(0).closeCount);
        assertEquals(TerminalSessionState.NOT_STARTED, last().getState());
        assertTrue(controller.status().getDetail().isEmpty());
    }

    private TerminalSessionStatus last() {
        return published.get(published.size() - 1);
    }

    private static HostRuntimeStatus running(String sessionId) {
        SessionSnapshot snapshot = new SessionSnapshot(
                sessionId, APP_ID, VERSION, SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 22022), 10L, 10L, "", 0);
        return new HostRuntimeStatus(snapshot, true, "ubuntu-base-arm64/ssh");
    }

    private static HostRuntimeStatus notRunning(SessionState state) {
        SessionSnapshot snapshot = new SessionSnapshot(
                SESSION, APP_ID, VERSION, state, null, 10L, 10L, "", 0);
        return new HostRuntimeStatus(snapshot, true, "ubuntu-base-arm64/ssh");
    }

    private static final class FakeTransport implements TerminalTransport {
        SshSessionConfig config;
        SshSessionListener listener;
        int startCount;
        int closeCount;
        final List<byte[]> writes = new ArrayList<>();
        int[] lastResize;

        @Override
        public void start(SshSessionConfig sessionConfig, SshSessionListener sessionListener) {
            config = sessionConfig;
            listener = sessionListener;
            startCount++;
        }

        @Override
        public void write(byte[] data) {
            writes.add(data);
        }

        @Override
        public void resize(int cols, int rows) {
            lastResize = new int[]{cols, rows};
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
