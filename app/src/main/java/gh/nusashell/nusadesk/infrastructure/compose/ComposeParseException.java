package gh.nusashell.nusadesk.infrastructure.compose;

/**
 * Expected failure when a Compose YAML document cannot be loaded or violates
 * the supported NusaDesk Compose subset.
 *
 * <p>The message is short, secret-free, and carries the offending document
 * path (for example {@code services.web.ports[0]}) so the caller can point the
 * user at the key to fix instead of parsing English text. It never echoes
 * whole document fragments beyond the scalar values already embedded in
 * domain validation messages.</p>
 */
public final class ComposeParseException extends Exception {
    public ComposeParseException(String message) {
        super(message);
    }

    public ComposeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
