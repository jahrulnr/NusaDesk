package gh.nusashell.nusadesk.infrastructure.proot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;

/**
 * Robolectric tests for {@link ProotLauncher#buildSpec}: the curated runtime
 * contract plus the add-on overlay auto-bind. Everything is faked at the
 * filesystem level (a stub rootfs, a stub packaged proot path, stub activated
 * overlays); no process is launched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29})
public class ProotLauncherTest {

    private Context context;
    private Path filesDir;
    private Path rootfs;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        filesDir = context.getFilesDir().toPath();
        rootfs = ProotPaths.activeRootfsPath(filesDir, ProotLauncher.SUPPORTED_APP_ID);
        // Minimum the rootfs validator asks for.
        Files.createDirectories(rootfs.resolve("etc"));
        writeIfAbsent(rootfs.resolve("etc/os-release"), "ID=ubuntu\n");
        Files.createDirectories(rootfs.resolve("usr/bin"));
        writeIfAbsent(rootfs.resolve("usr/bin/sh"), "");
        // The launcher requires the packaged bridge ELF to exist. Robolectric
        // leaves nativeLibraryDir unset; point it at a real private dir.
        Path nativeDir = filesDir.resolve("faked-nativelib");
        Files.createDirectories(nativeDir);
        context.getApplicationInfo().nativeLibraryDir = nativeDir.toString();
        Path proot = nativeDir.resolve(ProotPaths.PROOT_BINARY_NAME);
        writeIfAbsent(proot, "");
        proot.toFile().setExecutable(true);
    }

    @Test
    public void buildSpecBindsManagedOsReleaseOverlayAtLiteralGuestPath() throws Exception {
        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        Path managed = filesDir.resolve(GuestOsReleaseWriter.STATE_RELATIVE_PATH);
        assertTrue(Files.isRegularFile(managed));
        ProotBindMount found = null;
        for (ProotBindMount bind : spec.getBindMounts()) {
            if (managed.toString().equals(bind.getHostPath())
                    && GuestOsReleaseWriter.GUEST_PATH.equals(bind.getGuestPath())) {
                found = bind;
                break;
            }
        }
        assertTrue("managed os-release bind must be present", found != null);
        assertTrue("managed os-release bind must not dereference guest symlink", found.isStrict());
        String text = new String(Files.readAllBytes(managed), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("NUSADESK_CONTRIBUTOR=\"NusaDesk\""));
        assertTrue(text.contains("NUSADESK_SOURCE=\"https://github.com/jahrulnr/NusaDesk\""));
    }
    @Test
    public void buildSpecAutoBindsActivatedAddonOverlays() throws Exception {
        Path sshOverlay = activateOverlay(
                CuratedRuntimeCatalog.guestSshAddon().getAddonId(),
                CuratedRuntimeCatalog.SSH_OVERLAY_ENTRYPOINT);
        Path servicesOverlay = activateOverlay(
                CuratedRuntimeCatalog.guestServiceBridge().getAddonId(),
                CuratedRuntimeCatalog.SERVICES_OVERLAY_ENTRYPOINT);
        // A detectable bridge carries every vendored member at its pinned
        // digest (detect() re-verifies bytes), so stage the real packaged
        // assets; the /proc substitutes are what the launcher must
        // additionally bind (ADR-0024).
        for (gh.nusashell.nusadesk.domain.runtime.VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            Path target = servicesOverlay.resolve(vendored.getOverlayPath());
            Files.createDirectories(target.getParent());
            try (InputStream in = context.getAssets().open(vendored.getAssetPath())) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(containsBind(binds, sshOverlay, "/opt/lw-ssh"));
        assertTrue(containsBind(binds, servicesOverlay, "/opt/lw-services"));
        assertTrue(containsBind(binds,
                servicesOverlay.resolve("lw-proc/uptime"), "/proc/uptime"));
        assertTrue(containsBind(binds,
                servicesOverlay.resolve("lw-proc/stat"), "/proc/stat"));
    }

    @Test
    public void buildSpecSkipsInactiveAddonOverlays() throws Exception {
        // Only the SSH overlay activated; the service bridge is absent.
        Path sshOverlay = activateOverlay(
                CuratedRuntimeCatalog.guestSshAddon().getAddonId(),
                CuratedRuntimeCatalog.SSH_OVERLAY_ENTRYPOINT);

        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(containsBind(binds, sshOverlay, "/opt/lw-ssh"));
        assertFalse(containsGuestPath(binds, "/opt/lw-services"));
    }

    @Test
    public void buildSpecRejectsDuplicateCallerOverlayBind() throws Exception {
        // A caller that explicitly passes an already-bound overlay is folded
        // away, not duplicated.
        Path sshOverlay = activateOverlay(
                CuratedRuntimeCatalog.guestSshAddon().getAddonId(),
                CuratedRuntimeCatalog.SSH_OVERLAY_ENTRYPOINT);
        ProotBindMount same = ProotBindMount.of(sshOverlay.toString(), "/opt/lw-ssh");

        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.singletonList(same),
                Collections.<String, String>emptyMap());

        int count = 0;
        for (ProotBindMount bind : spec.getBindMounts()) {
            if (bind.equals(same)) {
                count++;
            }
        }
        assertTrue("overlay bind must appear exactly once", count == 1);
    }

    @Test
    public void buildSpecBindsPackagedProotPairForInnerRuntime() throws Exception {
        // The packaged pair is bound at the fixed guest paths the compose
        // wrapper pins via UDOCKER_USE_PROOT_EXECUTABLE / PROOT_LOADER.
        Path nativeDir = filesDir.resolve("faked-nativelib");
        Path loader = nativeDir.resolve(ProotPaths.PROOT_LOADER_BINARY_NAME);
        writeIfAbsent(loader, "");

        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(containsBind(binds,
                nativeDir.resolve(ProotPaths.PROOT_BINARY_NAME), "/usr/local/bin/proot"));
        assertTrue(containsBind(binds, loader, "/usr/local/bin/proot-loader"));
        // The bind's parent dir was created under the rootfs, not the file.
        assertTrue(Files.isDirectory(rootfs.resolve("usr/local/bin")));
        assertFalse(Files.exists(rootfs.resolve("usr/local/bin/proot")));
        // The outer PROOT_LOADER env is unchanged: still the host-side path.
        assertTrue(loader.toString().equals(
                spec.getEnv().get(ProotLauncher.ENV_PROOT_LOADER)));
    }

    @Test
    public void buildSpecSkipsInnerBindsWhenHostSourcesAbsent() throws Exception {
        // No libproot-loader.so and no /system tree on the JVM host: the
        // optional inner binds are skipped and no /system dirs are created.
        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(containsBind(binds,
                filesDir.resolve("faked-nativelib").resolve(ProotPaths.PROOT_BINARY_NAME),
                "/usr/local/bin/proot"));
        assertFalse(containsGuestPath(binds, "/usr/local/bin/proot-loader"));
        assertFalse(containsGuestPath(binds, "/system/bin/linker64"));
        assertFalse(containsGuestPath(binds, "/system/lib64/libc.so"));
        assertFalse(containsGuestPath(binds, "/system/lib64/libdl.so"));
        assertFalse(containsGuestPath(binds, "/system/lib64/libm.so"));
        assertFalse(Files.exists(rootfs.resolve("system")));
        // The absent loader keeps the outer PROOT_LOADER env unset too.
        assertFalse(spec.getEnv().containsKey(ProotLauncher.ENV_PROOT_LOADER));
    }

    @Test
    public void buildSpecBindsAndroidToolTreesAndCoveredBionic() throws Exception {
        // Stage a fake /system tree through the package-private seam: the
        // device's tool directories are bound at their identical guest paths
        // (ADR-0045), and the linker/Bionic files inside them are covered by
        // those binds instead of being bound twice.
        Path systemRoot = filesDir.resolve("faked-system");
        writeIfAbsent(systemRoot.resolve("bin/linker64"), "");
        writeIfAbsent(systemRoot.resolve("lib64/libc.so"), "");
        writeIfAbsent(systemRoot.resolve("lib64/libdl.so"), "");
        writeIfAbsent(systemRoot.resolve("lib64/libm.so"), "");

        ProotLaunchSpec spec = new ProotLauncher(context, systemRoot).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(describe(binds), containsBind(binds, systemRoot.resolve("bin"), "/system/bin"));
        assertTrue(describe(binds), containsBind(binds, systemRoot.resolve("lib64"), "/system/lib64"));
        assertFalse(describe(binds), containsBind(binds, systemRoot.resolve("bin/linker64"),
                "/system/bin/linker64"));
        assertFalse(describe(binds), containsBind(binds, systemRoot.resolve("lib64/libc.so"),
                "/system/lib64/libc.so"));
        assertTrue(Files.isDirectory(rootfs.resolve("system/bin")));
        assertTrue(Files.isDirectory(rootfs.resolve("system/lib64")));
    }

    @Test
    public void buildSpecSkipsOnlyMissingSystemFiles() throws Exception {
        // Only linker64 staged: the lib64 binds are skipped without failing,
        // and no unneeded parent dir is created.
        Path systemRoot = filesDir.resolve("faked-system-partial");
        writeIfAbsent(systemRoot.resolve("bin/linker64"), "");

        ProotLaunchSpec spec = new ProotLauncher(context, systemRoot).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(describe(binds), containsBind(binds, systemRoot.resolve("bin"), "/system/bin"));
        assertFalse(describe(binds), containsGuestPath(binds, "/system/lib64"));
        assertTrue(Files.isDirectory(rootfs.resolve("system/bin")));
        assertFalse(Files.exists(rootfs.resolve("system/lib64")));
    }

    @Test
    public void buildSpecBindsToolTreesOverProductSystemArtifacts() throws Exception {
        // The curated provisioning and the earlier file binds leave Bionic
        // artifacts under /system; they are product-owned, not guest content,
        // so the tool-tree binds still apply (ADR-0045).
        writeIfAbsent(rootfs.resolve("system/bin/linker64"), "");
        writeIfAbsent(rootfs.resolve("system/lib64/libc.so"), "");
        Path systemRoot = filesDir.resolve("faked-system");
        writeIfAbsent(systemRoot.resolve("bin/sh"), "");
        writeIfAbsent(systemRoot.resolve("lib64/libc.so"), "");
        // Android 11+ keeps the runtime in the APEX: stage that tree too.
        Path apexRoot = filesDir.resolve("apex");
        writeIfAbsent(apexRoot.resolve("com.android.runtime/bin/linker64"), "");

        ProotLaunchSpec spec = new ProotLauncher(context, systemRoot).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertTrue(describe(binds), containsBind(binds, systemRoot.resolve("bin"), "/system/bin"));
        assertTrue(describe(binds), containsBind(binds, systemRoot.resolve("lib64"), "/system/lib64"));
        assertTrue(describe(binds), containsBind(binds, filesDir.resolve("apex"), "/apex"));
    }

    @Test
    public void buildSpecKeepsGuestOwnedSystemTreeUntouched() throws Exception {
        // A real guest file under /system is content: the tool-tree binds are
        // skipped for the session instead of shadowing it.
        writeIfAbsent(rootfs.resolve("system/bin/guest-tool"), "");
        Path systemRoot = filesDir.resolve("faked-system");
        writeIfAbsent(systemRoot.resolve("bin/sh"), "");

        ProotLaunchSpec spec = new ProotLauncher(context, systemRoot).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        List<ProotBindMount> binds = spec.getBindMounts();
        assertFalse(describe(binds), containsBind(binds, systemRoot.resolve("bin"), "/system/bin"));
        assertTrue(Files.exists(rootfs.resolve("system/bin/guest-tool")));
    }

    @Test
    public void buildSpecDoesNotShadowExistingGuestFile() throws Exception {
        // A real file already in the rootfs at an inner target always wins:
        // the product bind is skipped and the guest file is untouched.
        Path guestProot = rootfs.resolve("usr/local/bin/proot");
        Files.createDirectories(guestProot.getParent());
        Files.write(guestProot, "guest-owned".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ProotLaunchSpec spec = new ProotLauncher(context).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        assertFalse(containsGuestPath(spec.getBindMounts(), "/usr/local/bin/proot"));
        assertTrue("guest-owned".equals(new String(
                Files.readAllBytes(guestProot), java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void buildSpecSkipsInnerBindWhenGuestComponentIsNotDirectory() throws Exception {
        // rootfs/system exists as a regular file: no directory may be created
        // over it, so the linker bind is skipped rather than shadowing content.
        Path systemRoot = filesDir.resolve("faked-system");
        writeIfAbsent(systemRoot.resolve("bin/linker64"), "");
        Files.write(rootfs.resolve("system"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ProotLaunchSpec spec = new ProotLauncher(context, systemRoot).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());

        assertFalse(containsGuestPath(spec.getBindMounts(), "/system/bin/linker64"));
        assertFalse(Files.isDirectory(rootfs.resolve("system")));
    }

    @Test
    public void innerRuntimeBindsAreEmittedAsDashBPairsInArgv() throws Exception {
        Path nativeDir = filesDir.resolve("faked-nativelib");
        writeIfAbsent(nativeDir.resolve(ProotPaths.PROOT_LOADER_BINARY_NAME), "");
        Path systemRoot = filesDir.resolve("faked-system");
        writeIfAbsent(systemRoot.resolve("bin/linker64"), "");

        ProotLaunchSpec spec = new ProotLauncher(context, systemRoot).buildSpec(
                ProotLauncher.SUPPORTED_APP_ID,
                ProotLauncher.GUEST_PROBE_ARGV,
                Collections.<ProotBindMount>emptyList(),
                Collections.<String, String>emptyMap());
        List<String> argv = new ProotCommandFactory().buildArgv(spec);

        for (String bindArg : Arrays.asList(
                nativeDir.resolve(ProotPaths.PROOT_BINARY_NAME) + ":/usr/local/bin/proot",
                nativeDir.resolve(ProotPaths.PROOT_LOADER_BINARY_NAME) + ":/usr/local/bin/proot-loader",
                systemRoot.resolve("bin") + ":/system/bin")) {
            int index = argv.indexOf(bindArg);
            assertTrue("missing -b argument: " + bindArg, index > 0);
            assertTrue("-b precedes " + bindArg,
                    ProotCommandFactory.OPT_BIND.equals(argv.get(index - 1)));
        }
    }

    private Path activateOverlay(String addonId, String entrypoint) throws Exception {
        Path active = ProotPaths.activeAddonPath(filesDir, addonId);
        Path entry = active.resolve(entrypoint);
        Files.createDirectories(entry.getParent());
        writeIfAbsent(entry, "");
        return active;
    }

    private static boolean containsBind(List<ProotBindMount> binds, Path hostPath,
                                        String guestPath) {
        for (ProotBindMount bind : binds) {
            if (bind.getHostPath().equals(hostPath.toString())
                    && bind.getGuestPath().equals(guestPath)) {
                return true;
            }
        }
        return false;
    }

    /** Diagnostic view of a bind list: host->guest pairs. */
    private static String describe(List<ProotBindMount> binds) {
        StringBuilder out = new StringBuilder("binds: ");
        for (ProotBindMount bind : binds) {
            out.append(bind.getHostPath()).append("->").append(bind.getGuestPath()).append("; ");
        }
        return out.toString();
    }

    private static boolean containsGuestPath(List<ProotBindMount> binds, String guestPath) {
        for (ProotBindMount bind : binds) {
            if (bind.getGuestPath().equals(guestPath)) {
                return true;
            }
        }
        return false;
    }

    private static void writeIfAbsent(Path path, String content) throws Exception {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
