package gh.nusashell.nusadesk.domain.session;

/**
 * Raised when a presented host key differs from an already-trusted record.
 *
 * <p>This is a domain-level signal that the key must not be silently replaced;
 * the caller must surface the mismatch to the user and require an explicit
 * decision before trusting the new key.</p>
 */
public class HostKeyMismatchException extends RuntimeException {
    public HostKeyMismatchException(String message) {
        super(message);
    }
}
