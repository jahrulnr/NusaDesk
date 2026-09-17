package gh.nusashell.nusadesk.domain.workspace;

import java.util.regex.Pattern;

/**
 * A folder the user picked in the system picker, exposed to the guest as a
 * workspace.
 *
 * <p>Pure Java: no Android, no filesystem, no I/O. The picked folder arrives as
 * a Storage Access Framework <em>tree document id</em> (for example
 * {@code primary:Documents/nusadesk}); PRoot however needs a real host path for
 * its {@code -b} bind, so this type is the one place that translates the id
 * into a path, and it refuses anything it cannot translate instead of guessing.
 * That refusal is what keeps a cloud provider, a broken id, or a traversal
 * attempt from ever reaching the guest mount.</p>
 *
 * <p>Provenance: the raw path is only meaningful together with the tree URI the
 * user granted (see {@code WorkspaceFolderAccess}); the path alone is not a
 * capability, because shared storage is still gated by the platform's
 * all-files access grant.</p>
 */
public final class WorkspaceFolder {

    /** Guest mount point for the workspace; the user sees it as {@code ~/nusadesk}. */
    public static final String GUEST_MOUNT_PATH = "/root/nusadesk";

    /** Mount point root of the primary shared-storage volume. */
    private static final String PRIMARY_ROOT = "/storage/emulated/0";

    /** Removable volume ids are the platform's {@code XXXX-XXXX} form. */
    private static final Pattern REMOVABLE_VOLUME = Pattern.compile("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}");

    private final String treeDocumentId;
    private final String hostPath;
    private final String displayName;

    private WorkspaceFolder(String treeDocumentId, String hostPath, String displayName) {
        this.treeDocumentId = treeDocumentId;
        this.hostPath = hostPath;
        this.displayName = displayName;
    }

    /**
     * Translate a picked tree document id into a usable workspace, or return
     * {@code null} when the id is not a local storage folder this app can bind.
     *
     * <p>Supported ids are the platform's own external-storage provider forms:
     * {@code primary:<relative>}, {@code <VOLUME-UUID>:<relative>} for a
     * removable volume, and {@code raw:/absolute/path} for providers that hand
     * back a filesystem path directly. A relative path may be empty (the volume
     * itself). Anything else — a cloud provider, a malformed id, a {@code ..}
     * segment, a relative path that is not relative — is refused.</p>
     */
    public static WorkspaceFolder fromTreeDocumentId(String treeDocumentId) {
        if (treeDocumentId == null) {
            return null;
        }
        String trimmed = treeDocumentId.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 ) {
            return null;
        }
        String volumeId = trimmed.substring(0, separator);
        String relative = trimmed.substring(separator + 1);

        if ("raw".equals(volumeId)) {
            if (!isAbsoluteSafe(relative)) {
                return null;
            }
            return new WorkspaceFolder(trimmed, relative, labelFor(relative, null));
        }
        String root;
        if ("primary".equals(volumeId)) {
            root = PRIMARY_ROOT;
        } else if (REMOVABLE_VOLUME.matcher(volumeId).matches()) {
            root = "/storage/" + volumeId.toUpperCase(java.util.Locale.US);
        } else {
            return null;
        }
        if (!isRelativeSafe(relative)) {
            return null;
        }
        String hostPath = relative.isEmpty() ? root : root + "/" + relative;
        return new WorkspaceFolder(trimmed, hostPath, labelFor(relative, volumeId));
    }

    /**
     * Rebuild a stored workspace without re-parsing, for a value that was already
     * accepted once. Returns {@code null} when the pair is not self-consistent,
     * so a hand-edited preference file cannot reintroduce an unusable path.
     */
    public static WorkspaceFolder restore(String treeDocumentId, String hostPath, String displayName) {
        WorkspaceFolder parsed = fromTreeDocumentId(treeDocumentId);
        if (parsed == null || !parsed.getHostPath().equals(hostPath)) {
            return null;
        }
        String label = displayName == null || displayName.trim().isEmpty()
                ? parsed.getDisplayName()
                : displayName;
        return new WorkspaceFolder(treeDocumentId, hostPath, label);
    }

    /**
     * A workspace the app computes itself rather than one derived from a picked
     * tree id: the app's own external files folder, used on platforms that cannot
     * bind a shared folder at all. The path is validated exactly like a picked
     * one, so a computed path cannot widen the bind either.
     *
     * @return the workspace, or null when the id or path is not usable
     */
    public static WorkspaceFolder ofHostPath(String id, String hostPath, String displayName) {
        if (id == null || id.trim().isEmpty() || !isAbsoluteSafe(hostPath)) {
            return null;
        }
        String label = displayName == null || displayName.trim().isEmpty()
                ? hostPath
                : displayName;
        return new WorkspaceFolder(id, hostPath, label);
    }

    /** The tree document id this workspace was derived from. */
    public String getTreeDocumentId() {
        return treeDocumentId;
    }

    /** Absolute host path bound into the guest. Never null for a live instance. */
    public String getHostPath() {
        return hostPath;
    }

    /** Short label for the UI: the folder path, or the volume when it is the root. */
    public String getDisplayName() {
        return displayName;
    }

    private static boolean isRelativeSafe(String relative) {
        if (relative.isEmpty()) {
            return true;
        }
        if (relative.charAt(0) == '/' || relative.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAbsoluteSafe(String path) {
        if (path.isEmpty() || path.charAt(0) != '/' || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static String labelFor(String relative, String volumeId) {
        if (!relative.isEmpty()) {
            return relative;
        }
        return volumeId == null ? "storage" : volumeId;
    }

    @Override
    public String toString() {
        return "WorkspaceFolder{" + treeDocumentId + " -> " + hostPath + "}";
    }
}
