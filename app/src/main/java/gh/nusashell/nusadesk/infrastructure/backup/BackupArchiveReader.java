package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.application.backup.BackupFailure;
import gh.nusashell.nusadesk.application.backup.BackupFailureException;
import gh.nusashell.nusadesk.domain.backup.BackupManifest;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Opens one backup archive stream: validates that the first tar entry is a
 * readable {@code manifest.json}, decodes it, and hands the still-open tar
 * stream back positioned at the first payload entry so the caller can extract
 * in the same single pass — the archive is never read twice.
 */
public final class BackupArchiveReader {

    /** Manifests are a few hundred bytes; cap defends against a hostile size. */
    private static final long MANIFEST_CAP_BYTES = 256L * 1024L;

    /** An open archive: decoded manifest plus the tar positioned after it. */
    public static final class Opened implements Closeable {
        private final TarArchiveInputStream tar;
        private final BackupManifest manifest;

        private Opened(TarArchiveInputStream tar, BackupManifest manifest) {
            this.tar = tar;
            this.manifest = manifest;
        }

        public BackupManifest getManifest() {
            return manifest;
        }

        /** The tar stream, positioned at the first payload entry. */
        public TarArchiveInputStream getTar() {
            return tar;
        }

        @Override
        public void close() throws IOException {
            tar.close();
        }
    }

    /**
     * @return the opened archive, or a typed {@link BackupFailureException} —
     *         {@code manifest-missing} when the first entry is not the
     *         manifest, {@code unsupported-format} when it cannot be decoded,
     *         {@code io-failure} when the stream itself is unreadable.
     */
    public Opened open(InputStream source) throws BackupFailureException {
        if (source == null) {
            throw new IllegalArgumentException("source stream must not be null");
        }
        TarArchiveInputStream tar;
        try {
            tar = new TarArchiveInputStream(
                    new GzipCompressorInputStream(new BufferedInputStream(source)));
        } catch (IOException exception) {
            throw new BackupFailureException(
                    BackupFailure.IO_FAILURE, "could not read the backup file", exception);
        }
        try {
            TarArchiveEntry first = tar.getNextTarEntry();
            if (first == null || !first.isFile()
                    || !BackupManifestCodec.ENTRY_NAME.equals(normalizeName(first.getName()))) {
                throw new BackupFailureException(
                        BackupFailure.MANIFEST_MISSING,
                        "the archive does not start with a backup manifest");
            }
            BackupManifest manifest;
            try {
                manifest = BackupManifestCodec.decode(readManifest(tar, first));
            } catch (BackupManifestCodec.Malformed exception) {
                throw new BackupFailureException(
                        BackupFailure.UNSUPPORTED_FORMAT,
                        "the backup manifest is not readable", exception);
            }
            return new Opened(tar, manifest);
        } catch (IOException exception) {
            closeQuietly(tar);
            throw new BackupFailureException(
                    BackupFailure.IO_FAILURE, "could not read the backup file", exception);
        } catch (BackupFailureException exception) {
            closeQuietly(tar);
            throw exception;
        }
    }

    private static byte[] readManifest(TarArchiveInputStream tar, TarArchiveEntry entry)
            throws IOException, BackupFailureException {
        long size = entry.getSize();
        if (size < 0 || size > MANIFEST_CAP_BYTES) {
            throw new BackupFailureException(
                    BackupFailure.UNSUPPORTED_FORMAT, "the backup manifest is too large");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) Math.min(size, 8192));
        byte[] chunk = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = tar.read(chunk, 0, (int) Math.min(chunk.length, remaining));
            if (read < 0) {
                break;
            }
            buffer.write(chunk, 0, read);
            remaining -= read;
        }
        return buffer.toByteArray();
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name;
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void closeQuietly(TarArchiveInputStream tar) {
        try {
            tar.close();
        } catch (IOException ignored) {
            // The open already failed; the close failure adds nothing.
        }
    }
}
