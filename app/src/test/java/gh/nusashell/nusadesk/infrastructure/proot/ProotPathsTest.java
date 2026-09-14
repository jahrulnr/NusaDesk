package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link ProotPaths} path resolution and validation. No Android,
 * no filesystem.
 */
public class ProotPathsTest {

    @Test
    public void activeRootfsPathMatchesInstallerLayout() {
        Path filesDir = Paths.get("/data/data/gh.nusashell.nusadesk/files");
        Path active = ProotPaths.activeRootfsPath(filesDir, "ubuntu-base-arm64");
        assertEquals(
                Paths.get("/data/data/gh.nusashell.nusadesk/files/linux-wrapper/runtimes/ubuntu-base-arm64/active"),
                active);
    }

    @Test
    public void prootBinaryPathResolvesLibprootSo() {
        Path binary = ProotPaths.prootBinaryPath("/data/app/gh.nusashell.nusadesk/lib/arm64");
        assertEquals(Paths.get("/data/app/gh.nusashell.nusadesk/lib/arm64/libproot.so"), binary);
    }

    @Test
    public void prootLoaderPathResolvesLibprootLoaderSo() {
        Path loader = ProotPaths.prootLoaderPath("/data/app/gh.nusashell.nusadesk/lib/arm64");
        assertEquals(Paths.get("/data/app/gh.nusashell.nusadesk/lib/arm64/libproot-loader.so"), loader);
    }

    @Test
    public void prootLoaderPathRejectsBlankDir() {
        try {
            ProotPaths.prootLoaderPath("");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void requireAbsolutePathRejectsRelative() {
        try {
            ProotPaths.requireAbsolutePath("relative/path", "field");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("absolute"));
        }
    }

    @Test
    public void requireAbsolutePathRejectsTraversal() {
        try {
            ProotPaths.requireAbsolutePath("/data/../etc/passwd", "field");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("'..'"));
        }
    }

    @Test
    public void requireAbsolutePathRejectsNullBytes() {
        try {
            ProotPaths.requireAbsolutePath("/data\0/evil", "field");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null bytes"));
        }
    }

    @Test
    public void requireAbsolutePathRejectsNullAndEmpty() {
        try {
            ProotPaths.requireAbsolutePath(null, "field");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ProotPaths.requireAbsolutePath("", "field");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void requireAppIdRejectsSlashesAndTraversal() {
        try {
            ProotPaths.requireAppId("ubuntu/../x");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ProotPaths.requireAppId("..");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ProotPaths.requireAppId("");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void isAppPrivatePathAcceptsAppPrivateAndRejectsArbitrary() {
        String filesDir = "/data/data/gh.nusashell.nusadesk/files";
        String cacheDir = "/data/data/gh.nusashell.nusadesk/cache";
        assertTrue(ProotPaths.isAppPrivatePath(
                filesDir + "/linux-wrapper/hostkeys", Arrays.asList(filesDir, cacheDir)));
        assertTrue(ProotPaths.isAppPrivatePath(
                cacheDir + "/tmp", Arrays.asList(filesDir, cacheDir)));
    }

    @Test
    public void isAppPrivatePathRejectsSiblingAndSystemPaths() {
        String filesDir = "/data/data/gh.nusashell.nusadesk/files";
        // A path that shares a prefix as a string but is not under the app dir.
        assertFalse(ProotPaths.isAppPrivatePath(
                "/data/data/gh.nusashell.nusadesk.evil/files", Collections.singletonList(filesDir)));
        assertFalse(ProotPaths.isAppPrivatePath(
                "/etc/passwd", Collections.singletonList(filesDir)));
        assertFalse(ProotPaths.isAppPrivatePath(
                "/proc/1", Collections.singletonList(filesDir)));
    }

    @Test
    public void isAppPrivatePathRejectsArbitraryHostPath() {
        String filesDir = "/data/data/gh.nusashell.nusadesk/files";
        assertFalse(ProotPaths.isAppPrivatePath("/sdcard/foo", Collections.singletonList(filesDir)));
    }

    @Test
    public void isAppPrivatePathEmptyRootsReturnsFalse() {
        assertFalse(ProotPaths.isAppPrivatePath("/data/data/gh.nusashell.nusadesk/files/x",
                Collections.<String>emptyList()));
    }
}
