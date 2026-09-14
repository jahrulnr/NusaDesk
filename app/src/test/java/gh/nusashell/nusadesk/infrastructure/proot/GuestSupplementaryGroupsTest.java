package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure-JVM tests for {@link GuestSupplementaryGroups}: parsing the kernel's
 * real supplementary group list, pinning Android AID names, and rendering inert
 * guest {@code /etc/group} entries. No Android, no device.
 */
public class GuestSupplementaryGroupsTest {

    /**
     * The exact {@code /proc/self/status} shape of this app on the physical
     * Android 10/API 29 arm64 test device (verified with
     * {@code run-as gh.nusashell.nusadesk id}).
     */
    private static final String DEVICE_STATUS =
            "Name:\tcat\n"
            + "Umask:\t0077\n"
            + "State:\tR (running)\n"
            + "Gid:\t10279\t10279\t10279\t10279\n"
            + "Groups:\t1004 1007 1011 1015 1028 3001 3002 3003 3006 3009 3011 50279 \n";

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void parseGroupIdsReadsTheKernelGroupsLine() {
        assertEquals(
                Arrays.asList(1004, 1007, 1011, 1015, 1028, 3001, 3002, 3003, 3006, 3009, 3011, 50279),
                GuestSupplementaryGroups.parseGroupIds(DEVICE_STATUS));
    }

    @Test
    public void parseGroupIdsIsEmptyWithoutTheLine() {
        assertTrue(GuestSupplementaryGroups.parseGroupIds("Name:\tcat\nUid:\t10279\n").isEmpty());
        assertTrue(GuestSupplementaryGroups.parseGroupIds("").isEmpty());
        assertTrue(GuestSupplementaryGroups.parseGroupIds(null).isEmpty());
    }

    @Test
    public void parseGroupIdsSkipsJunkAndNonPositiveTokens() {
        assertEquals(
                Arrays.asList(3003),
                GuestSupplementaryGroups.parseGroupIds("Groups:\t0 -7 3003 99999999999 junk \n"));
    }

    @Test
    public void readGroupIdsReadsAStatusFile() throws Exception {
        File status = temp.newFile("status");
        Files.write(status.toPath(), DEVICE_STATUS.getBytes(StandardCharsets.UTF_8));
        assertEquals(12, GuestSupplementaryGroups.readGroupIds(status.toPath()).size());
        assertEquals(GuestSupplementaryGroups.PROC_STATUS, "/proc/self/status");
    }

    @Test
    public void readGroupIdsRejectsNull() throws Exception {
        try {
            GuestSupplementaryGroups.readGroupIds(null);
            fail("null status path must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void guestGroupEntriesNameTheDeviceVerifiedAndroidAids() {
        assertEquals(
                Arrays.asList(
                        "aid_input:x:1004:",
                        "aid_log:x:1007:",
                        "aid_adb:x:1011:",
                        "aid_sdcard_rw:x:1015:",
                        "aid_sdcard_r:x:1028:",
                        "aid_net_bt_admin:x:3001:",
                        "aid_net_bt:x:3002:",
                        "aid_inet:x:3003:",
                        "aid_net_bw_stats:x:3006:",
                        "aid_readproc:x:3009:",
                        "aid_uhid:x:3011:",
                        "aid_50279:x:50279:"),
                GuestSupplementaryGroups.guestGroupEntries(
                        GuestSupplementaryGroups.parseGroupIds(DEVICE_STATUS)));
    }

    @Test
    public void guestGroupEntriesNameTheAppProcessListVerifiedOnDevice() {
        // /proc/<app pid>/status of the running app on the test device. The
        // per-app cache/shared gids (20000+appid, 50000+appid) have no pinned
        // name, so they keep the numeric fallback; the shared AIDs are named.
        assertEquals(
                Arrays.asList(
                        "aid_inet:x:3003:",
                        "aid_everybody:x:9997:",
                        "aid_20279:x:20279:",
                        "aid_50279:x:50279:"),
                GuestSupplementaryGroups.guestGroupEntries(Arrays.asList(3003, 9997, 20279, 50279)));
    }

    @Test
    public void guestGroupEntriesFallBackToTheNumericId() {
        assertEquals(
                Arrays.asList("aid_40000:x:40000:", "aid_50279:x:50279:"),
                GuestSupplementaryGroups.guestGroupEntries(Arrays.asList(50279, 40000)));
    }

    @Test
    public void guestGroupEntriesDropZeroDuplicatesAndNonPositiveIds() {
        // Group 0 is root's group in the guest and is never renamed.
        assertEquals(
                Collections.singletonList("aid_inet:x:3003:"),
                GuestSupplementaryGroups.guestGroupEntries(Arrays.asList(0, 3003, 3003, -5)));
        assertTrue(GuestSupplementaryGroups.guestGroupEntries(null).isEmpty());
        assertTrue(GuestSupplementaryGroups.guestGroupEntries(Collections.emptyList()).isEmpty());
    }

    @Test
    public void everyEntryIsAnInertGroupName() {
        List<Integer> ids = Arrays.asList(1, 1004, 1078, 2000, 3003, 3013, 10000, 50279, 2147483647);
        for (String entry : GuestSupplementaryGroups.guestGroupEntries(ids)) {
            assertTrue("not a plain group entry: " + entry,
                    entry.matches("aid_[a-z0-9_]*[0-9]*:x:[0-9]+:"));
        }
        assertEquals("aid_inet", GuestSupplementaryGroups.nameFor(3003));
        assertEquals("aid_99999", GuestSupplementaryGroups.nameFor(99999));
    }
}
