package gh.nusashell.nusadesk.domain.runtime;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeStateTest {
    @Test
    public void downloadFlowAllowsOnlyExpectedTransitions() {
        assertTrue(RuntimeState.NOT_INSTALLED.canTransitionTo(RuntimeState.DOWNLOADING));
        assertTrue(RuntimeState.DOWNLOADING.canTransitionTo(RuntimeState.VERIFYING));
        assertTrue(RuntimeState.VERIFYING.canTransitionTo(RuntimeState.EXTRACTING));
        assertTrue(RuntimeState.EXTRACTING.canTransitionTo(RuntimeState.READY));
        assertFalse(RuntimeState.NOT_INSTALLED.canTransitionTo(RuntimeState.RUNNING));
        assertFalse(RuntimeState.READY.canTransitionTo(RuntimeState.RUNNING));
    }

    @Test
    public void failedProcessCanEnterRecovery() {
        assertTrue(RuntimeState.FAILED.canTransitionTo(RuntimeState.RECOVERING));
        assertTrue(RuntimeState.RECOVERING.canTransitionTo(RuntimeState.STARTING));
        assertTrue(RuntimeState.RECOVERING.canTransitionTo(RuntimeState.STOPPED));
    }

    @Test
    public void runningFlowMustStopBeforeStopped() {
        assertTrue(RuntimeState.READY.canTransitionTo(RuntimeState.STARTING));
        assertTrue(RuntimeState.STARTING.canTransitionTo(RuntimeState.RUNNING));
        assertTrue(RuntimeState.RUNNING.canTransitionTo(RuntimeState.STOPPING));
        assertTrue(RuntimeState.STOPPING.canTransitionTo(RuntimeState.STOPPED));
        assertFalse(RuntimeState.RUNNING.canTransitionTo(RuntimeState.STOPPED));
    }
}
