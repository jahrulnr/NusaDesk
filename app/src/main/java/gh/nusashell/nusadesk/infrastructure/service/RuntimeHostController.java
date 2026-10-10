package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.SessionLivenessPolicy;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.session.SessionTransitionPolicy;
import gh.nusashell.nusadesk.infrastructure.integration.GuestSshdWorkload;

import java.util.function.LongSupplier;

/**
 * Android-free orchestrator that drives the host-side runtime session.
 *
 * <p>Owns the current {@link SessionSnapshot} and advances it through the
 * domain state machine. It delegates actual process start/stop to the
 * registered {@link RuntimeWorkload}; it never starts arbitrary code and never
 * reports {@code RUNNING} without an accepted readiness frame. The service is
 * a thin Android adapter around this controller, so all lifecycle policy is
 * verified by plain unit tests.
 *
 * <p>Operations are serialised by the caller (the service handles one intent at
 * a time on the main thread); this class is not thread-safe by design.
 */
public final class RuntimeHostController {

    private final RuntimeWorkloadRegistry registry;
    private final LongSupplier clock;
    private SessionSnapshot snapshot;
    /**
     * The canary's miss counter for the current session. A fresh session gets
     * a fresh policy, so a wedge cannot inherit failures from a previous one.
     */
    private SessionLivenessPolicy liveness = new SessionLivenessPolicy();
    /**
     * Set when the current {@code FAILED} came from the workload's typed
     * "survived the forced stop" report — the one failure a user-requested
     * restart may escalate to killing the app's own process tree (ADR-0063).
     * Cleared by a fresh start, a clean stop, any other failure kind, and a
     * reconcile, so it can never leak into a session it did not describe.
     */
    private boolean stopSurvived;

