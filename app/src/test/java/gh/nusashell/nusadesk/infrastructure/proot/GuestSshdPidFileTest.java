package gh.nusashell.nusadesk.infrastructure.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-JVM tests for the daemon pid file: only a live process whose command
 * line names the daemon may ever be signalled.
 */
public class GuestSshdPidFileTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsATrimmedNumericPid() throws Exception {
        Path pidFile = write("sshd.pid", "  12345\n");
        assertEquals(Long.valueOf(12345L), GuestSshdPidFile.read(pidFile));
    }

    @Test
    public void rejectsAbsentBlankNonNumericAndOutOfRangeContent() throws Exception {
        assertNull(GuestSshdPidFile.read(folder.getRoot().toPath().resolve("missing.pid")));
        assertNull(GuestSshdPidFile.read(null));
        assertNull(GuestSshdPidFile.read(write("blank.pid", "  \n")));
        assertNull(GuestSshdPidFile.read(write("text.pid", "sshd\n")));
        assertNull(GuestSshdPidFile.read(write("mixed.pid", "12a\n")));
        assertNull(GuestSshdPidFile.read(write("negative.pid", "-5\n")));
        assertNull(GuestSshdPidFile.read(write("zero.pid", "0\n")));
        assertNull(GuestSshdPidFile.read(write("huge.pid", "99999999999\n")));
    }

    @Test
    public void convertsNulSeparatedCommandLineToText() {
        byte[] raw = ("sshd: /opt/lw-ssh/usr/sbin/sshd" + "\0" + "-D" + "\0" + "-p" + "\0"
                + "44007" + "\0").getBytes(StandardCharsets.US_ASCII);
        String text = GuestSshdPidFile.commandLineText(raw);
        assertTrue(text.startsWith("sshd: /opt/lw-ssh/usr/sbin/sshd -D -p 44007"));
        assertEquals("", GuestSshdPidFile.commandLineText(null));
        assertEquals("", GuestSshdPidFile.commandLineText(new byte[0]));
    }

    @Test
    public void matchesOnlyCommandLinesNamingTheDaemon() {
        String overlayDaemon = "/opt/lw-ssh/usr/sbin/sshd";
        String titled = "sshd: /opt/lw-ssh/usr/sbin/sshd -D -e -o LogLevel=VERBOSE"
                + " -f /opt/lw-ssh/etc/sshd_config -p 44007 [listener] 0 of 10-100 startups";
        assertTrue(GuestSshdPidFile.matchesDaemon(titled, overlayDaemon));
        assertTrue(GuestSshdPidFile.matchesDaemon("/usr/sbin/sshd -D", "/usr/sbin/sshd"));
        // A reused pid belonging to something else must never match.
        assertFalse(GuestSshdPidFile.matchesDaemon(
                "gh.nusashell.nusadesk", overlayDaemon));
        assertFalse(GuestSshdPidFile.matchesDaemon(null, overlayDaemon));
        assertFalse(GuestSshdPidFile.matchesDaemon(titled, null));
        assertFalse(GuestSshdPidFile.matchesDaemon(titled, ""));
    }

    private Path write(String name, String content) throws Exception {
        File file = folder.newFile(name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.US_ASCII));
        return file.toPath();
    }
}
