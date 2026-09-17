package gh.nusashell.nusadesk.infrastructure.workspace;

import java.io.File;
import java.io.IOException;

/**
 * Verifies that a chosen workspace folder can actually be used before it is
 * bound into the guest.
 *
 * <p>Plain {@code java.io} only: no Android, no content resolver. The guest
 * mount is a hard requirement — PRoot binds the path, and a folder that is
 * missing, read-only, or blocked by the platform's storage rules would produce
 * a guest directory that silently fails on the first write. Probing here keeps
 * the failure at the point where the user can still act on it.</p>
 */
public final class WorkspaceDirectory {

    private WorkspaceDirectory() {
    }

    /**
     * Create the directory when it does not exist yet.
     *
     * @return true when the directory exists afterwards
     */
    public static boolean ensureExists(String hostPath) {
        if (hostPath == null || hostPath.isEmpty()) {
            return false;
        }
        File directory = new File(hostPath);
        if (directory.isDirectory()) {
            return true;
        }
        return directory.mkdirs() && directory.isDirectory();
    }

    /**
     * Create the directory when missing and prove it is writable by writing and
     * removing a uniquely named probe file.
     *
     * @return true only when a file could really be created in the directory
     */
    public static boolean ensureUsable(String hostPath) {
        if (!ensureExists(hostPath)) {
            return false;
        }
        File directory = new File(hostPath);
        File probe = null;
        try {
            probe = File.createTempFile("nusadesk-workspace-", ".probe", directory);
            return true;
        } catch (IOException | SecurityException notWritable) {
            return false;
        } finally {
            if (probe != null) {
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
            }
        }
    }
}
