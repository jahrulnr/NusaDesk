package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;

/** Persistence boundary for recoverable runtime working state. */
public interface RuntimeStateStore {
    RuntimeSnapshot load(String appId);

    void save(RuntimeSnapshot snapshot);

    void clear(String appId);
}
