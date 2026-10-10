package gh.nusashell.nusadesk.infrastructure.service;

/**
 * The self-restart seam behind the one-tap recovery escalation (ADR-0063).
 *
 * <p>When a supervised workload provably survives the forced-stop sequence,
 * the host arms a revival trigger that outlives the process and then kills
 * the app's own process tree, so the stuck workload cannot stay orphaned and
 * keep holding {@code 127.0.0.1:22022}. The platform then delivers the armed
 * trigger in a fresh process, which re-enters the same {@code ensureRunning}
 * boundary an app launch uses.</p>
 *
 * <p>The two operations are deliberately separate and ordered: the kill must
 * only run after a trigger is armed, because killing the tree with nothing
 * armed leaves the app dead and the failure unrecovered. The interface is the
 * unit-test seam; the production implementation is
 * {@link JobSchedulerSelfRestart}.</p>
 */
public interface AppSelfRestart {

    /**
     * Arm the post-death revival trigger and post the user-visible
     * "restarting" notice whose content intent reopens the app.
     *
     * @return {@code false} when no trigger could be armed — the caller then
     *         keeps the honest failed state rather than dying with no way
     *         back, and the notice must not have been posted.
     */
    boolean arm();

    /**
     * Thaw then kill every process this app's uid owns, this process last —
     * a surviving workload can be a suspended tracee holding the port, so the
     * sweep covers the whole uid, not only the tracer. Never returns on
     * success; a return means the kill could not complete and the caller must
     * degrade honestly.
     */
    void kill();
}
