package gh.nusashell.nusadesk.application.terminal;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Application boundary for the user-defined launcher terminal-command apps.
 *
 * <p>The registry is the only place that mints ids, timestamps, and launcher
 * order. It reads the store on every call instead of caching, so what the
 * launcher shows is what is actually persisted.</p>
 *
 * <p>Nothing here starts a process, opens a connection, or executes the stored
 * command. A registered terminal-command app is a launcher entry; running its
 * command inside the guest over the pinned loopback SSH session is decided at
 * open time by the terminal surface, not here.</p>
 */
public final class TerminalCommandRegistry {
    private static final Comparator<TerminalCommandApp> LAUNCHER_ORDER = (left, right) -> {
        int byOrder = Integer.compare(left.getSortOrder(), right.getSortOrder());
        if (byOrder != 0) {
            return byOrder;
        }
        int byCreation = Long.compare(
                left.getCreatedAtEpochMillis(), right.getCreatedAtEpochMillis());
        if (byCreation != 0) {
            return byCreation;
        }
        return left.getId().value().compareTo(right.getId().value());
    };

    private final TerminalCommandStore store;
    private final LongSupplier epochMillis;

    public TerminalCommandRegistry(TerminalCommandStore store) {
        this(store, System::currentTimeMillis);
    }

    /**
     * @param store       app-private persistence
     * @param epochMillis clock used for creation/update timestamps; injectable so
     *                    ordering and timestamp behavior are deterministic in tests
     */
    public TerminalCommandRegistry(TerminalCommandStore store, LongSupplier epochMillis) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (epochMillis == null) {
            throw new IllegalArgumentException("epochMillis must not be null");
        }
        this.store = store;
        this.epochMillis = epochMillis;
    }

    /** The registered terminal-command apps in launcher order; unmodifiable. */
    public List<TerminalCommandApp> list() {
        List<TerminalCommandApp> apps = new ArrayList<>(store.loadAll());
        apps.sort(LAUNCHER_ORDER);
        return Collections.unmodifiableList(apps);
    }

    /**
     * Registers a new terminal-command app at the end of the launcher order.
     *
     * @param displayName required user-visible name
     * @param iconUri     optional {@code content://} token from the document picker
     * @param command     guest command the tile runs when opened
     * @return the persisted app, including its generated id
     */
    public TerminalCommandApp add(String displayName, String iconUri, String command)
            throws TerminalCommandRegistryException {
        String name = normalizedName(displayName);
        String icon = normalizedIconUri(iconUri);
        TerminalCommand validated = validatedCommand(command);

        List<TerminalCommandApp> existing = store.loadAll();

        long now = epochMillis.getAsLong();
        TerminalCommandApp app = new TerminalCommandApp(
                TerminalCommandAppId.of(UUID.randomUUID().toString()),
                name, icon, validated, now, now, nextSortOrder(existing));
        persist(app);
        return app;
    }

    /**
     * Edits an existing terminal-command app. Identity, creation time, and
     * launcher position are preserved.
     *
     * @throws TerminalCommandRegistryException with {@code UNKNOWN_APP} when the
     *                                 id is not registered, or with a field
     *                                 reason when an edited value is invalid
     */
    public TerminalCommandApp update(
            String appId, String displayName, String iconUri, String command)
            throws TerminalCommandRegistryException {
        TerminalCommandAppId id = parseId(appId);
        List<TerminalCommandApp> existing = store.loadAll();
        TerminalCommandApp current = findById(existing, id);
        if (current == null) {
            throw new TerminalCommandRegistryException(
                    TerminalCommandRegistryException.Reason.UNKNOWN_APP,
                    "no terminal-command app is registered for id " + id);
        }

        String name = normalizedName(displayName);
        String icon = normalizedIconUri(iconUri);
        TerminalCommand validated = validatedCommand(command);

        TerminalCommandApp updated =
                current.withDetails(name, icon, validated, epochMillis.getAsLong());
        persist(updated);
        return updated;
    }

    /**
     * Removes a terminal-command app.
     *
     * <p>Idempotent by design: a stale launcher tile, an id that is no longer
     * registered, or a malformed id is a no-op rather than an error the user
     * cannot act on.</p>
     */
    public void delete(String appId) {
        if (!TerminalCommandAppId.isValid(appId)) {
            return;
        }
        store.delete(TerminalCommandAppId.of(appId));
    }

    private void persist(TerminalCommandApp app) throws TerminalCommandRegistryException {
        try {
            store.save(app);
        } catch (IllegalStateException failure) {
            String message = failure.getMessage() == null
                    ? "could not persist terminal-command app" : failure.getMessage();
            throw new TerminalCommandRegistryException(
                    TerminalCommandRegistryException.Reason.STORAGE_FAILURE, message);
        }
    }

    private static TerminalCommandAppId parseId(String appId)
            throws TerminalCommandRegistryException {
        if (!TerminalCommandAppId.isValid(appId)) {
            throw new TerminalCommandRegistryException(
                    TerminalCommandRegistryException.Reason.UNKNOWN_APP,
                    "no terminal-command app is registered for id " + appId);
        }
        return TerminalCommandAppId.of(appId);
    }

    private static TerminalCommandApp findById(
            List<TerminalCommandApp> apps, TerminalCommandAppId id) {
        for (TerminalCommandApp app : apps) {
            if (app.getId().equals(id)) {
                return app;
            }
        }
        return null;
    }

    private static int nextSortOrder(List<TerminalCommandApp> apps) {
        int highest = -1;
        for (TerminalCommandApp app : apps) {
            highest = Math.max(highest, app.getSortOrder());
        }
        return highest + 1;
    }

    private static String normalizedName(String displayName)
            throws TerminalCommandRegistryException {
        try {
            return TerminalCommandApp.normalizeDisplayName(displayName);
        } catch (IllegalArgumentException invalid) {
            throw new TerminalCommandRegistryException(
                    TerminalCommandRegistryException.Reason.INVALID_NAME, invalid.getMessage());
        }
    }

    private static String normalizedIconUri(String iconUri)
            throws TerminalCommandRegistryException {
        try {
            return TerminalCommandApp.normalizeIconUri(iconUri);
        } catch (IllegalArgumentException invalid) {
            throw new TerminalCommandRegistryException(
                    TerminalCommandRegistryException.Reason.INVALID_ICON_URI,
                    invalid.getMessage());
        }
    }

    private static TerminalCommand validatedCommand(String command)
            throws TerminalCommandRegistryException {
        try {
            return TerminalCommand.of(command);
        } catch (IllegalArgumentException invalid) {
            throw new TerminalCommandRegistryException(
                    TerminalCommandRegistryException.Reason.INVALID_COMMAND,
                    invalid.getMessage());
        }
    }
}
