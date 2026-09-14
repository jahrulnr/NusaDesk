package gh.nusashell.nusadesk.infrastructure.runtimehost;

import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Serialized supervisor for the guest runtime process.
 *
 * <p>Owns the start/stop lifecycle of one guest process per supervisor instance.
 * All public operations are serialized on an internal lock so start/stop/restart
 * are idempotent and never race. The supervisor never reports {@code RUNNING}
 * from a PID alone: before emitting {@link RuntimeState#RUNNING} it</p>
 *
 * <ol>
 *   <li>launches the process via the {@link ProcessLauncher} port,</li>
 *   <li>reads bounded stdout until a readiness line is accepted by
 *       {@link ReadinessLineParser},</li>
 *   <li>validates the frame's app id, version, and schema against the expected
 *       runtime identity, and</li>
 *   <li>performs an independent bounded health probe through
 *       {@link ReadinessHealthVerifier}.</li>
 * </ol>
 *
 * <p>Only when identity and health both pass does the supervisor publish the
 * concrete loopback port and emit {@code RUNNING}. On any failure the process is
 * terminated and {@code FAILED} is emitted. Stop requests a graceful
 * termination first and then forces termination after a bounded grace period.</p>
 *
 * <p>The supervisor depends only on ports ({@link ProcessLauncher},
 * {@link ReadinessHealthVerifier}, {@link SupervisorListener}) and pure domain
 * types, so it is unit-testable with deterministic fakes and no real Linux
 * execution. It does not persist working state itself; the caller wires the
 * listener to a {@code RuntimeStateStore} if recovery state is required.</p>
 */
public final class RuntimeSupervisor {

    /** Immutable parameters for a single start attempt. */
    public static final class StartRequest {
        private final String appId;
        private final String appVersion;
        private final String sessionId;
        private final List<String> argv;
        private final Map<String, String> extraEnv;
        private final BoundedOutputReader.Limits outputLimits;

        public StartRequest(
                String appId, String appVersion, String sessionId,
                List<String> argv, Map<String, String> extraEnv,
                BoundedOutputReader.Limits outputLimits) {
            if (appId == null || appId.trim().isEmpty()) {
                throw new IllegalArgumentException("appId must not be blank");
            }
            if (appVersion == null || appVersion.trim().isEmpty()) {
                throw new IllegalArgumentException("appVersion must not be blank");
            }
            if (sessionId == null || sessionId.trim().isEmpty()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (argv == null || argv.isEmpty()) {
                throw new IllegalArgumentException("argv must not be null or empty");
            }
            if (outputLimits == null) {
                throw new IllegalArgumentException("outputLimits must not be null");
            }
            this.appId = appId;
            this.appVersion = appVersion;
            this.sessionId = sessionId;
            this.argv = argv;
            this.extraEnv = extraEnv;
            this.outputLimits = outputLimits;
        }

        public String getAppId() {
            return appId;
        }

        public String getAppVersion() {
            return appVersion;
        }

        public String getSessionId() {
            return sessionId;
        }

        public List<String> getArgv() {
            return argv;
        }

        public Map<String, String> getExtraEnv() {
            return extraEnv;
        }

        public BoundedOutputReader.Limits getOutputLimits() {
            return outputLimits;
        }
    }

    /** Immutable result of a start attempt. */
    public static final class StartResult {
        private final RuntimeState state;
        private final String detail;
        private final int port;

        public StartResult(RuntimeState state, String detail, int port) {
            if (state == null) {
                throw new IllegalArgumentException("state must not be null");
            }
            this.state = state;
            this.detail = detail == null ? "" : detail;
            this.port = port;
        }

        public RuntimeState getState() {
            return state;
        }

        public String getDetail() {
            return detail;
        }

        public int getPort() {
            return port;
        }
    }

    private final ProcessLauncher launcher;
    private final ReadinessHealthVerifier healthVerifier;
    private final SupervisorListener listener;
    private final LongSupplier clock;
    private final long forceKillWaitMillis;
    private final long failedStartGraceMillis;
    private final Object lock = new Object();
    private ProcessHandle current;
    private int currentPort;

    /**
     * @param launcher               port that starts the guest process
     * @param healthVerifier          port that verifies readiness identity and health
     * @param listener                receives state transitions
     * @param clock                   epoch-millis source for bounded read deadlines
     * @param forceKillWaitMillis     time to wait after a forced termination
     * @param failedStartGraceMillis  graceful grace period when cleaning up a failed start
     */
    public RuntimeSupervisor(
            ProcessLauncher launcher,
            ReadinessHealthVerifier healthVerifier,
            SupervisorListener listener,
            LongSupplier clock,
            long forceKillWaitMillis,
            long failedStartGraceMillis) {
        if (launcher == null) {
            throw new IllegalArgumentException("launcher must not be null");
        }
        if (healthVerifier == null) {
            throw new IllegalArgumentException("healthVerifier must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (forceKillWaitMillis < 0) {
            throw new IllegalArgumentException("forceKillWaitMillis must not be negative");
        }
        if (failedStartGraceMillis < 0) {
            throw new IllegalArgumentException("failedStartGraceMillis must not be negative");
        }
        this.launcher = launcher;
        this.healthVerifier = healthVerifier;
        this.listener = listener;
        this.clock = clock;
        this.forceKillWaitMillis = forceKillWaitMillis;
        this.failedStartGraceMillis = failedStartGraceMillis;
    }

    /**
     * Start the guest runtime and verify readiness. Serialized.
     *
     * @return the resulting state, detail, and published port
     */
    public StartResult start(StartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        synchronized (lock) {
            if (current != null && current.isAlive()) {
                return new StartResult(RuntimeState.RUNNING, "already running", currentPort);
            }
            current = null;
            currentPort = 0;

            emit(RuntimeState.STARTING, "launching", 0);

            ProcessHandle handle;
            try {
                handle = launcher.launch(request.getArgv(), request.getExtraEnv());
            } catch (ProcessLaunchException e) {
                emit(RuntimeState.FAILED, "launch failed", 0);
                return new StartResult(RuntimeState.FAILED, "launch failed", 0);
            }
            current = handle;

            ReadinessFrame frame = awaitReadiness(handle, request);
            if (frame == null) {
                terminate(handle, failedStartGraceMillis);
                current = null;
                emit(RuntimeState.FAILED, "no valid readiness frame", 0);
                return new StartResult(RuntimeState.FAILED, "no valid readiness frame", 0);
            }

            ReadinessHealthVerifier.Result health =
                    healthVerifier.verify(frame, request.getAppId(), request.getAppVersion());
            if (health.getOutcome() != ReadinessHealthVerifier.Outcome.HEALTHY) {
                terminate(handle, failedStartGraceMillis);
                current = null;
                String detail = health.getOutcome().name() + ": " + health.getDetail();
                emit(RuntimeState.FAILED, detail, 0);
                return new StartResult(RuntimeState.FAILED, detail, 0);
            }

            currentPort = frame.getEndpoint().getPort();
            emit(RuntimeState.RUNNING, "ready", currentPort);
            return new StartResult(RuntimeState.RUNNING, "ready", currentPort);
        }
    }

    /**
     * Stop the guest runtime: graceful first, then forced after the grace
     * period. Serialized.
     *
     * @param gracefulTimeoutMillis maximum time to wait for a graceful exit
     */
    public void stop(long gracefulTimeoutMillis) {
        if (gracefulTimeoutMillis < 0) {
            throw new IllegalArgumentException("gracefulTimeoutMillis must not be negative");
        }
        synchronized (lock) {
            ProcessHandle handle = current;
            if (handle == null || !handle.isAlive()) {
                current = null;
                currentPort = 0;
                emit(RuntimeState.STOPPED, "not running", 0);
                return;
            }
            emit(RuntimeState.STOPPING, "graceful stop", currentPort);
            terminate(handle, gracefulTimeoutMillis);
            current = null;
            currentPort = 0;
            emit(RuntimeState.STOPPED, "stopped", 0);
        }
    }

    /** Whether the supervised process is currently alive. */
    public boolean isRunning() {
        synchronized (lock) {
            return current != null && current.isAlive();
        }
    }

    /** The published loopback port, or {@code 0} when not running. */
    public int getCurrentPort() {
        synchronized (lock) {
            return currentPort;
        }
    }

    private ReadinessFrame awaitReadiness(ProcessHandle handle, StartRequest request) {
        ReadinessCollector collector = new ReadinessCollector();
        try (InputStream stdout = handle.getStdout()) {
            new BoundedOutputReader().read(stdout, request.getOutputLimits(), collector, clock);
        } catch (IOException e) {
            return null;
        }
        return collector.frame();
    }

    private void terminate(ProcessHandle handle, long gracefulMillis) {
        handle.destroyGracefully();
        boolean exited = false;
        try {
            exited = handle.waitFor(gracefulMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!exited && handle.isAlive()) {
            handle.destroyForcibly();
            try {
                handle.waitFor(forceKillWaitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void emit(RuntimeState state, String detail, int port) {
        listener.onState(state, detail, port);
    }

    private static final class ReadinessCollector implements BoundedOutputReader.LineHandler {
        private ReadinessFrame frame;

        @Override
        public boolean onLine(String line) {
            ReadinessLineParser.Result result = ReadinessLineParser.parse(line);
            if (result.isAccepted()) {
                frame = result.getFrame();
                return false;
            }
            return true;
        }

        ReadinessFrame frame() {
            return frame;
        }
    }
}
