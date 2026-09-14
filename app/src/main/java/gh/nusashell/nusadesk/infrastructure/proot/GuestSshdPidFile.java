package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and validates the guest {@code sshd}'s persisted pid file.
 *
 * <p>Guest {@code sshd} writes its own pid (the real host pid — the packaged
 * PRoot build does not virtualise PIDs) into the daemon's config directory when
 * it starts. That file is the host's only reliable handle on the daemon: the
 * PRoot tracer is a <em>separate</em> process, and killing the tracer does not
 * kill its tracee (verified on-device: SIGTERM and SIGKILL to the tracer both
 * leave the guest daemon listening), so a stop that only tears down the tracer
 * orphans the daemon and leaks the port.</p>
 *
 * <p>The pid is only trusted together with the process's own command line: a
 * stale pid file whose number was reused by an unrelated process must never be
 * signalled. {@link #matchesDaemon} requires the command line to name this
 * daemon's guest binary, which the process reports even after OpenSSH rewrites
 * its title ({@code sshd: /opt/lw-ssh/usr/sbin/sshd -D ...}).</p>
 *
 * <p>Pure JVM file/string logic; unit-testable with temp files.</p>
 */
public final class GuestSshdPidFile {

    private GuestSshdPidFile() {
    }

    /**
     * Read the pid recorded in {@code pidFile}.
     *
     * @return the pid, or {@code null} when the file is absent, unreadable,
     *         blank, non-numeric, or outside the valid pid range
     */
    public static Long read(Path pidFile) {
        if (pidFile == null || !Files.isRegularFile(pidFile)) {
            return null;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(pidFile), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return null;
        }
        if (text.isEmpty() || text.length() > 20) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return null;
            }
        }
        try {
            long pid = Long.parseLong(text);
            return pid > 0 && pid <= Integer.MAX_VALUE ? pid : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Convert a raw {@code /proc/<pid>/cmdline} buffer to readable text. */
    public static String commandLineText(byte[] rawCmdline) {
        if (rawCmdline == null || rawCmdline.length == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(rawCmdline.length);
        for (byte b : rawCmdline) {
            text.append(b == 0 ? ' ' : (char) (b & 0xFF));
        }
        return text.toString().trim();
    }

    /**
     * Whether a process's command line belongs to this daemon. OpenSSH rewrites
     * its process title, so the check is a containment test on the guest binary
     * path rather than an argv[0] equality.
     */
    public static boolean matchesDaemon(String commandLineText, String daemonBinaryPath) {
        if (commandLineText == null || daemonBinaryPath == null || daemonBinaryPath.isEmpty()) {
            return false;
        }
        return commandLineText.contains(daemonBinaryPath);
    }
}
