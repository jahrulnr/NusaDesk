package gh.nusashell.nusadesk.domain.update;

import java.util.Locale;

/**
 * Normalization and comparison for a recorded release-asset digest.
 *
 * <p>GitHub reports each release asset's digest in the API as a URI-shaped
 * {@code sha256:<hex>} string, computed by GitHub itself on the uploaded
 * file. The recorded value and the freshly computed digest of a downloaded
 * APK must match before an install session may be staged. The comparison is
 * deliberately fail-closed: a null, unprefixed, or unparsable recorded
 * digest never matches, so an asset whose checksum the channel did not
 * report can never start the assisted install (the user keeps the browser
 * hand-off instead).
 */
public final class ApkDigest {

    private static final String PREFIX = "sha256:";
    /** A SHA-256 digest is exactly 64 lowercase hex characters. */
    private static final int SHA256_HEX_LENGTH = 64;

    private ApkDigest() {
    }

    /**
     * True when the freshly computed hex digest of a downloaded APK equals
     * the recorded {@code sha256:<hex>} value from the release channel.
     * Null, blank, unprefixed, or wrong-length recorded values never match.
     */
    public static boolean matches(String recordedSha256, String computedHex) {
        String recorded = normalize(recordedSha256);
        if (recorded == null || computedHex == null || computedHex.trim().isEmpty()) {
            return false;
        }
        return recorded.equals(computedHex.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The lowercase hex part of a {@code sha256:<hex>} digest, or null when
     * the input is missing, blank, does not carry the prefix (case
     * insensitive), or is not exactly 64 hex characters.
     */
    public static String normalize(String recordedSha256) {
        if (recordedSha256 == null) {
            return null;
        }
        String trimmed = recordedSha256.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return null;
        }
        String hex = trimmed.substring(PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        if (hex.length() != SHA256_HEX_LENGTH || !hex.matches("[0-9a-f]+")) {
            return null;
        }
        return hex;
    }
}
