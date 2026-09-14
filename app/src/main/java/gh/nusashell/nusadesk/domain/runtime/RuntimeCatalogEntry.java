package gh.nusashell.nusadesk.domain.runtime;

import java.util.Locale;
/**
 * Immutable, allowlisted payload metadata for one curated runtime profile.
 *
 * <p>The URL and digest are catalog data, not user-provided installation input.
 * The guest ABI is intentionally separate from the Android APK ABI.</p>
 */
public final class RuntimeCatalogEntry {
    private final String appId;
    private final String displayName;
    private final String version;
    private final String downloadUrl;
    private final String sha256;
    private final String guestAbi;
    private final long compressedBytes;
    private final long uncompressedBytes;

    public RuntimeCatalogEntry(
            String appId,
            String displayName,
            String version,
            String downloadUrl,
            String sha256,
            String guestAbi,
            long compressedBytes,
            long uncompressedBytes) {
        this.appId = requireText(appId, "appId");
        this.displayName = requireText(displayName, "displayName");
        this.version = requireText(version, "version");
        this.downloadUrl = requireText(downloadUrl, "downloadUrl");
        this.sha256 = requireSha256(sha256);
        this.guestAbi = requireText(guestAbi, "guestAbi");
        if (!downloadUrl.startsWith("https://")) {
            throw new IllegalArgumentException("downloadUrl must use HTTPS");
        }
        if (compressedBytes < 1 || uncompressedBytes < compressedBytes) {
            throw new IllegalArgumentException("payload sizes are invalid");
        }
        this.compressedBytes = compressedBytes;
        this.uncompressedBytes = uncompressedBytes;
    }

    public String getAppId() {
        return appId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getVersion() {
        return version;
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

    private static String requireSha256(String value) {
        String digest = requireText(value, "sha256").toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-character hex digest");
        }
        return digest;
    }
}
