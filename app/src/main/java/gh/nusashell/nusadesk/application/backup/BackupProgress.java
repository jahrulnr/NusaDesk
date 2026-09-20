package gh.nusashell.nusadesk.application.backup;

/**
 * One progress tick while an archive is being written or read: how many
 * payload entries and payload bytes have been handled so far, plus a short
 * English phase label ("Exporting", "Extracting", "Restoring") for display.
 */
public final class BackupProgress {

    private final long entries;
    private final long bytes;
    private final String detail;

    public BackupProgress(long entries, long bytes, String detail) {
        this.entries = entries;
        this.bytes = bytes;
        this.detail = detail == null ? "" : detail;
    }

    public long getEntries() {
        return entries;
    }

    public long getBytes() {
        return bytes;
    }

    public String getDetail() {
        return detail;
    }
}
