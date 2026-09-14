package gh.nusashell.nusadesk.application.session;

import gh.nusashell.nusadesk.domain.session.SessionSnapshot;

/**
 * Persistence boundary for recoverable runtime session state.
 *
 * <p>Infrastructure implements this so a session snapshot survives Activity
 * recreation. The application layer uses it to load, persist, and clear working
 * state; it performs no Android I/O itself.</p>
 */
public interface SessionStateStore {
    SessionSnapshot load(String sessionId);

    void save(SessionSnapshot snapshot);

    void clear(String sessionId);
}
