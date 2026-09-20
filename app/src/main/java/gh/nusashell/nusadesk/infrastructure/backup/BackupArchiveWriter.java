package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.application.backup.BackupProgress;
import gh.nusashell.nusadesk.application.backup.BackupProgressListener;
import gh.nusashell.nusadesk.domain.backup.BackupManifest;
import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.BackupScopePolicy;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * Streams a guest backup archive to an {@link OutputStream}: the
 * {@code manifest.json} first entry, then the payload trees as gzipped tar.
 *
 * <p>Pure {@link java.nio.file} — no Android types — so writer/reader
 * round-trips run in JVM tests over byte-array streams. A {@link Tree} is one
 * archived subtree; trees rooted inside the rootfs carry their rootfs-relative
 * base so {@link BackupScopePolicy} exclusions apply to the same spellings the
 * restore side validates. The workspace bind target's directory entry is
 * emitted (so the mount point survives a restore) but its contents are never
 * archived, and the virtual top-level dirs ({@code proc}, {@code sys},
 * {@code dev}, {@code run}, {@code tmp}) are skipped wholesale.</p>
 */
public final class BackupArchiveWriter {

    private static final int REPORT_ENTRY_STEP = 64;
    private static final long REPORT_BYTE_STEP = 4L * 1024L * 1024L;

    /**
     * One subtree to archive: {@code prefix} is the archive path base
     * ({@code "rootfs"}, {@code "rootfs/root"}, {@code "addons/<id>"},
     * {@code "state"}), {@code source} the host directory to walk, and
     * {@code rootfsBase} the rootfs-relative base used for exclusion checks —
     * {@code ""} for the whole rootfs, {@code "root"} for a subtree, or
     * {@code null} for non-rootfs trees (add-ons, state) that take no
     * exclusions.
     */
    static final class Tree {
        final String prefix;
        final Path source;
        final String rootfsBase;

        Tree(String prefix, Path source, String rootfsBase) {
            this.prefix = prefix;
            this.source = source;
            this.rootfsBase = rootfsBase;
        }

        /** Rootfs-relative spelling of {@code rel} for exclusion checks. */
        String guestRelative(String rel) {
            if (rootfsBase == null) {
                return null;
            }
            if (rel.isEmpty()) {
                return rootfsBase;
            }
            return rootfsBase.isEmpty() ? rel : rootfsBase + "/" + rel;
        }
    }

    /** Receives each walked node; implemented by the counting and emit passes. */
    private interface Sink {
        void directory(String archiveName, Path dir) throws IOException;

        void file(String archiveName, Path file, long size) throws IOException;

        void symlink(String archiveName, Path link) throws IOException;
    }

    /** Counts payload entries and bytes for the manifest's declared totals. */
    private static final class CountingSink implements Sink {
        long entries;
        long bytes;

        public void directory(String archiveName, Path dir) {
            entries++;
        }

        public void file(String archiveName, Path file, long size) {
            entries++;
            bytes += size;
        }

        public void symlink(String archiveName, Path link) {
            entries++;
        }
    }

    private final class EmittingSink implements Sink {
        private final TarArchiveOutputStream tar;
        private final BackupProgressListener listener;
        private long entries;
        private long bytes;
        private long lastReportedEntries;
        private long lastReportedBytes;

        EmittingSink(TarArchiveOutputStream tar, BackupProgressListener listener) {
            this.tar = tar;
            this.listener = listener;
        }

        public void directory(String archiveName, Path dir) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry(
                    archiveName + "/", TarConstants.LF_DIR);
            entry.setMode(modeOf(dir, 0755));
            entry.setModTime(dir.toFile().lastModified());
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            count(1, 0);
        }

