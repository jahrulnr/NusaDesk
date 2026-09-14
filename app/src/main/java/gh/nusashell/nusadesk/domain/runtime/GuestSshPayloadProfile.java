package gh.nusashell.nusadesk.domain.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Curated guest SSH add-on profile: an ordered, pinned set of upstream
 * artifacts whose contents install into a private overlay directory that the
 * runtime binds into the guest.
 *
 * <p>The profile is the allowlisted unit of trust: artifact URLs, digests,
 * and sizes are fixed product data reviewed in the catalog; nothing here is
 * user input. {@link #getGuestDir()} is the fixed guest-visible mount point
 * the overlay is bound at, and {@link #getEntrypoint()} is the guest-relative
 * daemon path that must exist after a verified install.</p>
 */
public final class GuestSshPayloadProfile {
    /** Fixed guest-visible mount point for the activated overlay. */
    public static final String GUEST_DIR = "/opt/lw-ssh";
    /** Guest-relative daemon path the profile must provide under {@link #GUEST_DIR}. */
    public static final String ENTRYPOINT = "usr/sbin/sshd";

    private final String addonId;
    private final String displayName;
    private final String version;
    private final String guestDir;
    private final String entrypoint;
    private final List<PayloadArtifact> artifacts;

    public GuestSshPayloadProfile(
            String addonId,
            String displayName,
            String version,
            String guestDir,
            String entrypoint,
            List<PayloadArtifact> artifacts) {
        this.addonId = requireSegment(addonId, "addonId");
        this.displayName = requireText(displayName, "displayName");
        this.version = requireText(version, "version");
        this.guestDir = requireGuestDir(guestDir);
        this.entrypoint = requireEntrypoint(entrypoint);
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IllegalArgumentException("artifacts must not be empty");
        }
        List<PayloadArtifact> copy = new ArrayList<>(artifacts);
        for (PayloadArtifact artifact : copy) {
            if (artifact == null) {
                throw new IllegalArgumentException("artifacts must not contain null");
            }
        }
        this.artifacts = Collections.unmodifiableList(copy);
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

    /** Guest-relative daemon path (under {@link #getGuestDir()}) that must exist. */
    public String getEntrypoint() {
        return entrypoint;
    }

    public List<PayloadArtifact> getArtifacts() {
        return artifacts;
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
