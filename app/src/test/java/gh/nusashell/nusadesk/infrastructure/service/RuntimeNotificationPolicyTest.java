package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RuntimeNotificationPolicyTest {

    private static final String APP_ID = "ubuntu-base-arm64";
    private static final String VERSION = "0.1.0";
    private static final String SESSION = "sess-1";

    @Test
    public void noSessionWithoutWorkloadAdvertisesAbsence() {
        HostRuntimeStatus status = new HostRuntimeStatus(null, false, "");
        assertEquals("Linux runtime", RuntimeNotificationPolicy.title(status));
        assertEquals("No runtime workload registered.", RuntimeNotificationPolicy.text(status));
        assertFalse(RuntimeNotificationPolicy.isOngoing(status));
        assertFalse(RuntimeNotificationPolicy.showsStopAction(status));
        assertFalse(RuntimeNotificationPolicy.requiresForeground(status));
    }

    @Test
    public void startingIsOngoingWithStopActionAndForeground() {
        HostRuntimeStatus status = status(SessionState.STARTING, null, true);
        assertEquals("Starting Linux runtime", RuntimeNotificationPolicy.title(status));
        assertTrue(RuntimeNotificationPolicy.isOngoing(status));
        assertTrue(RuntimeNotificationPolicy.showsStopAction(status));
        assertTrue(RuntimeNotificationPolicy.requiresForeground(status));
    }

    @Test
    public void runningWithoutEndpointIsNotReportedAsRunning() {
        // Defensive: RUNNING must not be treated as a live runtime without an endpoint.
        HostRuntimeStatus status = status(SessionState.RUNNING, null, true);
        assertFalse(status.isRuntimeRunning());
        assertEquals("Linux runtime connecting", RuntimeNotificationPolicy.title(status));
        assertTrue(RuntimeNotificationPolicy.isOngoing(status));
    }

    @Test
    public void runningWithEndpointShowsLoopbackUrl() {
        RuntimePort endpoint = new RuntimePort("127.0.0.1", 10994);
        HostRuntimeStatus status = status(SessionState.RUNNING, endpoint, true);
        assertTrue(status.isRuntimeRunning());
        assertEquals("Linux runtime running", RuntimeNotificationPolicy.title(status));
        assertEquals("Loopback http://127.0.0.1:10994", RuntimeNotificationPolicy.text(status));
        assertTrue(RuntimeNotificationPolicy.showsStopAction(status));
    }

    @Test
    public void stoppingIsOngoingWithoutStopAction() {
        HostRuntimeStatus status = status(SessionState.STOPPING, null, true);
        assertEquals("Stopping Linux runtime", RuntimeNotificationPolicy.title(status));
        assertTrue(RuntimeNotificationPolicy.isOngoing(status));
        assertFalse(RuntimeNotificationPolicy.showsStopAction(status));
    }

    @Test
    public void stoppedIsTerminalAndReleasesForeground() {
        HostRuntimeStatus status = status(SessionState.STOPPED, null, true);
        assertEquals("Linux runtime stopped", RuntimeNotificationPolicy.title(status));
        assertEquals("Stopped. Open the app to start it again.",
                RuntimeNotificationPolicy.text(status));
        assertFalse(RuntimeNotificationPolicy.isOngoing(status));
        assertFalse(RuntimeNotificationPolicy.requiresForeground(status));
        // The notification never offers a start action: the app owns starting,
        // and the user-visible Stop action is the only lifecycle control here.
        assertFalse(RuntimeNotificationPolicy.showsStopAction(status));
    }

    @Test
    public void runningShowsTheFixedLoopbackEndpoint() {
        RuntimePort endpoint = new RuntimePort("127.0.0.1", 22_022);
        HostRuntimeStatus status = status(SessionState.RUNNING, endpoint, true);

        assertEquals("Loopback http://127.0.0.1:22022",
                RuntimeNotificationPolicy.text(status));
    }

    @Test
    public void failedShowsReasonAndReleasesForeground() {
        HostRuntimeStatus status = failedStatus("guest exited before readiness");
        assertEquals("Linux runtime failed", RuntimeNotificationPolicy.title(status));
        assertEquals("guest exited before readiness", RuntimeNotificationPolicy.text(status));
        assertFalse(RuntimeNotificationPolicy.isOngoing(status));
        assertFalse(RuntimeNotificationPolicy.requiresForeground(status));
    }

    @Test
    public void reconnectingAndRecoveringAreOngoingWithStopAction() {
        assertTrue(RuntimeNotificationPolicy.showsStopAction(status(SessionState.RECONNECTING, null, true)));
        assertTrue(RuntimeNotificationPolicy.showsStopAction(status(SessionState.RECOVERING, null, true)));
    }

    @Test
    public void nullStatusIsSafe() {
        assertEquals("Linux runtime", RuntimeNotificationPolicy.title(null));
        assertEquals("", RuntimeNotificationPolicy.text(null));
        assertFalse(RuntimeNotificationPolicy.isOngoing(null));
        assertFalse(RuntimeNotificationPolicy.requiresForeground(null));
    }

    private static HostRuntimeStatus status(SessionState state, RuntimePort endpoint, boolean workload) {
        SessionSnapshot snapshot = new SessionSnapshot(
                SESSION, APP_ID, VERSION, state, endpoint, 10L, 10L, "", 0);
        return new HostRuntimeStatus(snapshot, workload, workload ? "ubuntu-base-arm64/ssh" : "");
    }

    private static HostRuntimeStatus failedStatus(String reason) {
        SessionSnapshot snapshot = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.FAILED, null, 10L, 10L, reason, 0);
        return new HostRuntimeStatus(snapshot, true, "ubuntu-base-arm64/ssh");
    }
}
