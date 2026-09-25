package gh.nusashell.nusadesk.presentation.terminal;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Android-free tests for the bounded pre-ready write queue (ADR-0054). The
 * queue is the only thing standing between "host session opened" and "packaged
 * page wired its port": a fresh command tab's first output lives there, so its
 * ordering, byte accounting, and drop policy are asserted without a WebView.
 */
public class TerminalPendingWritesTest {

    @Test
    public void startsEmpty() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.sizeBytes());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    public void drainsQueuedWritesInArrivalOrder() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        queue.add("first", false);
        queue.add("second", true);
        queue.add("third", false);

        List<TerminalPendingWrites.Entry> drained = queue.drain();

        assertEquals(3, drained.size());
        assertEquals("first", drained.get(0).getText());
        assertFalse(drained.get(0).isStderr());
        assertEquals("second", drained.get(1).getText());
        assertTrue(drained.get(1).isStderr());
        assertEquals("third", drained.get(2).getText());
        assertFalse(drained.get(2).isStderr());
    }

    @Test
    public void drainEmptiesTheQueue() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        queue.add("data", false);

        assertEquals(1, queue.drain().size());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.sizeBytes());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    public void countsUtf8BytesNotChars() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        // 2 ASCII + one 2-byte + one 3-byte + one surrogate pair (4 bytes).
        queue.add("abé€😀", false);
        assertEquals(2 + 2 + 3 + 4, queue.sizeBytes());
    }

    @Test
    public void acceptsUpToExactlyTheCap() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        String max = repeat('x', TerminalPendingWrites.MAX_BYTES);
        assertTrue(queue.add(max, false));
        assertEquals(TerminalPendingWrites.MAX_BYTES, queue.sizeBytes());
        assertFalse(queue.add("y", false));
    }

    @Test
    public void dropsTheNewChunkWhenItWouldExceedTheCap() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        String nearly = repeat('x', TerminalPendingWrites.MAX_BYTES - 4);
        assertTrue(queue.add(nearly, false));

        // Would exceed the cap: dropped, while the queued chunk stays intact.
        assertFalse(queue.add(repeat('y', 8), true));
        assertEquals(TerminalPendingWrites.MAX_BYTES - 4, queue.sizeBytes());

        // A chunk that still fits is still accepted.
        assertTrue(queue.add(repeat('z', 4), false));

        List<TerminalPendingWrites.Entry> drained = queue.drain();
        assertEquals(2, drained.size());
        assertEquals(nearly, drained.get(0).getText());
        assertEquals(repeat('z', 4), drained.get(1).getText());
    }

    @Test
    public void clearDropsEveryQueuedWrite() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        queue.add("one", false);
        queue.add("two", true);

        queue.clear();

        assertTrue(queue.isEmpty());
        assertEquals(0, queue.sizeBytes());
        assertTrue(queue.drain().isEmpty());
    }

    @Test
    public void rejectsNullText() {
        TerminalPendingWrites queue = new TerminalPendingWrites();
        try {
            queue.add(null, false);
            fail("expected rejection of null text");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertTrue(queue.isEmpty());
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
