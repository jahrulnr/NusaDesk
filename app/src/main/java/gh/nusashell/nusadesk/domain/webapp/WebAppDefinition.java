package gh.nusashell.nusadesk.domain.webapp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * One user-defined local web app in the launcher: a display name, an optional
 * icon token, and the fixed guest port its server listens on.
 *
 * <p>The endpoint is never user input. It is generated from the validated port
 * as exactly {@code http://127.0.0.1:<port>/}, so no stored record can point the
 * WebView at another host, scheme, or path. A user-defined app therefore cannot
 * become a general-purpose URL launcher.</p>
 *
 * <p>The icon is stored only as an opaque {@code content://} token that the
 * presentation layer obtained from the system document picker. This object never
 * opens, resolves, or reads it; requesting and retaining the persistable
 * permission for that token is a presentation concern.</p>
 *
 * <p>Immutable: every field is validated on construction and updates return a
 * new instance through {@link #withDetails(String, String, int, long)}.</p>
 */
public final class WebAppDefinition {
    /** The only host a generated web-app endpoint may use. */
    public static final String ENDPOINT_HOST = "127.0.0.1";
    /** Maximum accepted display-name length. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;
    /** Maximum accepted icon-token length. */
    public static final int MAX_ICON_URI_LENGTH = 512;

    private static final String ICON_SCHEME = "content";
    private static final String ENDPOINT_SCHEME = "http";

    private final WebAppId id;
    private final String displayName;
    private final String iconUri;
    private final int guestPort;
    private final long createdAtEpochMillis;
    private final long updatedAtEpochMillis;
    private final int sortOrder;

    /**
     * @param id                    stable identity; never derived from the name
     * @param displayName           required user-visible name
     * @param iconUri               optional {@code content://} token, or null
     * @param guestPort             fixed guest port the app's server listens on
     * @param createdAtEpochMillis  creation time; non-negative
     * @param updatedAtEpochMillis  last edit time; not before creation
     * @param sortOrder             launcher position; non-negative
     */
    public WebAppDefinition(
            WebAppId id,
            String displayName,
            String iconUri,
            int guestPort,
            long createdAtEpochMillis,
            long updatedAtEpochMillis,
            int sortOrder) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (createdAtEpochMillis < 0) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
        if (updatedAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException(
                    "updatedAtEpochMillis must not be before createdAtEpochMillis");
        }
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative");
        }
        this.id = id;
        this.displayName = normalizeDisplayName(displayName);
        this.iconUri = normalizeIconUri(iconUri);
        this.guestPort = GuestPortPolicy.validate(guestPort);
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.sortOrder = sortOrder;
    }

    /**
     * Validates and normalizes a user-supplied display name.
     *
     * @throws IllegalArgumentException when the name is missing or too long
     */
    public static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("displayName must not be null");
        }
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * Validates and normalizes an icon token.
     *
     * <p>Only an absolute {@code content://} URI with an authority is accepted,
     * so a stored token cannot become a {@code file://} read, a network fetch, or
     * a {@code javascript:} URL. A blank token means "no icon" and yields
     * {@code null}.</p>
     *
     * @return the normalized token, or {@code null} when none was supplied
     * @throws IllegalArgumentException when a non-blank token is not a usable
     *                                  content URI
     */
    public static String normalizeIconUri(String iconUri) {
        if (iconUri == null || iconUri.trim().isEmpty()) {
            return null;
        }
        String trimmed = iconUri.trim();
        if (trimmed.length() > MAX_ICON_URI_LENGTH) {
            throw new IllegalArgumentException(
                    "iconUri must be at most " + MAX_ICON_URI_LENGTH + " characters");
        }
        URI parsed;
        try {
            parsed = new URI(trimmed);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("iconUri must be a valid content URI");
        }
        if (!ICON_SCHEME.equals(parsed.getScheme())
                || parsed.getAuthority() == null || parsed.getAuthority().isEmpty()) {
            throw new IllegalArgumentException(
                    "iconUri must be a content URI with an authority");
        }
        return trimmed;
    }

    public WebAppId getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The stored icon token, or {@code null} when the app has no icon. */
    public String getIconUri() {
        return iconUri;
    }

    public boolean hasIcon() {
        return iconUri != null;
    }

    public int getGuestPort() {
        return guestPort;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    /** Launcher position; lower comes first. */
    public int getSortOrder() {
        return sortOrder;
    }

    /**
     * The only endpoint this app may ever be loaded from, generated exactly as
     * {@code http://127.0.0.1:<port>/}.
     */
    public String getEndpointUrl() {
        return ENDPOINT_SCHEME + "://" + ENDPOINT_HOST + ":" + guestPort + "/";
    }

    /**
     * Returns an edited copy. Identity, creation time, and launcher position are
     * preserved; only the editable fields change.
     *
     * @throws IllegalArgumentException when an edited field is invalid
     */
    public WebAppDefinition withDetails(
            String displayName, String iconUri, int guestPort, long updatedAtEpochMillis) {
        return new WebAppDefinition(
                id, displayName, iconUri, guestPort,
                createdAtEpochMillis, updatedAtEpochMillis, sortOrder);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WebAppDefinition)) {
            return false;
        }
        WebAppDefinition that = (WebAppDefinition) other;
        return guestPort == that.guestPort
                && createdAtEpochMillis == that.createdAtEpochMillis
                && updatedAtEpochMillis == that.updatedAtEpochMillis
                && sortOrder == that.sortOrder
                && id.equals(that.id)
                && displayName.equals(that.displayName)
                && Objects.equals(iconUri, that.iconUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, iconUri, guestPort,
                createdAtEpochMillis, updatedAtEpochMillis, sortOrder);
    }

    @Override
    public String toString() {
        return "WebAppDefinition{id=" + id + ", port=" + guestPort
                + ", name=" + displayName + ", order=" + sortOrder + "}";
    }
}
