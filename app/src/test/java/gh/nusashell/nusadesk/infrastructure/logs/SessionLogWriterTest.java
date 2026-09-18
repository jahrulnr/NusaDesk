package gh.nusashell.nusadesk.infrastructure.logs;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The session writer is what turns the supervised process's console into the
 * guest's {@code /var/log/lw/boot.log}: timestamped lines, one-generation
 * rotation between boots, and a bounded size even when a session is chatty.
 */
public class SessionLogWriterTest {

    private static final long FIXED_TIME = 1_700_000_000_000L;

    @Test
    public void linesAreTimestampedAndWrittenInsideTheRootfs() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        SessionLogWriter writer = new SessionLogWriter(rootfs, () -> FIXED_TIME);

        writer.append("Server listening on 127.0.0.1 port 22022.");
        writer.close();

        Path log = rootfs.resolve(SessionLogWriter.LOG_DIR)
                .resolve(SessionLogWriter.BOOT_LOG_NAME);
        assertTrue(Files.isRegularFile(log));
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        // syslog-style "Sep 17 21:49:05 <line>" prefix.
        assertTrue(lines.get(0).matches("[A-Z][a-z]{2} \\d{2} \\d{2}:\\d{2}:\\d{2} .+"));
        assertTrue(lines.get(0).endsWith("Server listening on 127.0.0.1 port 22022."));
    }

    @Test
    public void rotationKeepsExactlyOnePreviousBoot() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        Path logDir = rootfs.resolve(SessionLogWriter.LOG_DIR);
        Files.createDirectories(logDir);
        Files.write(logDir.resolve("boot.log"), "boot 2".getBytes(StandardCharsets.UTF_8));
        Files.write(logDir.resolve("boot.log.1"), "boot 1".getBytes(StandardCharsets.UTF_8));

        int rotated = SessionLogWriter.rotateAtSessionStart(rootfs);

        assertEquals(1, rotated);
        assertFalse(Files.exists(logDir.resolve("boot.log")));
        assertEquals("boot 2", new String(
                Files.readAllBytes(logDir.resolve("boot.log.1")), StandardCharsets.UTF_8));
    }

    @Test
    public void rotationCoversTheServiceJournalsToo() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        Path journal = rootfs.resolve("var/log/journal");
        Path userJournal = rootfs.resolve("root/.config/log/journal");
        Files.createDirectories(journal);
        Files.createDirectories(userJournal);
        Files.write(journal.resolve("sshd.service.log"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(userJournal.resolve("mine.service.log"), "b".getBytes(StandardCharsets.UTF_8));

        assertEquals(2, SessionLogWriter.rotateAtSessionStart(rootfs));
        assertTrue(Files.exists(journal.resolve("sshd.service.log.1")));
        assertTrue(Files.exists(userJournal.resolve("mine.service.log.1")));
    }

    @Test
    public void rotationOnAMissingTreeIsAQuietNoOp() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        assertEquals(0, SessionLogWriter.rotateAtSessionStart(rootfs));
        assertEquals(0, SessionLogWriter.rotateAtSessionStart(rootfs.resolve("absent")));
        assertEquals(0, SessionLogWriter.rotateAtSessionStart(null));
    }

    @Test
    public void theOpenLogSelfTrimsOnceItCrossesTheBound() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        SessionLogWriter writer = new SessionLogWriter(rootfs, () -> FIXED_TIME);

        // ~2.3 MB of lines crosses MAX_BYTES (2 MiB) and forces one trim.
        String line = repeat('x', 200);
        for (int i = 0; i < 11_000; i++) {
            writer.append(i + " " + line);
        }
        writer.close();

        long size = Files.size(writer.getLogFile());
        // Each crossing of MAX_BYTES trims back to KEEP_BYTES, so the file
        // never exceeds MAX plus one flush's slack.
        assertTrue("log must stay bounded, was " + size,
                size <= SessionLogWriter.MAX_BYTES + 64 * 1024);
        // The newest lines survived the trim.
        List<String> tail = Files.readAllLines(writer.getLogFile(), StandardCharsets.UTF_8);
        assertTrue(tail.get(tail.size() - 1).contains("10999"));
    }

    @Test
    public void aClosedWriterDropsLateLinesInsteadOfFailing() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        SessionLogWriter writer = new SessionLogWriter(rootfs, () -> FIXED_TIME);
        writer.append("before");
        writer.close();
        writer.append("after close");
        writer.close(); // idempotent

        List<String> lines = Files.readAllLines(
                writer.getLogFile(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("before"));
    }

    @Test
    public void missingArgumentsAreRejected() throws IOException {
        Path rootfs = Files.createTempDirectory("session-log-test");
        try {
            new SessionLogWriter(null, () -> FIXED_TIME);
            org.junit.Assert.fail("null rootfs must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
        try {
            new SessionLogWriter(rootfs, null);
            org.junit.Assert.fail("null clock must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
