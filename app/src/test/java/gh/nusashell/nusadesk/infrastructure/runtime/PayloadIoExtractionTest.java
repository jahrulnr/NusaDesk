package gh.nusashell.nusadesk.infrastructure.runtime;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Extraction limits and modes of the shared payload extractor, both learned
 * from the ADR-0044 round trip on the S10e (2026-09-20): a real Everything
 * backup carried 53,226 entries (the cap had been sized for the curated rootfs
 * payload), and the guest's Go module cache holds 597 read-only (0500)
 * directories that a mode-before-contents extractor cannot populate.
 */
public class PayloadIoExtractionTest {

    private static final long CAP = 64L * 1024L * 1024L;

    @Test
    public void theCapCoversARealGuestBackupWithHeadroom() {
        assertTrue("a real guest backup had 53,226 entries; keep generous headroom",
                PayloadIo.MAX_ARCHIVE_ENTRIES >= 200_000);
    }

    @Test
    public void anArchiveWithSixtyThousandEntriesStillExtracts() throws Exception {
        Path staging = Files.createTempDirectory("payload-entry-cap");
        try {
            byte[] archive;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                    TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                for (int i = 0; i < 60_000; i++) {
                    writeEntry(tar, "payload/file-" + i, TarConstants.LF_NORMAL,
                            0644, new byte[0]);
                }
                tar.finish();
                archive = out.toByteArray();
            }

            extract(archive, staging);

            long extracted;
            try (Stream<Path> files = Files.list(staging.resolve("payload"))) {
                extracted = files.count();
            }
            assertEquals("every entry must be extracted", 60_000L, extracted);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * A directory that is read-only in the archive must still receive its
     * contents: the mode is applied after the payload, so it cannot lock the
     * extractor out of its own tree.
     */
    @Test
    public void aReadOnlyDirectoryStillGetsItsContentsAndItsMode() throws Exception {
        Path staging = Files.createTempDirectory("payload-readonly-dir");
        try {
            byte[] archive;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                    TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
                writeEntry(tar, "payload/cache/", TarConstants.LF_DIR, 0500, null);
                writeEntry(tar, "payload/cache/entry.txt", TarConstants.LF_NORMAL, 0444,
                        "cached\n".getBytes(StandardCharsets.UTF_8));
                tar.finish();
                archive = out.toByteArray();
            }

            extract(archive, staging);

            Path directory = staging.resolve("payload/cache");
            assertTrue("the file inside the read-only directory must exist",
                    Files.isRegularFile(directory.resolve("entry.txt")));
            assertEquals("the archive's own mode still wins at the end",
                    PosixFilePermissions.fromString("r-x------"),
                    Files.getPosixFilePermissions(directory));
        } finally {
            // The fixture left a read-only directory behind: reopen it so the
            // cleanup can remove what is inside.
            Path directory = staging.resolve("payload/cache");
            if (Files.isDirectory(directory)) {
                Files.setPosixFilePermissions(directory,
                        PosixFilePermissions.fromString("rwx------"));
            }
            deleteRecursively(staging);
        }
    }

    private static void extract(byte[] archive, Path staging) throws Exception {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ByteArrayInputStream(archive))) {
            PayloadIo.extractTar(tar, staging, CAP);
        }
    }

    private static void writeEntry(TarArchiveOutputStream tar, String name, byte type,
            int mode, byte[] body) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, type);
        entry.setMode(mode);
        entry.setSize(body == null ? 0 : body.length);
        tar.putArchiveEntry(entry);
        if (body != null) {
            tar.write(body);
        }
        tar.closeArchiveEntry();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
}
