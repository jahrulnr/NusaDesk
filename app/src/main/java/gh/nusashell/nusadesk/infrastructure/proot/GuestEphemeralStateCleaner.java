package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Clears the curated guest's session-temporary directories before a new PRoot
 * session starts.
 *
 * <p>The active rootfs lives in persistent app-private storage, so its guest
 * {@code /tmp} is not ephemeral by Android's definition. This cleaner gives
 * that one fixed FHS directory session semantics without touching the rest of
 * the rootfs, package database, workspace, or user home. It never follows a
 * symlink while deleting and refuses a rootfs {@code tmp} path that is itself
 * a symlink or non-directory.</p>
 */
public final class GuestEphemeralStateCleaner {

    private GuestEphemeralStateCleaner() {
    }

    /**
     * Clear the guest {@code /tmp} directory, creating it when the curated
     * rootfs omitted it. A failure is returned to the caller rather than being
     * silently ignored: continuing with stale temporary state makes the
     * session contract dishonest.
     *
     * @param activeRootfs validated active rootfs directory
     * @throws IOException when the path is unsafe or an entry cannot be removed
     */
    public static void clearGuestTmp(Path activeRootfs) throws IOException {
        if (activeRootfs == null) {
            throw new IllegalArgumentException("activeRootfs must not be null");
        }
        Path tmp = activeRootfs.resolve("tmp");
        if (!Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(tmp);
            applyPrivateTmpMode(tmp);
            return;
        }
        if (Files.isSymbolicLink(tmp) || !Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("guest /tmp is not a real directory: " + tmp);
        }
        deleteChildren(tmp);
        applyPrivateTmpMode(tmp);
    }

    private static void deleteChildren(Path directory) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                deleteEntry(child);
            }
        }
    }

    private static void deleteEntry(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            deleteChildren(path);
        }
        Files.deleteIfExists(path);
    }

    /** Keep the conventional private temporary-directory mode when supported. */
    private static void applyPrivateTmpMode(Path tmp) {
        try {
            // PosixFilePermissions does not accept the sticky-bit 't' marker;
            // apply the rwx bits first, then request the full Unix mode where
            // the provider exposes that attribute.
            Files.setPosixFilePermissions(tmp,
                    PosixFilePermissions.fromString("rwxrwxrwx"));
            try {
                Files.setAttribute(tmp, "unix:mode", 01777, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException | IOException | IllegalArgumentException ignored) {
                // The rwx mode above is still the best portable fallback.
            }
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException ignored) {
            // Some Android/FUSE providers do not expose POSIX modes. The
            // directory has still been safely created/cleaned; do not make a
            // harmless mode-view limitation prevent a session from starting.
        }
    }
}
