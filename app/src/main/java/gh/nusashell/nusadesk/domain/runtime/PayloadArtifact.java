package gh.nusashell.nusadesk.domain.runtime;

import java.util.Locale;

/**
 * Immutable, allowlisted metadata for one downloadable payload artifact.
 *
 * <p>An artifact is a single upstream file (for example a pinned Ubuntu
 * {@code .deb}) whose URL, SHA-256 digest, and byte sizes are catalog data
 * reviewed before changing — never user-provided installation input. The
 * guest ABI is intentionally separate from the Android APK ABI.</p>
 */
public final class PayloadArtifact {
    private final String artifactId;
    private final String packageVersion;
    private final String downloadUrl;
    private final String sha256;
    private final String guestAbi;
    private final long compressedBytes;
    private final long uncompressedBytes;

    public PayloadArtifact(
            String artifactId,
            String packageVersion,
            String downloadUrl,
            String sha256,
            String guestAbi,
            long compressedBytes,
            long uncompressedBytes) {
        this.artifactId = requireSegment(artifactId, "artifactId");
        this.packageVersion = requireText(packageVersion, "packageVersion");
        this.downloadUrl = requireText(downloadUrl, "downloadUrl");
        this.sha256 = requireSha256(sha256);
        this.guestAbi = requireText(guestAbi, "guestAbi");
        if (!downloadUrl.startsWith("https://")) {
            throw new IllegalArgumentException("downloadUrl must use HTTPS");
        }
        if (compressedBytes < 1 || uncompressedBytes < 1) {
            throw new IllegalArgumentException("payload sizes are invalid");
        }
        this.compressedBytes = compressedBytes;
        this.uncompressedBytes = uncompressedBytes;
    }

    /** Single-segment id safe for use as a filename component. */
    public String getArtifactId() {
        return artifactId;
    }

    /** Upstream package version recorded for provenance. */
    public String getPackageVersion() {
        return packageVersion;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public String getGuestAbi() {
        return guestAbi;
    }

    public long getCompressedBytes() {
        return compressedBytes;
    }

    public long getUncompressedBytes() {
        return uncompressedBytes;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireSegment(String value, String field) {
        String text = requireText(value, field);
        if (text.indexOf('/') >= 0 || text.indexOf('\\') >= 0
                || text.indexOf('\0') >= 0 || "..".equals(text) || ".".equals(text)) {
            throw new IllegalArgumentException(field + " must be a single path segment: " + text);
        }
        return text;
    }

    private static String requireSha256(String value) {
        String digest = requireText(value, "sha256").toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-character hex digest");
        }
        return digest;
    }
}