    /**
     * @param registry source of the single allowlisted workload
     * @param clock    supplier of epoch milliseconds for snapshot timestamps
     */
    public RuntimeHostController(RuntimeWorkloadRegistry registry, LongSupplier clock) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.registry = registry;
        this.clock = clock;
    }

    /** Current host status, suitable for rendering a notification. */
    public HostRuntimeStatus status() {
        RuntimeWorkload workload = registry.get();
        return new HostRuntimeStatus(snapshot, workload != null,
                workload == null ? "" : workload.workloadId(), stopSurvived);
    }

    /**
     * Begin a session for the given app identity.
     *
     * <p>Transitions to {@code STARTING} and delegates to the registered
     * workload. If no workload is registered, transitions straight to
     * {@code FAILED} so the host never starts arbitrary code. A start while a
     * session is already active is a no-op that re-publishes the current
     * status.
     */
    public void start(String appId, String appVersion, String sessionId, HostListener listener) {
        long now = clock.getAsLong();
        SessionState current = snapshot == null ? SessionState.NOT_STARTED : snapshot.getState();
        if (!isStartable(current)) {
            publish(listener);
            return;
        }
        SessionSnapshot fresh = new SessionSnapshot(
                sessionId, appId, appVersion, SessionState.NOT_STARTED, null, now, now, "", 0);
        SessionSnapshot starting = SessionTransitionPolicy.attempt(fresh, SessionState.STARTING, now, "");
        snapshot = starting;
        liveness = new SessionLivenessPolicy();
        stopSurvived = false;
        publish(listener);

        RuntimeWorkload workload = registry.get();
        if (workload == null) {
            snapshot = SessionTransitionPolicy.attempt(
                    starting, SessionState.FAILED, clock.getAsLong(), "no runtime workload registered");
            publish(listener);
            return;
        }
        workload.start(starting, toWorkloadListener(listener));
    }

    /**
     * Idempotent "the app is in the foreground, so the runtime must be up"
     * boundary (ADR-0013).
     *
     * <p>The product has no start/stop control: Linux starts with the app and
     * keeps running under the foreground service when the app is backgrounded.
     * An Activity foreground event calls this; it starts a session only when no
     * session is live. A start while {@code STARTING}, {@code RUNNING},
     * {@code RECONNECTING}, {@code STOPPING}, or {@code RECOVERING} is a no-op
     * that re-publishes the current status, so repeated foreground events cannot
     * restart a running runtime, spawn a second guest daemon, or resurrect a
     * runtime the user just stopped with the notification's Stop action.</p>
     *
     * <p>It is deliberately not a background-start path: nothing here runs
     * without a user-visible launch, and there is no boot receiver, alarm,
     * or sticky restart that could call it. The one scheduled caller is the
     * ADR-0063 revival job, armed only by the user's own Restart tap while a
     * failed workload still survives — it is the same boundary, re-entered
     * from the fresh process the platform starts.</p>
     *
     * <p>{@code UNRESPONSIVE} counts as live here: a wedged session is not
     * resurrected by a foreground event either; the user's Restart action
     * takes it through {@code STOPPING} first.</p>
     */
    public void ensureRunning(String appId, String appVersion, String sessionId, HostListener listener) {
        SessionState current = snapshot == null ? SessionState.NOT_STARTED : snapshot.getState();
        if (isLiveRequired(current)) {
            publish(listener);
            return;
        }
        start(appId, appVersion, sessionId, listener);
    }

    /**
     * Stop a running or starting session gracefully.
     *
     * <p>Transitions to {@code STOPPING} and delegates to the registered
     * workload. With no registered workload, transitions directly to
     * {@code STOPPED}. A stop when nothing is active is a no-op.
     */
    public void stop(HostListener listener) {
        if (snapshot == null) {
            publish(listener);
            return;
        }
        if (!snapshot.getState().canTransitionTo(SessionState.STOPPING)) {
            publish(listener);
            return;
        }
        long now = clock.getAsLong();
        snapshot = SessionTransitionPolicy.attempt(snapshot, SessionState.STOPPING, now, "");
        publish(listener);

        RuntimeWorkload workload = registry.get();
        if (workload == null) {
            snapshot = SessionTransitionPolicy.attempt(snapshot, SessionState.STOPPED, clock.getAsLong(), "");
            publish(listener);
            return;
        }
        workload.stop(toWorkloadListener(listener));
    }

    /**
     * Accept a readiness frame emitted by the guest.
     *
     * <p>Transitions to {@code RUNNING} only when the frame is ready, belongs
     * to the current session, and the current state allows it. A non-ready or
     * mismatched frame is ignored so the guest cannot force a running state.
     */
    public void onReadiness(ReadinessFrame frame, HostListener listener) {
        if (snapshot == null || frame == null) {
            return;
        }
        if (!frame.isReady()) {
            return;
        }
        if (!frame.getSessionId().equals(snapshot.getSessionId())) {
            return;
        }
        if (!snapshot.getState().canTransitionTo(SessionState.RUNNING)) {
            return;
        }
        long now = clock.getAsLong();
        SessionSnapshot running = SessionTransitionPolicy.attempt(snapshot, SessionState.RUNNING, now, "");
        snapshot = withEndpoint(running, frame.getEndpoint(), now);
        publish(listener);
    }

    /**
     * Reconcile a persisted snapshot after the host process restarted.
     *
     * <p>A fresh controller has no snapshot. When the service recovers one from
     * the session state store, any state that required a live workload
     * ({@code STARTING}, {@code RUNNING}, {@code RECONNECTING}, {@code STOPPING},
     * {@code RECOVERING}, {@code UNRESPONSIVE}) is demoted to {@code FAILED} with an explicit reason —
     * after a real process death the workload is gone, so claiming it still
     * runs would be a false {@code RUNNING}. The demotion also asks the
     * registered workload to stop: the registry holds app-level singletons, so
     * when only the <em>service</em> (not the process) was destroyed the
     * workload's bound port and guest shell would otherwise stay orphaned —
     * {@link #stop} cannot reach them because {@code FAILED} cannot transition
     * to {@code STOPPING}. Terminal states ({@code STOPPED}, {@code FAILED},
     * {@code CANCELLED}, {@code NOT_STARTED}) are restored unchanged. Calling
     * this once a snapshot already exists is a no-op that re-publishes.
     */
    public void reconcileStored(SessionSnapshot stored, HostListener listener) {
        if (snapshot != null) {
            publish(listener);
            return;
        }
        if (stored == null) {
            return;
        }
        if (isLiveRequired(stored.getState())) {
            long now = clock.getAsLong();
            snapshot = new SessionSnapshot(
                    stored.getSessionId(), stored.getAppId(), stored.getAppVersion(),
                    SessionState.FAILED, null, stored.getStartedAtEpochMillis(), now,
                    "host process restarted; runtime was lost", stored.getReconnectAttempts());
            stopSurvived = false;
            RuntimeWorkload workload = registry.get();
            if (workload != null) {
                workload.stop(WorkloadListener.NONE);
            }
        } else {
            snapshot = stored;
            // The survivor flag is transient, but its reason is persisted:
            // re-derive it so a failed survivor still offers its Restart
            // action after a process restart (e.g. the escalation's revival
            // job was armed but never delivered). The reason is produced only
            // by the workload's own stop verdict, never by guest input.
            stopSurvived = GuestSshdWorkload.STOP_SURVIVED_REASON.equals(
                    stored.getFailureReason());
        }
        publish(listener);
    }

    /**
     * Whether the current status still needs a supervised workload behind it.
     *
     * <p>True while a session is starting, running, or transitioning; false
     * before any session exists and once the session is terminal. The host
     * service uses this on destroy: a service torn down while this is true must
     * stop the workload, otherwise the guest process keeps running with no
     * notification and no supervision.</p>
     */
    public boolean requiresLiveWorkload() {
        return snapshot != null && isLiveRequired(snapshot.getState());
    }

    private static boolean isLiveRequired(SessionState state) {
        switch (state) {
            case STARTING:
            case RUNNING:
            case RECONNECTING:
            case STOPPING:
            case RECOVERING:
            case UNRESPONSIVE:
                return true;
            default:
                return false;
        }
    }

    /** Guest reported a clean stop. */
    public void onWorkloadStopped(HostListener listener) {
        if (snapshot == null) {
            return;
        }
        long now = clock.getAsLong();
        if (snapshot.getState() == SessionState.STOPPING) {
            snapshot = SessionTransitionPolicy.attempt(snapshot, SessionState.STOPPED, now, "");
        } else if (snapshot.getState() == SessionState.UNRESPONSIVE) {
            snapshot = SessionTransitionPolicy.attempt(
                    snapshot, SessionState.FAILED, now, "unresponsive session ended");
        } else if (snapshot.getState().canTransitionTo(SessionState.FAILED)) {
            snapshot = SessionTransitionPolicy.attempt(
                    snapshot, SessionState.FAILED, now, "guest stopped before readiness");
        } else {
            return;
        }
        // Clear only once a real transition publishes: a stale report that
        // changes nothing must not disarm a survivor's escalation flag.
        stopSurvived = false;
        publish(listener);
    }

    /** Guest reported a failure. */
    public void onWorkloadFailed(String reason, HostListener listener) {
        if (snapshot == null) {
            return;
        }
        if (!snapshot.getState().canTransitionTo(SessionState.FAILED)) {
            return;
        }
        stopSurvived = false;
        long now = clock.getAsLong();
        snapshot = SessionTransitionPolicy.attempt(snapshot, SessionState.FAILED, now, reason);
        publish(listener);
    }

    /**
     * The workload's typed "the stop request left a survivor" report — a
     * process still alive after the forced window, or the fixed port still
     * held (ADR-0062). The transition is the same {@code FAILED} as any other
     * failure, but the status carries {@link HostRuntimeStatus#survivedStop()}
     * so the service's one-tap restart can escalate it to a self-restart of
     * the whole process tree — the only recovery left on a kernel where the
     * stopped tracer could not be killed (ADR-0063).
     */
    public void onWorkloadStopSurvived(String reason, HostListener listener) {
        if (snapshot == null) {
            return;
        }
        // A survivor report only exists as the answer to a stop in flight:
        // from any other state it is stale or a bug, and must never mark an
        // unrelated failure as escalatable.
        if (snapshot.getState() != SessionState.STOPPING) {
            return;
        }
        stopSurvived = true;
        long now = clock.getAsLong();
        snapshot = SessionTransitionPolicy.attempt(snapshot, SessionState.FAILED, now, reason);
        publish(listener);
    }

    /**
     * The liveness canary reports one bounded guest probe (ADR-0062).
     * {@link SessionLivenessPolicy} decides whether the result changes the
     * state: enough consecutive misses flip {@code RUNNING} to
     * {@code UNRESPONSIVE}, and the first success while unresponsive flips it
     * back — the guest never asked to stop, so nothing else is restarted.
     * Results while the session is not live are ignored.
     */
    public void onLivenessProbe(boolean alive, HostListener listener) {
        if (snapshot == null) {
            return;
        }
        SessionState next = liveness.record(snapshot.getState(), alive);
        if (next == null) {
            return;
        }
        // The snapshot's reason field is FAILED-specific; UNRESPONSIVE copy is
        // fixed wording in the notification and presentation policies.
        snapshot = SessionTransitionPolicy.attempt(snapshot, next, clock.getAsLong(), "");
        publish(listener);
    }

    private void publish(HostListener listener) {
        if (listener != null) {
            listener.onStatus(status());
        }
    }

    private WorkloadListener toWorkloadListener(HostListener hostListener) {
        return new WorkloadListener() {
            @Override
            public void onReadiness(ReadinessFrame frame) {
                RuntimeHostController.this.onReadiness(frame, hostListener);
            }

            @Override
            public void onStopped() {
                RuntimeHostController.this.onWorkloadStopped(hostListener);
            }

            @Override
            public void onFailed(String reason) {
                RuntimeHostController.this.onWorkloadFailed(reason, hostListener);
            }

            @Override
            public void onStopSurvived(String reason) {
                RuntimeHostController.this.onWorkloadStopSurvived(reason, hostListener);
            }
        };
    }

    private static SessionSnapshot withEndpoint(SessionSnapshot s, RuntimePort endpoint, long now) {
        return new SessionSnapshot(
                s.getSessionId(), s.getAppId(), s.getAppVersion(),
                s.getState(), endpoint, s.getStartedAtEpochMillis(), now,
                s.getFailureReason(), s.getReconnectAttempts());
    }

    private static boolean isStartable(SessionState state) {
        return state == SessionState.NOT_STARTED
                || state == SessionState.STOPPED
                || state == SessionState.FAILED
                || state == SessionState.CANCELLED;
    }

    /** Callback the service implements to re-render the notification. */
    public interface HostListener {
        void onStatus(HostRuntimeStatus status);
    }
}
