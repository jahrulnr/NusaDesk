package gh.nusashell.nusadesk.application.backup;

/**
 * Receives {@link BackupProgress} ticks from the backup engine. Invoked on the
 * caller's worker thread — never the UI thread — so a listener that updates
 * views must hop to the main thread itself.
 */
public interface BackupProgressListener {

    void onProgress(BackupProgress progress);
}
