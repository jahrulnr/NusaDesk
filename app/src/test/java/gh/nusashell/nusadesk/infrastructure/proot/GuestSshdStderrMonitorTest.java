package gh.nusashell.nusadesk.infrastructure.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM tests for the daemon output monitor: the first bind report is
 * surfaced, unrelated output is ignored, and stderr EOF is reported exactly
 * once as the daemon-death signal.
 */
public class GuestSshdStderrMonitorTest {

    @Test
    public void surfacesTheFirstBindReportAndThenReportsExit() throws Exception {
        PipedOutputStream output = new PipedOutputStream();
        PipedInputStream stderr = new PipedInputStream(output, 8192);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger closedCount = new AtomicInteger();
        GuestSshdStderrMonitor monitor = new GuestSshdStderrMonitor(
                stderr, emptyStream(), () -> {
                    closedCount.incrementAndGet();
                    closed.countDown();
                });
        monitor.start();

        output.write("proot info: v5.1.107.92\n".getBytes(StandardCharsets.UTF_8));
        output.write("Server listening on 127.0.0.1 port 45123.\n".getBytes(StandardCharsets.UTF_8));

        GuestSshdStartupLog.Event event = monitor.awaitBindEvent(5_000);
        assertEquals(GuestSshdStartupLog.Kind.LISTENING, event.getKind());
        assertEquals(45123, event.getPort());
        assertEquals("127.0.0.1", event.getHost());
        assertFalse(monitor.isClosed());

        // Later noise must not overwrite the recorded bind outcome.
        output.write("Connection from 127.0.0.1 port 50000 on 127.0.0.1 port 45123\n"
                .getBytes(StandardCharsets.UTF_8));
        output.close();

        assertTrue("stderr EOF must be reported", closed.await(5, TimeUnit.SECONDS));
        assertTrue(monitor.isClosed());
        assertEquals(GuestSshdStartupLog.Kind.LISTENING, monitor.awaitBindEvent(10).getKind());
        assertEquals(1, closedCount.get());
    }

    @Test
    public void surfacesBindFailure() throws Exception {
        PipedOutputStream output = new PipedOutputStream();
        PipedInputStream stderr = new PipedInputStream(output, 8192);
        GuestSshdStderrMonitor monitor = new GuestSshdStderrMonitor(stderr, emptyStream(), null);
        monitor.start();

        output.write("Bind to port 44007 on 127.0.0.1 failed: Address already in use.\n"
                .getBytes(StandardCharsets.UTF_8));

        GuestSshdStartupLog.Event event = monitor.awaitBindEvent(5_000);
        assertEquals(GuestSshdStartupLog.Kind.BIND_FAILED, event.getKind());
        output.close();
    }

    @Test
    public void timesOutWithoutAnyReportAndKeepsRecentLines() throws Exception {
        PipedOutputStream output = new PipedOutputStream();
        PipedInputStream stderr = new PipedInputStream(output, 8192);
        GuestSshdStderrMonitor monitor = new GuestSshdStderrMonitor(stderr, emptyStream(), null);
        monitor.start();

        output.write("proot warning: something odd\n".getBytes(StandardCharsets.UTF_8));

        List<String> lines = awaitRecentLines(monitor, 1);
        assertTrue(lines.toString(), lines.toString().contains("something odd"));
        assertNull(monitor.awaitBindEvent(150));
        assertFalse(monitor.isClosed());
        output.close();
    }

    @Test
    public void keepsOnlyABoundedNumberOfRecentLines() throws Exception {
        PipedOutputStream output = new PipedOutputStream();
        PipedInputStream stderr = new PipedInputStream(output, 8192);
        GuestSshdStderrMonitor monitor = new GuestSshdStderrMonitor(stderr, emptyStream(), null);
        monitor.start();
        for (int i = 0; i < 40; i++) {
            output.write(("line " + i + "\n").getBytes(StandardCharsets.UTF_8));
        }
        List<String> lines = awaitRecentLines(monitor, 16);
        assertTrue(lines.toString(), lines.size() <= 16);
        assertTrue(lines.toString(), lines.toString().contains("line 39"));
        assertFalse(lines.toString(), lines.toString().contains("line 0,"));
        output.close();
    }

    @Test
    public void reportsClosedForAnAlreadyEndedStream() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        GuestSshdStderrMonitor monitor = new GuestSshdStderrMonitor(
                emptyStream(), emptyStream(), closed::countDown);
        monitor.start();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertTrue(monitor.isClosed());
        assertNull(monitor.awaitBindEvent(10));
    }

    private static InputStream emptyStream() {
        return new ByteArrayInputStream(new ByteArrayOutputStream().toByteArray());
    }

    /** Poll until the drain thread has retained {@code expected} lines (or 5s pass). */
    private static List<String> awaitRecentLines(GuestSshdStderrMonitor monitor, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        List<String> lines = monitor.recentLines();
        while (lines.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
            lines = monitor.recentLines();
        }
        return lines;
    }
}
