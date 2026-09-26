package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Focused tests for the opt-in guest tool bundles: independent
 * install/report/reconcile of {@code USB_ADB} and {@code TERMUX}, marker
 * persistence under {@code /var/lib/nusadesk/optional-tools}, adoption of
 * pre-marker installs, and the safety rules (never count or clobber
 * unmarked files, never follow a symlinked marker or state directory).
 */
public class GuestOptionalToolsTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void freshRootfsReportsNeitherInstalledAndReconcileWritesNothing()
            throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));

        // A skipped reconcile creates nothing at all.
        assertFalse(Files.exists(rootfs.resolve(
                GuestOptionalTools.GUEST_STATE_DIR_RELATIVE_PATH)));
        assertFalse(Files.exists(rootfs.resolve("usr/local/bin")));
        assertFalse(Files.exists(rootfs.resolve("opt")));
    }

    @Test
    public void installUsbAdbWritesOnlyItsBundleAndMarker() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestOptionalTools.install(rootfs, "0.3.0",
                        GuestOptionalTools.Kind.USB_ADB));

        Path cli = rootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH);
        Path daemon = rootfs.resolve(GuestUsbDaemonWriter.GUEST_DAEMON_RELATIVE_PATH);
        Path wrapper = rootfs.resolve(GuestUsbShimWriter.WRAPPER_GUEST_PATH);
        Path shim = rootfs.resolve(GuestUsbShimWriter.SHIM_GUEST_PATH);
        assertTrue(Files.isRegularFile(cli));
        assertTrue(Files.isRegularFile(daemon));
        assertTrue(Files.isRegularFile(wrapper));
        assertTrue(Files.isRegularFile(shim));
        assertTrue(Files.isExecutable(cli));
        assertTrue(Files.isExecutable(daemon));
        assertTrue(Files.isExecutable(wrapper));
        assertFalse(Files.isExecutable(shim));
        assertTrue(new String(Files.readAllBytes(cli), StandardCharsets.UTF_8)
                .contains("App version: 0.3.0"));

        Path marker = rootfs.resolve(
                GuestOptionalTools.markerRelativePath(GuestOptionalTools.Kind.USB_ADB));
        assertTrue(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS));
        String markerText = new String(Files.readAllBytes(marker),
                StandardCharsets.UTF_8);
        assertTrue(markerText.contains("kind: usb-adb"));
        assertTrue(markerText.contains("0.3.0"));

        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));

        // The other bundle is untouched: no termux-*, no termux doc, and the
        // core-only module is not this bundle's to write.
        Path bin = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH);
        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            assertFalse(Files.exists(bin.resolve(command)));
        }
        assertFalse(Files.exists(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_DOC_RELATIVE_PATH)));
        assertFalse(Files.exists(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_MODULE_RELATIVE_PATH)));
    }

    @Test
    public void installTermuxWritesCommandsDocAndMarkerOnly() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestOptionalTools.install(rootfs, "0.3.0",
                        GuestOptionalTools.Kind.TERMUX));

        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            Path script = rootfs.resolve(
                    GuestTermuxCompatWriter.guestRelativePath(command));
            assertTrue(command + " must be installed", Files.isRegularFile(script));
            assertTrue(command + " must be executable", Files.isExecutable(script));
        }
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_DOC_RELATIVE_PATH)));
        // The module comes along because the layer's ensure writes it; it is
        // core content, not what makes this choice stick.
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_MODULE_RELATIVE_PATH)));

        Path marker = rootfs.resolve(
                GuestOptionalTools.markerRelativePath(GuestOptionalTools.Kind.TERMUX));
        assertTrue(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS));
        assertTrue(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)
                .contains("kind: termux"));

        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertFalse(Files.exists(
                rootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH)));
        assertFalse(Files.exists(
                rootfs.resolve(GuestUsbShimWriter.WRAPPER_GUEST_PATH)));
    }

    @Test
    public void installIsIdempotentAndReconcileStaysQuiet() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();

        GuestOptionalTools.install(rootfs, "0.3.0", GuestOptionalTools.Kind.USB_ADB);
        GuestOptionalTools.install(rootfs, "0.3.0", GuestOptionalTools.Kind.TERMUX);

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.install(rootfs, "0.3.0",
                        GuestOptionalTools.Kind.USB_ADB));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.install(rootfs, "0.3.0",
                        GuestOptionalTools.Kind.TERMUX));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));
        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
    }

    @Test
    public void reconcileRefreshesInstalledBundleOnVersionBump() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestOptionalTools.install(rootfs, "0.3.0", GuestOptionalTools.Kind.USB_ADB);

        Path cli = rootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH);
        Files.write(cli, "stale".getBytes(StandardCharsets.UTF_8));

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestOptionalTools.reconcile(rootfs, "0.4.0"));
        assertTrue(new String(Files.readAllBytes(cli), StandardCharsets.UTF_8)
                .contains("App version: 0.4.0"));
        String markerText = new String(Files.readAllBytes(rootfs.resolve(
                GuestOptionalTools.markerRelativePath(
                        GuestOptionalTools.Kind.USB_ADB))), StandardCharsets.UTF_8);
        assertTrue(markerText.contains("0.4.0"));
        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
    }

    @Test
    public void reconcileAdoptsLegacyUsbInstallAndKeepsIt() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path bin = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH);
        Files.createDirectories(bin);
        Path legacy = bin.resolve(GuestUsbCliWriter.CLI_FILE_NAME);
        Files.write(legacy, GuestUsbCliWriter.scriptContent("0.0.9")
                .getBytes(StandardCharsets.UTF_8));

        // No marker, but the generated file proves the install: adopt it.
        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));
        assertTrue(Files.isRegularFile(rootfs.resolve(
                GuestOptionalTools.markerRelativePath(
                        GuestOptionalTools.Kind.USB_ADB)),
                LinkOption.NOFOLLOW_LINKS));
        assertTrue(new String(Files.readAllBytes(legacy), StandardCharsets.UTF_8)
                .contains("App version: 0.3.0"));
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestUsbDaemonWriter.GUEST_DAEMON_RELATIVE_PATH)));
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestUsbShimWriter.WRAPPER_GUEST_PATH)));
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestUsbShimWriter.SHIM_GUEST_PATH)));

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));
    }

    @Test
    public void reconcileAdoptsLegacyTermuxInstallAndSweepsStale() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path bin = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH);
        Files.createDirectories(bin);
        Files.write(bin.resolve("termux-battery-status"),
                GuestTermuxCompatWriter.scriptContent("termux-battery-status", "0.0.9")
                        .getBytes(StandardCharsets.UTF_8));
        // A retired generated command from the old version is adopted and
        // then swept by the normal install path.
        Path stale = bin.resolve("termux-legacy-widget");
        Files.write(stale, String.join("\n",
                "#!/usr/bin/env python3",
                "# " + GuestTermuxCompatWriter.MARKER + " client: termux-legacy-widget",
                "# Generated by the NusaDesk Android app; do not edit.",
                "").getBytes(StandardCharsets.UTF_8));

        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));

        assertTrue(Files.isRegularFile(rootfs.resolve(
                GuestOptionalTools.markerRelativePath(
                        GuestOptionalTools.Kind.TERMUX)),
                LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_DOC_RELATIVE_PATH)));
        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            assertTrue(Files.isRegularFile(bin.resolve(command)));
        }
        assertFalse("stale generated command is swept on adoption",
                Files.exists(stale));
    }

    @Test
    public void unmarkedUserFilesAreNeverAdoptedOrClobbered() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path bin = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH);
        Files.createDirectories(bin);
        byte[] userUsb = "#!/bin/sh\necho mine\n".getBytes(StandardCharsets.UTF_8);
        byte[] userTermux = "#!/bin/sh\necho termux-mine\n".getBytes(StandardCharsets.UTF_8);
        byte[] userAdb = "#!/bin/sh\necho real-adb\n".getBytes(StandardCharsets.UTF_8);
        Files.write(bin.resolve(GuestUsbCliWriter.CLI_FILE_NAME), userUsb);
        Files.write(bin.resolve("termux-mine"), userTermux);
        Files.write(bin.resolve(GuestUsbShimWriter.WRAPPER_FILE_NAME), userAdb);

        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));

        assertTrue(java.util.Arrays.equals(userUsb,
                Files.readAllBytes(bin.resolve(GuestUsbCliWriter.CLI_FILE_NAME))));
        assertTrue(java.util.Arrays.equals(userTermux,
                Files.readAllBytes(bin.resolve("termux-mine"))));
        assertTrue(java.util.Arrays.equals(userAdb,
                Files.readAllBytes(bin.resolve(GuestUsbShimWriter.WRAPPER_FILE_NAME))));
        assertFalse(Files.exists(rootfs.resolve(
                GuestOptionalTools.GUEST_STATE_DIR_RELATIVE_PATH)));
    }

    @Test
    public void ensureEntryPointAdoptsLegacyBundlesOnSessionStart() throws Exception {
        // The session-start path calls only GuestAwarenessReadmeWriter.ensure;
        // adoption must ride along through its reconcile call.
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path bin = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH);
        Files.createDirectories(bin);
        Files.write(bin.resolve(GuestUsbCliWriter.CLI_FILE_NAME),
                GuestUsbCliWriter.scriptContent("0.0.9")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(bin.resolve("termux-battery-status"),
                GuestTermuxCompatWriter.scriptContent("termux-battery-status", "0.0.9")
                        .getBytes(StandardCharsets.UTF_8));

        GuestAwarenessReadmeWriter.ensure(rootfs, "0.3.0");

        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertTrue(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.TERMUX));
        assertTrue(Files.isRegularFile(rootfs.resolve(
                GuestOptionalTools.markerRelativePath(
                        GuestOptionalTools.Kind.USB_ADB))));
        assertTrue(Files.isRegularFile(rootfs.resolve(
                GuestOptionalTools.markerRelativePath(
                        GuestOptionalTools.Kind.TERMUX))));
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestUsbShimWriter.SHIM_GUEST_PATH)));
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_DOC_RELATIVE_PATH)));

        // And the next start is quiet.
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.3.0"));
    }

    @Test
    public void symlinkMarkerIsNotCountedAndInstallNeverFollowsIt() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path stateDir = rootfs.resolve(
                GuestOptionalTools.GUEST_STATE_DIR_RELATIVE_PATH);
        Files.createDirectories(stateDir);
        Path outside = temporary.newFile("outside-marker").toPath();
        byte[] outsideBytes = "user file".getBytes(StandardCharsets.UTF_8);
        Files.write(outside, outsideBytes);
        Path link = stateDir.resolve("usb-adb");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue("symlinks unsupported here", false);
        }

        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));
        assertTrue(Files.isSymbolicLink(link));

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestOptionalTools.install(rootfs, "0.3.0",
                        GuestOptionalTools.Kind.USB_ADB));
        assertTrue(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS));
        assertTrue(java.util.Arrays.equals(outsideBytes,
                Files.readAllBytes(outside)));
    }

    @Test
    public void stateDirectorySymlinkFailsClosed() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path parent = rootfs.resolve("var/lib/nusadesk");
        Files.createDirectories(parent);
        Path outsideDir = temporary.newFolder("outside-state").toPath();
        try {
            Files.createSymbolicLink(parent.resolve("optional-tools"), outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue("symlinks unsupported here", false);
        }

        try {
            GuestOptionalTools.install(rootfs, "0.3.0",
                    GuestOptionalTools.Kind.USB_ADB);
            fail("expected the symlinked state dir to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("optional-tools"));
        }
        assertFalse("nothing may be written through the link",
                Files.exists(outsideDir.resolve("usb-adb")));
    }

    /**
     * An unreadable foreign file must never fail detection or session-start
     * reconciliation: it is simply not proof of an install, and the app must
     * not need a live session to fix its own permissions.
     */
    @Test
    public void unreadableForeignFileNeverFailsDetectionOrReconcile() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        Path cli = rootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH);
        Files.createDirectories(cli.getParent());
        Files.write(cli, "#!/bin/sh\necho foreign\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(cli,
                java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        assumeTrue("needs a non-root process for mode 000 to deny reads",
                !Files.isReadable(cli));

        assertFalse(GuestOptionalTools.isInstalled(rootfs,
                GuestOptionalTools.Kind.USB_ADB));
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestOptionalTools.reconcile(rootfs, "0.3.0"));
        assertTrue("the unreadable foreign file must be left in place",
                Files.exists(cli));
    }

    @Test
    public void rejectsNullAndUnsafeArguments() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        try {
            GuestOptionalTools.install(null, "0.3.0",
                    GuestOptionalTools.Kind.USB_ADB);
            fail("null rootfs must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            GuestOptionalTools.install(rootfs, "0.3.0", null);
            fail("null kind must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            GuestOptionalTools.install(rootfs, "0.3.0\nrm -rf /",
                    GuestOptionalTools.Kind.USB_ADB);
            fail("unsafe version must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            GuestOptionalTools.isInstalled(null, GuestOptionalTools.Kind.TERMUX);
            fail("null rootfs must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            GuestOptionalTools.isInstalled(rootfs, null);
            fail("null kind must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            GuestOptionalTools.reconcile(null, "0.3.0");
            fail("null rootfs must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }
}
