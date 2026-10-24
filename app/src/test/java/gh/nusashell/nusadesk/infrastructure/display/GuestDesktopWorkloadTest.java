package gh.nusashell.nusadesk.infrastructure.display;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class GuestDesktopWorkloadTest {
    private static final String BUS = "unix:abstract=nusadesk-display-00000000-0000-0000-0000-000000000001";
    @Test public void probeFailureNeverStartsTheWindowManager() throws Exception {
        Path fixture = fixture();
        program(fixture, "xdpyinfo", "exit 23");
        program(fixture, "openbox", "touch '" + fixture.resolve("app-started") + "'");
        Process process = start(fixture);
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(23, process.exitValue());
        assertFalse(output.contains(GuestDesktopWorkload.READY_MARKER));
        assertFalse(Files.exists(fixture.resolve("app-started")));
    }

    @Test public void windowManagerReadinessIsProbedAndItsChannelOwnsCleanup() throws Exception {
        Path fixture = fixture();
        program(fixture, "xdpyinfo", "exit 0");
        program(fixture, "openbox", "echo $$ > '" + fixture.resolve("wm-pid") + "'; exec /bin/sleep 30");
        program(fixture, "xprop", "echo '_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x123'");
        Process process = start(fixture);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (process.getInputStream().available() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
        String output = new String(process.getInputStream().readNBytes(process.getInputStream().available()), StandardCharsets.UTF_8);
        assertTrue(output, output.contains(GuestDesktopWorkload.WM_READY_MARKER));
        long wm = Long.parseLong(Files.readString(fixture.resolve("wm-pid")).trim());
        process.destroy();
        assertTrue(process.waitFor(4, TimeUnit.SECONDS));
        assertFalse("window manager must not outlive its display channel", Files.exists(java.nio.file.Paths.get("/proc", Long.toString(wm))));
    }

    @Test public void commandIsQuotedForTheExistingGuestExecChannel() throws Exception {
        String command = GuestDesktopWorkload.command("org.xfce.mousepad.desktop", BUS);
        assertTrue("Each supervised app owns an addressed private bus", command.contains("exec dbus-run-session --dbus-daemon=/opt/lw-desktop/session-bus.sh -- python3"));
        assertTrue(command.contains(BUS));
        Process process = new ProcessBuilder("/bin/sh", "-n", "-c", command).start();
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    @Test public void scannedIdIsOneArgumentEvenWithShellMetacharacters() throws Exception {
        Path fixture = fixture();
        program(fixture, "xdpyinfo", "exit 0");
        program(fixture, "openbox", "exec /bin/sleep 30");
        program(fixture, "python3", "printf '%s\\n' \"$@\" > '" + fixture.resolve("arguments") + "'");
        program(fixture, "dbus-run-session", "shift; shift; exec \"$@\"");
        String id = "an app'; touch SHOULD_NOT_EXIST; #.desktop";
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", GuestDesktopWorkload.command(id, BUS));
        builder.environment().put("PATH", fixture + ":/usr/bin:/bin");
        builder.directory(fixture.toFile());
        Process process = builder.start();
        assertTrue(process.waitFor(4, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals(java.util.Arrays.asList(GuestDesktopCatalog.SCRIPT, "launch", id), Files.readAllLines(fixture.resolve("arguments")));
        assertFalse(Files.exists(fixture.resolve("SHOULD_NOT_EXIST")));
    }

    private static Path fixture() throws Exception { return Files.createTempDirectory("nusadesk-gui-test-"); }
    private static void program(Path fixture, String name, String body) throws Exception {
        Path file = fixture.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
    }
    private static Process start(Path fixture) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", GuestDesktopWorkload.WM_SCRIPT);
        builder.environment().put("PATH", fixture + ":/usr/bin:/bin");
        builder.redirectErrorStream(true);
        return builder.start();
    }
}
