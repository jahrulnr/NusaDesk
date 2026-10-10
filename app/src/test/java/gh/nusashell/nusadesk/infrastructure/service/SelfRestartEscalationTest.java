package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The one-tap recovery escalation (ADR-0063): when a user-requested restart
 * should restart the session in place, when it should escalate to killing
 * the app's own process tree, and the arm-then-kill order the seam enforces.
 */
public class SelfRestartEscalationTest {

    private static final String APP_ID = "ubuntu-base-arm64";
    private static final String VERSION = "0.1.0";
    private static final String SESSION = "sess-1";

    @Test
    public void pendingRestartOnStoppedRestartsTheSessionInPlace() {
        assertEquals(SelfRestartEscalation.Action.RESTART_SESSION,
                SelfRestartEscalation.decide(true, status(SessionState.STOPPED, false)));
    }

    @Test
    public void pendingRestartOnSurvivorFailureEscalatesToProcessTree() {
        // The honest-failure leg: the workload provably survived the forced
        // stop, so an in-place restart would only hit the held port — the
        // app's own tree has to go down and come back.
        assertEquals(SelfRestartEscalation.Action.RESTART_PROCESS_TREE,
                SelfRestartEscalation.decide(true, status(SessionState.FAILED, true)));
    }

    @Test
    public void pendingRestartOnOrdinaryFailureReleases() {
        // A failure that is not the survivor report does not justify killing
        // the process tree; the honest FAILED state stays the answer.
        assertEquals(SelfRestartEscalation.Action.RELEASE,
                SelfRestartEscalation.decide(true, status(SessionState.FAILED, false)));
        assertEquals(SelfRestartEscalation.Action.RELEASE,
                SelfRestartEscalation.decide(true, status(SessionState.CANCELLED, false)));
        assertEquals(SelfRestartEscalation.Action.RELEASE,
                SelfRestartEscalation.decide(true, status(SessionState.NOT_STARTED, false)));
    }

    @Test
    public void noPendingRestartNeverEscalates() {
        // The escalation is bound to the user's tap: a survivor failure from
        // a plain Stop releases instead of restarting the app unasked.
        assertEquals(SelfRestartEscalation.Action.RELEASE,
                SelfRestartEscalation.decide(false, status(SessionState.FAILED, true)));
        assertEquals(SelfRestartEscalation.Action.RELEASE,
                SelfRestartEscalation.decide(false, status(SessionState.STOPPED, false)));
    }

    @Test
    public void nullStatusReleases() {
        assertEquals(SelfRestartEscalation.Action.RELEASE,
                SelfRestartEscalation.decide(true, null));
    }

    @Test
    public void runArmsBeforeItKills() {
        // The ordering contract: the revival trigger must be armed before the
        // tree dies, because killing first would strand the failure. A fake
        // kill cannot actually die, so a returned call stands in — and run()
        // still reports armed, which is the only truth the caller uses.
        RecordingRestart restart = new RecordingRestart();

        assertTrue(SelfRestartEscalation.run(restart));

        assertEquals("the trigger must be armed before the tree dies",
                List.of("arm", "kill"), restart.calls);
    }

    @Test
    public void runNeverKillsWhenNoTriggerCouldBeArmed() {
        // Arming failed means nothing brings the app back: killing the tree
        // would leave the failure unrecoverable, so the seam reports refusal
        // and the caller keeps the honest FAILED state.
        RecordingRestart restart = new RecordingRestart();
        restart.armResult = false;

        assertFalse(SelfRestartEscalation.run(restart));

        assertEquals(List.of("arm"), restart.calls);
    }

    private static HostRuntimeStatus status(SessionState state, boolean survivedStop) {
        SessionSnapshot snapshot = new SessionSnapshot(
                SESSION, APP_ID, VERSION, state,
                state == SessionState.STOPPED ? null : new RuntimePort("127.0.0.1", 22_022),
                10L, 10L, "", 0);
        return new HostRuntimeStatus(snapshot, true, "ubuntu-base-arm64/ssh", survivedStop);
    }

    /**
     * The {@link AppSelfRestart} seam under test: records the call order and
     * scripts whether arming succeeded. The fake kill returns — the seam's
     * contract makes that the "could not complete" case, which is what the
     * caller's honest-degradation path already covers.
     */
    private static final class RecordingRestart implements AppSelfRestart {
        final List<String> calls = new ArrayList<>();
        boolean armResult = true;

        @Override
        public boolean arm() {
            calls.add("arm");
            return armResult;
        }

        @Override
        public void kill() {
            calls.add("kill");
        }
    }
}
