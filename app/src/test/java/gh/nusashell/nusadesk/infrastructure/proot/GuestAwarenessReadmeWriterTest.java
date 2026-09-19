package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests the version-aware, app-managed guest awareness bundle writer. */
public class GuestAwarenessReadmeWriterTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void writesConciseManagedReadmeWithDocsLinksAndLeavesItWritable() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));
        Path readme = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_RELATIVE_PATH);
        String text = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(readme));
        assertTrue(Files.isWritable(readme));
        assertTrue(text.contains("# NusaDesk Linux"));
        assertTrue(text.contains("App version: 0.1.0"));
        assertTrue(text.contains("managed by the NusaDesk Android app"));
        assertTrue(text.contains("systemctl3"));
        assertTrue(text.contains("(docs/README.md)"));
        assertTrue(text.contains("(docs/bridge.md)"));
        assertTrue(text.contains("(docs/media.md)"));
        assertTrue(text.contains("(docs/battery-sensors-location.md)"));
        assertTrue(text.contains("(docs/messaging-telephony.md)"));
        assertTrue(text.contains("(docs/termux-compat.md)"));
        assertTrue(text.split("\\n", -1).length < 34);
    }

    @Test
    public void writesDocsAndExecutableCli() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");

        assertTrue(Files.isRegularFile(rootfs.resolve("root/docs/README.md")));
        assertTrue(Files.isRegularFile(rootfs.resolve("root/docs/bridge.md")));
        assertTrue(Files.isRegularFile(rootfs.resolve("root/docs/media.md")));
        assertTrue(Files.isRegularFile(
                rootfs.resolve("root/docs/battery-sensors-location.md")));
        assertTrue(Files.isRegularFile(rootfs.resolve("root/docs/messaging-telephony.md")));

        Path cli = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_CLI_RELATIVE_PATH);
        assertTrue("CLI must exist", Files.isRegularFile(cli));
        assertTrue("CLI must be executable", Files.isExecutable(cli));
        if (posixSupported()) {
            assertEquals("rwxr-xr-x", mode(cli));
            assertEquals("rw-r--r--", mode(rootfs.resolve("root/docs/media.md")));
        }
    }

    private static boolean posixSupported() {
        return java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");
    }

    @Test
    public void sameVersionIsIdempotentAcrossTheWholeBundle() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));
        byte[] readme = Files.readAllBytes(
                rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_RELATIVE_PATH));
        byte[] cli = Files.readAllBytes(
                rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_CLI_RELATIVE_PATH));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));
        assertTrue(java.util.Arrays.equals(readme, Files.readAllBytes(
                rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_RELATIVE_PATH))));
        assertTrue(java.util.Arrays.equals(cli, Files.readAllBytes(
                rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_CLI_RELATIVE_PATH))));
    }

    @Test
    public void versionChangeReplacesStaleAwarenessText() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
        Path readme = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_RELATIVE_PATH);
        Path mediaDoc = rootfs.resolve("root/docs/media.md");
        Path cli = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_CLI_RELATIVE_PATH);
        Files.write(readme, "stale user-visible text".getBytes(StandardCharsets.UTF_8));
        Files.write(mediaDoc, "stale media text".getBytes(StandardCharsets.UTF_8));
        Files.write(cli, "stale cli text".getBytes(StandardCharsets.UTF_8));

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.2.0"));
        String readmeText = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
        assertTrue(readmeText.contains("App version: 0.2.0"));
        assertFalse(readmeText.contains("stale user-visible text"));
        String mediaText = new String(Files.readAllBytes(mediaDoc), StandardCharsets.UTF_8);
        assertTrue(mediaText.contains("version 0.2.0"));
        assertFalse(mediaText.contains("stale media text"));
        String cliText = new String(Files.readAllBytes(cli), StandardCharsets.UTF_8);
        assertTrue(cliText.contains("App version: 0.2.0"));
        assertFalse(cliText.contains("stale cli text"));
    }

    @Test
    public void rejectsRootSymlinkInsteadOfWritingOutsideRootfs() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Files.createSymbolicLink(rootfs.resolve("root"), outside.getFileName());

        try {
            GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
            fail("expected root symlink to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("/root"));
        }
        assertFalse(Files.exists(outside.resolve("README.md")));
    }

    @Test
    public void rejectsDocsSymlinkInsteadOfWritingOutsideRootfs() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Files.createDirectories(rootfs.resolve("root"));
        Files.createSymbolicLink(rootfs.resolve("root/docs"), outside.getFileName());

        try {
            GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
            fail("expected docs symlink to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("/root/docs"));
        }
        assertFalse(Files.exists(outside.resolve("README.md")));
    }

    @Test
    public void rejectsCliParentSymlinkInsteadOfWritingOutsideRootfs() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Files.createDirectories(rootfs.resolve("usr/local"));
        Files.createSymbolicLink(rootfs.resolve("usr/local/bin"), outside.getFileName());

        try {
            GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
            fail("expected /usr/local/bin symlink to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("/usr/local/bin"));
        }
        assertFalse(Files.exists(outside.resolve("nusadesk-android")));
    }

    @Test
    public void rejectsUnsafeVersionText() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        try {
            GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0\nmanaged");
            fail("expected multiline version to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("single safe line"));
        }
    }

    private static String mode(Path path) throws Exception {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        String result = "";
        result += permissions.contains(PosixFilePermission.OWNER_READ) ? 'r' : '-';
        result += permissions.contains(PosixFilePermission.OWNER_WRITE) ? 'w' : '-';
        result += permissions.contains(PosixFilePermission.OWNER_EXECUTE) ? 'x' : '-';
        result += permissions.contains(PosixFilePermission.GROUP_READ) ? 'r' : '-';
        result += permissions.contains(PosixFilePermission.GROUP_WRITE) ? 'w' : '-';
        result += permissions.contains(PosixFilePermission.GROUP_EXECUTE) ? 'x' : '-';
        result += permissions.contains(PosixFilePermission.OTHERS_READ) ? 'r' : '-';
        result += permissions.contains(PosixFilePermission.OTHERS_WRITE) ? 'w' : '-';
        result += permissions.contains(PosixFilePermission.OTHERS_EXECUTE) ? 'x' : '-';
        return result;
    }
}