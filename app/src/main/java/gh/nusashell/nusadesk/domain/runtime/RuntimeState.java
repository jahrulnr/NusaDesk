package gh.nusashell.nusadesk.domain.runtime;

/**
 * Lifecycle states exposed by the future runtime host.
 *
 * <p>The scaffold owns only the vocabulary and transition policy. Android
 * services, downloads, and processes are intentionally implemented later.</p>
 */
public enum RuntimeState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    RECOVERING,
    FAILED;

    public boolean canTransitionTo(RuntimeState next) {
        if (next == null || next == this) {
            return false;
        }

        switch (this) {
            case NOT_INSTALLED:
                return next == DOWNLOADING;
            case DOWNLOADING:
                return next == VERIFYING || next == FAILED;
            case VERIFYING:
                return next == EXTRACTING || next == FAILED;
            case EXTRACTING:
                return next == READY || next == FAILED;
            case READY:
                return next == STARTING || next == DOWNLOADING;
            case STARTING:
                return next == RUNNING || next == FAILED || next == STOPPING;
            case RUNNING:
                return next == STOPPING || next == FAILED;
            case STOPPING:
                return next == STOPPED || next == FAILED;
            case STOPPED:
                return next == STARTING || next == DOWNLOADING;
            case RECOVERING:
                return next == STARTING || next == STOPPED || next == FAILED;
            case FAILED:
                return next == RECOVERING || next == DOWNLOADING || next == STOPPED;
            default:
                return false;
        }
    }
}
