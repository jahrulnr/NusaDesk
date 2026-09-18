package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drains a guest {@code sshd} process's output and turns it into lifecycle
 * signals.
 *
 * <p>Two facts come out of this monitor:</p>
 * <ol>
 *   <li><b>Bind outcome</b> — the first {@link GuestSshdStartupLog} event on the
 *       daemon's stderr. Because it is the daemon's own report, it attributes
 *       the endpoint to the process this host launched; a port that answers
 *       from somewhere else can never be mistaken for our listener.</li>
 *   <li><b>Daemon death</b> — the stderr stream reaching end-of-file means the
 *       daemon process tree is gone (the tracer and the daemon share the pipe,
 *       and the daemon holds it open for as long as it lives). That is the
 *       signal that stops a dead guest from being reported as {@code RUNNING}.</li>
 * </ol>
 *
 * <p>Draining is also required for correctness on its own: an unread pipe fills
 * at ~64 KiB and blocks the writer, which would wedge a chatty daemon.</p>
 *
 * <p>An optional {@code lineSink} receives every drained line (both pipes) on
 * the drain thread; the session's boot log writer hangs off it, so the same
 * console the supervisor shares — setup script, {@code systemctl init}, the
 * daemon's {@code -e} log — is also persisted to the guest's
 * {@code /var/log/lw/boot.log}.</p>
 *
 * <p>Constructible from raw streams so the parsing/waiting behaviour is
 * unit-testable without spawning a process.</p>
 */
public final class GuestSshdStderrMonitor {

    /** Diagnostics keep only the most recent lines; the stream is always drained. */
    private static final int RECENT_LINE_LIMIT = 16;

    private final InputStream stderr;
    private final InputStream stdout;
    private final Runnable onClosed;
    private final Consumer<String> lineSink;
    private final Object lock = new Object();
    private final Deque<String> recent = new ArrayDeque<>();

    private GuestSshdStartupLog.Event bindEvent;
    private boolean closed;

    /**
     * @param stderr   the daemon's stderr (the readiness channel)
     * @param stdout   the daemon's stdout (drained and discarded)
     * @param onClosed invoked once, on the drain thread, after stderr reaches EOF
     */
    public GuestSshdStderrMonitor(InputStream stderr, InputStream stdout, Runnable onClosed) {
        this(stderr, stdout, onClosed, null);
    }

    /**
     * @param stderr   the daemon's stderr (the readiness channel)
     * @param stdout   the daemon's stdout (drained; lines go to the sink)
     * @param onClosed invoked once, on the drain thread, after stderr reaches EOF
     * @param lineSink receives every drained line from both pipes; may be null
     */
    public GuestSshdStderrMonitor(InputStream stderr, InputStream stdout,
                                Runnable onClosed, Consumer<String> lineSink) {
        if (stderr == null || stdout == null) {
            throw new IllegalArgumentException("stderr and stdout are required");
        }
        this.stderr = stderr;
        this.stdout = stdout;
        this.onClosed = onClosed;
        this.lineSink = lineSink;
    }

    /** Monitor a live process's two output pipes. */
    public static GuestSshdStderrMonitor forProcess(Process process, Runnable onClosed) {
        return forProcess(process, onClosed, null);
    }

    /** Monitor a live process's two output pipes, feeding {@code lineSink}. */
    public static GuestSshdStderrMonitor forProcess(Process process, Runnable onClosed,
                                                  Consumer<String> lineSink) {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        return new GuestSshdStderrMonitor(
                process.getErrorStream(), process.getInputStream(), onClosed, lineSink);
    }

    /** Start the drain threads. */
    public void start() {
        daemonThread(this::drainStderr, "guest-sshd-stderr").start();
        daemonThread(this::drainStdout, "guest-sshd-stdout").start();
    }

    /**
     * Wait for the daemon to report either a bound listener or a bind failure.
     *
     * @return the first non-ignored event, or {@code null} on timeout or when
     *         the daemon exited before reporting anything
     */
    public GuestSshdStartupLog.Event awaitBindEvent(long timeoutMillis) {
        long deadline = System.nanoTime() + Math.max(timeoutMillis, 0L) * 1_000_000L;
        synchronized (lock) {
            while (bindEvent == null && !closed) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    lock.wait(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return bindEvent;
        }
    }

    /** Whether the daemon's output has reached end-of-file. */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Most recent daemon output lines, newest last. Diagnostic text only. */
    public List<String> recentLines() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(recent));
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                GuestSshdStartupLog.Event event = GuestSshdStartupLog.parse(line);
                synchronized (lock) {
                    recent.addLast(line);
                    while (recent.size() > RECENT_LINE_LIMIT) {
                        recent.removeFirst();
                    }
                    if (bindEvent == null
                            && event.getKind() != GuestSshdStartupLog.Kind.IGNORED) {
                        bindEvent = event;
                    }
                    lock.notifyAll();
                }
                if (lineSink != null) {
                    lineSink.accept(line);
                }
            }
        } catch (IOException ignored) {
            // A closed pipe is the normal teardown path; EOF is reported below.
        } finally {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
            if (onClosed != null) {
                onClosed.run();
            }
        }
    }

    private void drainStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Guest sshd logs to stderr; stdout lines are the session
                // supervisor's / systemctl's — they still belong in boot.log.
                if (lineSink != null) {
                    lineSink.accept(line);
                }
            }
        } catch (IOException ignored) {
            // Teardown closes the pipe; nothing to report.
        }
    }

    private static Thread daemonThread(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }
}
