package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import java.util.List;

/**
 * Persistence boundary for the user-defined launcher web apps.
 *
 * <p>Implementations own app-private storage only. A record is written whole or
 * not at all, and a record that cannot be read back as a complete, valid
 * definition is dropped by {@link #loadAll()} rather than surfaced as a
 * half-built app — a corrupt tile must never reach the launcher.</p>
 */
public interface WebAppStore {

    /** All readable definitions, in no particular order. Corrupt records are omitted. */
    List<WebAppDefinition> loadAll();

    /**
     * Persists one definition, replacing any previous record for the same id.
     *
     * @throws IllegalStateException when the write cannot be committed
     */
    void save(WebAppDefinition definition);

    /** Removes the record for {@code webAppId}; a missing record is not an error. */
    void delete(WebAppId webAppId);
}
