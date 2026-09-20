package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.LastBackupRecord;
import gh.nusashell.nusadesk.domain.backup.LastBackupRun;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.content.SharedPreferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The persisted backup display records: round-trips, and the malformed-prefs
 * cases that must degrade to {@code null} rather than crash the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LastBackupStoreTest {

    private static LastBackupStore store() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("guest_backup", Context.MODE_PRIVATE)
                .edit().clear().commit();
        return new LastBackupStore(RuntimeEnvironment.getApplication());
    }

    @Test
    public void lastBackupRecordRoundTrips() {
        LastBackupStore store = store();
        assertNull(store.load());
        store.save(new LastBackupRecord(BackupMode.CUSTOM, 1_726_000_000_000L,
                "nusadesk-backup-custom.tar.gz", 12L, 3_456L));
        LastBackupRecord record = store.load();
        assertEquals(BackupMode.CUSTOM, record.getMode());
        assertEquals(1_726_000_000_000L, record.getCreatedAtEpochMs());
        assertEquals("nusadesk-backup-custom.tar.gz", record.getDisplayName());
        assertEquals(12L, record.getEntries());
        assertEquals(3_456L, record.getTotalBytes());
    }

    @Test
    public void malformedRecordLoadsNull() {
        LastBackupStore store = store();
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("guest_backup", Context.MODE_PRIVATE);
        preferences.edit().putString("last.mode", "not-a-mode").commit();
        assertNull(store.load());
    }

    @Test
    public void lastRunRecordRoundTrips() {
        LastBackupStore store = store();
        assertNull(store.loadRun());
        store.saveRun(new LastBackupRun(LastBackupRun.OPERATION_RESTORE, false,
                "unsafe-archive", 1_726_000_000_000L));
        LastBackupRun run = store.loadRun();
        assertEquals(LastBackupRun.OPERATION_RESTORE, run.getOperation());
        assertFalse(run.isSuccess());
        assertEquals("unsafe-archive", run.getFailureCode());
        assertEquals(1_726_000_000_000L, run.getFinishedAtEpochMs());
    }

    @Test
    public void malformedRunRecordLoadsNull() {
        LastBackupStore store = store();
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("guest_backup", Context.MODE_PRIVATE);
        preferences.edit().putString("run.operation", "something-else").commit();
        assertNull(store.loadRun());
        assertTrue("a success record carries an empty failure code",
                new LastBackupRun(LastBackupRun.OPERATION_EXPORT, true, "", 0L)
                        .getFailureCode().isEmpty());
    }
}
