package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Application boundary for the user-defined launcher web apps.
 *
 * <p>The registry is the only place that mints ids, timestamps, and launcher
 * order, and the only place that decides whether a guest port is free. It reads
 * the store on every call instead of caching, so what the launcher shows is what
 * is actually persisted.</p>
 *
 * <p>Nothing here starts a process, probes a port, or touches a WebView. A
 * registered web app is a launcher entry; whether its server is currently
 * running is observed separately through the readiness probe.</p>
 */
public final class WebAppRegistry {
    private static final Comparator<WebAppDefinition> LAUNCHER_ORDER = (left, right) -> {
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

    private final WebAppStore store;
    private final LongSupplier epochMillis;

    public WebAppRegistry(WebAppStore store) {
        this(store, System::currentTimeMillis);
    }

    /**
     * @param store       app-private persistence
     * @param epochMillis clock used for creation/update timestamps; injectable so
     *                    ordering and timestamp behavior are deterministic in tests
     */
    public WebAppRegistry(WebAppStore store, LongSupplier epochMillis) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (epochMillis == null) {
            throw new IllegalArgumentException("epochMillis must not be null");
        }
        this.store = store;
        this.epochMillis = epochMillis;
    }

    /** The registered web apps in launcher order; unmodifiable. */
    public List<WebAppDefinition> list() {
        List<WebAppDefinition> definitions = new ArrayList<>(store.loadAll());
        definitions.sort(LAUNCHER_ORDER);
        return Collections.unmodifiableList(definitions);
    }

    /**
     * Registers a new web app at the end of the launcher order.
     *
     * @param displayName required user-visible name
     * @param iconUri     optional {@code content://} token from the document picker
     * @param guestPort   fixed guest port the app's server listens on
     * @return the persisted definition, including its generated id
     */
    public WebAppDefinition add(String displayName, String iconUri, int guestPort)
            throws WebAppRegistryException {
        String name = normalizedName(displayName);
        String icon = normalizedIconUri(iconUri);
        int port = validatedPort(guestPort);

        List<WebAppDefinition> existing = store.loadAll();
        requirePortFree(existing, port, null);

        long now = epochMillis.getAsLong();
        WebAppDefinition definition = new WebAppDefinition(
                WebAppId.of(UUID.randomUUID().toString()),
                name, icon, port, now, now, nextSortOrder(existing));
        persist(definition);
        return definition;
    }

    /**
     * Edits an existing web app. Identity, creation time, and launcher position
     * are preserved.
     *
     * @throws WebAppRegistryException with {@code UNKNOWN_APP} when the id is not
     *                                 registered, or with a field reason when an
     *                                 edited value is invalid or the port is owned
     *                                 by another app
     */
    public WebAppDefinition update(
            String webAppId, String displayName, String iconUri, int guestPort)
            throws WebAppRegistryException {
        WebAppId id = parseId(webAppId);
        List<WebAppDefinition> existing = store.loadAll();
        WebAppDefinition current = findById(existing, id);
        if (current == null) {
            throw new WebAppRegistryException(
                    WebAppRegistryException.Reason.UNKNOWN_APP,
                    "no web app is registered for id " + id);
        }

        String name = normalizedName(displayName);
        String icon = normalizedIconUri(iconUri);
        int port = validatedPort(guestPort);
        requirePortFree(existing, port, id);

        WebAppDefinition updated = current.withDetails(name, icon, port, epochMillis.getAsLong());
        persist(updated);
        return updated;
    }

    /**
     * Removes a web app and frees its guest port.
     *
     * <p>Idempotent by design: a stale launcher tile, an id that is no longer
     * registered, or a malformed id is a no-op rather than an error the user
     * cannot act on.</p>
     */
    public void delete(String webAppId) {
        if (!WebAppId.isValid(webAppId)) {
            return;
        }
        store.delete(WebAppId.of(webAppId));
    }

    private void persist(WebAppDefinition definition) throws WebAppRegistryException {
        try {
            store.save(definition);
        } catch (IllegalStateException failure) {
            String message = failure.getMessage() == null
                    ? "could not persist web app" : failure.getMessage();
            throw new WebAppRegistryException(
                    WebAppRegistryException.Reason.STORAGE_FAILURE, message);
        }
    }

    private static WebAppId parseId(String webAppId) throws WebAppRegistryException {
        if (!WebAppId.isValid(webAppId)) {
            throw new WebAppRegistryException(
                    WebAppRegistryException.Reason.UNKNOWN_APP,
                    "no web app is registered for id " + webAppId);
        }
        return WebAppId.of(webAppId);
    }

    private static WebAppDefinition findById(List<WebAppDefinition> definitions, WebAppId id) {
        for (WebAppDefinition definition : definitions) {
            if (definition.getId().equals(id)) {
                return definition;
            }
        }
        return null;
    }

    private static void requirePortFree(
            List<WebAppDefinition> definitions, int guestPort, WebAppId owner)
            throws WebAppRegistryException {
        for (WebAppDefinition definition : definitions) {
            if (definition.getGuestPort() == guestPort
                    && !definition.getId().equals(owner)) {
                throw new WebAppRegistryException(
                        WebAppRegistryException.Reason.PORT_IN_USE,
                        "guest port " + guestPort + " is already used by "
                                + definition.getDisplayName());
            }
        }
    }

    private static int nextSortOrder(List<WebAppDefinition> definitions) {
        int highest = -1;
        for (WebAppDefinition definition : definitions) {
            highest = Math.max(highest, definition.getSortOrder());
        }
        return highest + 1;
    }

    private static String normalizedName(String displayName) throws WebAppRegistryException {
        try {
            return WebAppDefinition.normalizeDisplayName(displayName);
        } catch (IllegalArgumentException invalid) {
            throw new WebAppRegistryException(
                    WebAppRegistryException.Reason.INVALID_NAME, invalid.getMessage());
        }
    }

    private static String normalizedIconUri(String iconUri) throws WebAppRegistryException {
        try {
            return WebAppDefinition.normalizeIconUri(iconUri);
        } catch (IllegalArgumentException invalid) {
            throw new WebAppRegistryException(
                    WebAppRegistryException.Reason.INVALID_ICON_URI, invalid.getMessage());
        }
    }

    private static int validatedPort(int guestPort) throws WebAppRegistryException {
        try {
            return GuestPortPolicy.validate(guestPort);
        } catch (IllegalArgumentException invalid) {
            WebAppRegistryException.Reason reason = GuestPortPolicy.isReserved(guestPort)
                    ? WebAppRegistryException.Reason.PORT_RESERVED
                    : WebAppRegistryException.Reason.INVALID_PORT;
            throw new WebAppRegistryException(reason, invalid.getMessage());
        }
    }
}
