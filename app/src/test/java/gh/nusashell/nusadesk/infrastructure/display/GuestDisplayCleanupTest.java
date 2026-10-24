package gh.nusashell.nusadesk.infrastructure.display;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GuestDisplayCleanupTest {
    @Test public void reapingAnOwnedLiveBusLeavesAnotherLiveBusAndItsSocketUsable() throws Exception {
        Path root = Files.createTempDirectory("display-bus-isolation-");
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        String ownedAddress = "unix:abstract=nusadesk-display-" + java.util.UUID.randomUUID();
        String otherAddress = "unix:path=" + tmp.resolve("dbus-terminal");
        Process owned = new ProcessBuilder("/usr/bin/dbus-daemon", "--session", "--nofork", "--print-address=1", "--print-pid=1", "--address=" + ownedAddress).start();
        Process other = new ProcessBuilder("/usr/bin/dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=" + otherAddress).start();
        try {
            java.io.BufferedReader ownedOutput = new java.io.BufferedReader(new java.io.InputStreamReader(owned.getInputStream()));
            assertTrue(ownedOutput.readLine().startsWith(ownedAddress));
            long ownedPid = Long.parseLong(ownedOutput.readLine());
            assertTrue(new java.io.BufferedReader(new java.io.InputStreamReader(other.getInputStream())).readLine().startsWith(otherAddress));
            List<Long> selected = GuestDisplayCleanup.privateSessionBusPids(Path.of("/proc"), Set.of(ownedAddress));
            assertEquals(List.of(ownedPid), selected);
            owned.destroyForcibly();
            assertTrue(owned.waitFor(3, TimeUnit.SECONDS));
            GuestDisplayCleanup.unlinkServerSocket(root);
            assertTrue(other.isAlive());
            assertTrue(Files.exists(tmp.resolve("dbus-terminal")));
            Process ping = new ProcessBuilder("/usr/bin/dbus-send", "--bus=" + otherAddress, "--print-reply",
                    "--dest=org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.ListNames").start();
            assertTrue(ping.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, ping.exitValue());
        } finally {
            owned.destroyForcibly(); other.destroyForcibly();
            owned.waitFor(3, TimeUnit.SECONDS); other.waitFor(3, TimeUnit.SECONDS);
        }
    }
    @Test public void selectsOnlyRecordedDisplayBusesAndPreservesOtherPrivateSessions() throws Exception {
        Path proc = Files.createTempDirectory("nusadesk-proc-");
        String owned = "unix:abstract=nusadesk-display-123";
        command(proc, "40", "dbus-daemon\0--nofork\0--print-address\0--address=" + owned + "\0");
        command(proc, "41", "/opt/lw-dbus/usr/bin/dbus-daemon\0--address=unix:path=/run/user/0/bus");
        command(proc, "42", "python3\0-m\0http.server");
        command(proc, "43", "dbus-daemon\0--nofork\0--print-address\0--session");
        command(proc, "44", "dbus-daemon\0--address=" + owned + "-other\0");
        command(proc, "45", "sh\0-c\0dbus-daemon --address=" + owned + "\0");
        Files.createDirectory(proc.resolve("not-a-pid"));

        List<Long> pids = GuestDisplayCleanup.privateSessionBusPids(proc, Set.of(owned));

        assertEquals(List.of(40L), pids);
        assertTrue(GuestDisplayCleanup.privateSessionBusPids(proc, Set.of()).isEmpty());
    }

    @Test public void removesTheDisplaySocketAndLeavesTheRestOfTmp() throws Exception {
        Path root = Files.createTempDirectory("nusadesk-rootfs-");
        Path tmp = root.resolve("tmp");
        Files.createDirectory(tmp);
        Files.createDirectory(tmp.resolve(".X11-unix"));
        Files.writeString(tmp.resolve(".X1-lock"), "123\n");
        Files.writeString(tmp.resolve(".X11-unix").resolve("X1"), "socket");
        Files.writeString(tmp.resolve("keep-me"), "stay");

        GuestDisplayCleanup.unlinkServerSocket(root);

        assertFalse(Files.exists(tmp.resolve(".X1-lock")));
        assertFalse(Files.exists(tmp.resolve(".X11-unix").resolve("X1")));
        assertEquals("stay", Files.readString(tmp.resolve("keep-me")));
    }

    @Test public void displaySocketCleanupPreservesUnrelatedPrivateSessionSockets() throws Exception {
        Path root = Files.createTempDirectory("nusadesk-rootfs-");
        Path tmp = root.resolve("tmp");
        Files.createDirectory(tmp);
        Files.writeString(tmp.resolve("dbus-DEADSOCK"), "gone");
        Files.createDirectory(tmp.resolve("dbus-keep-dir"));
        Files.writeString(tmp.resolve("keep-me"), "stay");

        GuestDisplayCleanup.unlinkServerSocket(root);

        assertTrue(Files.exists(tmp.resolve("dbus-DEADSOCK")));
        assertTrue(Files.isDirectory(tmp.resolve("dbus-keep-dir")));
        assertEquals("stay", Files.readString(tmp.resolve("keep-me")));
    }

    @Test public void symlinkTmpIsNotFollowed() throws Exception {
        Path root = Files.createTempDirectory("nusadesk-rootfs-");
        Path outside = Files.createTempDirectory("nusadesk-outside-");
        Files.writeString(outside.resolve(".X1-lock"), "nope");
        Files.writeString(outside.resolve("dbus-OUTSIDE"), "stay");
        Files.createSymbolicLink(root.resolve("tmp"), outside);

        GuestDisplayCleanup.unlinkServerSocket(root);

        assertEquals("nope", Files.readString(outside.resolve(".X1-lock")));
        assertEquals("stay", Files.readString(outside.resolve("dbus-OUTSIDE")));
    }

    private static void command(Path proc, String pid, String nulSeparated) throws Exception {
        Path dir = proc.resolve(pid);
        Files.createDirectory(dir);
        Files.write(dir.resolve("cmdline"), nulSeparated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
