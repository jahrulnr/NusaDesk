package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests the bounded host-side cleanup of the persistent rootfs /tmp. */
public class GuestEphemeralStateCleanerTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void clearsFilesDirectoriesAndSymlinksButKeepsTmpDirectory() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path tmp = rootfs.resolve("tmp");
        Files.createDirectories(tmp.resolve("nested"));
        Files.write(tmp.resolve("m.txt"), "stale".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("nested/child"), "stale".getBytes(StandardCharsets.UTF_8));
        Path outside = temporary.newFile("outside").toPath();
        Files.createSymbolicLink(tmp.resolve("link"), outside.getFileName());

        GuestEphemeralStateCleaner.clearGuestTmp(rootfs);

        assertTrue(Files.isDirectory(tmp));
        assertFalse(Files.exists(tmp.resolve("m.txt")));
        assertFalse(Files.exists(tmp.resolve("nested")));
        assertFalse(Files.exists(tmp.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outside));
    }

    @Test
    public void createsMissingTmpDirectory() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        GuestEphemeralStateCleaner.clearGuestTmp(rootfs);

        assertTrue(Files.isDirectory(rootfs.resolve("tmp")));
    }

    @Test
    public void refusesRootfsTmpSymlink() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path outside = temporary.newFolder("outside").toPath();
        Files.createSymbolicLink(rootfs.resolve("tmp"), outside.getFileName());

        try {
            GuestEphemeralStateCleaner.clearGuestTmp(rootfs);
            fail("expected unsafe tmp path to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("not a real directory"));
        }
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    public void refusesRootfsTmpFile() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Files.write(rootfs.resolve("tmp"), new byte[]{1});

        try {
            GuestEphemeralStateCleaner.clearGuestTmp(rootfs);
            fail("expected non-directory tmp path to be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("not a real directory"));
        }
    }
}
