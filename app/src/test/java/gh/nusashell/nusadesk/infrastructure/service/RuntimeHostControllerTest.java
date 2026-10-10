package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.integration.GuestSshdWorkload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RuntimeHostControllerTest {

    private static final String APP_ID = "ubuntu-base-arm64";
    private static final String VERSION = "0.1.0";
    private static final String SESSION = "sess-1";

    private RuntimeWorkloadRegistry registry;
    private MutableClock clock;
    private RuntimeHostController controller;
    private List<HostRuntimeStatus> published;

    @Before
    public void setUp() {
        // Use a fresh registry instance via the singleton, cleared each test.
        registry = RuntimeWorkloadRegistry.getInstance();
        registry.unregister();
        clock = new MutableClock();
        controller = new RuntimeHostController(registry, clock);
        published = new ArrayList<>();
    }

    @After
    public void tearDown() {
        registry.unregister();
    }

    @Test
    public void initialStatusIsNotStartedWithoutWorkload() {
        HostRuntimeStatus status = controller.status();
        assertEquals(SessionState.NOT_STARTED, status.getState());
        assertFalse(status.isWorkloadRegistered());
        assertFalse(status.isRuntimeRunning());
        assertNull(status.getEndpoint());
    }

    @Test
    public void startWithoutWorkloadFailsAndNeverStartsArbitraryCode() {
        controller.start(APP_ID, VERSION, SESSION, this::record);

        // Transitions through STARTING then FAILED; never RUNNING.
        assertEquals(SessionState.FAILED, last().getState());
        assertEquals("no runtime workload registered", last().getFailureReason());
        assertFalse(last().isRuntimeRunning());
        assertNull(last().getEndpoint());
    }

    @Test
    public void startWithoutWorkloadPublishesStartingThenFailed() {
        controller.start(APP_ID, VERSION, SESSION, this::record);

        assertEquals(2, published.size());
        assertEquals(SessionState.STARTING, published.get(0).getState());
        assertEquals(SessionState.FAILED, published.get(1).getState());
    }

    @Test
    public void startWithWorkloadTransitionsToStartingAndDelegates() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);

        controller.start(APP_ID, VERSION, SESSION, this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertEquals(1, workload.startCount);
        assertEquals(0, workload.stopCount);
    }

    @Test
    public void readinessFrameTransitionsToRunningWithEndpoint() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        ReadinessFrame frame = readyFrame(new RuntimePort("127.0.0.1", 10994));
        controller.onReadiness(frame, this::record);

        HostRuntimeStatus status = last();
        assertEquals(SessionState.RUNNING, status.getState());
        assertEquals("127.0.0.1", status.getEndpoint().getHost());
        assertEquals(10994, status.getEndpoint().getPort());
        assertTrue(status.isRuntimeRunning());
    }

    @Test
    public void nonReadyFrameDoesNotTransitionToRunning() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        ReadinessFrame degraded = new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA, APP_ID, VERSION, SESSION,
                new RuntimePort("127.0.0.1", 10994), ReadinessHealth.DEGRADED, 100L);
        controller.onReadiness(degraded, this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertFalse(last().isRuntimeRunning());
    }

    @Test
    public void mismatchedSessionIdFrameIsIgnored() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        ReadinessFrame other = new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA, APP_ID, VERSION, "other-session",
                new RuntimePort("127.0.0.1", 10994), ReadinessHealth.HEALTHY, 100L);
        controller.onReadiness(other, this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertNull(last().getEndpoint());
    }

    @Test
    public void runtimeRunningRequiresReadinessNotServiceExistence() {
        // Starting with a workload but no readiness frame must not be RUNNING.
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        HostRuntimeStatus status = controller.status();
        assertEquals(SessionState.STARTING, status.getState());
        assertFalse(status.isRuntimeRunning());
    }

    @Test
    public void stopFromRunningTransitionsToStoppingAndDelegates() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 10994)), this::record);

        controller.stop(this::record);

        assertEquals(SessionState.STOPPING, last().getState());
        assertEquals(1, workload.stopCount);
    }

    @Test
    public void workloadStoppedDuringStoppingTransitionsToStopped() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 10994)), this::record);
        controller.stop(this::record);

        controller.onWorkloadStopped(this::record);

        assertEquals(SessionState.STOPPED, last().getState());
    }

    @Test
    public void workloadStoppedDuringStartingFails() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        controller.onWorkloadStopped(this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals("guest stopped before readiness", last().getFailureReason());
    }

    @Test
    public void workloadFailedTransitionsToFailedWithReason() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        controller.onWorkloadFailed("guest crashed", this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals("guest crashed", last().getFailureReason());
    }

    @Test
    public void stopWhenNotStartedIsNoOp() {
        controller.stop(this::record);
        // No snapshot exists; status stays NOT_STARTED.
        assertEquals(SessionState.NOT_STARTED, controller.status().getState());
    }

    @Test
    public void startWhileActiveIsNoOpThatRepublishes() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        int before = published.size();

        controller.start(APP_ID, VERSION, SESSION, this::record);

        assertEquals(before + 1, published.size());
        assertEquals(SessionState.STARTING, last().getState());
        assertEquals(1, workload.startCount);
    }

    @Test
    public void canRestartFromStopped() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 10994)), this::record);
        controller.stop(this::record);
        controller.onWorkloadStopped(this::record);
        assertEquals(SessionState.STOPPED, last().getState());

        RecordingWorkload second = new RecordingWorkload();
        registry.register(second);
        controller.start(APP_ID, VERSION, "sess-2", this::record);
        assertEquals(SessionState.STARTING, last().getState());
        assertEquals(1, second.startCount);
    }

    @Test
    public void reconcileStoredDemotesLiveRequiringStateToFailed() {
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 10994), 50L, 60L, "", 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertNull(last().getEndpoint());
        assertEquals("host process restarted; runtime was lost", last().getFailureReason());
        assertFalse(last().isRuntimeRunning());
    }

    @Test
    public void reconcileStoredDemotionStopsOrphanedWorkload() {
        // The registry holds app-level singletons: when only the service (not
        // the process) was destroyed, the workload may still hold a bound port
        // and guest shell. The demotion must release them because FAILED can
        // no longer transition to STOPPING through stop().
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 10994), 50L, 60L, "", 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals(1, workload.stopCount);
    }

    @Test
    public void reconcileStoredTerminalStateDoesNotStopWorkload() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.STOPPED,
                null, 50L, 60L, "", 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(0, workload.stopCount);
    }

    @Test
    public void reconcileStoredRestoresTerminalStateUnchanged() {
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.STOPPED,
                null, 50L, 60L, "", 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(SessionState.STOPPED, last().getState());
    }

    @Test
    public void reconcileStoredNullIsNoOp() {
        controller.reconcileStored(null, this::record);
        assertTrue(published.isEmpty());
        assertEquals(SessionState.NOT_STARTED, controller.status().getState());
    }

    @Test
    public void reconcileStoredAfterStartIsNoOpRepublish() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        int before = published.size();

        SessionSnapshot stored = new SessionSnapshot(
                "old", APP_ID, VERSION, SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 10994), 50L, 60L, "", 0);
        controller.reconcileStored(stored, this::record);

        assertEquals(before + 1, published.size());
        assertEquals(SessionState.STARTING, last().getState());
    }

    @Test
    public void requiresLiveWorkloadIsFalseWithoutSession() {
        assertFalse(controller.requiresLiveWorkload());
    }

    @Test
    public void requiresLiveWorkloadIsTrueWhileStartingAndRunning() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);

        assertTrue(controller.requiresLiveWorkload());

        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 10994)), this::record);

        assertTrue(controller.requiresLiveWorkload());
    }

    @Test
    public void requiresLiveWorkloadStaysTrueWhileStoppingAndFalseWhenTerminal() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 10994)), this::record);

        controller.stop(this::record);

        // STOPPING still has a workload to supervise; only terminal states do not.
        assertTrue(controller.requiresLiveWorkload());

        controller.onWorkloadStopped(this::record);

        assertFalse(controller.requiresLiveWorkload());
    }

    @Test
    public void requiresLiveWorkloadIsFalseAfterReconcileDemotion() {
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 10994), 50L, 60L, "", 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(SessionState.FAILED, controller.status().getState());
        assertFalse(controller.requiresLiveWorkload());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullRegistryThrows() {
        new RuntimeHostController(null, clock);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullClockThrows() {
        new RuntimeHostController(registry, null);
    }

    // ---- ensureRunning: the idempotent Activity-foreground autostart boundary ----

    @Test
    public void ensureRunningStartsWhenNoSessionExists() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);

        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertEquals(1, workload.startCount);
    }

    @Test
    public void ensureRunningStartsFromStoppedAndFailed() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.stop(this::record);
        controller.onWorkloadStopped(this::record);
        assertEquals(SessionState.STOPPED, last().getState());

        controller.ensureRunning(APP_ID, VERSION, "sess-2", this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertEquals(2, workload.startCount);

        controller.onWorkloadFailed("guest sshd exited unexpectedly", this::record);
        assertEquals(SessionState.FAILED, last().getState());

        controller.ensureRunning(APP_ID, VERSION, "sess-3", this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertEquals(3, workload.startCount);
    }

    @Test
    public void ensureRunningIsIdempotentWhileStarting() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);
        int before = published.size();

        controller.ensureRunning(APP_ID, VERSION, "sess-2", this::record);

        assertEquals(before + 1, published.size());
        assertEquals(SessionState.STARTING, last().getState());
        assertEquals("a second foreground event must not start a second session",
                1, workload.startCount);
        assertEquals(SESSION, controller.status().getSnapshot().getSessionId());
    }

    @Test
    public void ensureRunningIsIdempotentWhileRunning() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);

        controller.ensureRunning(APP_ID, VERSION, "sess-2", this::record);

        assertEquals(SessionState.RUNNING, last().getState());
        assertEquals("a live runtime must be left alone", 1, workload.startCount);
        assertEquals(SESSION, controller.status().getSnapshot().getSessionId());
    }

    @Test
    public void ensureRunningDoesNotResurrectARuntimeWhileStopping() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.stop(this::record);

        controller.ensureRunning(APP_ID, VERSION, "sess-2", this::record);

        assertEquals(SessionState.STOPPING, last().getState());
        assertEquals(1, workload.startCount);
    }

    @Test
    public void ensureRunningWithoutWorkloadFailsHonestly() {
        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals("no runtime workload registered", last().getFailureReason());
        assertFalse(last().isRuntimeRunning());
    }

    // ---- liveness canary results (ADR-0062) ----

    @Test
    public void livenessMissesBelowThresholdLeaveSessionRunning() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        int before = published.size();

        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);

        // A miss is a bookkeeping detail, not a state: nothing is republished
        // and the session still reports itself as running.
        assertEquals(before, published.size());
        assertTrue(controller.status().isRuntimeRunning());
    }

    @Test
    public void livenessThresholdFlipToUnresponsiveAndBackOnFirstSuccess() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);

        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);

        assertEquals(SessionState.UNRESPONSIVE, last().getState());
        assertFalse("a wedged session must never claim RUNNING",
                last().isRuntimeRunning());
        // The endpoint is preserved: the guest is still up, just not answering.
        assertEquals(22022, last().getEndpoint().getPort());

        controller.onLivenessProbe(true, this::record);

        assertEquals(SessionState.RUNNING, last().getState());
        assertTrue(last().isRuntimeRunning());
    }

    @Test
    public void livenessSuccessWhileRunningPublishesNothing() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        int before = published.size();

        controller.onLivenessProbe(true, this::record);

        assertEquals(before, published.size());
    }

    @Test
    public void livenessResultIsIgnoredWithoutASession() {
        controller.onLivenessProbe(false, this::record);

        assertTrue(published.isEmpty());
        assertEquals(SessionState.NOT_STARTED, controller.status().getState());
    }

    @Test
    public void stopFromUnresponsiveTransitionsToStoppingAndDelegates() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        assertEquals(SessionState.UNRESPONSIVE, last().getState());

        controller.stop(this::record);

        assertEquals(SessionState.STOPPING, last().getState());
        assertEquals(1, workload.stopCount);
    }

    @Test
    public void workloadStoppedWhileUnresponsiveFailsHonestly() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);

        controller.onWorkloadStopped(this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals("unresponsive session ended", last().getFailureReason());
    }

    @Test
    public void workloadFailedWhileStoppingEndsFailedNeverStopped() {
        // The forced-stop survivor path (ADR-0062): when the workload reports
        // that its process is still alive, the session ends FAILED with the
        // typed reason — STOPPED must never be published over a live process.
        // The typed survivor report also marks the status so a user-requested
        // restart can escalate it (ADR-0063).
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.stop(this::record);
        assertEquals(SessionState.STOPPING, last().getState());

        controller.onWorkloadStopSurvived(
                "guest session workload is still alive or still holds the "
                        + "fixed SSH port after forced termination; tap "
                        + "Restart Linux to restart the app itself and "
                        + "recover, or force-stop it in Settings only if "
                        + "that fails",
                this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals(
                "guest session workload is still alive or still holds the "
                        + "fixed SSH port after forced termination; tap "
                        + "Restart Linux to restart the app itself and "
                        + "recover, or force-stop it in Settings only if "
                        + "that fails",
                last().getFailureReason());
        assertTrue("the survivor report must mark the published status",
                last().survivedStop());
        for (HostRuntimeStatus status : published) {
            assertFalse("a failed stop must never publish STOPPED",
                    status.getState() == SessionState.STOPPED);
        }
    }

    @Test
    public void genericFailureDoesNotMarkTheStatusAsSurvivor() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.stop(this::record);

        controller.onWorkloadFailed("guest sshd exited unexpectedly", this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertFalse("an ordinary failure is never the escalatable survivor",
                last().survivedStop());
    }

    @Test
    public void survivorFlagClearsOnTheNextStart() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.stop(this::record);
        controller.onWorkloadStopSurvived("survived", this::record);
        assertTrue(last().survivedStop());

        // A new session must never inherit the flag: the escalation decision
        // belongs to the failure that produced it, not the next attempt.
        controller.start(APP_ID, VERSION, "sess-2", this::record);

        assertEquals(SessionState.STARTING, last().getState());
        assertFalse(last().survivedStop());
    }

    @Test
    public void survivorReportOutsideAStopIsIgnored() {
        // The typed survivor report is only the answer to a stop in flight;
        // delivered on a live session it must not mark a healthy FAILED
        // transition as escalatable.
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        int publishes = published.size();

        controller.onWorkloadStopSurvived("spurious survivor report", this::record);

        assertEquals("a survivor report with no stop in flight is ignored",
                publishes, published.size());
        assertEquals(SessionState.RUNNING, last().getState());
        assertFalse(last().survivedStop());
    }

    @Test
    public void reconcileStoredRestoresSurvivorFlagFromPersistedReason() {
        // The survivor flag is transient, but its reason is persisted: after
        // a process death the restored FAILED still offers its Restart
        // action (ADR-0063), so an armed-but-undelivered revival does not
        // leave the user a dead button.
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.FAILED,
                null, 50L, 60L, GuestSshdWorkload.STOP_SURVIVED_REASON, 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertTrue(last().survivedStop());
    }

    @Test
    public void reconcileStoredOrdinaryFailureStaysUnmarked() {
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.FAILED,
                null, 50L, 60L, "guest exited before readiness", 0);

        controller.reconcileStored(stored, this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertFalse(last().survivedStop());
    }

    @Test
    public void workloadFailedAfterStopFromUnresponsiveEndsFailed() {
        // The Restart path's failure leg: a wedged session whose workload
        // survives the forced stop ends FAILED so the user gets the named
        // recovery instead of a false STOPPED.
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        assertEquals(SessionState.UNRESPONSIVE, last().getState());
        controller.stop(this::record);
        assertEquals(SessionState.STOPPING, last().getState());

        controller.onWorkloadFailed("workload survived forced stop", this::record);

        assertEquals(SessionState.FAILED, last().getState());
        assertEquals("workload survived forced stop", last().getFailureReason());
    }

    @Test
    public void unresponsiveStillRequiresALiveWorkload() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);

        // A wedged session is still supervised: service destroy must stop it.
        assertTrue(controller.requiresLiveWorkload());
    }

    @Test
    public void ensureRunningIsIdempotentWhileUnresponsive() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.ensureRunning(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        assertEquals(SessionState.UNRESPONSIVE, last().getState());

        controller.ensureRunning(APP_ID, VERSION, "sess-2", this::record);

        // A foreground event never resurrects a wedged session: only the
        // user's Restart takes it through STOPPING first.
        assertEquals(SessionState.UNRESPONSIVE, last().getState());
        assertEquals(1, workload.startCount);
        assertEquals(SESSION, controller.status().getSnapshot().getSessionId());
    }

    @Test
    public void reconcileStoredDemotesPersistedUnresponsiveToFailed() {
        SessionSnapshot stored = new SessionSnapshot(
                SESSION, APP_ID, VERSION, SessionState.UNRESPONSIVE,
                new RuntimePort("127.0.0.1", 22022), 50L, 60L, "", 0);

        controller.reconcileStored(stored, this::record);

        // A wedged session that died with the process is FAILED, never a
        // false RUNNING and never resurrected as still-unresponsive.
        assertEquals(SessionState.FAILED, last().getState());
        assertFalse(last().isRuntimeRunning());
    }

    @Test
    public void livenessCountResetsForANewSession() {
        RecordingWorkload workload = new RecordingWorkload();
        registry.register(workload);
        controller.start(APP_ID, VERSION, SESSION, this::record);
        controller.onReadiness(readyFrame(new RuntimePort("127.0.0.1", 22022)), this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);
        assertEquals(SessionState.UNRESPONSIVE, last().getState());
        controller.stop(this::record);
        controller.onWorkloadStopped(this::record);

        controller.start(APP_ID, VERSION, "sess-2", this::record);
        ReadinessFrame frame = new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA, APP_ID, VERSION, "sess-2",
                new RuntimePort("127.0.0.1", 22022), ReadinessHealth.HEALTHY, 100L);
        controller.onReadiness(frame, this::record);
        int before = published.size();

        // The new session starts with a clean counter: two misses do not
        // inherit the previous wedge's threshold.
        controller.onLivenessProbe(false, this::record);
        controller.onLivenessProbe(false, this::record);

        assertEquals(before, published.size());
        assertTrue(controller.status().isRuntimeRunning());
    }

    private void record(HostRuntimeStatus status) {
        published.add(status);
    }

    private HostRuntimeStatus last() {
        return published.get(published.size() - 1);
    }

    private static ReadinessFrame readyFrame(RuntimePort endpoint) {
        return new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA, APP_ID, VERSION, SESSION,
                endpoint, ReadinessHealth.HEALTHY, 100L);
    }

    private static final class MutableClock implements LongSupplier {
        long now = 1_000L;

        @Override
        public long getAsLong() {
            return now++;
        }
    }

    private static final class RecordingWorkload implements RuntimeWorkload {
        int startCount;
        int stopCount;

        @Override
        public String workloadId() {
            return "ubuntu-base-arm64/ssh";
        }

        @Override
        public void start(SessionSnapshot session, WorkloadListener listener) {
            startCount++;
        }

        @Override
        public void stop(WorkloadListener listener) {
            stopCount++;
        }
    }
}
