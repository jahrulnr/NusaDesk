package gh.nusashell.nusadesk.application.terminal;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;

import java.util.List;

/**
 * Persistence boundary for the user-defined launcher terminal-command apps.
 *
 * <p>Implementations own app-private storage only. A record is written whole or
 * not at all, and a record that cannot be read back as a complete, valid app is
 * dropped by {@link #loadAll()} rather than surfaced as a half-built entry — a
 * corrupt tile must never reach the launcher.</p>
 */
public interface TerminalCommandStore {

    /** All readable apps, in no particular order. Corrupt records are omitted. */
    List<TerminalCommandApp> loadAll();

    /**
     * Persists one app, replacing any previous record for the same id.
     *
     * @throws IllegalStateException when the write cannot be committed
     */
    void save(TerminalCommandApp app);

    /** Removes the record for {@code appId}; a missing record is not an error. */
    void delete(TerminalCommandAppId appId);
}
