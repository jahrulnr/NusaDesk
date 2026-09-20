package gh.nusashell.nusadesk.infrastructure.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import gh.nusashell.nusadesk.infrastructure.update.ApkDownloader.ProgressListener;
import gh.nusashell.nusadesk.infrastructure.update.ApkDownloader.UpdateSource;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The cache-write contract of the assisted install flow: the SHA-256 is
 * computed over exactly the bytes that land in the destination, the temp
 * file is atomic-rename material so a partial download can never occupy the
 * destination path, and the size cap holds whether the source declares its
 * length or not.
 */
public class ApkDownloaderTest {

    /** sha256("nusadesk-update-fixture") — anchors the digest on a known vector. */
    private static final String FIXTURE = "nusadesk-update-fixture";
    private static final String FIXTURE_SHA256 =
            "c877a0f19085e9c3d31146e563d35ab3059b99cc7812d1feea623e46bb4fa27b";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void writesThePayloadAtomicallyAndReturnsItsSha256() throws Exception {
        byte[] payload = FIXTURE.getBytes(StandardCharsets.UTF_8);
        File destination = new File(folder.getRoot(), "update.apk");

        String digest = ApkDownloader.download(
                knownLength(payload), destination, 64 * 1024 * 1024, null);

        assertEquals(FIXTURE_SHA256, digest);
        assertArrayEquals(payload, Files.readAllBytes(destination.toPath()));
        assertFalse("no temp file may survive a successful download",
                tempOf(destination).exists());
    }

    @Test
    public void reportsMonotonicProgressAgainstTheExpectedLength() throws Exception {
        byte[] payload = pattern(16 * 1024 * 2 + 100); // >2 chunks
        File destination = new File(folder.getRoot(), "update.apk");
        List<String> progress = new ArrayList<>();

        ApkDownloader.download(knownLength(payload), destination,
                payload.length, (read, total) -> progress.add(read + "/" + total));

        assertEquals(3, progress.size());
        long previous = -1;
        for (String entry : progress) {
            String[] parts = entry.split("/");
            long read = Long.parseLong(parts[0]);
            assertTrue("progress must increase monotonically", read > previous);
            assertEquals("total is the declared length", payload.length,
                    Long.parseLong(parts[1]));
            previous = read;
        }
        assertEquals(payload.length, previous);
    }

    @Test
    public void reportsMinusOneTotalWhenTheLengthIsUnknown() throws Exception {
        byte[] payload = pattern(16 * 1024 + 1); // two chunks
        File destination = new File(folder.getRoot(), "update.apk");
        List<Long> totals = new ArrayList<>();

        ApkDownloader.download(unknownLength(payload), destination,
                payload.length, (read, total) -> totals.add(total));

        assertEquals(2, totals.size());
        for (long total : totals) {
            assertEquals(-1, total);
        }
    }

    @Test
    public void rejectsADeclaredLengthAboveTheCapBeforeOpeningTheStream()
            throws Exception {
        File destination = new File(folder.getRoot(), "update.apk");
        UpdateSource oversized = new UpdateSource() {
            @Override
            public long expectedLength() {
                return 101;
            }

            @Override
            public InputStream open() {
                fail("a source above the cap must never be opened");
                return null;
            }
        };

        IOException e = assertThrows(IOException.class, () -> ApkDownloader.download(
                oversized, destination, 100, null));

        assertTrue(e.getMessage().contains("cap"));
        assertFalse(destination.exists());
        assertFalse(tempOf(destination).exists());
    }

    @Test
    public void enforcesTheCapWhileStreamingWhenTheLengthIsUnknown()
            throws Exception {
        File destination = new File(folder.getRoot(), "update.apk");

        IOException e = assertThrows(IOException.class, () -> ApkDownloader.download(
                unknownLength(pattern(101)), destination, 100, null));

        assertTrue(e.getMessage().contains("cap"));
        assertFalse(destination.exists());
        assertFalse("the over-cap temp file must be deleted",
                tempOf(destination).exists());
    }

    @Test
    public void aMidStreamFailureDeletesTheTempAndPreservesTheDestination()
            throws Exception {
        File destination = new File(folder.getRoot(), "update.apk");
        byte[] previous = "previous-good-download".getBytes(StandardCharsets.UTF_8);
        Files.write(destination.toPath(), previous);

        assertThrows(IOException.class, () -> ApkDownloader.download(
                failingAfter(64, pattern(4096)), destination, 64 * 1024 * 1024, null));

        assertFalse(tempOf(destination).exists());
        assertArrayEquals("the destination must keep its previous content",
                previous, Files.readAllBytes(destination.toPath()));
    }

    @Test
    public void aListenerFailureAlsoDeletesTheTempFile() {
        File destination = new File(folder.getRoot(), "update.apk");
        ProgressListener cancelling = (read, total) -> {
            throw new IllegalStateException("user cancelled");
        };

        assertThrows(IllegalStateException.class, () -> ApkDownloader.download(
                knownLength(pattern(1024)), destination, 64 * 1024 * 1024, cancelling));

        assertFalse(destination.exists());
        assertFalse(tempOf(destination).exists());
    }

    @Test
    public void aRetryReplacesThePreviousDestination() throws Exception {
        File destination = new File(folder.getRoot(), "update.apk");
        byte[] first = pattern(100);
        byte[] second = pattern(200);

        ApkDownloader.download(knownLength(first), destination, 1024, null);
        ApkDownloader.download(knownLength(second), destination, 1024, null);

        assertArrayEquals(second, Files.readAllBytes(destination.toPath()));
    }

    private static File tempOf(File destination) {
        return new File(destination.getPath() + ".tmp");
    }

    private static byte[] pattern(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return bytes;
    }

    private static UpdateSource knownLength(byte[] payload) {
        return new UpdateSource() {
            @Override
            public long expectedLength() {
                return payload.length;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(payload);
            }
        };
    }

    private static UpdateSource unknownLength(byte[] payload) {
        return new UpdateSource() {
            @Override
            public long expectedLength() {
                return -1;
            }

            @Override
            public InputStream open() {
                return new ByteArrayInputStream(payload);
            }
        };
    }

    /** A source that yields {@code failAfter} bytes then dies mid-stream. */
    private static UpdateSource failingAfter(int failAfter, byte[] payload) {
        return new UpdateSource() {
            @Override
            public long expectedLength() {
                return payload.length;
            }

            @Override
            public InputStream open() {
                return new InputStream() {
                    private final ByteArrayInputStream delegate =
                            new ByteArrayInputStream(payload);
                    private int remaining = failAfter;

                    @Override
                    public int read() throws IOException {
                        if (remaining <= 0) {
                            throw new IOException("simulated network failure");
                        }
                        int value = delegate.read();
                        if (value >= 0) {
                            remaining--;
                        }
                        return value;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length)
                            throws IOException {
                        if (remaining <= 0) {
                            throw new IOException("simulated network failure");
                        }
                        int read = delegate.read(
                                buffer, offset, Math.min(length, remaining));
                        if (read > 0) {
                            remaining -= read;
                        }
                        return read;
                    }
                };
            }
        };
    }
}
