package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests the namespaced, Ubuntu-compatible os-release overlay. */
public class GuestOsReleaseWriterTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void appendsContributorAndSourceWithoutChangingUbuntuIdentity() throws Exception {
        Path files = temporary.newFolder("files").toPath();
        Path rootfs = temporary.newFolder("rootfs").toPath();
        writeBase(rootfs, "NAME=\"Ubuntu\"\nID=ubuntu\nVERSION_ID=\"24.04\"\n");

        assertEquals(GuestOsReleaseWriter.Result.UPDATED,
                GuestOsReleaseWriter.ensure(files, rootfs));
        String text = readManaged(files);

        assertTrue(text.contains("NAME=\"Ubuntu\""));
        assertTrue(text.contains("ID=ubuntu"));
        assertTrue(text.contains("NUSADESK_CONTRIBUTOR=\"NusaDesk\""));
        assertTrue(text.contains(
                "NUSADESK_SOURCE=\"https://github.com/jahrulnr/NusaDesk\""));
        assertTrue(Files.isWritable(files.resolve(GuestOsReleaseWriter.STATE_RELATIVE_PATH)));
    }

    @Test
    public void sameBaseIsIdempotentAndExistingCustomLinesAreCanonicalized() throws Exception {
        Path files = temporary.newFolder("files").toPath();
        Path rootfs = temporary.newFolder("rootfs").toPath();
        writeBase(rootfs, "ID=ubuntu\nNUSADESK_CONTRIBUTOR=\"old\"\n"
                + "NUSADESK_SOURCE=\"old\"\n");

        assertEquals(GuestOsReleaseWriter.Result.UPDATED,
                GuestOsReleaseWriter.ensure(files, rootfs));
        byte[] first = Files.readAllBytes(files.resolve(GuestOsReleaseWriter.STATE_RELATIVE_PATH));
        assertEquals(GuestOsReleaseWriter.Result.UNCHANGED,
                GuestOsReleaseWriter.ensure(files, rootfs));
        assertTrue(java.util.Arrays.equals(first,
                Files.readAllBytes(files.resolve(GuestOsReleaseWriter.STATE_RELATIVE_PATH))));
        String text = readManaged(files);
        assertFalse(text.contains("old"));
        assertEquals(1, count(text, GuestOsReleaseWriter.CONTRIBUTOR_KEY + "="));
        assertEquals(1, count(text, GuestOsReleaseWriter.SOURCE_KEY + "="));
    }

    @Test
    public void baseOsReleaseChangeRefreshesManagedCopy() throws Exception {
        Path files = temporary.newFolder("files").toPath();
        Path rootfs = temporary.newFolder("rootfs").toPath();
        writeBase(rootfs, "ID=ubuntu\nVERSION_ID=\"24.04\"\n");
        GuestOsReleaseWriter.ensure(files, rootfs);
        writeBase(rootfs, "ID=ubuntu\nVERSION_ID=\"24.10\"\n");

        assertEquals(GuestOsReleaseWriter.Result.UPDATED,
                GuestOsReleaseWriter.ensure(files, rootfs));
        assertTrue(readManaged(files).contains("VERSION_ID=\"24.10\""));
    }

    @Test
    public void followsOnlySafeRelativeOsReleaseSymlinkInsideRootfs() throws Exception {
        Path files = temporary.newFolder("files").toPath();
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path usrLib = rootfs.resolve("usr/lib");
        Files.createDirectories(usrLib);
        Files.write(usrLib.resolve("os-release"),
                "ID=ubuntu\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(rootfs.resolve("etc"));
        Files.createSymbolicLink(rootfs.resolve("etc/os-release"),
                java.nio.file.Paths.get("../usr/lib/os-release"));

        GuestOsReleaseWriter.ensure(files, rootfs);

        assertTrue(readManaged(files).contains("ID=ubuntu"));
    }

    @Test
    public void rejectsEscapingOsReleaseSymlink() throws Exception {
        Path files = temporary.newFolder("files").toPath();
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path outside = temporary.newFile("outside-os-release").toPath();
        Files.write(outside, "ID=evil\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(rootfs.resolve("etc"));
        Files.createSymbolicLink(rootfs.resolve("etc/os-release"), outside);

        try {
            GuestOsReleaseWriter.ensure(files, rootfs);
            fail("expected escaping os-release link to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
    }

    private static void writeBase(Path rootfs, String text) throws Exception {
        Files.createDirectories(rootfs.resolve("etc"));
        Files.write(rootfs.resolve("etc/os-release"), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readManaged(Path files) throws Exception {
        return new String(Files.readAllBytes(
                files.resolve(GuestOsReleaseWriter.STATE_RELATIVE_PATH)),
                StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
