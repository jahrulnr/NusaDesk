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
 * The catalog is the boundary between "files that exist in the rootfs" and
 * "logs the surface may open": only regular, non-symlink files under the
 * rootfs are listed, boot items always lead, and rotated generations are
 * hidden except the documented previous boot.
 */
public class GuestLogCatalogTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void aMissingOrEmptyRootfsListsNothing() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        assertTrue(GuestLogCatalog.list(rootfs).isEmpty());
        assertTrue(GuestLogCatalog.list(rootfs.resolve("absent")).isEmpty());
        assertTrue(GuestLogCatalog.list(null).isEmpty());
    }

    @Test
    public void bootItemsComeFirstAndCarryBothGenerations() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        Path logDir = rootfs.resolve(SessionLogWriter.LOG_DIR);
        write(logDir, "boot.log", "current");
        write(logDir, "boot.log.1", "previous");
        write(rootfs.resolve("var/log/journal"), "sshd.service.log", "x\n");

        List<GuestLog> items = GuestLogCatalog.list(rootfs);

        assertEquals(3, items.size());
        assertEquals(GuestLog.Section.BOOT, items.get(0).getSection());
        assertEquals("This boot", items.get(0).getTitle());
        assertEquals("/var/log/lw/boot.log", items.get(0).getGuestPath());
        assertEquals(GuestLog.Section.BOOT, items.get(1).getSection());
        assertEquals("Previous boot", items.get(1).getTitle());
        assertEquals(GuestLog.Section.SYSTEM, items.get(2).getSection());
    }

    @Test
    public void serviceJournalsListPerUnitWithCleanTitles() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        write(rootfs.resolve("var/log/journal"), "sshd.service.log", "x\n");
        write(rootfs.resolve("var/log/journal"), "lw-user-manager.service.log", "y\n");
        write(rootfs.resolve("var/log/journal"), "sshd.service.log.1", "rotated\n");

        List<GuestLog> items = GuestLogCatalog.list(rootfs);

        assertEquals(2, items.size());
        assertEquals("lw-user-manager.service", items.get(0).getTitle());
        assertEquals("sshd.service", items.get(1).getTitle());
        assertEquals("/var/log/journal/lw-user-manager.service.log",
                items.get(0).getGuestPath());
        // The host path is what the reader actually opens.
        assertTrue(items.get(0).getHostPath().toString().endsWith(
                "lw-user-manager.service.log"));
    }

    @Test
    public void userModeJournalsAreTaggedSoTheyCannotPassForSystemUnits() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        write(rootfs.resolve("var/log/journal"), "app.service.log", "sys\n");
        write(rootfs.resolve("root/.config/log/journal"), "app.service.log", "usr\n");

        List<GuestLog> items = GuestLogCatalog.list(rootfs);

        assertEquals(2, items.size());
        GuestLog userItem = items.get(0).getQualifier().equals("user")
                ? items.get(0) : items.get(1);
        GuestLog systemItem = items.get(0).getQualifier().equals("user")
                ? items.get(1) : items.get(0);
        assertEquals("user", userItem.getQualifier());
        assertEquals("/root/.config/log/journal/app.service.log",
                userItem.getGuestPath());
        assertTrue(systemItem.getQualifier().isEmpty());
    }

    @Test
    public void composeLogsListUnderTheSystemSection() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        Path state = rootfs.resolve("root/.config/nusadesk/compose");
        write(state, "supervisor.log", "sup\n");
        write(state.resolve("myapp/logs"), "web.log", "w\n");

        List<GuestLog> items = GuestLogCatalog.list(rootfs);

        assertEquals(2, items.size());
        for (GuestLog item : items) {
            assertEquals(GuestLog.Section.SYSTEM, item.getSection());
            assertEquals("compose", item.getQualifier());
        }
        assertEquals("Compose supervisor", items.get(0).getTitle());
        assertEquals("myapp/web", items.get(1).getTitle());
    }

    @Test
    public void symlinksAndEscapesAreNeverListed() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test").toRealPath();
        Path outside = Files.createTempDirectory("catalog-outside");
        Path secret = write(outside, "secret.log", "no\n");
        Path journal = rootfs.resolve("var/log/journal");
        Files.createDirectories(journal);
        try {
            Files.createSymbolicLink(journal.resolve("evil.log"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // FS without symlink support: the guard cannot be exercised.
        }

        for (GuestLog item : GuestLogCatalog.list(rootfs)) {
            assertFalse(item.getGuestPath().contains("evil"));
        }
    }

    @Test
    public void nonLogFilesAndDirectoriesAreIgnored() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        Path journal = rootfs.resolve("var/log/journal");
        write(journal, "real.service.log", "x\n");
        write(journal, "notes.txt", "not a log\n");
        Files.createDirectories(journal.resolve("nested.log"));

        List<GuestLog> items = GuestLogCatalog.list(rootfs);
        assertEquals(1, items.size());
        assertEquals("real.service", items.get(0).getTitle());
    }

    @Test
    public void theReturnedListIsImmutable() throws IOException {
        Path rootfs = Files.createTempDirectory("catalog-test");
        List<GuestLog> items = GuestLogCatalog.list(rootfs);
        try {
            items.add(null);
            org.junit.Assert.fail("catalog list must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }
}
