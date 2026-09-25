package gh.nusashell.nusadesk.domain.terminal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * One user-defined terminal-command app in the launcher: a display name, an
 * optional icon token, and the guest {@link TerminalCommand} its tile runs.
 *
 * <p>The command is only ever stored here. At open time it runs inside the
 * app's own guest Linux over the pinned loopback SSH session; the Android host
 * never executes it (see {@link TerminalCommand}).</p>
 *
 * <p>The icon is stored only as an opaque {@code content://} token that the
 * presentation layer obtained from the system document picker. This object never
 * opens, resolves, or reads it; requesting and retaining the persistable
 * permission for that token is a presentation concern.</p>
 *
 * <p>Immutable: every field is validated on construction and updates return a
 * new instance through
 * {@link #withDetails(String, String, TerminalCommand, long)}.</p>
 */
public final class TerminalCommandApp {
    /** Maximum accepted display-name length. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;
    /** Maximum accepted icon-token length. */
    public static final int MAX_ICON_URI_LENGTH = 512;

    private static final String ICON_SCHEME = "content";

    private final TerminalCommandAppId id;
    private final String displayName;
    private final String iconUri;
    private final TerminalCommand command;
    private final long createdAtEpochMillis;
    private final long updatedAtEpochMillis;
    private final int sortOrder;

    /**
     * @param id                    stable identity; never derived from the name
     *                              or the command
     * @param displayName           required user-visible name
     * @param iconUri               optional {@code content://} token, or null
     * @param command               the guest command run when the tile is opened
     * @param createdAtEpochMillis  creation time; non-negative
     * @param updatedAtEpochMillis  last edit time; not before creation
     * @param sortOrder             launcher position; non-negative
     */
    public TerminalCommandApp(
            TerminalCommandAppId id,
            String displayName,
            String iconUri,
            TerminalCommand command,
            long createdAtEpochMillis,
            long updatedAtEpochMillis,
            int sortOrder) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
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
        this.command = command;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.sortOrder = sortOrder;
    }

    // The two normalizers below deliberately duplicate WebAppDefinition's:
    // a terminal-command app and a web app are separate concrete models, not
    // one option matrix, so each kind owns its own validation copy.

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

    public TerminalCommandAppId getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The stored icon token, or {@code null} when the app has no icon. */
    public String getIconUri() {
        return iconUri;
    }

    /** The guest command this app's tile runs at open time. */
    public TerminalCommand getCommand() {
        return command;
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
     * Returns an edited copy. Identity, creation time, and launcher position are
     * preserved; only the editable fields change.
     *
     * @throws IllegalArgumentException when an edited field is invalid
     */
    public TerminalCommandApp withDetails(
            String displayName, String iconUri,
            TerminalCommand command, long updatedAtEpochMillis) {
        return new TerminalCommandApp(
                id, displayName, iconUri, command,
                createdAtEpochMillis, updatedAtEpochMillis, sortOrder);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TerminalCommandApp)) {
            return false;
        }
        TerminalCommandApp that = (TerminalCommandApp) other;
        return createdAtEpochMillis == that.createdAtEpochMillis
                && updatedAtEpochMillis == that.updatedAtEpochMillis
                && sortOrder == that.sortOrder
                && id.equals(that.id)
                && displayName.equals(that.displayName)
                && Objects.equals(iconUri, that.iconUri)
                && command.equals(that.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, displayName, iconUri, command,
                createdAtEpochMillis, updatedAtEpochMillis, sortOrder);
    }

    @Override
    public String toString() {
        // The command is user-authored text and may embed arguments the user
        // does not want echoed into diagnostics, so it stays out of toString.
        return "TerminalCommandApp{id=" + id + ", name=" + displayName
                + ", order=" + sortOrder + "}";
    }
}
