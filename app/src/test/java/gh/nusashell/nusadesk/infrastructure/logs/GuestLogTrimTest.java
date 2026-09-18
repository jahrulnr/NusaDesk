package gh.nusashell.nusadesk.infrastructure.logs;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The trim is the only rotation that is safe while a guest service holds the
 * file open in append mode: it rewrites the same inode rather than renaming
 * it. These tests pin the bounds and the line-boundary behaviour.
 */
public class GuestLogTrimTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    public void aFileAlreadyUnderTheLimitIsLeftUntouched() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");
        Path file = write(dir, "a.log", "one\ntwo\nthree\n");
        long before = Files.size(file);

        assertFalse(GuestLogTrim.shrinkToTail(file, before + 100));
        assertEquals("one\ntwo\nthree\n", read(file));
        assertFalse(GuestLogTrim.shrinkToTail(file, before));
        assertEquals("one\ntwo\nthree\n", read(file));
    }

    @Test
    public void anOvergrownFileKeepsOnlyItsNewestBytes() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");
        Path file = write(dir, "a.log",
                "old line that must go away\nsecond line\nthird line\n");

        assertTrue(GuestLogTrim.shrinkToTail(file, 20));
        String kept = read(file);
        assertTrue(kept.endsWith("third line\n"));
        assertTrue(Files.size(file) <= 20);
        assertFalse(kept.contains("old line"));
    }

    @Test
    public void theKeptTailStartsOnALineBoundary() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");
        Path file = write(dir, "a.log", "aaaaaaaaaaaaaaaa\nbbbb\ncccc\n");

        assertTrue(GuestLogTrim.shrinkToTail(file, 10));
        // The newest 10 bytes are "bb\ncccc\n"-ish; the partial first line is
        // dropped, so the content begins with a complete line.
        String kept = read(file);
        assertTrue(kept.endsWith("cccc\n"));
        assertFalse(kept.isEmpty());
        assertTrue(kept.startsWith("b") || kept.startsWith("c"));
    }

    @Test
    public void aFileWithNoNewlineIsEmptiedRatherThanKept() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");
        Path file = write(dir, "a.log", "one very long line without a newline");

        assertTrue(GuestLogTrim.shrinkToTail(file, 5));
        assertEquals("", read(file));
    }

    @Test
    public void missingAndNonRegularFilesAreNoOps() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");

        assertFalse(GuestLogTrim.shrinkToTail(dir.resolve("absent.log"), 10));
        assertFalse(GuestLogTrim.shrinkToTail(dir, 10));
    }

    @Test
    public void invalidLimitsAreRejected() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");
        Path file = write(dir, "a.log", "x\n");
        for (long limit : new long[]{0L, -1L, (long) Integer.MAX_VALUE + 1L}) {
            try {
                GuestLogTrim.shrinkToTail(file, limit);
                fail("limit " + limit + " must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(true);
            }
        }
        try {
            GuestLogTrim.shrinkToTail(null, 10);
            fail("null file must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void theSameFileKeepsAcceptingAppendsAfterATrim() throws IOException {
        Path dir = Files.createTempDirectory("trim-test");
        Path file = write(dir, "a.log", "first line\nsecond line\n");

        assertTrue(GuestLogTrim.shrinkToTail(file, 15));
        // The guest writer holds an append descriptor on this same inode: the
        // next append must land in the trimmed file, not an orphaned one.
        Files.write(file, "third line\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        assertTrue(read(file).endsWith("third line\n"));
    }
}
