package gh.nusashell.nusadesk.domain.session;

import java.util.Objects;

/**
 * Immutable record of a trusted host key for one host.
 *
 * <p>The {@link HostKeyTrust} field records the current decision state; only
 * {@link HostKeyTrust#VERIFIED} records authorise an SSH session. A mismatched
 * key is never stored here as verified.</p>
 */
public final class HostKeyRecord {
    private final String host;
    private final HostKeyFingerprint fingerprint;
    private final HostKeyTrust trust;
    private final long trustedSinceEpochMillis;

    public HostKeyRecord(
            String host,
            HostKeyFingerprint fingerprint,
            HostKeyTrust trust,
            long trustedSinceEpochMillis) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (fingerprint == null) {
            throw new IllegalArgumentException("fingerprint must not be null");
        }
        if (trust == null) {
            throw new IllegalArgumentException("trust must not be null");
        }
        if (trustedSinceEpochMillis < 0) {
            throw new IllegalArgumentException("trustedSinceEpochMillis must not be negative");
        }
        this.host = host;
        this.fingerprint = fingerprint;
        this.trust = trust;
        this.trustedSinceEpochMillis = trustedSinceEpochMillis;
    }

    public String getHost() {
        return host;
    }

    public HostKeyFingerprint getFingerprint() {
        return fingerprint;
    }

    public HostKeyTrust getTrust() {
        return trust;
    }

    public long getTrustedSinceEpochMillis() {
        return trustedSinceEpochMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HostKeyRecord)) {
            return false;
        }
        HostKeyRecord that = (HostKeyRecord) o;
        return trustedSinceEpochMillis == that.trustedSinceEpochMillis
                && Objects.equals(host, that.host)
                && Objects.equals(fingerprint, that.fingerprint)
                && trust == that.trust;
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, fingerprint, trust, trustedSinceEpochMillis);
    }
}
