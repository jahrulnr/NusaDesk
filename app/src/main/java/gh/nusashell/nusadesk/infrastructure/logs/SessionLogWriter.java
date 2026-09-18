package gh.nusashell.nusadesk.infrastructure.logs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/**
 * Persists the guest session console stream to {@code /var/log/lw/boot.log}
 * inside the active rootfs.
 *
 * <p>The session's whole console output — the setup script, the vendored
 * {@code systemctl init}, and the sshd daemon's {@code -e} log — reaches the
 * host through the supervised process pipes. The supervisor
 * ({@code GuestSshdWorkload}) feeds each drained line to {@link #append(String)},
 * which prefixes a syslog-style timestamp and writes it into the rootfs so the
 * file is readable both host-side (Logs surface) and guest-side
 * ({@code cat /var/log/lw/boot.log}).</p>
 *
 * <p>Bounds: {@link #rotateAtSessionStart(Path)} runs once per session start
 * and renames {@code boot.log} to {@code boot.log.1} (one previous boot, like
 * {@code journalctl -b -1}); the same rotation covers the per-unit journal
 * files. While open, the writer self-trims with {@link GuestLogTrim} whenever
 * the file grows past {@link #MAX_BYTES}, keeping the newest
 * {@link #KEEP_BYTES}. All of it is plain JVM file I/O — unit-testable with
 * temp directories.</p>
 */
public final class SessionLogWriter {

    /** Rootfs-relative directory holding the product-owned session logs. */
    public static final String LOG_DIR = "var/log/lw";
    /** Name of the current boot's session log. */
    public static final String BOOT_LOG_NAME = "boot.log";
    /** Rotated name of the previous boot's session log. */
    public static final String PREVIOUS_BOOT_LOG_NAME = "boot.log.1";
    /** Rootfs-relative journal dirs the vendored systemctl3 appends unit logs to. */
    public static final String[] JOURNAL_DIRS = {
            "var/log/journal",
            "root/.config/log/journal"
    };
    /** Size at which the open session log is tail-trimmed. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    /** Tail bytes kept when the session log crosses {@link #MAX_BYTES}. */
    public static final long KEEP_BYTES = 1024 * 1024;

    /** Syslog-style stamp, like a real /var/log line: "Sep 17 21:49:05".
     * Locale.US matters: ICU renders abbreviated MMM under ROOT as "M09". */
    private static final DateTimeFormatter LINE_TIME =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", java.util.Locale.US);

    private final Path logFile;
    private final LongSupplier clock;
    private final BufferedWriter writer;
    private boolean closed;
    private long bytesSinceTrim;

    /**
     * Opens {@code <rootfs>/var/log/lw/boot.log} for appending, creating the
     * directory when needed. Rotation is the caller's job
     * ({@link #rotateAtSessionStart(Path)}), so an open writer never sees the
     * file renamed under it.
     */
    public SessionLogWriter(Path rootfs, LongSupplier clock) throws IOException {
        if (rootfs == null) {
            throw new IllegalArgumentException("rootfs must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
        Path dir = rootfs.resolve(LOG_DIR);
        Files.createDirectories(dir);
        this.logFile = dir.resolve(BOOT_LOG_NAME);
        this.writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(logFile, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND),
                StandardCharsets.UTF_8));
        this.bytesSinceTrim = Files.exists(logFile) ? Files.size(logFile) : 0L;
    }

    /** The file this writer appends to ({@code <rootfs>/var/log/lw/boot.log}). */
    public Path getLogFile() {
        return logFile;
    }

    /**
     * One-session-start log rotation, mirroring what a real init does between
     * boots: the current {@code boot.log} becomes {@code boot.log.1} (the
     * previous boot, one generation only), and every {@code *.log} in the two
     * journal directories rotates to {@code *.log.1} the same way. It must run
     * before the session's writers open their files — the workload calls it
     * before the guest processes launch, when no file is held open.
     *
     * @return number of files rotated; errors on individual files are skipped
     *         (rotation is hygiene, never a reason to block a session start)
     */
    public static int rotateAtSessionStart(Path rootfs) {
        if (rootfs == null || !Files.isDirectory(rootfs)) {
            return 0;
        }
        int rotated = rotateFile(rootfs.resolve(LOG_DIR).resolve(BOOT_LOG_NAME));
        for (String journalDir : JOURNAL_DIRS) {
            rotated += rotateDir(rootfs.resolve(journalDir));
        }
        return rotated;
    }

    private static int rotateDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int rotated = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.log")) {
            for (Path entry : entries) {
                rotated += rotateFile(entry);
            }
        } catch (IOException ignored) {
            // A journal dir that cannot be listed simply does not rotate.
        }
        return rotated;
    }

    private static int rotateFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            Path previous = file.resolveSibling(file.getFileName() + ".1");
            Files.deleteIfExists(previous);
            Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
            return 1;
        } catch (IOException | RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * Appends one drained console line with a syslog-style timestamp prefix.
     * Safe to call from the monitor's two drain threads; lines from stdout and
     * stderr interleave in arrival order. Calls after {@link #close()} are
     * dropped silently so a late drain during teardown cannot fail.
     */
    public synchronized void append(String line) {
        if (closed || line == null) {
            return;
        }
        String timestamped = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(clock.getAsLong()), ZoneId.systemDefault())
                .format(LINE_TIME) + " " + line;
        try {
            writer.write(timestamped);
            writer.newLine();
            writer.flush();
            bytesSinceTrim += timestamped.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytesSinceTrim > MAX_BYTES) {
                GuestLogTrim.shrinkToTail(logFile, KEEP_BYTES);
                bytesSinceTrim = Files.size(logFile);
            }
        } catch (IOException ignored) {
            // Logging must never fail the session it describes.
        }
    }

    /** Flushes and closes the log. Idempotent; appends afterwards are dropped. */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException ignored) {
            // Best-effort close; nothing else can write anyway.
        }
    }
}
