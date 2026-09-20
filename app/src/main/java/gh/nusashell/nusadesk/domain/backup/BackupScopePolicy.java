package gh.nusashell.nusadesk.domain.backup;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which guest subtrees a backup may contain.
 *
 * <p>Owns three facts of the backup format: the fixed {@link #HOME} root set,
 * the CUSTOM allowlist of top-level guest directories, and the export-side
 * exclusions that apply to every mode. Excluded paths are the bind mount
 * targets inside the guest — the workspace folder ({@code root/nusadesk}) and
 * the add-on overlays ({@code opt/lw-ssh}, {@code opt/lw-services}) — where the
 * directory entry itself is kept so the mount point survives a restore but
 * nothing under it is ever archived (the overlay payloads travel as
 * {@code addons/<id>} trees), plus the virtual top-level directories
 * {@code proc}, {@code sys}, {@code dev}, {@code run} and {@code tmp} with
 * everything below them.</p>
 *
 * <p>All paths handled here are rootfs-relative, slash-separated and carry no
 * leading slash ({@code "usr/local"}); the allowlist literals in
 * {@link #allowedCustomRoots()} are the guest-absolute spellings shown in the
 * UI ({@code "/usr/local"}).</p>
 */
public final class BackupScopePolicy {

    /** Top-level guest directories never archived, as rootfs-relative names. */
    private static final Set<String> EXCLUDED_TOP_LEVEL = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("proc", "sys", "dev", "run", "tmp")));

    /** Rootfs-relative workspace bind target whose contents are never archived. */
    public static final String WORKSPACE_RELATIVE_PATH = "root/nusadesk";

    /**
     * Rootfs-relative add-on overlay mount points whose contents are never
     * archived. The guest-side provisioning creates them with mode {@code 000}
     * (unreadable even for the app that owns them), and their payloads are the
     * separately archived {@code addons/<id>} trees, so descending into them
     * can only fail an export or duplicate data.
     */
    private static final Set<String> OVERLAY_MOUNT_POINTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    relativeOf(CuratedRuntimeCatalog.SSH_OVERLAY_GUEST_DIR),
                    relativeOf(CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR))));

    /**
     * Rootfs-relative device trees the session binds in (ADR-0045). They belong
     * to the device, not to the guest: a backup keeps their mount-point entries
     * so a restore reproduces the layout, but never the device's own files —
     * those would be a foreign, device-specific copy inside someone's rootfs.
     */
    private static final Set<String> TOOL_TREE_MOUNT_POINTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("system", "apex", "linkerconfig")));

    /** Fixed root set for {@link BackupMode#HOME}, guest-absolute spellings. */
    private static final List<String> HOME_ROOTS = Collections.unmodifiableList(
            Arrays.asList("/root", "/home"));

    /** Top-level guest directories a CUSTOM backup may select. */
    private static final List<String> CUSTOM_ROOTS = Collections.unmodifiableList(
            Arrays.asList("/root", "/home", "/opt", "/usr/local", "/etc", "/var/lib"));

    private BackupScopePolicy() {
    }

    /** @return the immutable CUSTOM allowlist, in UI display order. */
    public static List<String> allowedCustomRoots() {
        return CUSTOM_ROOTS;
    }

    /** @return the immutable HOME root set ({@code /root}, {@code /home}). */
    public static List<String> homeRoots() {
        return HOME_ROOTS;
    }

    /**
     * True when a rootfs-relative path must not be archived: it is, or sits
     * under, a virtual top-level directory, or it sits under a bind mount
     * target (the workspace folder or an add-on overlay). The mount-point
     * directories themselves return false so their entries are still emitted
     * and the mount points survive a restore.
     */
    public static boolean isExcludedFromArchive(String rootfsRelativePath) {
        if (rootfsRelativePath == null || rootfsRelativePath.isEmpty()) {
            return false;
        }
        String normalized = normalizeRelative(rootfsRelativePath);
        String first = normalized;
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            first = normalized.substring(0, slash);
        }
        if (EXCLUDED_TOP_LEVEL.contains(first)) {
            return true;
        }
        if (normalized.startsWith(WORKSPACE_RELATIVE_PATH + "/")) {
            return true;
        }
        for (String mountPoint : OVERLAY_MOUNT_POINTS) {
            if (normalized.startsWith(mountPoint + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a bind mount target inside the guest whose directory entry is
     * archived but whose contents never are. The walk emits the entry so the
     * mount point survives a restore, then skips the subtree — it cannot even
     * be listed: the guest-side provisioning creates these directories with
     * mode {@code 000} (the workspace folder and the add-on overlays alike),
     * so descending into one fails the export with an {@code io-failure}.
     */
    public static boolean isMountPointWithoutContents(String rootfsRelativePath) {
        if (rootfsRelativePath == null || rootfsRelativePath.isEmpty()) {
            return false;
        }
        String normalized = normalizeRelative(rootfsRelativePath);
        return normalized.equals(WORKSPACE_RELATIVE_PATH)
                || OVERLAY_MOUNT_POINTS.contains(normalized)
                || TOOL_TREE_MOUNT_POINTS.contains(normalized);
    }

    /**
     * Validates and canonicalises a CUSTOM selection.
     *
     * @return the distinct allowed roots in allowlist order.
     * @throws IllegalArgumentException when the selection is empty or holds a
     *         root outside the allowlist.
     */
    public static List<String> validateCustomRoots(List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "a custom backup needs at least one allowed folder");
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String root : roots) {
            String canonical = canonicalGuestPath(root);
            if (!CUSTOM_ROOTS.contains(canonical)) {
                throw new IllegalArgumentException(
                        "folder is outside the custom allowlist: " + root);
            }
            requested.add(canonical);
        }
        List<String> ordered = new ArrayList<>(requested.size());
        for (String allowed : CUSTOM_ROOTS) {
            if (requested.contains(allowed)) {
                ordered.add(allowed);
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Validates that a manifest's {@code roots} value matches its mode:
     * FULL carries none, HOME a non-empty subset of {@link #homeRoots()}, and
     * CUSTOM a non-empty subset of the allowlist.
     */
    public static void validateManifestRoots(BackupMode mode, List<String> roots) {
        if (mode == null) {
            throw new IllegalArgumentException("backup mode must not be null");
        }
        List<String> safe = roots == null ? Collections.emptyList() : roots;
        switch (mode) {
            case FULL:
                if (!safe.isEmpty()) {
                    throw new IllegalArgumentException("a full backup has no selected roots");
                }
                return;
            case HOME:
                if (safe.isEmpty() || !HOME_ROOTS.containsAll(safe)) {
                    throw new IllegalArgumentException(
                            "a home backup may only list /root and /home");
                }
                return;
            case CUSTOM:
                validateCustomRoots(safe);
                return;
            default:
                throw new IllegalArgumentException("unknown backup mode: " + mode);
        }
    }

    /**
     * Canonicalises a guest path spelling: leading/trailing slashes and inner
     * {@code ./} segments are removed, and {@code ..} or an empty result is
     * rejected so a selection can never point outside the allowlist shape.
     */
    public static String canonicalGuestPath(String guestPath) {
        if (guestPath == null) {
            throw new IllegalArgumentException("guest path must not be null");
        }
        String trimmed = guestPath.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("guest path must not be empty");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new IllegalArgumentException(
                        "guest path may not contain '..': " + guestPath);
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("guest path must not be empty");
        }
        StringBuilder out = new StringBuilder("/");
        for (String segment : segments) {
            if (out.length() > 1) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    /** @return the rootfs-relative spelling of a guest-absolute root. */
    public static String relativeOf(String guestAbsoluteRoot) {
        String canonical = canonicalGuestPath(guestAbsoluteRoot);
        return canonical.substring(1);
    }

    private static String normalizeRelative(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
