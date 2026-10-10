package gh.nusashell.nusadesk.infrastructure.service;

/**
 * What one terminal publish should do with a pending user restart
 * (ADR-0063): restart the session in place when the workload really went
 * away, or escalate to a self-restart of the app's own process tree when the
 * workload provably survived the forced stop and would otherwise stay
 * orphaned holding {@code 127.0.0.1:22022}.
 *
 * <p>Pure policy over {@link HostRuntimeStatus} — no Android types — so the
 * escalation decision and the arm-then-kill ordering are unit-tested through
 * the {@link AppSelfRestart} seam.</p>
 */
public final class SelfRestartEscalation {

    /** Terminal-publish action for a pending restart. */
    public enum Action {
        /**
         * The workload is gone: restart the session in place through the same
         * {@code ensureRunning} boundary an app launch uses (ADR-0062).
         */
        RESTART_SESSION,
        /**
         * The workload survived the forced stop: arm the revival trigger, then
         * kill the app's own process tree so the survivor cannot keep the
         * fixed port (ADR-0063).
         */
        RESTART_PROCESS_TREE,
        /** No restart is owed: release the service. */
        RELEASE
    }

    private SelfRestartEscalation() {
    }

    /**
     * Decide for one terminal publish. {@code restartPending} is the flag
     * {@code ACTION_RESTART} sets; {@code status} is the just-published
     * terminal status. Only a {@code FAILED} that carries
     * {@link HostRuntimeStatus#survivedStop()} — the workload's typed
     * survivor report — escalates: any other failure keeps its honest
     * terminal state instead of risking a kill the failure did not justify.
     */
    public static Action decide(boolean restartPending, HostRuntimeStatus status) {
        if (!restartPending || status == null) {
            return Action.RELEASE;
        }
        switch (status.getState()) {
            case STOPPED:
                return Action.RESTART_SESSION;
            case FAILED:
                return status.survivedStop()
                        ? Action.RESTART_PROCESS_TREE : Action.RELEASE;
            default:
                return Action.RELEASE;
        }
    }

    /**
     * Arm, then kill through the {@link AppSelfRestart} seam — in that order,
     * always. Returns {@code false} when no revival trigger could be armed;
     * the caller then keeps the honest {@code FAILED} state instead of dying
     * unrecoverable. A {@code kill} that returns has left the process alive;
     * the caller still releases the service and lets the armed trigger or the
     * persisted state carry the record.
     */
    public static boolean run(AppSelfRestart restart) {
        if (!restart.arm()) {
            return false;
        }
        restart.kill();
        return true;
    }
}
