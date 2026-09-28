package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.PayloadArtifact;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Pure-JVM tests for {@link GuestSystemdBusFace}: the catalog profile's pins,
 * overlay detection (never-installed vs torn), the rootfs symlink wiring
 * contract (never shadowing real guest content, never bouncing a satisfied
 * library link between overlays), the provider-unit enablement, and the
 * runtime-dir/machine-id setup the bus needs.
 *
 * <p>The {@code lw-busctl} shim asset is additionally executed with the
 * host's real {@code python3} against a minimal fake {@code dbus} package
 * staged into the staged overlay's {@code dist-packages} — the same
 * re-rooting discipline {@code LwSystemctlWrapperAssetTest} uses. That pins
 * the operand parsing and output shapes consumers string-match, without a
 * live bus in a unit test.</p>
 */
public class GuestSystemdBusFaceTest {

    @Rule
    public final TemporaryFolder rootfs = new TemporaryFolder();

    @Rule
    public final TemporaryFolder overlay = new TemporaryFolder();

    @Rule
    public final TemporaryFolder shimStage = new TemporaryFolder();

    // -- profile pins --------------------------------------------------------

    @Test
    public void profilePinsTheEntrypointAndGuestDir() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestSystemdBusFace();
        assertEquals("guest-systemd-dbus-face", profile.getAddonId());
        assertEquals("/opt/lw-dbus", profile.getGuestDir());
        // The entrypoint is the AArch64 ELF the installer ABI-checks; the
        // provider and the shim are scripts, so dbus-daemon carries it.
        assertEquals("usr/bin/dbus-daemon", profile.getEntrypoint());
        assertEquals(CuratedRuntimeCatalog.DBUS_FACE_OVERLAY_GUEST_DIR,
                profile.getGuestDir());
        assertEquals(CuratedRuntimeCatalog.DBUS_FACE_OVERLAY_ENTRYPOINT,
                profile.getEntrypoint());
    }

    @Test
    public void profileCarriesTheMeasuredDbusClosure() {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestSystemdBusFace();
        List<String> ids = new ArrayList<>();
        for (PayloadArtifact artifact : profile.getArtifacts()) {
            ids.add(artifact.getArtifactId());
            assertTrue(artifact.getArtifactId() + " must be digest-pinned",
                    artifact.getSha256().matches("[0-9a-f]{64}"));
            assertTrue(artifact.getDownloadUrl()
                    .startsWith("https://ports.ubuntu.com/ubuntu-ports/"));
        }
        // The closure measured against the Noble arm64 index: daemon + tools +
        // libdbus + the session-bus config + the python bindings the shim
        // imports + the two runtime libs the base rootfs lacks.
        assertEquals(Arrays.asList(
                "dbus-daemon_1.14.10-4ubuntu4_arm64",
                "dbus-bin_1.14.10-4ubuntu4_arm64",
                "libdbus-1-3_1.14.10-4ubuntu4_arm64",
                "dbus-session-bus-common_1.14.10-4ubuntu4_all",
                "python3-dbus_1.3.2-5build3_arm64",
                "libapparmor1_4.0.0-beta3-0ubuntu3_arm64",
                "libexpat1_2.6.1-2build1_arm64"), ids);
        // GLib is deliberately absent: the provider speaks the wire protocol
        // itself rather than pay libglib2.0 for dbus-python's only concrete
        // main loop.
        assertFalse(ids.toString().contains("glib"));
    }

    @Test
    public void profilePinsThePythonExtensionAbi() {
        // python3-dbus is a C extension: its filename tag IS the ABI check —
        // a rebuild for another interpreter or arch changes the member name
        // and fails install verification loudly instead of an ImportError at
        // runtime.
        assertTrue(CuratedRuntimeCatalog.guestSystemdBusFace().getRequiredFiles()
                .contains("usr/lib/python3/dist-packages/"
                        + "_dbus_bindings.cpython-312-aarch64-linux-gnu.so"));
    }

    @Test
    public void profileDeclaresTheBridgeDependency() {
        // The face delegates to the bridge's systemctl and runs its scripts
        // on the bridge's interpreter — both are rootfs paths the bridge
        // wires, so they are declared as required rootfs members.
        assertEquals(Arrays.asList("usr/bin/systemctl", "usr/bin/python3.12"),
                CuratedRuntimeCatalog.guestSystemdBusFace().getRequiredRootfsTools());
    }

    @Test
    public void theFaceIsRegisteredAfterTheBridgeItDependsOn() {
        List<String> ids = new ArrayList<>();
        for (GuestAddonPayloadProfile profile : CuratedRuntimeCatalog.guestAddons()) {
            ids.add(profile.getAddonId());
        }
        // Install order: ssh, the service bridge, base extras, then the face —
        // last, because its wiring reads rootfs members the bridge installs.
        assertEquals(Arrays.asList("guest-ssh-openssh", "guest-service-bridge",
                "guest-base-extras", "guest-systemd-dbus-face"), ids);
    }

    // -- detection -------------------------------------------------------------

    @Test
    public void inspectSeparatesNeverInstalledFromDamaged() throws Exception {
        assertEquals(GuestSystemdBusFace.Absence.NOT_INSTALLED,
                GuestSystemdBusFace.inspect(null).getAbsence());
        assertEquals(GuestSystemdBusFace.Absence.NOT_INSTALLED,
                GuestSystemdBusFace.inspect(overlay.getRoot().toPath()).getAbsence());

        // Only the entrypoint present: something of the face is here, so it
        // is damage, not an opt-out — the session-bus config is named first.
        touch(overlay.getRoot(), "usr/bin/dbus-daemon");
        GuestSystemdBusFace.Detection half =
                GuestSystemdBusFace.inspect(overlay.getRoot().toPath());
        assertEquals(GuestSystemdBusFace.Absence.CORRUPT, half.getAbsence());
        assertNull(half.getFace());
        assertEquals("usr/share/dbus-1/session.conf", half.getDetail());

        touch(overlay.getRoot(), "usr/share/dbus-1/session.conf");
        half = GuestSystemdBusFace.inspect(overlay.getRoot().toPath());
        assertEquals("usr/bin/busctl", half.getDetail());

        // A complete overlay is usable.
        face();
        GuestSystemdBusFace.Detection complete =
                GuestSystemdBusFace.inspect(overlay.getRoot().toPath());
        assertNull(complete.getAbsence());
        assertNotNull(complete.getFace());
        assertEquals("", complete.getDetail());
        assertNotNull(GuestSystemdBusFace.detect(overlay.getRoot().toPath()));
    }

    @Test
    public void inspectCallsAStaleVendoredMemberDamageNotAbsence() throws Exception {
        assertNotNull(face());
        Path shim = overlay.getRoot().toPath().resolve("usr/bin/busctl");
        byte[] pinned = Files.readAllBytes(shim);
        try {
            Files.write(shim, "tampered".getBytes(StandardCharsets.UTF_8));
            GuestSystemdBusFace.Detection detection =
                    GuestSystemdBusFace.inspect(overlay.getRoot().toPath());
            assertEquals(GuestSystemdBusFace.Absence.CORRUPT, detection.getAbsence());
            assertEquals("usr/bin/busctl", detection.getDetail());
        } finally {
            Files.write(shim, pinned);
        }
    }

    // -- wiring ----------------------------------------------------------------

    @Test
    public void wireIntoLinksTheConventionalSurface() throws Exception {
        GuestSystemdBusFace face = face();
        touch(overlay.getRoot(), "usr/share/dbus-1/services/.keep");
        touch(overlay.getRoot(), "etc/dbus-1/session.d/.keep");
        touch(overlay.getRoot(), "usr/lib/python3/dist-packages/dbus/__init__.py");
        touch(overlay.getRoot(), "usr/lib/aarch64-linux-gnu/libdbus-1.so.3.32.4");
        link(overlay.getRoot(), "usr/lib/aarch64-linux-gnu/libdbus-1.so.3",
                "libdbus-1.so.3.32.4");

        List<String> wired = face.wireInto(rootfs.getRoot().toPath());

        assertLinked(rootfs.getRoot(), "usr/bin/busctl",
                "/opt/lw-dbus/usr/bin/busctl");
        assertLinked(rootfs.getRoot(), "usr/sbin/lw-systemd-dbus-provider",
                "/opt/lw-dbus/usr/sbin/lw-systemd-dbus-provider");
        assertLinked(rootfs.getRoot(), "usr/share/dbus-1",
                "/opt/lw-dbus/usr/share/dbus-1");
        assertLinked(rootfs.getRoot(), "etc/dbus-1",
                "/opt/lw-dbus/etc/dbus-1");
        assertLinked(rootfs.getRoot(), "usr/lib/python3/dist-packages",
                "/opt/lw-dbus/usr/lib/python3/dist-packages");
        assertLinked(rootfs.getRoot(), "usr/lib/aarch64-linux-gnu/libdbus-1.so.3",
                "/opt/lw-dbus/usr/lib/aarch64-linux-gnu/libdbus-1.so.3");
        assertLinked(rootfs.getRoot(), "usr/lib/aarch64-linux-gnu/libdbus-1.so.3.32.4",
                "/opt/lw-dbus/usr/lib/aarch64-linux-gnu/libdbus-1.so.3.32.4");
        assertTrue(wired.contains("usr/bin/busctl"));
        assertTrue(wired.contains("usr/lib/aarch64-linux-gnu/libdbus-1.so.3"));
    }

    @Test
    public void wireIntoEnablesTheProviderForUserInit() throws Exception {
        GuestSystemdBusFace face = face();

        List<String> wired = face.wireInto(rootfs.getRoot().toPath());

        // The vendored systemctl3 only restart-supervises units enabled at
        // `--user init`, so the wants link is pre-created with the same
        // relative target `systemctl --user enable` would write — under the
        // *user* unit dir, in default.target (lw-user-manager's
        // SYSTEMD_DEFAULT_TARGET).
        String wants =
                "etc/systemd/user/default.target.wants/lw-systemd-dbus-provider.service";
        assertLinked(rootfs.getRoot(), wants, "../lw-systemd-dbus-provider.service");
        assertTrue(wired.contains(wants));
        assertTrue("rewiring must be a no-op",
                face.wireInto(rootfs.getRoot().toPath()).isEmpty());
    }

    @Test
    public void wireIntoCreatesRuntimeDirAndMachineId() throws Exception {
        GuestSystemdBusFace face = face();

        face.wireInto(rootfs.getRoot().toPath());

        assertTrue(Files.isDirectory(rootfs.getRoot().toPath().resolve("run/user/0")));
        Path machineId = rootfs.getRoot().toPath().resolve("etc/machine-id");
        assertTrue(Files.isRegularFile(machineId));
        String id = new String(Files.readAllBytes(machineId), StandardCharsets.US_ASCII).trim();
        assertTrue("machine-id must be 32 lowercase hex: " + id,
                id.matches("[0-9a-f]{32}"));
        // Idempotent: the generated id is guest-owned afterwards.
        face.wireInto(rootfs.getRoot().toPath());
        assertEquals(id, new String(Files.readAllBytes(machineId),
                StandardCharsets.US_ASCII).trim());
    }

    @Test
    public void wireIntoNeverShadowsRealGuestContent() throws Exception {
        GuestSystemdBusFace face = face();
        touch(rootfs.getRoot(), "usr/bin/busctl");
        touch(rootfs.getRoot(), "etc/machine-id");

        List<String> wired = face.wireInto(rootfs.getRoot().toPath());

        assertFalse(wired.contains("usr/bin/busctl"));
        assertFalse(Files.isSymbolicLink(rootfs.getRoot().toPath().resolve("usr/bin/busctl")));
        assertEquals("guest content is never overwritten", 0,
                Files.size(rootfs.getRoot().toPath().resolve("etc/machine-id")));
    }

    @Test
    public void wireIntoKeepsAnAlreadySatisfiedLibraryLink() throws Exception {
        GuestSystemdBusFace face = face();
        touch(overlay.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1.9.1");
        link(overlay.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1",
                "libexpat.so.1.9.1");
        // The service bridge wires the identical pinned libexpat bytes from
        // its own overlay first: the face must not bounce the pointer. The
        // stand-in target must actually resolve (a dangling link is repaired
        // by design), so it points at this overlay's real member — different
        // target string, same "already satisfied" semantics.
        Path bridgeStandIn = overlay.getRoot().toPath()
                .resolve("usr/lib/aarch64-linux-gnu/libexpat.so.1.9.1")
                .toAbsolutePath();
        link(rootfs.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1",
                bridgeStandIn.toString());

        List<String> wired = face.wireInto(rootfs.getRoot().toPath());

        assertFalse(wired.contains("usr/lib/aarch64-linux-gnu/libexpat.so.1"));
        assertEquals(bridgeStandIn,
                Files.readSymbolicLink(rootfs.getRoot().toPath()
                        .resolve("usr/lib/aarch64-linux-gnu/libexpat.so.1")));
        // …but a dangling library symlink is still repaired.
        Files.delete(rootfs.getRoot().toPath()
                .resolve("usr/lib/aarch64-linux-gnu/libexpat.so.1"));
        link(rootfs.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1",
                "/nonexistent/libexpat.so.1");
        wired = face.wireInto(rootfs.getRoot().toPath());
        assertTrue(wired.contains("usr/lib/aarch64-linux-gnu/libexpat.so.1"));
        assertLinked(rootfs.getRoot(), "usr/lib/aarch64-linux-gnu/libexpat.so.1",
                "/opt/lw-dbus/usr/lib/aarch64-linux-gnu/libexpat.so.1");
    }

    @Test
    public void requiredBindsMountsOverlayAndStrictBindsEntrypoints() throws Exception {
        GuestSystemdBusFace face = face();

        List<ProotBindMount> binds = face.requiredBinds();

        assertEquals(overlay.getRoot().getAbsolutePath(), binds.get(0).getHostPath());
        assertEquals("/opt/lw-dbus", binds.get(0).getGuestPath());
        for (String guestPath : Arrays.asList(
                "/usr/bin/busctl", "/usr/sbin/lw-systemd-dbus-provider")) {
            int index = indexOfGuestPath(binds, guestPath);
            assertTrue("missing strict bind for " + guestPath, index > 0);
            assertTrue(guestPath + " must be strict", binds.get(index).isStrict());
            assertEquals(overlay.getRoot().getAbsolutePath() + guestPath,
                    binds.get(index).getHostPath());
        }
    }

    // -- the shim asset, executed ----------------------------------------------

    @Test
    public void busctlShimParsesGetPropertyAndPrintsTheLegacyShape() throws Exception {
        // The exact probe openclaw's transport candidate runs; the answer
        // must be one `s "…"` line and nothing else.
        Result result = runShim("--user", "--auto-start=no", "get-property",
                "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
                "org.freedesktop.systemd1.Manager", "Version");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals("s \"systemd 219\"\n", result.out);
        assertEquals("", result.err);
    }

    @Test
    public void busctlShimEmitsTheJsonShortShape() throws Exception {
        Result result = runShim("--user", "--json=short", "call",
                "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
                "org.freedesktop.systemd1.Manager", "LoadUnit", "s", "ok.service");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals("{\"type\":\"o\",\"data\":["
                + "\"/org/freedesktop/systemd1/unit/ok_2eservice\"]}\n", result.out);
    }

    @Test
    public void busctlShimRendersTheExactNotFoundError() throws Exception {
        // openclaw string-matches `Call failed: Unit <name> not found.` —
        // the message, not the error name.
        Result result = runShim("--user", "--json=short", "call",
                "org.freedesktop.systemd1", "/org/freedesktop/systemd1",
                "org.freedesktop.systemd1.Manager", "LoadUnit", "s", "nope.service");

        assertEquals(1, result.code);
        assertEquals("Call failed: Unit nope.service not found.\n", result.err);
        assertEquals("", result.out);
    }

    @Test
    public void busctlShimRejectsUnsupportedVerbsCleanly() throws Exception {
        Result result = runShim("--user", "status", "org.freedesktop.systemd1");

        assertNotEquals(0, result.code);
        assertTrue(result.err.contains("unsupported verb"));
    }

    @Test
    public void busctlShimFailsWhenTheBusDoesNotAnswer() throws Exception {
        // A shim that prints a synthetic result here would be the worst
        // outcome — consumers would read a dead bus as a working systemd.
        Result result = runShimUnreachable("--user", "--auto-start=no",
                "get-property", "org.freedesktop.systemd1",
                "/org/freedesktop/systemd1",
                "org.freedesktop.systemd1.Manager", "Version");

        assertNotEquals(0, result.code);
        assertTrue(result.err.contains("Failed to connect to bus"));
        assertEquals("", result.out);
    }

    @Test
    public void busctlShimIsAPython312ScriptUsingOnlyPython3Dbus() throws Exception {
        List<String> lines = Files.readAllLines(
                assetsDir().resolve("services/lw-busctl"), StandardCharsets.UTF_8);
        assertEquals("#!/usr/bin/python3.12", lines.get(0));
        // No GLib anywhere in the face: the dependency the task forbids.
        String content = String.join("\n", lines);
        assertFalse(content.contains("mainloop.glib"));
        assertFalse(content.contains("import gi"));
    }

    @Test
    public void providerSpeaksTheWireProtocolWithoutDbusPythonOrGlib() throws Exception {
        // The server side cannot use python3-dbus at all (its only concrete
        // main loop is the GLib one) — pin that it never imports the client
        // bindings or GLib, and that it delegates through /usr/bin/systemctl
        // (the one-manager rule).
        List<String> lines = Files.readAllLines(
                assetsDir().resolve("services/lw-systemd-dbus-provider"),
                StandardCharsets.UTF_8);
        assertEquals("#!/usr/bin/python3.12", lines.get(0));
        String content = String.join("\n", lines);
        // The word may appear in the design comment; what is pinned is that
        // no line actually imports the client bindings or GLib.
        for (String line : lines) {
            String trimmed = line.trim();
            assertFalse("provider must not import dbus/glib: " + trimmed,
                    trimmed.matches("(import|from)\\s+(dbus|gi)\\b.*"));
        }
        assertTrue("the provider must delegate to the bridge's CLI",
                content.contains("SYSTEMCTL = \"/usr/bin/systemctl\""));
        assertTrue("the provider must spawn the vendored daemon",
                content.contains("\"/usr/bin/dbus-daemon\""));
        // The exact error text consumers string-match must be produced.
        assertTrue(content.contains("not found.\""));
    }

    // -- harness -----------------------------------------------------------------

    private GuestSystemdBusFace face() throws Exception {
        // Entrypoint + session config are payload members: presence is
        // enough. Every vendored member is staged with its real packaged
        // bytes because inspect() re-verifies each pinned digest.
        touch(overlay.getRoot(), "usr/bin/dbus-daemon");
        touch(overlay.getRoot(), "usr/share/dbus-1/session.conf");
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestSystemdBusFace().getVendoredFiles()) {
            stage(overlay.getRoot(), vendored);
        }
        return GuestSystemdBusFace.detect(overlay.getRoot().toPath());
    }

    private void stage(File base, VendoredFile vendored) throws Exception {
        Path source = assetsDir().resolve(vendored.getAssetPath());
        assertTrue("packaged asset missing: " + source, Files.isRegularFile(source));
        Path target = base.toPath().resolve(vendored.getOverlayPath());
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path assetsDir() {
        Path workingDir = Paths.get("").toAbsolutePath();
        Path moduleAssets = workingDir.resolve("src/main/assets");
        return Files.isDirectory(moduleAssets)
                ? moduleAssets
                : workingDir.resolve("app/src/main/assets");
    }

    /**
     * A minimal fake {@code dbus} package staged into the staged overlay's
     * {@code dist-packages}: just enough of the python3-dbus API for the
     * shim's client path — typed wrappers, MethodCallMessage, the NULL main
     * loop, and a BusConnection whose reply is driven by LW_FAKE_MODE
     * ("ok" → MethodReturn with a canned value, "error" → the wired error,
     * "unreachable" → the constructor throws like a dead socket).
     */
    private static final String FAKE_DBUS_INIT =
            "import os\n"
            + "class DBusException(Exception):\n"
            + "    def __init__(self, message='', name=None):\n"
            + "        super().__init__(message)\n"
            + "        self._name = name\n"
            + "    def get_dbus_message(self):\n"
            + "        return str(self)\n"
            + "    def get_dbus_name(self):\n"
            + "        return self._name\n"
            + "class String(str): pass\n"
            + "class ObjectPath(str): pass\n"
            + "class Signature(str): pass\n"
            + "class Boolean(int): pass\n"
            + "class Byte(int): pass\n"
            + "class Int16(int): pass\n"
            + "class Int32(int): pass\n"
            + "class Int64(int): pass\n"
            + "class UInt16(int): pass\n"
            + "class UInt32(int): pass\n"
            + "class UInt64(int): pass\n"
            + "class Double(float): pass\n"
            + "class ByteArray(bytes): pass\n"
            + "class Struct(tuple): pass\n"
            + "class Array(list): pass\n"
            + "from . import lowlevel, mainloop, bus  # noqa: E402,F401\n";

    private static final String FAKE_DBUS_LOWLEVEL =
            "MESSAGE_TYPE_METHOD_CALL = 1\n"
            + "MESSAGE_TYPE_METHOD_RETURN = 2\n"
            + "MESSAGE_TYPE_ERROR = 3\n"
            + "class MethodCallMessage:\n"
            + "    def __init__(self, destination, path, interface, method):\n"
            + "        self.destination = destination\n"
            + "        self.path = path\n"
            + "        self.interface = interface\n"
            + "        self.method = method\n"
            + "        self.auto_start = True\n"
            + "        self.appended = []\n"
            + "    def set_auto_start(self, value):\n"
            + "        self.auto_start = value\n"
            + "    def append(self, *args, signature=None):\n"
            + "        self.appended.append((signature, args))\n"
            + "class _Reply:\n"
            + "    def __init__(self, mtype, sig, args, error_name=None):\n"
            + "        self._t = mtype; self._s = sig; self._a = args; self._e = error_name\n"
            + "    def get_type(self): return self._t\n"
            + "    def get_signature(self): return self._s\n"
            + "    def get_args_list(self): return self._a\n"
            + "    def get_error_name(self): return self._e\n"
            + "def make_reply(mode):\n"
            + "    if mode == 'error':\n"
            + "        return _Reply(MESSAGE_TYPE_ERROR, 's',\n"
            + "                      [__import__('dbus').String('Unit nope.service not found.')],\n"
            + "                      'org.freedesktop.systemd1.NoSuchUnit')\n"
            + "    return _Reply(MESSAGE_TYPE_METHOD_RETURN, 'v',\n"
            + "                  [__import__('dbus').String('systemd 219')])\n";

    private static final String FAKE_DBUS_MAINLOOP =
            "class _Null:\n"
            + "    pass\n"
            + "NULL_MAIN_LOOP = _Null()\n";

    private static final String FAKE_DBUS_BUS =
            "import os\n"
            + "import dbus\n"
            + "from . import lowlevel\n"
            + "class BusConnection:\n"
            + "    def __init__(self, address, mainloop=None):\n"
            + "        if os.environ.get('LW_FAKE_MODE') == 'unreachable':\n"
            + "            raise dbus.DBusException(\n"
            + "                'Failed to connect to socket: No such file or directory')\n"
            + "        self.address = address\n"
            + "    def send_message_with_reply_and_block(self, request, timeout):\n"
            + "        mode = os.environ.get('LW_FAKE_MODE', 'ok')\n"
            + "        if request.method == 'LoadUnit':\n"
            + "            if request.appended and request.appended[0][1][0] == 'ok.service':\n"
            + "                return lowlevel._Reply(lowlevel.MESSAGE_TYPE_METHOD_RETURN,\n"
            + "                    'o', [dbus.ObjectPath(\n"
            + "                        '/org/freedesktop/systemd1/unit/ok_2eservice')])\n"
            + "            return lowlevel.make_reply('error')\n"
            + "        return lowlevel.make_reply('ok')\n"
            + "    def close(self):\n"
            + "        pass\n";

    private void stageFakeDbus(Path stageRoot) throws Exception {
        Path dir = stageRoot.resolve("usr/lib/python3/dist-packages/dbus");
        Files.createDirectories(dir);
        Files.write(dir.resolve("__init__.py"),
                FAKE_DBUS_INIT.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("lowlevel.py"),
                FAKE_DBUS_LOWLEVEL.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("mainloop.py"),
                FAKE_DBUS_MAINLOOP.getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("bus.py"),
                FAKE_DBUS_BUS.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;
        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private Result runShim(String... args) throws Exception {
        return runShimMode("ok", args);
    }

    private Result runShimUnreachable(String... args) throws Exception {
        return runShimMode("unreachable", args);
    }

    private Result runShimMode(String mode, String... args) throws Exception {
        assumeTrue("python3 must be available to execute the shim asset",
                python3Available());
        Path stageRoot = shimStage.getRoot().toPath();
        stageFakeDbus(stageRoot);
        // The real asset bytes, with only the overlay prefix moved — the
        // same re-rooting the guest bind performs.
        String content = new String(Files.readAllBytes(
                assetsDir().resolve("services/lw-busctl")), StandardCharsets.UTF_8);
        assertTrue(content.contains("OVERLAY = \"/opt/lw-dbus\""));
        Path shim = stageRoot.resolve("lw-busctl");
        Files.write(shim, content.replace("/opt/lw-dbus", stageRoot.toString())
                .getBytes(StandardCharsets.UTF_8));

        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(shim.toString());
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("LW_FAKE_MODE", mode);
        builder.environment().put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/nonexistent");
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("lw-busctl timed out");
        }
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return new Result(process.exitValue(), out, err);
    }

    private static boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "-c", "pass")
                    .start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
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

    private void assertLinked(File root, String guestRelative, String target)
            throws Exception {
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
}
