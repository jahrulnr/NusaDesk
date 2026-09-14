package gh.nusashell.nusadesk.domain.session;

import java.util.Locale;

/**
 * Immutable host-key fingerprint presented by the guest SSH endpoint.
 *
 * <p>This value performs no cryptographic work: it stores and compares an
 * opaque fingerprint string. Two formats are accepted so the SSH bridge can
 * reuse OpenSSH-style {@code SHA256:<base64>} fingerprints or a raw SHA-256
 * hex digest. Normalisation is limited to trimming whitespace and lowercasing
 * a hex digest so equality is stable.</p>
 */
public final class HostKeyFingerprint {
    private final String value;

    public HostKeyFingerprint(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("fingerprint must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        this.value = normalise(trimmed);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HostKeyFingerprint)) {
            return false;
        }
        return value.equals(((HostKeyFingerprint) o).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    private static String normalise(String trimmed) {
        if (trimmed.matches("[0-9a-fA-F]{64}")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (trimmed.matches("SHA256:[A-Za-z0-9+/]+={0,2}")) {
            return trimmed;
        }
        throw new IllegalArgumentException(
                "fingerprint must be a 64-char hex digest or SHA256:<base64>");
    }
}
