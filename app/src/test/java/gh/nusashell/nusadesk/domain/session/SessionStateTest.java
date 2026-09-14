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
    public void rejectsInvalidLifecycleTransitions() {
        assertFalse(SessionState.NOT_STARTED.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.STOPPED.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.NOT_STARTED));
        assertFalse(SessionState.STOPPING.canTransitionTo(SessionState.RUNNING));
    }

    @Test
    public void rejectsSelfAndNullTransitions() {
        assertFalse(SessionState.RUNNING.canTransitionTo(SessionState.RUNNING));
        assertFalse(SessionState.RUNNING.canTransitionTo(null));
    }
}
