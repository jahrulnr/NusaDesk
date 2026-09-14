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
    CANCELLED;

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
                        || next == FAILED || next == CANCELLED;
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
            default:
                return false;
        }
    }
}
