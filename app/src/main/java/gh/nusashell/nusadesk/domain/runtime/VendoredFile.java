package gh.nusashell.nusadesk.domain.runtime;

import java.util.Locale;

/**
 * One file vendored into the application package and installed into a curated
 * add-on overlay verbatim.
 *
 * <p>Unlike a {@link PayloadArtifact} the file is not downloaded: it ships
 * inside the APK under {@code assets/} and the installer copies it into the
 * overlay staging tree. The pinned SHA-256 is verified at install time, so a
 * corrupted or tampered packaged asset fails closed exactly like a downloaded
 * artifact whose digest mismatches. Provenance (source repository, tag,
 * commit) is recorded in the catalog call site and the third-party notices.</p>
 */
public final class VendoredFile {
    private final String assetPath;
    private final String overlayPath;
    private final boolean executable;
    private final String sha256;

    public VendoredFile(
            String assetPath,
            String overlayPath,
            boolean executable,
            String sha256) {
        this.assetPath = requireRelativePath(assetPath, "assetPath");
        this.overlayPath = requireRelativePath(overlayPath, "overlayPath");
        this.executable = executable;
        this.sha256 = requireSha256(sha256);
    }

    /** Path inside the packaged {@code assets/} directory. */
    public String getAssetPath() {
        return assetPath;
    }

    /** Guest-relative path inside the activated overlay (no leading slash). */
    public String getOverlayPath() {
        return overlayPath;
    }

    /** Whether the installed file gets mode 0755 instead of 0644. */
    public boolean isExecutable() {
        return executable;
    }

    public String getSha256() {
        return sha256;
    }

    private static String requireRelativePath(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.startsWith("/")) {
            throw new IllegalArgumentException(field + " must be a relative path: " + value);
        }
        for (String segment : value.split("/")) {
            if ("..".equals(segment) || ".".equals(segment) || segment.isEmpty()) {
                throw new IllegalArgumentException(
                        field + " must not contain traversal or empty segments: " + value);
            }
        }
        return value;
    }

    private static String requireSha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("sha256 must not be null");
        }
        String digest = value.toLowerCase(Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a 64-character hex digest");
        }
        return digest;
    }
}
