package gh.nusashell.nusadesk.infrastructure.files;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Listing rules of the built-in real-path file picker (ADR-0059): every entry a
 * directory really holds, in the order the picker shows them.
 *
 * <p>Plain {@link java.io.File} — no Android, no adapter — so the rules are
 * testable on the JVM. Directories come first, each group sorted
 * case-insensitively by name, and hidden entries are kept: this picker exists
 * for the paths behind the Linux session, where dotfiles are exactly what a
 * user picks. A directory the process cannot list yields an empty, explicitly
 * unreadable listing instead of a fabricated empty folder.</p>
 */
public final class LocalFileListing {

    /** One directory's children, split the way the picker renders them. */
    public static final class Listing {
        private final List<File> directories;
        private final List<File> files;
        private final boolean readable;

        private Listing(List<File> directories, List<File> files, boolean readable) {
            this.directories = directories;
            this.files = files;
            this.readable = readable;
        }

        /** Child directories, sorted by name. */
        public List<File> getDirectories() {
            return directories;
        }

        /** Child files (anything that is not a directory), sorted by name. */
        public List<File> getFiles() {
            return files;
        }

        /** Whether the directory could be listed at all. */
        public boolean isReadable() {
            return readable;
        }
    }

    private LocalFileListing() {
    }

    /**
     * @param directory the directory to list; null or a non-directory yields an
     *                  unreadable, empty listing
     */
    public static Listing list(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return new Listing(Collections.emptyList(), Collections.emptyList(), false);
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return new Listing(Collections.emptyList(), Collections.emptyList(), false);
        }
        List<File> directories = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                directories.add(child);
            } else {
                files.add(child);
            }
        }
        sortByName(directories);
        sortByName(files);
        return new Listing(
                Collections.unmodifiableList(directories),
                Collections.unmodifiableList(files),
                true);
    }

    /** The parent of {@code directory}, or {@code null} at the filesystem root. */
    public static File parentOf(File directory) {
        return directory == null ? null : directory.getParentFile();
    }

    private static void sortByName(List<File> entries) {
        entries.sort((first, second) -> first.getName().compareToIgnoreCase(second.getName()));
    }
}
