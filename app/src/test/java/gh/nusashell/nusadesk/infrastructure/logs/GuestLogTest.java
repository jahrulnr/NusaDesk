package gh.nusashell.nusadesk.infrastructure.logs;

import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The value object the Logs surface lists: validated at construction, with
 * the stable id the catalog pins and the host path the reader opens.
 */
public class GuestLogTest {

    @Test
    public void carriesItsFields() {
        GuestLog log = new GuestLog("/var/log/lw/boot.log", "This boot",
                "/var/log/lw/boot.log", Paths.get("/tmp/x/boot.log"),
                GuestLog.Section.BOOT, "");

        assertEquals("/var/log/lw/boot.log", log.getId());
        assertEquals("This boot", log.getTitle());
        assertEquals("/var/log/lw/boot.log", log.getGuestPath());
        assertEquals(Paths.get("/tmp/x/boot.log"), log.getHostPath());
        assertEquals(GuestLog.Section.BOOT, log.getSection());
        assertEquals("", log.getQualifier());
    }

    @Test
    public void nullQualifierBecomesEmpty() {
        GuestLog log = new GuestLog("id", "t", "/p", Paths.get("/x"),
                GuestLog.Section.SYSTEM, null);
        assertEquals("", log.getQualifier());
    }

    @Test
    public void requiredFieldsAreValidated() {
        assertRejects(null, "t", "/p", Paths.get("/x"), GuestLog.Section.BOOT);
        assertRejects("", "t", "/p", Paths.get("/x"), GuestLog.Section.BOOT);
        assertRejects("id", null, "/p", Paths.get("/x"), GuestLog.Section.BOOT);
        assertRejects("id", "t", null, Paths.get("/x"), GuestLog.Section.BOOT);
        assertRejects("id", "t", "/p", null, GuestLog.Section.BOOT);
        assertRejects("id", "t", "/p", Paths.get("/x"), null);
    }

    private static void assertRejects(String id, String title, String guestPath,
                                      java.nio.file.Path hostPath, GuestLog.Section section) {
        try {
            new GuestLog(id, title, guestPath, hostPath, section, "");
            fail("invalid GuestLog must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
