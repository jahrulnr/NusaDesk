package gh.nusashell.nusadesk.application.session;

import gh.nusashell.nusadesk.domain.session.HostKeyRecord;

/**
 * Persistence boundary for host-key trust decisions.
 *
 * <p>Infrastructure implements this so trust records survive process death.
 * The application layer never silently mutates a verified record; it delegates
 * to {@code HostKeyTrustPolicy} and persists the resulting decision here.</p>
 */
public interface HostKeyTrustStore {
    HostKeyRecord load(String host);

    void save(HostKeyRecord record);

    void clear(String host);
}
