package gh.nusashell.nusadesk.domain.backup;

/**
 * The persisted outcome of the most recent backup export or restore: which
 * operation ran, whether it reached {@code READY}, the typed failure code
 * when it did not, and when it finished. Written only once the operation
 * reaches its terminal state — never mid-transfer — so the record always
 * describes a completed run and a killed app leaves the previous record
 * untouched.
 */
public final class LastBackupRun {

    public static final String OPERATION_EXPORT = "export";
    public static final String OPERATION_RESTORE = "restore";

    private final String operation;
    private final boolean succeeded;
    private final String failureCode;
    private final long finishedAtEpochMs;

    public LastBackupRun(String operation, boolean succeeded, String failureCode,
            long finishedAtEpochMs) {
        if (!OPERATION_EXPORT.equals(operation) && !OPERATION_RESTORE.equals(operation)) {
            throw new IllegalArgumentException("unknown backup operation: " + operation);
        }
        if (finishedAtEpochMs < 0) {
            throw new IllegalArgumentException("finishedAtEpochMs must not be negative");
        }
        this.operation = operation;
        this.succeeded = succeeded;
        this.failureCode = failureCode == null ? "" : failureCode;
        this.finishedAtEpochMs = finishedAtEpochMs;
    }

    /** @return {@link #OPERATION_EXPORT} or {@link #OPERATION_RESTORE}. */
    public String getOperation() {
        return operation;
    }

    public boolean isSuccess() {
        return succeeded;
    }

    /** @return the typed failure code, or {@code ""} on success. */
    public String getFailureCode() {
        return failureCode;
    }

    public long getFinishedAtEpochMs() {
        return finishedAtEpochMs;
    }
}
