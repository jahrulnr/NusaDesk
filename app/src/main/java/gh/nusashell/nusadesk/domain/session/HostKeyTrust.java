package gh.nusashell.nusadesk.domain.session;

/**
 * Outcome of comparing a presented host key against the trusted record.
 *
 * <p>{@link #MISMATCH} must never be silently replaced: the host must surface
 * it to the user and require an explicit decision before a new key is trusted.</p>
 */
public enum HostKeyTrust {
    /** No prior record exists for this host. */
    UNKNOWN,
    /** The presented fingerprint matches the trusted record. */
    VERIFIED,
    /** The presented fingerprint differs from the trusted record. */
    MISMATCH
}
