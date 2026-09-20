package gh.nusashell.nusadesk.application.backup;

/**
 * Checked failure that carries a typed {@link BackupFailure} out of the
 * archive reader/transfer internals so the coordinator can map it onto a
 * {@link BackupResult} without string matching.
 */
public final class BackupFailureException extends Exception {

    private final BackupFailure failure;

    public BackupFailureException(BackupFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public BackupFailureException(BackupFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public BackupFailure getFailure() {
        return failure;
    }
}
