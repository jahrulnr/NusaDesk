package gh.nusashell.nusadesk.domain.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable value object for the {@code manifest.json} entry that leads every
 * NusaDesk backup archive.
 *
 * <p>Format version {@value #FORMAT_VERSION} is the only supported version.
 * {@code roots} records the selected top-level guest directories for HOME and
 * CUSTOM backups (FULL carries an empty list); {@code entries} and
 * {@code totalBytes} describe the payload that follows the manifest so the
 * reader can bound the extraction work before touching disk.</p>
 */
public final class BackupManifest {

    public static final int FORMAT_VERSION = 1;

    private final int formatVersion;
    private final BackupMode mode;
    private final String runtimeAppId;
    private final String runtimeVersion;
    private final String appVersion;
    private final long createdAtEpochMs;
    private final List<String> roots;
    private final long entries;
    private final long totalBytes;

    public BackupManifest(int formatVersion, BackupMode mode, String runtimeAppId,
            String runtimeVersion, String appVersion, long createdAtEpochMs,
            List<String> roots, long entries, long totalBytes) {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported backup format version: " + formatVersion);
        }
        if (mode == null) {
            throw new IllegalArgumentException("backup mode must not be null");
        }
        if (runtimeAppId == null || runtimeAppId.trim().isEmpty()) {
            throw new IllegalArgumentException("runtimeAppId must not be empty");
        }
        BackupScopePolicy.validateManifestRoots(mode, roots);
        if (createdAtEpochMs < 0 || entries < 0 || totalBytes < 0) {
            throw new IllegalArgumentException(
                    "manifest counters must not be negative");
        }
        this.formatVersion = formatVersion;
        this.mode = mode;
        this.runtimeAppId = runtimeAppId;
        this.runtimeVersion = runtimeVersion == null ? "" : runtimeVersion;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.createdAtEpochMs = createdAtEpochMs;
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
        this.entries = entries;
        this.totalBytes = totalBytes;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public BackupMode getMode() {
        return mode;
    }

    public String getRuntimeAppId() {
        return runtimeAppId;
    }

    public String getRuntimeVersion() {
        return runtimeVersion;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public List<String> getRoots() {
        return roots;
    }

    public long getEntries() {
        return entries;
    }

    public long getTotalBytes() {
        return totalBytes;
    }
}
