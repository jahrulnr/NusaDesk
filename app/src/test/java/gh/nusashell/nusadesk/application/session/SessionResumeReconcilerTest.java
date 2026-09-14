package gh.nusashell.nusadesk.application.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SessionResumeReconcilerTest {
    private SessionSnapshot snapshot(SessionState state, int reconnects) {
        return new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "0.1.0",
                state, new RuntimePort("127.0.0.1", 10994),
                10L, 20L, "", reconnects);
    }

    @Test
    public void nullSnapshotReconcilesToNull() {
        assertNull(SessionResumeReconciler.reconcile(null, 30L));
    }

    @Test
    public void runningBecomesReconnectingToReverifyHealth() {
        SessionSnapshot running = snapshot(SessionState.RUNNING, 0);

        SessionSnapshot reconciled = SessionResumeReconciler.reconcile(running, 30L);

        assertEquals(SessionState.RECONNECTING, reconciled.getState());
        assertEquals(1, reconciled.getReconnectAttempts());
        assertEquals("resuming: re-verifying previous session", reconciled.getFailureReason());
    }

    @Test
    public void interruptedStartingBecomesFailed() {
        SessionSnapshot starting = snapshot(SessionState.STARTING, 0);

        SessionSnapshot reconciled = SessionResumeReconciler.reconcile(starting, 30L);

        assertEquals(SessionState.FAILED, reconciled.getState());
        assertEquals("session interrupted by host recreation", reconciled.getFailureReason());
    }

    @Test
    public void interruptedReconnectingBecomesFailed() {
        SessionSnapshot reconnecting = snapshot(SessionState.RECONNECTING, 2);

        SessionSnapshot reconciled = SessionResumeReconciler.reconcile(reconnecting, 30L);

        assertEquals(SessionState.FAILED, reconciled.getState());
        assertEquals(2, reconciled.getReconnectAttempts());
    }

    @Test
    public void interruptedStoppingBecomesFailed() {
        SessionSnapshot stopping = snapshot(SessionState.STOPPING, 0);

        SessionSnapshot reconciled = SessionResumeReconciler.reconcile(stopping, 30L);

        assertEquals(SessionState.FAILED, reconciled.getState());
    }

    @Test
    public void cancelledStateIsPreservedOnResume() {
        SessionSnapshot cancelled = snapshot(SessionState.CANCELLED, 0);

        assertSame(cancelled, SessionResumeReconciler.reconcile(cancelled, 30L));
    }

    @Test
    public void stoppedStateIsPreservedOnResume() {
        SessionSnapshot stopped = snapshot(SessionState.STOPPED, 0);

        assertSame(stopped, SessionResumeReconciler.reconcile(stopped, 30L));
    }

    @Test
    public void failedStateIsPreservedForRetry() {
        SessionSnapshot failed = snapshot(SessionState.FAILED, 0);

        assertSame(failed, SessionResumeReconciler.reconcile(failed, 30L));
    }
}
