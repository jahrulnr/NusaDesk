package gh.nusashell.nusadesk.domain.webapp;

import java.util.Objects;

/**
 * One registered web app's saved HTTP sign-in pair: the username and password
 * that answer the Basic challenge its own server raises.
 *
 * <p>This exists because a local app is allowed to protect its own loopback
 * endpoint. An app like that is reachable and working, yet the WebView aborts
 * the load with a bare 401 unless somebody answers the challenge. Before this
 * type the host had no way to do that at all, so such an app was unreachable
 * from the launcher while reporting itself as broken (ADR-0058).</p>
 *
 * <p>Two rules make the secret safe to carry around:</p>
 *
 * <ul>
 *   <li>the pair belongs to exactly one {@link WebAppId} and is never shared
 *       across apps, so one registered app can never be answered with another
 *       app's credentials;</li>
 *   <li>neither field is ever rendered. {@link #toString()} reports only the id
 *       and whether a secret is present, so a stray log line, a crash message,
 *       or a debugger dump cannot carry the password.</li>
 * </ul>
 *
 * <p>The username rejects control characters because the stored form encodes
 * the pair with a {@code NUL} separator; a control character in the username
 * would make that encoding ambiguous. The password is opaque and may contain
 * anything, including {@code NUL}.</p>
 *
 * <p>Immutable, validated on construction.</p>
 */
public final class WebAppSignInCredential {
    /** Maximum accepted username length. */
    public static final int MAX_USERNAME_LENGTH = 256;
    /** Maximum accepted password length; a cap so a stored blob stays bounded. */
    public static final int MAX_PASSWORD_LENGTH = 1024;

    private final WebAppId webAppId;
    private final String username;
    private final String password;

    /**
     * @param webAppId the app this pair answers for; never null
     * @param username the sign-in name; not blank, no control characters
     * @param password the sign-in secret; not empty
     * @throws IllegalArgumentException when any field is missing or malformed
     */
    public WebAppSignInCredential(WebAppId webAppId, String username, String password) {
        if (webAppId == null) {
            throw new IllegalArgumentException("webAppId must not be null");
        }
        this.webAppId = webAppId;
        this.username = normalizeUsername(username);
        this.password = normalizePassword(password);
    }

    /**
     * Validates a sign-in name.
     *
     * <p>A blank name is refused rather than trimmed into one: a user who typed
     * only spaces has not entered a username, and silently accepting whitespace
     * would turn a mistyped form into a second failed sign-in attempt.</p>
     *
     * <p>A colon is refused for a wire-format reason. Basic authentication
     * sends {@code user-id ":" password} and the server splits at the
     * <em>first</em> colon, so a stored {@code "a:b"} with password {@code "pw"}
     * arrives as user {@code "a"} and password {@code "b:pw"} — it can never
     * authenticate, and the only symptom the user would ever see is a password
     * that is mysteriously wrong. Refusing it turns that into an inline error
     * on the field that caused it.</p>
     *
     * @throws IllegalArgumentException when the name is missing, too long,
     *                                  contains a colon, or contains a control
     *                                  character
     */
    public static String normalizeUsername(String username) {
        if (username == null) {
            throw new IllegalArgumentException("username must not be null");
        }
        String trimmed = username.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (trimmed.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException(
                    "username must be at most " + MAX_USERNAME_LENGTH + " characters");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                throw new IllegalArgumentException("username must not contain control characters");
            }
        }
        if (trimmed.indexOf(':') >= 0) {
            throw new IllegalArgumentException("username must not contain a colon");
        }
        return trimmed;
    }

    /**
     * Validates a sign-in secret.
     *
     * <p>The secret is never trimmed: leading or trailing spaces are legitimate
     * parts of a password, and silently stripping them is a silent wrong
     * password.</p>
     *
     * @throws IllegalArgumentException when the secret is missing or too long
     */
    public static String normalizePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
        if (password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be at most " + MAX_PASSWORD_LENGTH + " characters");
        }
        return password;
    }

    /** The app this pair answers for. */
    public WebAppId getWebAppId() {
        return webAppId;
    }

    /**
     * The sign-in name.
     *
     * <p>This is the one field safe to show: a user needs to recognise which
     * account is saved. It is never logged, and it is not the secret.</p>
     */
    public String getUsername() {
        return username;
    }

    /**
     * The sign-in secret.
     *
     * <p>Reaches exactly two consumers: the WebView's own auth challenge, and
     * the same-origin favicon request. It is never logged, never rendered, and
     * never written to disk in this form.</p>
     */
    public String getPassword() {
        return password;
    }

    @Override
    public String toString() {
        return "WebAppSignInCredential{webAppId=" + webAppId.value()
                + ", username=<redacted>, password=<redacted>}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WebAppSignInCredential)) {
            return false;
        }
        WebAppSignInCredential that = (WebAppSignInCredential) other;
        return webAppId.equals(that.webAppId)
                && username.equals(that.username)
                && password.equals(that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(webAppId, username, password);
    }
}
