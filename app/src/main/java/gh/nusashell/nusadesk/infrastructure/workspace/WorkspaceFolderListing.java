package gh.nusashell.nusadesk.infrastructure.workspace;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Listing rules of the in-app workspace folder browser (ADR-0047).
 *
 * <p>Pure {@link java.io.File} — no Android, no adapter — so the rules are
 * testable on the JVM: the browser may only walk directories inside its root,
 * only child <em>directories</em> are offered, and the parent row disappears at
 * the root. A directory that cannot be listed yields no rows instead of an
 * error, because the caller (the browser) shows the empty folder as-is.</p>
 */
public final class WorkspaceFolderListing {

    private WorkspaceFolderListing() {
    }

    /**
     * @return the child directories of {@code directory}, sorted by name
     *         (case-insensitive), or an empty list when it cannot be listed
     */
    public static List<File> childFolders(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return Collections.emptyList();
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return Collections.emptyList();
        }
        List<File> folders = new ArrayList<>();
        for (File child : children) {
            if (child != null && child.isDirectory() && !child.isHidden()) {
                folders.add(child);
            }
        }
        folders.sort((first, second) -> first.getName()
                .compareToIgnoreCase(second.getName()));
        return folders;
    }

    /** Whether {@code directory} is {@code root} itself or inside it. */
    public static boolean isInside(File root, File directory) {
        if (root == null || directory == null) {
            return false;
        }
        String rootPath = root.getAbsolutePath();
        String path = directory.getAbsolutePath();
        return path.equals(rootPath) || path.startsWith(rootPath + File.separator);
    }

    /**
     * The parent of {@code directory}, or {@code null} when it is the root
     * itself or above it — the browser never leaves its root.
     */
    public static File parentWithin(File root, File directory) {
        if (root == null || directory == null) {
            return null;
        }
        if (directory.getAbsolutePath().equals(root.getAbsolutePath())) {
            return null;
        }
        File parent = directory.getParentFile();
        return parent != null && isInside(root, parent) ? parent : null;
    }
}
