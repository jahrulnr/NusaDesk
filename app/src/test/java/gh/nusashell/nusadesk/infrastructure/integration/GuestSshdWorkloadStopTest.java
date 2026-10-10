package gh.nusashell.nusadesk.infrastructure.integration;

import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.infrastructure.service.WorkloadListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The stop path's terminal-report rules (ADR-0062): a workload process that
 * survives the forced-stop window must never let the session claim a stop,
 * and a healthy process reports a normal stop exactly as before.
 */
public class GuestSshdWorkloadStopTest {

    /**
     * A path that matches no real process: the thaw scans the host's own
     * child list, so a bogus path guarantees no signal can be delivered
     * while the terminate/verify logic is exercised.
     */
    private static final String PROOT_PATH = "/nonexistent/libproot.so";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * The wedge device's discovery path, exercised on a procfs-shaped
     * fixture: the verified wedge kernel (S10e, Samsung 4.14.113) has task
     * dirs but no usable {@code /proc/self/task/<tid>/children} file (that
     * file needs {@code CONFIG_PROC_CHILDREN}), so the fixture's task dir
     * is empty and any pid found can only have come from the
     * {@code /proc/<pid>/stat} PPid scan.
     */
    @Test
    public void tracerPidsFindsTracerChildByParentPidScan() throws IOException {
        Path proc = folder.newFolder("proc").toPath();
        Files.createDirectories(proc.resolve("self/task/3171"));
        // The device-verified shape: tracer pid 3464, a direct child of the
        // app process 3171, argv0 the packaged libproot.so path.
        writeProcEntry(proc, 3464, 3171, "libproot.so",
                PROOT_PATH + "\0-R\0/guest\0");
        // A lookalike owned by another parent: the argv0 match must not win
        // over the parent check.
        writeProcEntry(proc, 900, 1000, "libproot.so", PROOT_PATH);
        // A direct child that is not the tracer: right parent, wrong argv0.
        writeProcEntry(proc, 5000, 3171, "java", "java\0-daemon\0");

        assertEquals(Collections.singletonList(3464L),
                GuestSshdWorkload.tracerPids(proc, 3171L, PROOT_PATH));
    }

    @Test
    public void tracerPidsReadsParentPidAfterCommsLastParenthesis() throws IOException {
        Path proc = folder.newFolder("proc").toPath();
        Files.createDirectories(proc.resolve("self/task/3171"));
        // comm may itself contain a ')' and spaces: a scan that splits at
        // the first parenthesis would read "S" as the parent field and drop
        // a real child.
        writeProcEntry(proc, 3464, 3171, "odd ) name", PROOT_PATH);

        assertEquals(Collections.singletonList(3464L),
                GuestSshdWorkload.tracerPids(proc, 3171L, PROOT_PATH));
    }

    /**
     * Writes a fake {@code /proc/<pid>} entry: a {@code stat} in the
     * kernel's {@code pid (comm) state ppid ...} layout -- the scan reads
     * only the ppid field after comm's last parenthesis -- and a
     * NUL-separated {@code cmdline} buffer.
     */
    private static void writeProcEntry(Path proc, long pid, long parentPid,
                                       String comm, String cmdline)
            throws IOException {
        Path dir = Files.createDirectories(proc.resolve(Long.toString(pid)));
        Files.write(dir.resolve("stat"),
                (pid + " (" + comm + ") S " + parentPid + " 1 1 0 -1 0 0\n")
                        .getBytes(StandardCharsets.US_ASCII));
        Files.write(dir.resolve("cmdline"), cmdline.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void stopResultCallbackReportsStoppedWhenProcessIsGone() {
        RecordingListener listener = new RecordingListener();

        GuestSshdWorkload.stopResultCallback(listener, true).run();

        assertEquals(1, listener.stoppedCount);
        assertNull(listener.failureReason);
    }

    @Test
    public void stopResultCallbackReportsTypedFailureWhenProcessSurvives() {
        RecordingListener listener = new RecordingListener();

        GuestSshdWorkload.stopResultCallback(listener, false).run();

        // The survivor report is the typed onStopSurvived, which delegates to
        // onFailed for listeners that do not escalate — so both land here,
        // and the reason names the one-tap self-restart recovery with
        // force-stop demoted to the last resort (ADR-0063).
        assertEquals(0, listener.stoppedCount);
        String expected =
                "guest session workload is still alive or still holds the "
                        + "fixed SSH port after forced termination; tap "
                        + "Restart Linux to restart the app itself and "
                        + "recover, or force-stop it in Settings only if "
                        + "that fails";
        assertEquals(expected, listener.survivedReason);
        assertEquals(expected, listener.failureReason);
    }

    @Test
    public void stopProcessReturnsTrueWhenDestroyExitsTheProcess() {
        FakeProcess process = new FakeProcess();
        process.dieOnDestroy = true;

        assertTrue(GuestSshdWorkload.stopProcess(process, PROOT_PATH));

        assertTrue(process.destroyCalled);
        assertFalse(process.forciblyCalled);
    }

    @Test
    public void stopProcessReturnsTrueWhenForciblyKillsTheProcess() {
        FakeProcess process = new FakeProcess();
        process.dieOnForcibly = true;

        assertTrue(GuestSshdWorkload.stopProcess(process, PROOT_PATH));

        assertTrue(process.destroyCalled);
        assertTrue(process.forciblyCalled);
    }

    @Test
    public void stopProcessReturnsFalseWhenProcessSurvivesForcedStop() {
        // The verified wedge shape: a SIGSTOPped tracer that does not die is
        // still alive after the graceful and forced windows, so the stop must
        // not report success — the session must end FAILED, never STOPPED.
        FakeProcess process = new FakeProcess();

        assertFalse(GuestSshdWorkload.stopProcess(process, PROOT_PATH));

        assertTrue(process.destroyCalled);
        assertTrue(process.forciblyCalled);
    }

    @Test
    public void stopProcessTreatsNullAsGone() {
        assertTrue(GuestSshdWorkload.stopProcess(null, PROOT_PATH));
    }

    private static final class RecordingListener implements WorkloadListener {
        int stoppedCount;
        String failureReason;
        String survivedReason;

        @Override
        public void onReadiness(ReadinessFrame frame) {
        }

        @Override
        public void onStopped() {
            stoppedCount++;
        }

        @Override
        public void onFailed(String reason) {
            failureReason = reason;
        }

        @Override
        public void onStopSurvived(String reason) {
            survivedReason = reason;
            WorkloadListener.super.onStopSurvived(reason);
        }
    }

    /**
     * A {@link Process} whose death is scripted: {@code dieOnDestroy} exits
     * on SIGTERM, {@code dieOnForcibly} exits only on SIGKILL, and the
     * default survives both — the frozen-tracer shape. The bounded waits
     * return immediately so the test stays in milliseconds.
     */
    private static final class FakeProcess extends Process {
        boolean dieOnDestroy;
        boolean dieOnForcibly;
        boolean destroyCalled;
        boolean forciblyCalled;
        private boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public void destroy() {
            destroyCalled = true;
            if (dieOnDestroy) {
                alive = false;
            }
        }

        @Override
        public Process destroyForcibly() {
            forciblyCalled = true;
            if (dieOnForcibly) {
                alive = false;
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            return 0;
        }
    }
}
