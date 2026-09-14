package gh.nusashell.nusadesk.domain.webapp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identity of one user-defined local web app.
 *
 * <p>The id is generated once by the host when the app is added and is never
 * derived from the display name, so renaming an app cannot break its stored
 * record or a launcher tile that already points at it.</p>
 *
 * <p>The accepted character set is deliberately narrow because the id becomes
 * part of the app-private storage key. The separator used between the id and a
 * stored field is {@code .}, which is excluded here so two ids can never
 * produce overlapping key prefixes.</p>
 */
public final class WebAppId {
    /** Maximum accepted id length. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]+");

    private final String value;

    private WebAppId(String value) {
        this.value = value;
    }

    /**
     * Validates and normalizes a raw id.
     *
     * @param raw candidate id; surrounding whitespace is ignored
     * @return the normalized id
     * @throws IllegalArgumentException when the id is missing, too long, or
     *                                  contains a character that is not safe in
     *                                  a storage key
     */
    public static WebAppId of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("webAppId must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("webAppId must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "webAppId must be at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "webAppId may only contain letters, digits, '-' and '_': " + trimmed);
        }
        return new WebAppId(trimmed);
    }

    /** True when {@link #of(String)} would accept {@code raw}. */
    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        return !trimmed.isEmpty()
                && trimmed.length() <= MAX_LENGTH
                && ALLOWED.matcher(trimmed).matches();
    }

    /** The normalized id. */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WebAppId)) {
            return false;
        }
        return value.equals(((WebAppId) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
