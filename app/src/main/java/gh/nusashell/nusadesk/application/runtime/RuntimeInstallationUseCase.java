package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;

/** Application boundary for installing one allowlisted runtime profile. */
public interface RuntimeInstallationUseCase {
    void install(RuntimeCatalogEntry entry, ProgressListener listener)
            throws RuntimeInstallationException;

    interface ProgressListener {
        void onSnapshot(RuntimeSnapshot snapshot);
    }
}
