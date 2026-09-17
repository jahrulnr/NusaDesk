package gh.nusashell.nusadesk.domain.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Curated guest add-on profile: an ordered, pinned set of upstream artifacts
 * plus packaged vendored files whose contents install into a private overlay
 * directory that the runtime binds into the guest.
 *
 * <p>The profile is the allowlisted unit of trust: artifact URLs, digests,
 * and sizes are fixed product data reviewed in the catalog; nothing here is
 * user input. {@link #getGuestDir()} is the fixed guest-visible mount point
 * the overlay is bound at, and {@link #getEntrypoint()} is the guest-relative
 * executable path that must exist after a verified install. The entrypoint is
 * always an AArch64 ELF — for a script-driven add-on it is the interpreter,
 * not the script — so the installer can keep one uniform ABI check.</p>
 */
public final class GuestAddonPayloadProfile {
    private final String addonId;
    private final String displayName;
    private final String version;
    private final String guestDir;
    private final String entrypoint;
    private final List<PayloadArtifact> artifacts;
    private final List<VendoredFile> vendoredFiles;
    private final List<String> requiredFiles;
    private final List<String> requiredRootfsTools;

    public GuestAddonPayloadProfile(
            String addonId,
            String displayName,
            String version,
            String guestDir,
            String entrypoint,
            List<PayloadArtifact> artifacts,
            List<VendoredFile> vendoredFiles,
            List<String> requiredFiles,
            List<String> requiredRootfsTools) {
        this.addonId = requireSegment(addonId, "addonId");
        this.displayName = requireText(displayName, "displayName");
        this.version = requireText(version, "version");
        this.guestDir = requireGuestDir(guestDir);
        this.entrypoint = requireEntrypoint(entrypoint);
        this.artifacts = immutableList(artifacts, "artifacts");
        if (this.artifacts.isEmpty()) {
            throw new IllegalArgumentException("artifacts must not be empty");
        }
        this.vendoredFiles = immutableList(vendoredFiles, "vendoredFiles");
        this.requiredFiles = immutablePathList(requiredFiles, "requiredFiles");
        this.requiredRootfsTools =
                immutablePathList(requiredRootfsTools, "requiredRootfsTools");
        for (VendoredFile vendored : this.vendoredFiles) {
            if (vendored == null) {
                throw new IllegalArgumentException("vendoredFiles must not contain null");
            }
        }
    }

    public String getAddonId() {
        return addonId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getVersion() {
        return version;
    }

    /** Fixed guest-visible directory the overlay is bind-mounted at. */
    public String getGuestDir() {
        return guestDir;
    }

    /**
     * Guest-relative executable path (under {@link #getGuestDir()}) that must
     * exist after install and be an AArch64 ELF.
     */
    public String getEntrypoint() {
        return entrypoint;
    }

    /** Downloaded, digest-pinned {@code .deb} artifacts, in install order. */
    public List<PayloadArtifact> getArtifacts() {
        return artifacts;
    }

    /**
     * Files vendored into the app package and copied into the overlay at
     * install time, each verified against its pinned digest.
     */
    public List<VendoredFile> getVendoredFiles() {
        return vendoredFiles;
    }

    /**
     * Additional guest-relative overlay paths that must resolve after install
     * (e.g. a vendored script or a payload-shipped symlink). Checked after the
     * entrypoint's ELF validation; entries may be symlinks.
     */
    public List<String> getRequiredFiles() {
        return requiredFiles;
    }

    /**
     * Guest-relative paths that must exist inside the already-installed
     * rootfs because the profile's guest-side setup consumes them
     * (e.g. {@code usr/bin/perl} for the SSH account setup).
     */
    public List<String> getRequiredRootfsTools() {
        return requiredRootfsTools;
    }

    /** Sum of all artifact download sizes; used for storage and progress. */
    public long totalCompressedBytes() {
        long total = 0;
        for (PayloadArtifact artifact : artifacts) {
            total += artifact.getCompressedBytes();
        }
        return total;
    }

    /** Sum of all artifact extraction sizes; the staging size cap. */
    public long totalUncompressedBytes() {
        long total = 0;
        for (PayloadArtifact artifact : artifacts) {
            total += artifact.getUncompressedBytes();
        }
        return total;
    }

    private static <T> List<T> immutableList(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> immutablePathList(List<String> values, String field) {
        List<String> copy = new ArrayList<>();
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        for (String value : values) {
            if (value == null || value.trim().isEmpty()
                    || value.startsWith("/") || value.contains("..")
                    || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        field + " entries must be relative guest paths: " + value);
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
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

    private static String requireGuestDir(String value) {
        String text = requireText(value, "guestDir");
        if (!text.startsWith("/") || text.contains("..") || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("guestDir must be an absolute guest path");
        }
        return text;
    }

    private static String requireEntrypoint(String value) {
        String text = requireText(value, "entrypoint");
        if (text.startsWith("/") || text.contains("..") || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("entrypoint must be a relative guest path");
        }
        return text;
    }
}
