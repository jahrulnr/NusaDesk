package gh.nusashell.nusadesk.infrastructure.logs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-side {@code tail -n N -F} over a guest log file, driving the terminal
 * surface. This is what makes the Logs surface a <i>live</i> view: the host
 * reads the file directly (the rootfs is an app-private dir), so no SSH
 * session, no guest-side process, and no extra daemon is needed — exactly the
 * data {@code tail -F /var/log/journal/sshd.service.log} would show, streamed
 * into xterm.js.
 *
 * <p>Lifecycle: {@link #follow(Path, Handler, int, int)} spawns one daemon
 * thread per tail; {@link TailHandle#stop()} closes the file and cancels the
 * thread. The follow loop is size-based polling ({@link #POLL_MS}) — cheaper
 * and more reliable under proot than inotify — and it notices the three ways
 * the file can change underneath it:</p>
 *
 * <ul>
 *   <li><b>append</b> — reads forward from the last offset</li>
 *   <li><b>truncate/rotate-in-place</b> ({@code size < offset}) — restarts
 *       from {@code max(0, size - initialBytes)} so the kept tail is shown</li>
 *   <li><b>rename-replace</b> ({@code !isSameFile}) — reopens the path so the
 *       view follows the <i>name</i>, like {@code tail -F}</li>
 * </ul>
 *
 * <p>Handler callbacks arrive on the tail's single thread, never concurrently.
 * Chunks are capped by {@link #READ_CHUNK_BYTES}; the initial tail keeps the
 * newest {@code initialBytes} aligned to a line boundary so the view opens at
 * the bottom of the file, like {@code journalctl -f}.</p>
 */
public final class GuestLogTail {

    /** Default initial backfill: newest 64 KiB of the file. */
    public static final int DEFAULT_INITIAL_BYTES = 64 * 1024;
    /** Follow poll cadence; small enough to feel live, cheap enough to leave on. */
    public static final int POLL_MS = 300;
    /** Max bytes handed to the handler per callback. */
    public static final int READ_CHUNK_BYTES = 16 * 1024;
    /** How long {@link #stop()} waits for the poll thread to exit. */
    private static final long JOIN_MS = 800;

    private GuestLogTail() {
    }

    /** Receives decoded UTF-8 chunks; called on the tail's single thread. */
    public interface Handler {
        void onOutput(String text);
    }

    /** Stop handle for one running follow. */
    public interface TailHandle {
        void stop();
    }

    /**
     * Starts following {@code file}: emits the newest {@code initialBytes}
     * (line-aligned), then polls for appends until stopped.
     *
     * @param file         absolute host path (catalog-resolved)
     * @param handler      output sink; invoked on the tail thread
     * @param initialBytes backfill cap; ≤0 means no backfill
     * @param pollMs       poll cadence; ≤0 uses {@link #POLL_MS}
     */
    public static TailHandle follow(Path file, Handler handler,
                                    int initialBytes, int pollMs) {
        if (file == null || handler == null) {
            throw new IllegalArgumentException("file and handler must not be null");
        }
        Worker worker = new Worker(file, handler, initialBytes,
                pollMs > 0 ? pollMs : POLL_MS);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "guest-log-tail");
            t.setDaemon(true);
            return t;
        });
        worker.attach(exec);
        exec.scheduleWithFixedDelay(worker, 0, worker.pollMs, TimeUnit.MILLISECONDS);
        return new Handle(worker, exec);
    }

    private static final class Handle implements TailHandle {
        private final Worker worker;
        private final ScheduledExecutorService exec;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private Handle(Worker worker, ScheduledExecutorService exec) {
            this.worker = worker;
            this.exec = exec;
        }

        @Override
        public void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            worker.stop();
            exec.shutdownNow();
            try {
                exec.awaitTermination(JOIN_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Worker implements Runnable {
        private final Path file;
        private final Handler handler;
        private final int initialBytes;
        private final int pollMs;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile ScheduledExecutorService exec;
        private RandomAccessFile raf;
        private Object fileKey;
        private long offset;

        private Worker(Path file, Handler handler, int initialBytes, int pollMs) {
            this.file = file;
            this.handler = handler;
            this.initialBytes = Math.max(0, initialBytes);
            this.pollMs = pollMs;
        }

        private void attach(ScheduledExecutorService exec) {
            this.exec = exec;
        }

        private void stop() {
            running.set(false);
            closeQuietly();
        }

        @Override
        public void run() {
            if (!running.get()) {
                return;
            }
            try {
                poll();
            } catch (IOException | RuntimeException e) {
                // A file that vanished or broke mid-read is a normal guest
                // event; stop this tail quietly rather than kill the surface.
                running.set(false);
                closeQuietly();
            } finally {
                if (!running.get() && exec != null) {
                    exec.shutdown();
                }
            }
        }

        private void poll() throws IOException {
            if (raf == null) {
                // Not there yet — guest may create it later; retry opens it.
                openAtTail();
                return;
            }
            if (!isSameOpenFile()) {
                // Replaced by rename — follow the name, not the inode.
                openAtTail();
                return;
            }
            long size = raf.length();
            if (size < offset) {
                // Rotated in place (trim rewrote the file) — jump to the kept tail.
                offset = Math.max(0L, size - initialBytes);
                raf.seek(offset);
                skipPartialLine(raf, offset, size);
                offset = raf.getFilePointer();
            }
            drain();
        }

        /** Opens the file (if present) positioned so the newest tail is emitted. */
        private void openAtTail() throws IOException {
            if (!Files.isRegularFile(file)) {
                closeQuietly();
                raf = null;
                return;
            }
            RandomAccessFile fresh = new RandomAccessFile(file.toFile(), "r");
            long size = fresh.length();
            long start = Math.max(0L, size - initialBytes);
            fresh.seek(start);
            skipPartialLine(fresh, start, size);
            offset = fresh.getFilePointer();
            closeQuietly();
            raf = fresh;
            fileKey = safeFileKey();
            drain();
        }

        /**
         * When positioned mid-file (start &gt; 0) drop the partial first line so
         * the backfill begins on a line boundary.
         */
        private void skipPartialLine(RandomAccessFile r, long start, long size)
                throws IOException {
            if (start <= 0 || start >= size) {
                return;
            }
            int b;
            while ((b = r.read()) != -1) {
                if (b == '\n') {
                    return;
                }
            }
        }

        /** Reads and emits everything from {@code offset} to EOF, in chunks. */
        private void drain() throws IOException {
            if (raf == null) {
                return;
            }
            byte[] buf = new byte[READ_CHUNK_BYTES];
            long size = raf.length();
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            while (offset < size) {
                int want = (int) Math.min(buf.length, size - offset);
                int got = raf.read(buf, 0, want);
                if (got < 0) {
                    break;
                }
                offset += got;
                pending.write(buf, 0, got);
            }
            if (pending.size() > 0) {
                handler.onOutput(pending.toString("UTF-8"));
            }
        }

        /**
         * Compares the path's current identity with the opened inode's so a
         * rename-rotate is detected even when the new file has the same size.
         */
        private boolean isSameOpenFile() {
            try {
                if (!Files.isRegularFile(file)) {
                    return true; // gone → poll() handles via openAtTail later
                }
                Object current = Files.readAttributes(file, "unix:fileKey")
                        .get("fileKey");
                return fileKey == null || fileKey.equals(current);
            } catch (IOException | UnsupportedOperationException
                     | IllegalArgumentException e) {
                // FS without unix fileKey support: same-file check can't run.
                return true;
            }
        }

        private Object safeFileKey() {
            try {
                return Files.readAttributes(file, "unix:fileKey").get("fileKey");
            } catch (IOException | UnsupportedOperationException
                     | IllegalArgumentException e) {
                return null;
            }
        }

        private void closeQuietly() {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
