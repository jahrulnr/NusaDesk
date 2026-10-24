package gh.nusashell.nusadesk.infrastructure.display;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Removes what a killed display leaves behind in the still-running guest.
 * A private {@code dbus-run-session} bus survives when its parent is killed
 * before the helper's own cleanup runs, and a SIGKILLed X server does not
 * unlink its socket. The guest's long-lived service bus is a different process.
 */
final class GuestDisplayCleanup {
    private GuestDisplayCleanup() { }

    /** Match exact daemon arguments against addresses recorded by this display owner. */
    static List<Long> privateSessionBusPids(Path procRoot, Set<String> ownedAddresses) throws IOException {
        List<Long> pids = new ArrayList<>();
        if (ownedAddresses.isEmpty() || procRoot == null || !Files.isDirectory(procRoot, LinkOption.NOFOLLOW_LINKS)) return pids;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(procRoot)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!isPid(name)) continue;
                String command = commandLine(entry.resolve("cmdline"));
                if (isPrivateSessionBus(command, ownedAddresses)) pids.add(Long.parseLong(name));
            }
        }
        return pids;
    }

    static boolean isPrivateSessionBus(String command, Set<String> ownedAddresses) {
        if (command == null || ownedAddresses.isEmpty()) return false;
        String[] args = command.split("\u0000");
        if (args.length == 0 || !(args[0].equals("dbus-daemon") || args[0].endsWith("/dbus-daemon"))) return false;
        for (String arg : args) {
            if (arg.startsWith("--address=") && ownedAddresses.contains(arg.substring("--address=".length()))) return true;
        }
        return false;
    }

    /**
     * Remove the display :1 socket and lock. A symlinked tmp or socket directory
     * is left untouched so cleanup cannot be redirected.
     */
    static void unlinkServerSocket(Path rootfs) throws IOException {
        if (rootfs == null) return;
        Path tmp = rootfs.resolve("tmp");
        // Check-then-delete is non-atomic by design: a raced-in symlink only
        // redirects a stale-artifact delete inside guest tmp, never outside it.
        if (Files.isSymbolicLink(tmp) || !Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS)) return;
        Path directory = tmp.resolve(".X11-unix");
        if (Files.isSymbolicLink(directory)) return;
        Files.deleteIfExists(tmp.resolve(".X1-lock"));
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(directory.resolve("X1"));
        }
    }

    private static boolean isPid(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    private static String commandLine(Path cmdline) {
        try {
            return new String(Files.readAllBytes(cmdline), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }
}
