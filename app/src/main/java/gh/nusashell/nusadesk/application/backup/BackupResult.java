package gh.nusashell.nusadesk.application.backup;

/**
 * Terminal state of one backup export or import.
 *
 * <p>{@link Status#READY} means the archive was fully written or fully
 * restored; {@link Status#FAILED} carries a bounded {@link BackupFailure}
 * reason and a human-readable detail. Successes also report the payload
 * {@code entries}/{@code bytes} counters so the UI can confirm real work.</p>
 */
public final class BackupResult {

    public enum Status {
        READY,
        FAILED
    }

    private final Status status;
    private final BackupFailure failure;
    private final String detail;
    private final long entries;
    private final long bytes;

    private BackupResult(Status status, BackupFailure failure, String detail,
            long entries, long bytes) {
        this.status = status;
        this.failure = failure;
        this.detail = detail == null ? "" : detail;
        this.entries = entries;
        this.bytes = bytes;
    }

    public static BackupResult ready(String detail, long entries, long bytes) {
        return new BackupResult(Status.READY, null, detail, entries, bytes);
    }

    public static BackupResult failed(BackupFailure failure, String detail) {
        if (failure == null) {
            throw new IllegalArgumentException("a failed result needs a typed failure");
        }
        return new BackupResult(Status.FAILED, failure, detail, 0, 0);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    /** @return the typed failure, or {@code null} when {@link #isReady()}. */
    public BackupFailure getFailure() {
        return failure;
    }

    public String getDetail() {
        return detail;
    }

    public long getEntries() {
        return entries;
    }

    public long getBytes() {
        return bytes;
    }
}
