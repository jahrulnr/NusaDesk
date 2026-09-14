package gh.nusashell.nusadesk.domain.session;

/**
 * Deterministic policy for host-key trust decisions.
 *
 * <p>Enforces the security rule that a verified key is never silently replaced
 * by a different fingerprint. The normal {@link #trust} path accepts a first
 * contact or confirms an existing key; any change throws
 * {@link HostKeyMismatchException}. Explicit replacement after a mismatch is a
 * separate, deliberate operation.</p>
 */
public final class HostKeyTrustPolicy {
    private HostKeyTrustPolicy() {
    }

    /**
     * Classify a presented fingerprint against the known record.
     *
     * @param known     the persisted record, or null if the host is unseen
     * @param presented the fingerprint the endpoint just presented
     * @return {@link HostKeyTrust#UNKNOWN}, {@link HostKeyTrust#VERIFIED}, or {@link HostKeyTrust#MISMATCH}
     */
    public static HostKeyTrust evaluate(HostKeyRecord known, HostKeyFingerprint presented) {
        if (presented == null) {
            throw new IllegalArgumentException("presented fingerprint must not be null");
        }
        if (known == null) {
            return HostKeyTrust.UNKNOWN;
        }
        return known.getFingerprint().equals(presented)
                ? HostKeyTrust.VERIFIED
                : HostKeyTrust.MISMATCH;
    }

    /**
     * Trust a presented key for a host, returning the record to persist.
     *
     * <p>First contact creates a verified record. Presenting the already-trusted
     * key is idempotent. Presenting a different key to an already-trusted host
     * throws: the mismatch must be resolved explicitly via
     * {@link #replaceAfterMismatch}.</p>
     */
    public static HostKeyRecord trust(
            HostKeyRecord known, HostKeyFingerprint presented, String host, long now) {
        HostKeyTrust decision = evaluate(known, presented);
        switch (decision) {
            case UNKNOWN:
                return new HostKeyRecord(host, presented, HostKeyTrust.VERIFIED, now);
            case VERIFIED:
                return known;
            case MISMATCH:
            default:
                throw new HostKeyMismatchException(
                        "host key for " + host + " changed; refusing silent replacement");
        }
    }

    /**
     * Explicitly replace a mismatched key after the user acknowledged the risk.
     * This is the only path that stores a different fingerprint for a known host.
     */
    public static HostKeyRecord replaceAfterMismatch(
            HostKeyRecord known, HostKeyFingerprint presented, String host, long now) {
        if (known == null) {
            throw new IllegalArgumentException(
                    "replaceAfterMismatch requires an existing record; use trust for first contact");
        }
        if (known.getFingerprint().equals(presented)) {
            return known;
        }
        return new HostKeyRecord(host, presented, HostKeyTrust.VERIFIED, now);
    }
}
