package gh.nusashell.nusadesk.infrastructure.runtimehost;

import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link RuntimeSupervisor} using deterministic fakes for
 * {@link ProcessLauncher}, {@link ProcessHandle}, and {@link HttpHealthProbe}.
 * No real Linux process or network is used.
 */
public class RuntimeSupervisorTest {

    private static final String APP = "ubuntu-base";
    private static final String VER = "0.1.0";
    private static final LongSupplier FIXED_CLOCK = () -> 1_000L;
    private static final BoundedOutputReader.Limits LIMITS =
            new BoundedOutputReader.Limits(0, 100, 8192, 1_000_000);

    /** Fake process handle backed by an in-memory stdout buffer. */
    private static final class FakeHandle implements ProcessHandle {
        private final InputStream stdout;
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private boolean alive = true;
        private boolean gracefulDestroyed = false;
        private boolean forceDestroyed = false;
        private final boolean respondsToGraceful;

        FakeHandle(String stdoutText, boolean respondsToGraceful) {
            this.stdout = new ByteArrayInputStream(stdoutText.getBytes(StandardCharsets.UTF_8));
            this.respondsToGraceful = respondsToGraceful;
        }

        @Override
        public InputStream getStdout() {
            return stdout;
        }

        @Override
        public InputStream getStderr() {
            return stderr;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void destroyGracefully() {
            gracefulDestroyed = true;
            if (respondsToGraceful) {
                alive = false;
            }
        }

        @Override
        public void destroyForcibly() {
            forceDestroyed = true;
            alive = false;
        }

        @Override
        public boolean waitFor(long timeoutMillis) {
            return !alive;
        }

        @Override
        public int exitValue() {
            return 0;
        }
    }

    /** Fake launcher that returns a configured handle or throws. */
    private static final class FakeLauncher implements ProcessLauncher {
        private FakeHandle nextHandle;
        private ProcessLaunchException nextFailure;

        void enqueue(FakeHandle handle) {
            this.nextHandle = handle;
            this.nextFailure = null;
        }

        void enqueueFailure(ProcessLaunchException failure) {
            this.nextFailure = failure;
            this.nextHandle = null;
        }

        @Override
        public ProcessHandle launch(List<String> argv, Map<String, String> extraEnv) throws ProcessLaunchException {
            if (nextFailure != null) {
                throw nextFailure;
            }
            if (nextHandle == null) {
                throw new ProcessLaunchException("no handle enqueued");
            }
            FakeHandle handle = nextHandle;
            nextHandle = null;
            return handle;
        }
    }

    /** Fake probe returning a configured status code. */
    private static final class FakeProbe implements HttpHealthProbe {
        private int status;

        FakeProbe(int status) {
            this.status = status;
        }

        void setStatus(int status) {
            this.status = status;
        }

        @Override
        public int probe(String scheme, String host, int port, String path,
                         int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
            return status;
        }
    }

    /** Recording listener. */
    private static final class RecordingListener implements SupervisorListener {
        final List<RuntimeState> states = new java.util.ArrayList<>();
        final List<String> details = new java.util.ArrayList<>();
        final List<Integer> ports = new java.util.ArrayList<>();

        @Override
        public void onState(RuntimeState state, String detail, int port) {
            states.add(state);
            details.add(detail);
            ports.add(port);
        }
    }

    private static String readinessLine(ReadinessHealth health, int port) {
        return "READY/1 app=" + APP + " ver=" + VER + " session=sess-1 "
                + "host=127.0.0.1 port=" + port + " health="
                + health.name().toLowerCase() + " emitted=1000";
    }

    private RuntimeSupervisor newSupervisor(FakeLauncher launcher, FakeProbe probe,
                                            RecordingListener listener) {
        ReadinessHealthVerifier verifier = new ReadinessHealthVerifier(probe, "/healthz", 500, 500);
        return new RuntimeSupervisor(launcher, verifier, listener, FIXED_CLOCK, 50L, 50L);
    }

    private RuntimeSupervisor.StartRequest request() {
        return new RuntimeSupervisor.StartRequest(
                APP, VER, "sess-1",
                Arrays.asList("/bin/true"),
                Collections.emptyMap(),
                LIMITS);
    }

