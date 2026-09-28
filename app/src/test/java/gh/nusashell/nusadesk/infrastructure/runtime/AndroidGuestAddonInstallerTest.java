package gh.nusashell.nusadesk.infrastructure.runtime;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The {@code requiredRootfsTools} presence check treats a tool as present
 * when the rootfs path is a regular file or any symlink: overlays expose
 * guest tools through guest-absolute links (the service bridge wires
 * {@code usr/bin/systemctl} to its own {@code /opt/lw-services} path), so a
 * link that dangles on the host still satisfies a guest consumer once the
 * overlay is bound under PRoot. Plain JUnit — the helper is a pure
 * filesystem predicate with no Android types.
 */
public class AndroidGuestAddonInstallerTest {

    @Test
    public void aRealRootfsFileCountsAsPresent() throws Exception {
        Path rootfs = Files.createTempDirectory("rootfs-tools");
        try {
            Files.createDirectories(rootfs.resolve("usr/bin"));
            Path tool = rootfs.resolve("usr/bin/sh");
            Files.write(tool, new byte[]{0x7f, 'E', 'L', 'F'});

            assertTrue(AndroidGuestAddonInstaller.rootfsToolPresent(
                    rootfs, "usr/bin/sh"));
        } finally {
            deleteRecursively(rootfs);
        }
    }

    @Test
    public void aWiredSymlinkCountsAsPresent() throws Exception {
        Path rootfs = Files.createTempDirectory("rootfs-tools");
        try {
            Files.createDirectories(rootfs.resolve("usr/bin"));
            Path real = rootfs.resolve("usr/bin/systemctl-real");
            Files.write(real, new byte[]{1});
            Files.createSymbolicLink(
                    rootfs.resolve("usr/bin/systemctl"), real.getFileName());

            assertTrue(AndroidGuestAddonInstaller.rootfsToolPresent(
                    rootfs, "usr/bin/systemctl"));
        } finally {
            deleteRecursively(rootfs);
        }
    }

    @Test
    public void aDanglingGuestAbsoluteSymlinkCountsAsPresent() throws Exception {
        Path rootfs = Files.createTempDirectory("rootfs-tools");
        try {
            Files.createDirectories(rootfs.resolve("usr/bin"));
            // Exactly the shape the service bridge wires: the link target is
            // a guest-absolute path that only exists inside the overlay, so
            // it is dangling for any host-side check that resolves targets.
            Files.createSymbolicLink(rootfs.resolve("usr/bin/systemctl"),
                    Paths.get("/opt/lw-services/usr/bin/systemctl"));

            assertTrue("a guest-absolute link is the guest-visible tool",
                    AndroidGuestAddonInstaller.rootfsToolPresent(
                            rootfs, "usr/bin/systemctl"));
        } finally {
            deleteRecursively(rootfs);
        }
    }

    @Test
    public void aMissingToolCountsAsAbsent() throws Exception {
        Path rootfs = Files.createTempDirectory("rootfs-tools");
        try {
            assertFalse(AndroidGuestAddonInstaller.rootfsToolPresent(
                    rootfs, "usr/bin/systemctl"));
        } finally {
            deleteRecursively(rootfs);
        }
    }

    @Test
    public void aDirectoryCountsAsAbsent() throws Exception {
        Path rootfs = Files.createTempDirectory("rootfs-tools");
        try {
            Files.createDirectories(rootfs.resolve("usr/bin/tool"));

            assertFalse(AndroidGuestAddonInstaller.rootfsToolPresent(
                    rootfs, "usr/bin/tool"));
        } finally {
            deleteRecursively(rootfs);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (java.io.IOException ignored) {
                            // Best-effort cleanup for temp test trees.
                        }
                    });
        }
    }
}
