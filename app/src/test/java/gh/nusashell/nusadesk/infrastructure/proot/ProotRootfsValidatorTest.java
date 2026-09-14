package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link ProotRootfsValidator} using a temporary directory as a
 * stand-in rootfs. No Android; uses {@link java.nio.file.Files} on the JVM.
 */
public class ProotRootfsValidatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path newRootfs() throws IOException {
        return tmp.newFolder("active").toPath();
    }

    private void touch(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    @Test
    public void acceptsCompleteRootfs() throws IOException, ProotLaunchException {
        Path rootfs = newRootfs();
        touch(rootfs, "etc/os-release");
        touch(rootfs, "usr/bin/sh");
        new ProotRootfsValidator().validate(rootfs, "ubuntu-base-arm64");
    }

    @Test
    public void rejectsMissingOsRelease() throws IOException {
        Path rootfs = newRootfs();
        touch(rootfs, "usr/bin/sh");
        try {
            new ProotRootfsValidator().validate(rootfs, "ubuntu-base-arm64");
            fail("expected exception");
        } catch (ProotLaunchException expected) {
            assertTrue(expected.getMessage().contains("etc/os-release"));
        }
    }

    @Test
    public void rejectsMissingSh() throws IOException {
        Path rootfs = newRootfs();
        touch(rootfs, "etc/os-release");
        try {
            new ProotRootfsValidator().validate(rootfs, "ubuntu-base-arm64");
            fail("expected exception");
        } catch (ProotLaunchException expected) {
            assertTrue(expected.getMessage().contains("usr/bin/sh"));
        }
    }

    @Test
    public void rejectsMissingRootfsDirectory() throws IOException {
        Path missing = tmp.getRoot().toPath().resolve("does-not-exist");
        try {
            new ProotRootfsValidator().validate(missing, "ubuntu-base-arm64");
            fail("expected exception");
        } catch (ProotLaunchException expected) {
            assertTrue(expected.getMessage().contains("not a directory"));
        }
    }

    @Test
    public void rejectsFileAsRootfs() throws IOException {
        Path file = tmp.newFile("not-a-dir").toPath();
        try {
            new ProotRootfsValidator().validate(file, "ubuntu-base-arm64");
            fail("expected exception");
        } catch (ProotLaunchException expected) {
            assertTrue(expected.getMessage().contains("not a directory"));
        }
    }

    @Test
    public void rejectsBlankAppId() throws IOException, ProotLaunchException {
        Path rootfs = newRootfs();
        try {
            new ProotRootfsValidator().validate(rootfs, "  ");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullRootfs() {
        try {
            new ProotRootfsValidator().validate(null, "ubuntu-base-arm64");
            fail("expected exception");
        } catch (ProotLaunchException expected) {
            // expected
        }
    }

    @Test
    public void rejectsOsReleaseThatIsADirectory() throws IOException {
        Path rootfs = newRootfs();
        Files.createDirectories(rootfs.resolve("etc/os-release"));
        touch(rootfs, "usr/bin/sh");
        try {
            new ProotRootfsValidator().validate(rootfs, "ubuntu-base-arm64");
            fail("expected exception");
        } catch (ProotLaunchException expected) {
            assertTrue(expected.getMessage().contains("etc/os-release"));
        }
    }
}
