package gh.nusashell.nusadesk.infrastructure.runtimehost;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Pure-Java tests for {@link BoundedOutputReader} using in-memory input. No
 * Android or real process is involved.
 */
public class BoundedOutputReaderTest {
    private final BoundedOutputReader reader = new BoundedOutputReader();
    private final LongSupplier fixedClock = () -> 1_000L;

    private static InputStream in(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void stopsWhenHandlerReturnsFalse() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 1024, 0);
        final int[] seen = {0};
        BoundedOutputReader.StopReason reason = reader.read(in("a\nb\nc\n"), limits, line -> {
            seen[0]++;
            return seen[0] < 2;
        }, fixedClock);
        assertEquals(BoundedOutputReader.StopReason.COMPLETED, reason);
        assertEquals(2, seen[0]);
    }

    @Test
    public void reachesEofWhenHandlerNeverStops() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 1024, 0);
        final int[] seen = {0};
        BoundedOutputReader.StopReason reason = reader.read(in("x\ny\n"), limits, line -> {
            seen[0]++;
            return true;
        }, fixedClock);
        assertEquals(BoundedOutputReader.StopReason.EOF, reason);
        assertEquals(2, seen[0]);
    }

    @Test
    public void handlesCrlfLineEndings() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 1024, 0);
        final String[] first = {null};
        reader.read(in("line1\r\nline2\n"), limits, line -> {
            first[0] = line;
            return false;
        }, fixedClock);
        assertEquals("line1", first[0]);
    }

    @Test
    public void deliversTrailingLineWithoutNewlineAtEof() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 1024, 0);
        final String[] last = {null};
        reader.read(in("a\nnoterminated"), limits, line -> {
            last[0] = line;
            return true;
        }, fixedClock);
        assertEquals("noterminated", last[0]);
    }

    @Test
    public void rejectsLineExceedingPerLineCap() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 4, 0);
        BoundedOutputReader.StopReason reason =
                reader.read(in("toolong\n"), limits, line -> true, fixedClock);
        assertEquals(BoundedOutputReader.StopReason.LINE_TOO_LONG, reason);
    }

    @Test
    public void stopsAfterMaxLines() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 2, 1024, 0);
        final int[] seen = {0};
        BoundedOutputReader.StopReason reason = reader.read(in("a\nb\nc\n"), limits, line -> {
            seen[0]++;
            return true;
        }, fixedClock);
        assertEquals(BoundedOutputReader.StopReason.MAX_LINES_EXCEEDED, reason);
        assertEquals(2, seen[0]);
    }

    @Test
    public void stopsAfterMaxTotalBytes() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 1024, 4);
        BoundedOutputReader.StopReason reason =
                reader.read(in("ab\ncd\nef\n"), limits, line -> true, fixedClock);
        assertEquals(BoundedOutputReader.StopReason.MAX_BYTES_EXCEEDED, reason);
    }

    @Test
    public void deadlineExceededStopsRead() throws IOException {
        AtomicLong clock = new AtomicLong(0L);
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(100, 100, 1024, 0);
        BoundedOutputReader.StopReason reason = reader.read(in("a\nb\n"), limits, line -> {
            clock.addAndGet(200L);
            return true;
        }, clock::get);
        assertEquals(BoundedOutputReader.StopReason.DEADLINE_EXCEEDED, reason);
    }

    @Test
    public void zeroDeadlineNeverExpires() throws IOException {
        BoundedOutputReader.Limits limits = new BoundedOutputReader.Limits(0, 100, 1024, 0);
        BoundedOutputReader.StopReason reason =
                reader.read(in("a\nb\nc\n"), limits, line -> true, fixedClock);
        assertEquals(BoundedOutputReader.StopReason.EOF, reason);
    }

    @Test
    public void rejectsInvalidLimits() {
        try {
            new BoundedOutputReader.Limits(-1, 1, 1, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new BoundedOutputReader.Limits(0, 0, 1, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new BoundedOutputReader.Limits(0, 1, 0, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new BoundedOutputReader.Limits(0, 1, 1, -1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
