package gh.nusashell.nusadesk.domain.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pure-logic coverage of the canary's failure counting (ADR-0062): when a
 * miss becomes a state, and when a result changes nothing visible.
 */
public class SessionLivenessPolicyTest {

    @Test
    public void missesBelowThresholdChangeNothing() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();

        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD - 1; i++) {
            assertNull(policy.record(SessionState.RUNNING, false));
        }
        assertEquals(SessionLivenessPolicy.FAILURE_THRESHOLD - 1,
                policy.getConsecutiveFailures());
    }

    @Test
    public void thresholdConsecutiveMissesDegradeRunningToUnresponsive() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();
        SessionState result = null;
        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD; i++) {
            result = policy.record(SessionState.RUNNING, false);
        }

        assertEquals(SessionState.UNRESPONSIVE, result);
    }

    @Test
    public void successResetsTheMissCounter() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();
        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD - 1; i++) {
            policy.record(SessionState.RUNNING, false);
        }
        policy.record(SessionState.RUNNING, true);

        assertEquals(0, policy.getConsecutiveFailures());
        // One miss after a success is the first of a new run, not the
        // threshold-crossing one.
        assertNull(policy.record(SessionState.RUNNING, false));
    }

    @Test
    public void successWhileHealthyChangesNothing() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();

        assertNull(policy.record(SessionState.RUNNING, true));
        assertEquals(0, policy.getConsecutiveFailures());
    }

    @Test
    public void firstSuccessWhileUnresponsiveRestoresRunning() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();
        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD; i++) {
            policy.record(SessionState.RUNNING, false);
        }

        assertEquals(SessionState.RUNNING,
                policy.record(SessionState.UNRESPONSIVE, true));
    }

    @Test
    public void furtherMissesWhileUnresponsiveStayUnresponsive() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();
        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD; i++) {
            policy.record(SessionState.RUNNING, false);
        }

        assertNull(policy.record(SessionState.UNRESPONSIVE, false));
        assertNull(policy.record(SessionState.UNRESPONSIVE, false));
    }

    @Test
    public void probesOnlyMatterForTheTwoLiveStates() {
        SessionLivenessPolicy policy = new SessionLivenessPolicy();

        // Non-live states never produce a liveness transition from a probe.
        assertNull(policy.record(SessionState.STARTING, false));
        assertNull(policy.record(SessionState.STARTING, false));
        assertNull(policy.record(SessionState.STARTING, false));
        assertNull(policy.record(SessionState.STARTING, false));
        assertNull(policy.record(SessionState.STOPPING, false));
        assertNull(policy.record(SessionState.RECONNECTING, true));
    }

    @Test
    public void missesAgainstNonLiveStatesNeverCount() {
        // A late in-flight probe landing during STOPPING must not borrow its
        // miss into the healthy session that follows (ADR-0062).
        SessionLivenessPolicy policy = new SessionLivenessPolicy();

        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD - 1; i++) {
            assertNull(policy.record(SessionState.STOPPING, false));
        }

        assertEquals(0, policy.getConsecutiveFailures());
        // Two real misses below the threshold still change nothing.
        for (int i = 0; i < SessionLivenessPolicy.FAILURE_THRESHOLD - 1; i++) {
            assertNull(policy.record(SessionState.RUNNING, false));
        }
    }
}
