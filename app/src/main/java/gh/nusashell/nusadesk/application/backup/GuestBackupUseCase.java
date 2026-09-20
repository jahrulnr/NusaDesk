package gh.nusashell.nusadesk.application.backup;

import gh.nusashell.nusadesk.domain.backup.BackupSelection;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Guest backup and restore, expressed as two stream-based use cases.
 *
 * <p>Both methods run on the caller's worker thread and never return before
 * reaching a terminal {@link BackupResult} — the stream is fully consumed or
 * the failure is typed. They take plain {@link OutputStream}/{@link
 * InputStream} so the SAF plumbing stays in infrastructure and the engine is
 * unit-testable against byte-array streams. Neither method throws for an
 * expected failure; everything the user can hit is a typed result.</p>
 */
public interface GuestBackupUseCase {

    /**
     * Streams a backup archive for {@code selection} into {@code destination},
     * reporting {@link BackupProgress} ticks to {@code listener} (nullable).
     */
    BackupResult exportBackup(BackupSelection selection, OutputStream destination,
            BackupProgressListener listener);

    /**
     * Streams one backup archive from {@code source}, validates its manifest,
     * and restores it — atomic swap for FULL, wholesale subtree merge for
     * HOME/CUSTOM — reporting progress to {@code listener} (nullable).
     */
    BackupResult importBackup(InputStream source, BackupProgressListener listener);
}