        public void file(String archiveName, Path file, long size) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry(archiveName, TarConstants.LF_NORMAL);
            entry.setSize(size);
            entry.setMode(modeOf(file, 0644));
            entry.setModTime(file.toFile().lastModified());
            tar.putArchiveEntry(entry);
            Files.copy(file, tar);
            tar.closeArchiveEntry();
            count(1, size);
        }

        public void symlink(String archiveName, Path link) throws IOException {
            TarArchiveEntry entry = new TarArchiveEntry(archiveName, TarConstants.LF_SYMLINK);
            entry.setLinkName(Files.readSymbolicLink(link).toString());
            entry.setMode(0777);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            count(1, 0);
        }

        private void count(long newEntries, long newBytes) {
            entries += newEntries;
            bytes += newBytes;
            if (listener == null) {
                return;
            }
            if (entries - lastReportedEntries >= REPORT_ENTRY_STEP
                    || bytes - lastReportedBytes >= REPORT_BYTE_STEP) {
                lastReportedEntries = entries;
                lastReportedBytes = bytes;
                listener.onProgress(new BackupProgress(entries, bytes, "Exporting"));
            }
        }
    }

    /**
     * Writes {@code trees} as a gzipped tar archive to {@code destination},
     * with the manifest as the first entry. The stream is closed on return.
     *
     * @param manifestRoots the guest-absolute roots actually archived (empty
     *                      for FULL); recorded in the manifest verbatim.
     * @return the manifest that was written, with counted entry/byte totals.
     */
    public BackupManifest write(List<Tree> trees, BackupMode mode, List<String> manifestRoots,
            String runtimeAppId, String runtimeVersion, String appVersion,
            OutputStream destination, BackupProgressListener listener) throws IOException {
        CountingSink counting = new CountingSink();
        for (Tree tree : trees) {
            walk(tree, counting);
        }
        BackupManifest manifest = new BackupManifest(
                BackupManifest.FORMAT_VERSION, mode, runtimeAppId, runtimeVersion,
                appVersion, System.currentTimeMillis(), manifestRoots,
                counting.entries, counting.bytes);

        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(new BufferedOutputStream(destination)))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);

            byte[] manifestBytes = BackupManifestCodec.encode(manifest);
            TarArchiveEntry manifestEntry = new TarArchiveEntry(
                    BackupManifestCodec.ENTRY_NAME, TarConstants.LF_NORMAL);
            manifestEntry.setSize(manifestBytes.length);
            manifestEntry.setMode(0644);
            tar.putArchiveEntry(manifestEntry);
            tar.write(manifestBytes);
            tar.closeArchiveEntry();

            EmittingSink emitting = new EmittingSink(tar, listener);
            for (Tree tree : trees) {
                walk(tree, emitting);
            }
            tar.finish();
            if (listener != null) {
                listener.onProgress(new BackupProgress(
                        emitting.entries, emitting.bytes, "Exporting"));
            }
        }
        return manifest;
    }

    /**
     * Depth-first walk of {@code tree.source} feeding {@code sink}. Applies
     * the shared exclusions for rootfs trees, emits the workspace mount-point
     * directory without its contents, stores symlinks as links, and skips
     * non-regular non-directory files (fifos, sockets, device nodes — runtime
     * ephemera that must never be restored).
     */
    private void walk(final Tree tree, final Sink sink) throws IOException {
        Files.walkFileTree(tree.source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                String rel = relativize(tree.source, dir);
                String guestRel = tree.guestRelative(rel);
                if (guestRel != null && BackupScopePolicy.isExcludedFromArchive(guestRel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                sink.directory(archiveName(tree, rel), dir);
                if (BackupScopePolicy.WORKSPACE_RELATIVE_PATH.equals(guestRel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String rel = relativize(tree.source, file);
                String guestRel = tree.guestRelative(rel);
                if (guestRel != null && BackupScopePolicy.isExcludedFromArchive(guestRel)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = archiveName(tree, rel);
                if (attrs.isSymbolicLink()) {
                    sink.symlink(name, file);
                } else if (attrs.isRegularFile()) {
                    sink.file(name, file, attrs.size());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception)
                    throws IOException {
                // A file that cannot be read fails the backup honestly instead
                // of silently producing an archive that misses user data.
                throw exception;
            }
        });
    }

    private static String relativize(Path base, Path path) {
        Path relative = base.relativize(path);
        return relative.toString().replace('\\', '/');
    }

    private static String archiveName(Tree tree, String rel) {
        return rel.isEmpty() ? tree.prefix : tree.prefix + "/" + rel;
    }

    /** POSIX permission bits of {@code path}, or {@code fallback} when unsupported. */
    private static int modeOf(Path path, int fallback) {
        try {
            Set<PosixFilePermission> permissions =
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            int mode = 0;
            if (permissions.contains(PosixFilePermission.OWNER_READ)) {
                mode |= 0400;
            }
            if (permissions.contains(PosixFilePermission.OWNER_WRITE)) {
                mode |= 0200;
            }
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                mode |= 0100;
            }
            if (permissions.contains(PosixFilePermission.GROUP_READ)) {
                mode |= 0040;
            }
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)) {
                mode |= 0020;
            }
            if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) {
                mode |= 0010;
            }
            if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
                mode |= 0004;
            }
            if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                mode |= 0002;
            }
            if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                mode |= 0001;
            }
            return mode;
        } catch (UnsupportedOperationException | IOException exception) {
            return fallback;
        }
    }
}
