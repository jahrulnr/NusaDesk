package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.VendoredFile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for {@link GuestServiceBridge}: overlay detection, the
 * rootfs symlink wiring contract (never shadowing real guest content), the
 * {@code /run/systemd/system} marker, and the supervised init argv shape.
 */
public class GuestServiceBridgeTest {

    @Rule
    public final TemporaryFolder rootfs = new TemporaryFolder();

    @Rule
    public final TemporaryFolder overlay = new TemporaryFolder();

    @Test
    public void detectReturnsNullWhenOverlayMissingOrIncomplete() throws Exception {
        assertNull(GuestServiceBridge.detect(null));
        assertNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
        // Interpreter present but the vendored script missing: not usable.
        touch(overlay.getRoot(), "usr/bin/python3.12");
        assertNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
        // Script present but the interpreter missing: still not usable.
        touch(overlay.getRoot(), "usr/bin/systemctl");
        Files.delete(overlay.getRoot().toPath().resolve("usr/bin/python3.12"));
        assertNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
        // Interpreter + script but a stale overlay missing a vendored member:
        // "not installed" so the pipeline re-installs instead of degrading.
        touch(overlay.getRoot(), "usr/bin/python3.12");
        assertNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
        // Every compose member is part of the pinned set too: an overlay
        // missing one (here the udocker launcher) is equally "not installed".
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            if (!"usr/local/bin/udocker".equals(vendored.getOverlayPath())) {
                stage(overlay.getRoot(), vendored);
            }
        }
        assertNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
        stage(overlay.getRoot(), vendored("usr/local/bin/udocker"));
        assertNotNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
    }

    @Test
    public void detectReturnsNullWhenAVendoredMemberIsStale() throws Exception {
        assertNotNull(bridge());
        // The same-version update guard: the packaged asset changed but this
        // overlay still carries the previous bytes, so the bridge must read
        // as "not installed" and the install pipeline re-installs it.
        Path runtime = overlay.getRoot().toPath()
                .resolve("usr/local/lib/nusadesk/compose/lw_compose_runtime.py");
        byte[] pinnedBytes = Files.readAllBytes(runtime);
        try {
            Files.write(runtime, "stale overlay bytes".getBytes(StandardCharsets.UTF_8));
            assertNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
        } finally {
            Files.write(runtime, pinnedBytes);
        }
        assertNotNull(GuestServiceBridge.detect(overlay.getRoot().toPath()));
    }

    @Test
    public void detectFindsCompleteOverlay() throws Exception {
        GuestServiceBridge bridge = bridge();
        assertNotNull(bridge);
        assertEquals("/opt/lw-services/usr/bin/systemctl", bridge.getBinaryPath());
        assertEquals("/opt/lw-services/usr/bin/python3.12",
                GuestServiceBridge.PYTHON_GUEST_PATH);
        assertTrue(bridge.requiresFakeRoot());
    }

    @Test
    public void wireIntoLinksOverlayMembersAndCreatesMarker() throws Exception {
        GuestServiceBridge bridge = bridge();
        // Mirror the real payload: usr/bin/python3 is a deb-shipped relative
        // symlink to the interpreter inside the overlay.
        link(overlay.getRoot(), "usr/bin/python3", "python3.12");
        touch(overlay.getRoot(), "usr/lib/python3.12/os.py");
        touch(overlay.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1.9.1");
        link(overlay.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1", "libexpat.so.1.9.1");
        touch(overlay.getRoot(), "etc/services");
        touch(overlay.getRoot(), "usr/share/zoneinfo/UTC");

        List<String> wired = bridge.wireInto(rootfs.getRoot().toPath());

        assertLinked(rootfs.getRoot(), "usr/bin/systemctl",
                "/opt/lw-services/usr/bin/systemctl");
        assertLinked(rootfs.getRoot(), "usr/bin/python3",
                "/opt/lw-services/usr/bin/python3");
        assertLinked(rootfs.getRoot(), "usr/lib/python3.12",
                "/opt/lw-services/usr/lib/python3.12");
        assertLinked(rootfs.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1",
                "/opt/lw-services/usr/lib/aarch64-linux-gnu/libexpat.so.1");
        assertLinked(rootfs.getRoot(), "etc/services",
                "/opt/lw-services/etc/services");
        assertTrue(Files.isDirectory(rootfs.getRoot().toPath().resolve("run/systemd/system")));
        assertTrue(wired.contains("usr/bin/systemctl"));
        assertTrue(wired.contains("usr/lib/aarch64-linux-gnu/libexpat.so.1"));
    }

    @Test
    public void wireIntoLinksComposePayload() throws Exception {
        GuestServiceBridge bridge = bridge();

        List<String> wired = bridge.wireInto(rootfs.getRoot().toPath());

        assertLinked(rootfs.getRoot(), "usr/local/bin/udocker",
                "/opt/lw-services/usr/local/bin/udocker");
        assertLinked(rootfs.getRoot(), "usr/local/bin/lw-compose-supervisor",
                "/opt/lw-services/usr/local/bin/lw-compose-supervisor");
        // The compose runtime directory is exposed as one directory symlink,
        // not per-file links.
        assertLinked(rootfs.getRoot(), "usr/local/lib/nusadesk/compose",
                "/opt/lw-services/usr/local/lib/nusadesk/compose");
        assertLinked(rootfs.getRoot(),
                "etc/systemd/system/lw-compose-supervisor.service",
                "/opt/lw-services/etc/systemd/system/lw-compose-supervisor.service");
        assertTrue(wired.contains("usr/local/bin/udocker"));
        assertTrue(wired.contains("usr/local/bin/lw-compose-supervisor"));
        assertTrue(wired.contains("usr/local/lib/nusadesk/compose"));
        assertTrue(wired.contains("etc/systemd/system/lw-compose-supervisor.service"));
    }

    @Test
    public void wireIntoEnablesComposeSupervisorForNextInit() throws Exception {
        GuestServiceBridge bridge = bridge();

        List<String> wired = bridge.wireInto(rootfs.getRoot().toPath());

        // The vendored systemctl3 only restart-supervises units enabled at
        // `init`, so the product pre-creates the wants link at wire-up with
        // the same relative target `systemctl enable` would write.
        String wants =
                "etc/systemd/system/multi-user.target.wants/lw-compose-supervisor.service";
        assertLinked(rootfs.getRoot(), wants, "../lw-compose-supervisor.service");
        assertTrue(wired.contains(wants));
        // Idempotent: the second wire is a no-op.
        assertTrue(bridge.wireInto(rootfs.getRoot().toPath()).isEmpty());
        assertLinked(rootfs.getRoot(), wants, "../lw-compose-supervisor.service");
    }

    @Test
    public void wireIntoRefreshesStaleComposeWantsSymlink() throws Exception {
        GuestServiceBridge bridge = bridge();
        String wants =
                "etc/systemd/system/multi-user.target.wants/lw-compose-supervisor.service";
        link(rootfs.getRoot(), wants, "../lw-old-compose-supervisor.service");

        bridge.wireInto(rootfs.getRoot().toPath());

        assertLinked(rootfs.getRoot(), wants, "../lw-compose-supervisor.service");
    }

    @Test
    public void wireIntoEnablesRealGuestUnitButKeepsRealWantsFile() throws Exception {
        GuestServiceBridge bridge = bridge();
        String wants =
                "etc/systemd/system/multi-user.target.wants/lw-compose-supervisor.service";
        // A guest-owned unit file wins over the overlay link; it is still
        // enabled — the relative wants target resolves to the real file.
        touch(rootfs.getRoot(), "etc/systemd/system/lw-compose-supervisor.service");
        bridge.wireInto(rootfs.getRoot().toPath());
        assertTrue(Files.isRegularFile(rootfs.getRoot().toPath()
                .resolve("etc/systemd/system/lw-compose-supervisor.service")));
        assertLinked(rootfs.getRoot(), wants, "../lw-compose-supervisor.service");

        // …but real guest content at the wants path itself is never
        // replaced by the expected symlink.
        GuestServiceBridge bridge2 = bridge();
        touch(rootfs.getRoot(), wants);
        List<String> wired = bridge2.wireInto(rootfs.getRoot().toPath());
        assertTrue(Files.isRegularFile(rootfs.getRoot().toPath().resolve(wants)));
        assertFalse(wired.contains(wants));
    }

    @Test
    public void wireIntoLinksUserServiceManagerAndEnablesIt() throws Exception {
        GuestServiceBridge bridge = bridge();

        List<String> wired = bridge.wireInto(rootfs.getRoot().toPath());

        // The launcher is wired onto PATH and the unit into the system unit
        // dir, then enabled for the next `systemctl init` — the vendored
        // manager snapshots that set, so a mid-session enable would never be
        // supervised.
        assertLinked(rootfs.getRoot(), "usr/local/bin/lw-user-manager",
                "/opt/lw-services/usr/local/bin/lw-user-manager");
        assertLinked(rootfs.getRoot(), "etc/systemd/system/lw-user-manager.service",
                "/opt/lw-services/etc/systemd/system/lw-user-manager.service");
        String wants =
                "etc/systemd/system/multi-user.target.wants/lw-user-manager.service";
        assertLinked(rootfs.getRoot(), wants, "../lw-user-manager.service");
        assertTrue(wired.contains("usr/local/bin/lw-user-manager"));
        assertTrue(wired.contains("etc/systemd/system/lw-user-manager.service"));
        assertTrue(wired.contains(wants));
        // Idempotent, like the compose enablement.
        assertTrue(bridge.wireInto(rootfs.getRoot().toPath()).isEmpty());
    }

    @Test
    public void wireIntoWipesStaleUserStatusMarksToo() throws Exception {
        GuestServiceBridge bridge = bridge();
        touch(rootfs.getRoot(), "run/nusashell.service.status");
        // Today's user marks live one level deeper: the replacement's PID dir
        // for a non-root instance is <runtime dir>/run.
        touch(rootfs.getRoot(), "run/user/0/run/nusashell.service.status");
        touch(rootfs.getRoot(), "run/user/0/nusashell.service.status");
        touch(rootfs.getRoot(), "run/user/0/run/keep-me");

        bridge.wireInto(rootfs.getRoot().toPath());

        // A previous session's mark is pointless state at best and a lie at
        // worst (a recycled pid makes it look active), so both locations are
        // cleared at wire-up.
        assertFalse(Files.exists(rootfs.getRoot().toPath()
                .resolve("run/nusashell.service.status")));
        assertFalse(Files.exists(rootfs.getRoot().toPath()
                .resolve("run/user/0/run/nusashell.service.status")));
        assertFalse(Files.exists(rootfs.getRoot().toPath()
                .resolve("run/user/0/nusashell.service.status")));
        assertTrue("only status marks are wiped",
                Files.exists(rootfs.getRoot().toPath().resolve("run/user/0/run/keep-me")));
    }

    @Test
    public void wireIntoSkipsComposeWantsWhenUnitAbsent() throws Exception {
        GuestServiceBridge bridge = bridge();
        // A torn overlay (unit member deleted after detection) leaves nothing
        // to enable — no dangling wants link is created.
        Files.delete(overlay.getRoot().toPath()
                .resolve("etc/systemd/system/lw-compose-supervisor.service"));

        bridge.wireInto(rootfs.getRoot().toPath());

        assertFalse(Files.exists(rootfs.getRoot().toPath().resolve(
                "etc/systemd/system/multi-user.target.wants/lw-compose-supervisor.service"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void wireIntoNeverShadowsRealComposeRuntimeDir() throws Exception {
        GuestServiceBridge bridge = bridge();
        // A real guest-owned compose directory wins over the overlay's
        // directory symlink (same rule as every wired path).
        touch(rootfs.getRoot(), "usr/local/lib/nusadesk/compose/keep.me");
        List<String> wired = bridge.wireInto(rootfs.getRoot().toPath());
        assertFalse(wired.contains("usr/local/lib/nusadesk/compose"));
        assertFalse(Files.isSymbolicLink(
                rootfs.getRoot().toPath().resolve("usr/local/lib/nusadesk/compose")));
        assertTrue(Files.isRegularFile(
                rootfs.getRoot().toPath().resolve("usr/local/lib/nusadesk/compose/keep.me")));
    }

    @Test
    public void wireIntoIsIdempotentAndSkipsAbsentMembers() throws Exception {
        GuestServiceBridge bridge = bridge();
        // No etc/services, no zoneinfo, no payload libs: simply not linked.
        bridge.wireInto(rootfs.getRoot().toPath());
        List<String> second = bridge.wireInto(rootfs.getRoot().toPath());
        assertTrue("rewiring must be a no-op", second.isEmpty());
        assertFalse(Files.exists(rootfs.getRoot().toPath().resolve("etc/services"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void wireIntoNeverShadowsRealGuestContent() throws Exception {
        GuestServiceBridge bridge = bridge();
        // A real file the guest owns (e.g. an apt-installed python3) wins over
        // the overlay link.
        touch(rootfs.getRoot(), "usr/bin/python3.12");
        List<String> wired = bridge.wireInto(rootfs.getRoot().toPath());
        assertFalse(wired.contains("usr/bin/python3.12"));
        assertFalse(Files.isSymbolicLink(rootfs.getRoot().toPath().resolve("usr/bin/python3.12")));
        assertTrue(Files.isRegularFile(rootfs.getRoot().toPath().resolve("usr/bin/python3.12")));
    }

    @Test
    public void wireIntoRefreshesStaleSymlink() throws Exception {
        GuestServiceBridge bridge = bridge();
        link(rootfs.getRoot(), "usr/bin/systemctl", "/opt/lw-old/usr/bin/systemctl");
        bridge.wireInto(rootfs.getRoot().toPath());
        assertLinked(rootfs.getRoot(), "usr/bin/systemctl",
                "/opt/lw-services/usr/bin/systemctl");
    }

    @Test
    public void sessionArgvRunsSupervisorThenDaemonArgv() throws Exception {
        GuestServiceBridge bridge = bridge();
        List<String> daemonArgv = java.util.Arrays.asList(
                "/opt/lw-ssh/usr/sbin/sshd", "-D", "-e");
        List<String> argv = bridge.sessionArgv(daemonArgv);
        // The tracer's initial tracee is the supervisor script; it records the
        // manager's real pid and exit marker, then runs the daemon as a
        // sibling under the same tracer (killing the tracer is not a stop).
        assertEquals("/bin/sh", argv.get(0));
        assertEquals("/opt/lw-services/usr/sbin/lw-session-supervisor", argv.get(1));
        assertEquals("/run/lw-services-init.pid", argv.get(2));
        assertEquals("/run/lw-services-init.exit", argv.get(3));
        assertEquals("--", argv.get(4));
        assertEquals(daemonArgv, argv.subList(5, argv.size()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sessionArgvRejectsEmptyDaemonArgv() throws Exception {
        bridge().sessionArgv(java.util.Collections.<String>emptyList());
    }

    @Test
    public void wireIntoWipesStaleStatusMarksOnly() throws Exception {
        GuestServiceBridge bridge = bridge();
        // Leftovers from a previous session: dead state must not survive a
        // new "boot". Non-status files (e.g. the init pid file name pattern)
        // and directories are untouched.
        touch(rootfs.getRoot(), "run/lwdemo.service.status");
        touch(rootfs.getRoot(), "run/lw-services-init.pid");
        touch(rootfs.getRoot(), "run/sshd/keep.me");
        bridge.wireInto(rootfs.getRoot().toPath());
        assertFalse(Files.exists(rootfs.getRoot().toPath().resolve("run/lwdemo.service.status")));
        assertTrue(Files.exists(rootfs.getRoot().toPath().resolve("run/lw-services-init.pid")));
        assertTrue(Files.exists(rootfs.getRoot().toPath().resolve("run/sshd/keep.me")));
    }

    @Test
    public void requiredBindsMountsOverlayAtFixedGuestDir() throws Exception {
        GuestServiceBridge bridge = bridge();
        List<ProotBindMount> binds = bridge.requiredBinds();
        // Overlay bind first; strict entrypoint binds and any present /proc
        // stand-ins follow it.
        assertEquals(overlay.getRoot().getAbsolutePath(), binds.get(0).getHostPath());
        assertEquals("/opt/lw-services", binds.get(0).getGuestPath());
        for (int i = 1; i < binds.size(); i++) {
            String guestPath = binds.get(i).getGuestPath();
            assertTrue(guestPath.startsWith("/usr/bin/")
                    || guestPath.startsWith("/proc/"));
        }
    }

    @Test
    public void requiredBindsStrictBindsEntrypointsAfterOverlayBind() throws Exception {
        // Mirror the real payload: usr/bin/python3 is the deb-shipped relative
        // symlink to the interpreter inside the overlay.
        link(overlay.getRoot(), "usr/bin/python3", "python3.12");
        GuestServiceBridge bridge = bridge();

        List<ProotBindMount> binds = bridge.requiredBinds();

        // Each product entrypoint is bound at its literal conventional path
        // with the no-dereference (!) marker, after the directory bind, so a
        // real or foreign guest file/symlink can never shadow it.
        for (String guestPath : Arrays.asList(
                "/usr/bin/systemctl", "/usr/bin/service",
                "/usr/bin/python3", "/usr/bin/python3.12")) {
            int index = indexOfGuestPath(binds, guestPath);
            assertTrue("missing strict bind for " + guestPath, index > 0);
            ProotBindMount bind = binds.get(index);
            assertTrue(guestPath + " must be a strict bind", bind.isStrict());
            assertEquals(overlay.getRoot().getAbsolutePath() + guestPath,
                    bind.getHostPath());
            assertTrue("bind argument must end with !: " + bind.toBindArgument(),
                    bind.toBindArgument().endsWith("!"));
        }
    }

    @Test
    public void requiredBindsSkipsStrictBindForAbsentOverlayMember() throws Exception {
        GuestServiceBridge bridge = bridge();
        // A torn overlay (member deleted after detection) is skipped rather
        // than bound dangling — same rule the symlink wiring uses.
        Files.delete(overlay.getRoot().toPath().resolve("usr/bin/service"));

        List<ProotBindMount> binds = bridge.requiredBinds();

        assertTrue(indexOfGuestPath(binds, "/usr/bin/service") < 0);
        assertTrue(indexOfGuestPath(binds, "/usr/bin/systemctl") > 0);
        assertTrue(indexOfGuestPath(binds, "/usr/bin/python3.12") > 0);
    }

    @Test
    public void requiredBindsAddsProcSubstitutesWhenOverlayCarriesThem() throws Exception {
        touch(overlay.getRoot(), "lw-proc/uptime");
        touch(overlay.getRoot(), "lw-proc/stat");
        GuestServiceBridge bridge = bridge();
        List<ProotBindMount> binds = bridge.requiredBinds();
        int uptime = indexOfGuestPath(binds, "/proc/uptime");
        int stat = indexOfGuestPath(binds, "/proc/stat");
        assertTrue("missing /proc/uptime bind", uptime > 0);
        assertTrue("missing /proc/stat bind", stat > 0);
        assertEquals(overlay.getRoot().getAbsolutePath() + "/lw-proc/uptime",
                binds.get(uptime).getHostPath());
        assertEquals(overlay.getRoot().getAbsolutePath() + "/lw-proc/stat",
                binds.get(stat).getHostPath());
    }

    @Test
    public void resolvePidFileLivesUnderGuestRun() throws Exception {
        GuestServiceBridge bridge = bridge();
        assertEquals(rootfs.getRoot().toPath().resolve("run/lw-services-init.pid"),
                bridge.resolvePidFile(rootfs.getRoot().toPath()));
        assertEquals(rootfs.getRoot().toPath().resolve("run/lw-services-init.exit"),
                bridge.resolveExitFile(rootfs.getRoot().toPath()));
    }

    @Test
    public void pidFileMatchCoversInitProcessCommandLine() {
        // The init process's /proc cmdline is
        // "python3.12 /opt/lw-services/usr/bin/systemctl init"; the containment
        // test on the script path identifies it.
        String cmdline = "/opt/lw-services/usr/bin/python3.12 "
                + "/opt/lw-services/usr/bin/systemctl init";
        assertTrue(GuestSshdPidFile.matchesDaemon(
                cmdline, GuestServiceBridge.SYSTEMCTL_GUEST_PATH));
        assertFalse(GuestSshdPidFile.matchesDaemon(
                "/opt/lw-services/usr/bin/python3.12 -c pass",
                GuestServiceBridge.SYSTEMCTL_GUEST_PATH));
    }

    private GuestServiceBridge bridge() throws Exception {
        // The entrypoint ships inside the .deb payload, not the vendored
        // set: presence is enough. Every vendored member is staged with its
        // real packaged bytes because detect() re-verifies each pinned
        // digest — a partial or stale overlay is "not installed", never
        // "usable but degraded".
        touch(overlay.getRoot(), "usr/bin/python3.12");
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            stage(overlay.getRoot(), vendored);
        }
        return GuestServiceBridge.detect(overlay.getRoot().toPath());
    }

    /**
     * Copy the real packaged asset into the overlay at the member's install
     * path so the staged bytes hash to the catalog pin. Unit tests run with
     * the working directory at the module dir, but tolerate a repo-root launch.
     */
    private void stage(File base, VendoredFile vendored) throws Exception {
        Path source = assetsDir().resolve(vendored.getAssetPath());
        assertTrue("packaged asset missing: " + source, Files.isRegularFile(source));
        Path target = base.toPath().resolve(vendored.getOverlayPath());
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private VendoredFile vendored(String overlayPath) {
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            if (overlayPath.equals(vendored.getOverlayPath())) {
                return vendored;
            }
        }
        throw new AssertionError("no vendored member at " + overlayPath);
    }

    private static Path assetsDir() {
        // Anchor on the process working directory, not the JVM-global
        // user.dir property: Robolectric rewrites user.dir to the app data
        // dir while running ANY Robolectric test in the same test JVM, which
        // made this helper order-dependent on other test classes. The worker
        // process working directory is untouched and stays at the module dir.
        Path workingDir = Paths.get("").toAbsolutePath();
        Path moduleAssets = workingDir.resolve("src/main/assets");
        return Files.isDirectory(moduleAssets)
                ? moduleAssets
                : workingDir.resolve("app/src/main/assets");
    }

    private void assertLinked(File root, String guestRelative, String target) throws Exception {
        Path link = root.toPath().resolve(guestRelative);
        assertTrue(guestRelative + " must be a symlink", Files.isSymbolicLink(link));
        assertEquals(Paths.get(target), Files.readSymbolicLink(link));
    }

    private static int indexOfGuestPath(List<ProotBindMount> binds, String guestPath) {
        for (int i = 0; i < binds.size(); i++) {
            if (binds.get(i).getGuestPath().equals(guestPath)) {
                return i;
            }
        }
        return -1;
    }

    private void touch(File base, String relativePath) throws Exception {
        File f = new File(base, relativePath);
        assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
        if (!f.exists()) {
            assertTrue(f.createNewFile());
        }
    }

    private void link(File base, String relativePath, String target) throws Exception {
        Path link = base.toPath().resolve(relativePath);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, Paths.get(target));
    }
}
