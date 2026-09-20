package gh.nusashell.nusadesk.infrastructure.backup;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.LastBackupRecord;
import gh.nusashell.nusadesk.domain.backup.LastBackupRun;

/**
 * Persists the backup page's two small records: the most recent successful
 * export (mode, time, document name — shown as "Last backup") and the typed
 * outcome of the most recent export or restore (shown as "Last run"). Small
 * SharedPreferences records, deliberately separate from the runtime install
 * state machine they describe.
 */
public final class LastBackupStore {

    private static final String PREFERENCES = "guest_backup";
    private static final String KEY_MODE = "last.mode";
    private static final String KEY_AT = "last.at";
    private static final String KEY_NAME = "last.name";
    private static final String KEY_ENTRIES = "last.entries";
    private static final String KEY_BYTES = "last.bytes";
    private static final String KEY_RUN_OPERATION = "run.operation";
    private static final String KEY_RUN_SUCCEEDED = "run.succeeded";
    private static final String KEY_RUN_FAILURE = "run.failure";
    private static final String KEY_RUN_AT = "run.at";

    private final SharedPreferences preferences;

    public LastBackupStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @SuppressLint("ApplySharedPref")
    public void save(LastBackupRecord record) {
        boolean committed = preferences.edit()
                .putString(KEY_MODE, record.getMode().getWireValue())
                .putLong(KEY_AT, record.getCreatedAtEpochMs())
                .putString(KEY_NAME, record.getDisplayName())
                .putLong(KEY_ENTRIES, record.getEntries())
                .putLong(KEY_BYTES, record.getTotalBytes())
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist the last-backup record");
        }
    }

    /** @return the stored record, or {@code null} when absent or unreadable. */
    public LastBackupRecord load() {
        String modeValue;
        try {
            modeValue = preferences.getString(KEY_MODE, null);
        } catch (ClassCastException exception) {
            return null;
        }
        BackupMode mode = modeValue == null ? null : BackupMode.fromWireValue(modeValue);
        if (mode == null) {
            return null;
        }
        return new LastBackupRecord(
                mode,
                preferences.getLong(KEY_AT, 0L),
                preferences.getString(KEY_NAME, ""),
                preferences.getLong(KEY_ENTRIES, 0L),
                preferences.getLong(KEY_BYTES, 0L));
    }

    /**
     * Persists the typed outcome of a finished export or restore. Called only
     * at the operation's terminal state, so the record always describes a
     * completed run.
     */
    @SuppressLint("ApplySharedPref")
    public void saveRun(LastBackupRun run) {
        boolean committed = preferences.edit()
                .putString(KEY_RUN_OPERATION, run.getOperation())
                .putBoolean(KEY_RUN_SUCCEEDED, run.isSuccess())
                .putString(KEY_RUN_FAILURE, run.getFailureCode())
                .putLong(KEY_RUN_AT, run.getFinishedAtEpochMs())
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist the last-run record");
        }
    }

    /** @return the stored last-run record, or {@code null} when absent or unreadable. */
    public LastBackupRun loadRun() {
        String operation;
        try {
            operation = preferences.getString(KEY_RUN_OPERATION, null);
        } catch (ClassCastException exception) {
            return null;
        }
        if (!LastBackupRun.OPERATION_EXPORT.equals(operation)
                && !LastBackupRun.OPERATION_RESTORE.equals(operation)) {
            return null;
        }
        try {
            return new LastBackupRun(
                    operation,
                    preferences.getBoolean(KEY_RUN_SUCCEEDED, false),
                    preferences.getString(KEY_RUN_FAILURE, ""),
                    preferences.getLong(KEY_RUN_AT, 0L));
        } catch (ClassCastException exception) {
            return null;
        }
    }
}