    @Test
    public void startEmitsRunningAfterIdentityAndHealthPass() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueue(new FakeHandle(readinessLine(ReadinessHealth.HEALTHY, 8080) + "\n", true));
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.RUNNING, result.getState());
        assertEquals(8080, result.getPort());
        assertEquals(8080, supervisor.getCurrentPort());
        assertTrue(supervisor.isRunning());
        assertEquals(Arrays.asList(RuntimeState.STARTING, RuntimeState.RUNNING), listener.states);
    }

    @Test
    public void startFailsWhenNoReadinessFrameIsEmitted() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueue(new FakeHandle("ordinary stdout\nno readiness here\n", true));
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.FAILED, result.getState());
        assertFalse(supervisor.isRunning());
        assertEquals(Arrays.asList(RuntimeState.STARTING, RuntimeState.FAILED), listener.states);
    }

    @Test
    public void startFailsOnMalformedReadinessLine() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueue(new FakeHandle("READY/1 app=ubuntu-base\n", true));
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.FAILED, result.getState());
        assertFalse(supervisor.isRunning());
    }

    @Test
    public void startFailsOnIdentityMismatchAndTerminatesProcess() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        String wrongApp = "READY/1 app=other-app ver=" + VER + " session=sess-1 "
                + "host=127.0.0.1 port=8080 health=healthy emitted=1000\n";
        FakeHandle handle = new FakeHandle(wrongApp, true);
        launcher.enqueue(handle);
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.FAILED, result.getState());
        assertFalse(supervisor.isRunning());
        assertTrue(handle.gracefulDestroyed);
        assertEquals(RuntimeState.FAILED, listener.states.get(listener.states.size() - 1));
    }

    @Test
    public void startFailsWhenGuestSelfReportsUnhealthy() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueue(new FakeHandle(readinessLine(ReadinessHealth.UNHEALTHY, 8080) + "\n", true));
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.FAILED, result.getState());
        assertFalse(supervisor.isRunning());
    }

    @Test
    public void startFailsWhenHealthProbeReturnsNon2xx() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(503);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        FakeHandle handle = new FakeHandle(readinessLine(ReadinessHealth.HEALTHY, 8080) + "\n", true);
        launcher.enqueue(handle);
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.FAILED, result.getState());
        assertFalse(supervisor.isRunning());
        assertTrue(handle.gracefulDestroyed);
    }

    @Test
    public void startFailsWhenLauncherThrows() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueueFailure(new ProcessLaunchException("spawn error"));
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.FAILED, result.getState());
        assertFalse(supervisor.isRunning());
        assertEquals(Arrays.asList(RuntimeState.STARTING, RuntimeState.FAILED), listener.states);
    }

    @Test
    public void secondStartWhileRunningReturnsAlreadyRunningWithoutRelaunch() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueue(new FakeHandle(readinessLine(ReadinessHealth.HEALTHY, 8080) + "\n", false));
        supervisor.start(request());
        // No new handle enqueued: a relaunch would throw "no handle enqueued".
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.RUNNING, result.getState());
        assertEquals(8080, result.getPort());
    }

    @Test
    public void stopGracefullyExitsWithinTimeoutAndEmitsStopped() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        FakeHandle handle = new FakeHandle(readinessLine(ReadinessHealth.HEALTHY, 8080) + "\n", true);
        launcher.enqueue(handle);
        supervisor.start(request());

        supervisor.stop(1000L);

        assertFalse(supervisor.isRunning());
        assertTrue(handle.gracefulDestroyed);
        assertFalse(handle.forceDestroyed);
        assertEquals(RuntimeState.STOPPING, listener.states.get(listener.states.size() - 2));
        assertEquals(RuntimeState.STOPPED, listener.states.get(listener.states.size() - 1));
    }

    @Test
    public void stopForciblyKillsWhenGracefulTimesOut() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        // respondsToGraceful=false: destroyGracefully does not clear alive, waitFor returns false.
        FakeHandle handle = new FakeHandle(readinessLine(ReadinessHealth.HEALTHY, 8080) + "\n", false);
        launcher.enqueue(handle);
        supervisor.start(request());

        supervisor.stop(50L);

        assertFalse(supervisor.isRunning());
        assertTrue(handle.gracefulDestroyed);
        assertTrue(handle.forceDestroyed);
    }

    @Test
    public void stopWhenNotRunningEmitsStopped() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        supervisor.stop(1000L);

        assertEquals(Collections.singletonList(RuntimeState.STOPPED), listener.states);
    }

    @Test
    public void restartAfterFailureLaunchesNewProcess() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        launcher.enqueueFailure(new ProcessLaunchException("first attempt fails"));
        supervisor.start(request());
        assertFalse(supervisor.isRunning());

        launcher.enqueue(new FakeHandle(readinessLine(ReadinessHealth.HEALTHY, 9000) + "\n", true));
        RuntimeSupervisor.StartResult result = supervisor.start(request());

        assertEquals(RuntimeState.RUNNING, result.getState());
        assertEquals(9000, result.getPort());
        assertTrue(supervisor.isRunning());
    }

    @Test
    public void rejectsNullArguments() {
        FakeLauncher launcher = new FakeLauncher();
        FakeProbe probe = new FakeProbe(200);
        RecordingListener listener = new RecordingListener();
        RuntimeSupervisor supervisor = newSupervisor(launcher, probe, listener);

        try {
            supervisor.start(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            supervisor.stop(-1L);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
