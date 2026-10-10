package gh.nusashell.nusadesk.domain.session;

/**
 * Lifecycle states for one Android-owned runtime session.
 *
 * <p>This is the host-side session state, distinct from the install
 * {@code RuntimeState}. It is persisted across Activity recreation so the host
 * can reconcile an interrupted session honestly instead of showing a false
 * running state.</p>
 */
public enum SessionState {
    NOT_STARTED,
    STARTING,
    RUNNING,
    RECONNECTING,
    STOPPING,
    STOPPED,
    FAILED,
    RECOVERING,
    CANCELLED,
    /**
     * The guest still runs under the supervisor but stopped answering the
     * liveness canary: it is neither {@code RUNNING} nor {@code FAILED} —
     * the workload is assumed wedged until a probe succeeds again or the
     * user restarts or stops it (ADR-0062).
     */
    UNRESPONSIVE;

    public boolean canTransitionTo(SessionState next) {
        if (next == null || next == this) {
            return false;
        }
        switch (this) {
            case NOT_STARTED:
                return next == STARTING;
            case STARTING:
                return next == RUNNING || next == RECONNECTING
                        || next == STOPPING || next == FAILED || next == CANCELLED;
            case RUNNING:
                return next == RECONNECTING || next == STOPPING
                        || next == FAILED || next == CANCELLED
                        || next == UNRESPONSIVE;
            case RECONNECTING:
                return next == RUNNING || next == STOPPING
                        || next == FAILED || next == CANCELLED;
            case STOPPING:
                return next == STOPPED || next == FAILED;
            case STOPPED:
                return next == STARTING;
            case FAILED:
                return next == RECOVERING || next == STOPPED || next == STARTING;
            case RECOVERING:
                return next == STARTING || next == STOPPED
                        || next == FAILED || next == CANCELLED;
            case CANCELLED:
                return next == STARTING;
            case UNRESPONSIVE:
                // A probe success revives it; the user's Stop/Restart and a
                // workload death take the same paths a running session takes.
                return next == RUNNING || next == STOPPING
                        || next == FAILED || next == CANCELLED;
            default:
                return false;
        }
    }
}
