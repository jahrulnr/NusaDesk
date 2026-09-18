package gh.nusashell.nusadesk.infrastructure.logs;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The tail is the host's {@code tail -F}: backfill the newest bounded bytes on
 * a line boundary, then follow appends, in-place trims, and rename-replaces.
 * Poll intervals are short so the follow paths are exercised, not slept.
 */
public class GuestLogTailTest {

    private static final int POLL_MS = 25;
    private static final long WAIT_MS = 4_000L;

    /** Collects emitted chunks; {@link #awaitText} polls until they contain s. */
    private static final class Sink implements GuestLogTail.Handler {
        private final CopyOnWriteArrayList<String> chunks = new CopyOnWriteArrayList<>();
        private final CountDownLatch first = new CountDownLatch(1);

        @Override
        public void onOutput(String text) {
            chunks.add(text);
            first.countDown();
        }

        private String all() {
            StringBuilder sb = new StringBuilder();
            for (String chunk : chunks) {
                sb.append(chunk);
            }
            return sb.toString();
        }

        private boolean awaitText(String needle) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                if (all().contains(needle)) {
                    return true;
                }
                Thread.sleep(15);
            }
            return all().contains(needle);
        }
    }

    private static void append(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
    }

    @Test
    public void theInitialTailEmitsTheExistingContent() throws Exception {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("a.log");
        Files.write(file, "one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        Sink sink = new Sink();

        GuestLogTail.TailHandle tail =
                GuestLogTail.follow(file, sink, GuestLogTail.DEFAULT_INITIAL_BYTES, POLL_MS);
        try {
            assertTrue(sink.awaitText("one"));
            assertTrue(sink.awaitText("two"));
        } finally {
            tail.stop();
        }
    }

    @Test
    public void backfillKeepsTheNewestBytesOnALineBoundary() throws Exception {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("a.log");
        Files.write(file, "old line that should drop\nkept line\n".getBytes(StandardCharsets.UTF_8));
        Sink sink = new Sink();

        GuestLogTail.TailHandle tail = GuestLogTail.follow(file, sink, 20, POLL_MS);
        try {
            assertTrue(sink.awaitText("kept line"));
            assertFalse(sink.all().contains("old line"));
        } finally {
            tail.stop();
        }
    }

    @Test
    public void appendsArriveWhileFollowing() throws Exception {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("a.log");
        Files.write(file, "start\n".getBytes(StandardCharsets.UTF_8));
        Sink sink = new Sink();

        GuestLogTail.TailHandle tail = GuestLogTail.follow(file, sink, 1024, POLL_MS);
        try {
            assertTrue(sink.awaitText("start"));
            append(file, "next line\n");
            assertTrue(sink.awaitText("next line"));
        } finally {
            tail.stop();
        }
    }

    @Test
    public void aMissingFileIsFollowedOnceItAppears() throws Exception {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("later.log");
        Sink sink = new Sink();

        GuestLogTail.TailHandle tail = GuestLogTail.follow(file, sink, 1024, POLL_MS);
        try {
            Thread.sleep(120); // a few polls on a file that does not exist yet
            Files.write(file, "created later\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(sink.awaitText("created later"));
        } finally {
            tail.stop();
        }
    }

    @Test
    public void anInPlaceTrimReseeksToTheKeptTail() throws Exception {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("a.log");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append("line ").append(i).append('\n');
        }
        Files.write(file, big.toString().getBytes(StandardCharsets.UTF_8));
        Sink sink = new Sink();

        GuestLogTail.TailHandle tail = GuestLogTail.follow(file, sink, 1024, POLL_MS);
        try {
            assertTrue(sink.awaitText("line 399"));
            sink.chunks.clear();
            // Rotate in place, like the sweeper: same file, newest bytes only.
            GuestLogTrim.shrinkToTail(file, 200);
            assertTrue(sink.awaitText("line 39"));
        } finally {
            tail.stop();
        }
    }

    @Test
    public void stopEndsDelivery() throws Exception {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("a.log");
        Files.write(file, "x\n".getBytes(StandardCharsets.UTF_8));
        Sink sink = new Sink();

        GuestLogTail.TailHandle tail = GuestLogTail.follow(file, sink, 1024, POLL_MS);
        assertTrue(sink.awaitText("x"));
        tail.stop();
        int seen = sink.chunks.size();
        append(file, "after stop\n");
        Thread.sleep(4 * POLL_MS);
        assertEquals(seen, sink.chunks.size());
        tail.stop(); // idempotent
    }

    @Test
    public void invalidArgumentsAreRejected() throws IOException {
        Path dir = Files.createTempDirectory("tail-test");
        Path file = dir.resolve("a.log");
        Files.write(file, "x\n".getBytes(StandardCharsets.UTF_8));
        try {
            GuestLogTail.follow(null, text -> { }, 10, POLL_MS);
            fail("null file must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
        try {
            GuestLogTail.follow(file, null, 10, POLL_MS);
            fail("null handler must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
