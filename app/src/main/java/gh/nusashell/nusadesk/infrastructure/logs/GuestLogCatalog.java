package gh.nusashell.nusadesk.infrastructure.logs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lists the guest log sources the Logs surface can open.
 *
 * <p>The scan mirrors what a real Linux box offers: a <b>boot</b> section (the
 * session console stream {@code /var/log/lw/boot.log} plus its one rotated
 * predecessor) and a <b>system</b> section (each service's own log — the
 * per-unit journals the vendored {@code systemctl3} writes under
 * {@code /var/log/journal} for system units and
 * {@code /root/.config/log/journal} for {@code --user} units, and the compose
 * supervisor's logs under {@code /root/.config/nusadesk/compose}).</p>
 *
 * <p>Only regular files are listed: rotated {@code *.1} generations are
 * excluded except the documented previous-boot file, symlinks are never
 * followed, and a candidate that resolves outside the rootfs is rejected — the
 * rootfs is user-writable through the terminal, so a planted symlink must not
 * turn the Logs surface into a file reader. Plain JVM, temp-dir testable.</p>
 */
public final class GuestLogCatalog {

    /** Rootfs-relative compose state dir (mirrors lw_compose_runtime's layout). */
    private static final String COMPOSE_STATE_DIR = "root/.config/nusadesk/compose";
    private static final String COMPOSE_SUPERVISOR_LOG = "supervisor.log";
    private static final String COMPOSE_LOGS_DIR = "logs";

    private GuestLogCatalog() {
    }

    /**
     * Scans the active rootfs for openable log files.
     *
     * @param rootfs active rootfs dir ({@code ProotPaths.activeRootfsPath});
     *               a missing dir simply yields an empty list
     * @return boot items first (current boot, then previous boot), then system
     *         items sorted by title
     */
    public static List<GuestLog> list(Path rootfs) {
        List<GuestLog> items = new ArrayList<>();
        if (rootfs == null || !Files.isDirectory(rootfs)) {
            return Collections.unmodifiableList(items);
        }
        addBootItems(rootfs, items);
        List<GuestLog> system = new ArrayList<>();
        addJournalItems(rootfs, system);
        addComposeItems(rootfs, system);
        system.sort(Comparator.comparing(GuestLog::getTitle)
                .thenComparing(GuestLog::getGuestPath));
        items.addAll(system);
        return Collections.unmodifiableList(items);
    }

    /**
     * Boot section: the current session log first, then the previous boot.
     * Only files that exist are listed — a device that never ran a session
     * shows no boot items at all.
     */
    private static void addBootItems(Path rootfs, List<GuestLog> items) {
        Path logDir = rootfs.resolve(SessionLogWriter.LOG_DIR);
        Path current = logDir.resolve(SessionLogWriter.BOOT_LOG_NAME);
        if (isSafeFile(rootfs, current)) {
            items.add(new GuestLog("/" + SessionLogWriter.LOG_DIR + "/"
                    + SessionLogWriter.BOOT_LOG_NAME,
                    "This boot", "/" + SessionLogWriter.LOG_DIR + "/"
                    + SessionLogWriter.BOOT_LOG_NAME,
                    current, GuestLog.Section.BOOT, ""));
        }
        Path previous = logDir.resolve(SessionLogWriter.PREVIOUS_BOOT_LOG_NAME);
        if (isSafeFile(rootfs, previous)) {
            items.add(new GuestLog("/" + SessionLogWriter.LOG_DIR + "/"
                    + SessionLogWriter.PREVIOUS_BOOT_LOG_NAME,
                    "Previous boot", "/" + SessionLogWriter.LOG_DIR + "/"
                    + SessionLogWriter.PREVIOUS_BOOT_LOG_NAME,
                    previous, GuestLog.Section.BOOT, ""));
        }
    }

    /**
     * System section, part one: per-unit journals. System units live under
     * {@code /var/log/journal}; {@code --user} units under
     * {@code /root/.config/log/journal} and carry the {@code user} qualifier so
     * a same-named user unit can never be mistaken for the system one.
     */
    private static void addJournalItems(Path rootfs, List<GuestLog> items) {
        for (String journalDir : SessionLogWriter.JOURNAL_DIRS) {
            boolean userMode = journalDir.startsWith("root/");
            for (Path file : listLogFiles(rootfs, rootfs.resolve(journalDir))) {
                String name = file.getFileName().toString();
                String guestPath = "/" + journalDir + "/" + name;
                items.add(new GuestLog(guestPath, baseName(name), guestPath, file,
                        GuestLog.Section.SYSTEM, userMode ? "user" : ""));
            }
        }
    }

    /**
     * System section, part two: the compose supervisor's own log and each
     * project service log under {@code <project>/logs/}. These already
     * self-rotate inside the guest; the surface only reads them.
     */
    private static void addComposeItems(Path rootfs, List<GuestLog> items) {
        Path stateDir = rootfs.resolve(COMPOSE_STATE_DIR);
        Path supervisor = stateDir.resolve(COMPOSE_SUPERVISOR_LOG);
        if (isSafeFile(rootfs, supervisor)) {
            String guestPath = "/" + COMPOSE_STATE_DIR + "/" + COMPOSE_SUPERVISOR_LOG;
            items.add(new GuestLog(guestPath, "Compose supervisor", guestPath,
                    supervisor, GuestLog.Section.SYSTEM, "compose"));
        }
        if (!Files.isDirectory(stateDir)) {
            return;
        }
        try (DirectoryStream<Path> projects = Files.newDirectoryStream(stateDir)) {
            for (Path project : projects) {
                if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(project)) {
                    continue;
                }
                Path logsDir = project.resolve(COMPOSE_LOGS_DIR);
                for (Path file : listLogFiles(rootfs, logsDir)) {
                    String projectName = project.getFileName().toString();
                    String svcName = file.getFileName().toString();
                    String guestPath = "/" + COMPOSE_STATE_DIR + "/" + projectName
                            + "/" + COMPOSE_LOGS_DIR + "/" + svcName;
                    items.add(new GuestLog(guestPath,
                            projectName + "/" + baseName(svcName), guestPath, file,
                            GuestLog.Section.SYSTEM, "compose"));
                }
            }
        } catch (IOException ignored) {
            // A half-written compose state dir just yields fewer items.
        }
    }

    /**
     * Safe {@code *.log} files in one directory: regular, non-symlink, and
     * resolving under {@code rootfs} ({@link #isSafeFile}). Never throws.
     */
    private static List<Path> listLogFiles(Path rootfs, Path dir) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.log")) {
            for (Path entry : entries) {
                if (isSafeFile(rootfs, entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException ignored) {
            // Unreadable dirs contribute no items.
        }
        return files;
    }

    /**
     * {@code file} must be an existing regular file that is not a symlink and
     * whose real path stays under {@code rootfs}. The terminal lets the user
     * plant anything inside the rootfs, so this is the boundary that keeps the
     * Logs surface from being turned into a generic file reader.
     */
    private static boolean isSafeFile(Path rootfs, Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                return false;
            }
            Path real = file.toRealPath();
            Path realRoot = rootfs.toRealPath();
            return real.startsWith(realRoot);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** {@code name.service.log} → {@code name.service}; keeps other names as-is. */
    private static String baseName(String fileName) {
        return fileName.endsWith(".log")
                ? fileName.substring(0, fileName.length() - ".log".length())
                : fileName;
    }
}
