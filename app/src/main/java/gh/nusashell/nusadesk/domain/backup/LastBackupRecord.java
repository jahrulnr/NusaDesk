package gh.nusashell.nusadesk.domain.backup;

/**
 * The one "last backup" fact the app persists for display: which mode ran,
 * when, and under which document name the user saved it. Deliberately small —
 * it is a UI convenience record, not runtime install state, and lives in the
 * app state area so it survives next to the runtime it describes.
 */
public final class LastBackupRecord {

    private final BackupMode mode;
    private final long createdAtEpochMs;
    private final String displayName;
    private final long entries;
    private final long totalBytes;

    public LastBackupRecord(BackupMode mode, long createdAtEpochMs, String displayName,
            long entries, long totalBytes) {
        if (mode == null) {
            throw new IllegalArgumentException("backup mode must not be null");
        }
        this.mode = mode;
        this.createdAtEpochMs = createdAtEpochMs;
        this.displayName = displayName == null ? "" : displayName;
        this.entries = entries;
        this.totalBytes = totalBytes;
    }

    public BackupMode getMode() {
        return mode;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getEntries() {
        return entries;
    }

    public long getTotalBytes() {
        return totalBytes;
    }
}
