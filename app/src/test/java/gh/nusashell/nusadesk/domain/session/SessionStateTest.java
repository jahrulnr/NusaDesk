package gh.nusashell.nusadesk.domain.session;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionStateTest {
    @Test
    public void allowsStartFromNotInstalled() {
        assertTrue(SessionState.NOT_STARTED.canTransitionTo(SessionState.STARTING));
    }

    @Test
    public void allowsReconnectFromRunning() {
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.RECONNECTING));
    }

    @Test
    public void allowsCancelFromActiveStates() {
        assertTrue(SessionState.STARTING.canTransitionTo(SessionState.CANCELLED));
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.CANCELLED));
        assertTrue(SessionState.RECONNECTING.canTransitionTo(SessionState.CANCELLED));
        assertTrue(SessionState.RECOVERING.canTransitionTo(SessionState.CANCELLED));
    }

    @Test
    public void allowsResumeAfterStopAndCancel() {
        assertTrue(SessionState.STOPPED.canTransitionTo(SessionState.STARTING));
        assertTrue(SessionState.CANCELLED.canTransitionTo(SessionState.STARTING));
    }

    @Test
    public void allowsUnresponsiveFromRunningAndItsRecoveryPaths() {
        // ADR-0062: the canary degrades RUNNING, a probe success revives it,
        // and the user/stop/failure paths behave like a live session's.
        assertTrue(SessionState.RUNNING.canTransitionTo(SessionState.UNRESPONSIVE));
        assertTrue(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.RUNNING));
        assertTrue(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.STOPPING));
        assertTrue(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.FAILED));
        assertTrue(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.CANCELLED));
    }

    @Test
    public void rejectsInvalidLifecycleTransitions() {
        assertFalse(SessionState.NOT_STARTED.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.STOPPED.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.NOT_STARTED));
        assertFalse(SessionState.STOPPING.canTransitionTo(SessionState.RUNNING));
        // A wedge is only ever diagnosed on a live session: nothing may jump
        // straight into UNRESPONSIVE, and an unresponsive session cannot skip
        // the stop path back into a fresh session.
        assertFalse(SessionState.STARTING.canTransitionTo(SessionState.UNRESPONSIVE));
        assertFalse(SessionState.RECONNECTING.canTransitionTo(SessionState.UNRESPONSIVE));
        assertFalse(SessionState.STOPPED.canTransitionTo(SessionState.UNRESPONSIVE));
        assertFalse(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.STARTING));
        assertFalse(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.RECONNECTING));
        assertFalse(SessionState.UNRESPONSIVE.canTransitionTo(SessionState.STOPPED));
    }

    @Test
    public void rejectsSelfAndNullTransitions() {
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.RUNNING.canTransitionTo(null));
    }
}
