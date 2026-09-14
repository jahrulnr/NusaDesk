package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The launcher's readiness indicator is pure policy, so it is asserted without a
 * device: which published truth wins, and what the launcher says when Linux is
 * stopped or failed instead of offering a start button.
 */
public class LauncherStatusTest {

    private static RuntimeSnapshot runtime(RuntimeState state, String detail) {
        return new RuntimeSnapshot("ubuntu-base-arm64", state, detail, 0, 1_000L);
    }

    private static HostRuntimeStatus session(SessionState state, String failureReason) {
        return new HostRuntimeStatus(
                new SessionSnapshot("session-1", "ubuntu-base-arm64", "24.04.5", state,
                        null, 1_000L, 2_000L, failureReason, 0),
                true, "ubuntu-base-arm64");
    }

    private static LauncherStatus status(
            RuntimeState install, GuestSshUiState service, SessionState session) {
        return LauncherStatus.of(
                runtime(install, "install detail"), service, session(session, null));
    }

    @Test
    public void anUninstalledSystemIsSetupNotASessionState() {
        LauncherStatus status = LauncherStatus.of(
                runtime(RuntimeState.NOT_INSTALLED, ""), GuestSshUiState.missing(),
                session(SessionState.RUNNING, null));

        assertEquals(LauncherStatus.Kind.SETUP, status.getKind());
        assertFalse(status.isReady());
    }

    @Test
    public void anInstalledSystemWithoutTheTerminalComponentIsStillSetup() {
        for (GuestSshUiState service : new GuestSshUiState[]{
                GuestSshUiState.missing(),
                GuestSshUiState.installing("half way"),
                GuestSshUiState.failed("disk full")}) {
            LauncherStatus status = LauncherStatus.of(
                    runtime(RuntimeState.READY, ""), service,
                    session(SessionState.NOT_STARTED, null));

            assertEquals(service.getKind().name(), LauncherStatus.Kind.SETUP, status.getKind());
            assertNotEquals(service.getKind().name(), 0, status.getDetailRes());
        }
    }

    @Test
    public void aMissingServiceAndAnInstallingServiceSayDifferentThings() {
        LauncherStatus missing = LauncherStatus.of(
                runtime(RuntimeState.READY, ""), GuestSshUiState.missing(), null);
        LauncherStatus installing = LauncherStatus.of(
                runtime(RuntimeState.READY, ""), GuestSshUiState.installing(""), null);

        assertNotEquals(missing.getDetailRes(), installing.getDetailRes());
    }

    @Test
    public void aRunningSessionIsReady() {
        LauncherStatus status = status(
                RuntimeState.READY, GuestSshUiState.installed(), SessionState.RUNNING);

        assertEquals(LauncherStatus.Kind.READY, status.getKind());
        assertTrue(status.isReady());
        assertNull(status.getFailureReason());
    }

    @Test
    public void inFlightStatesAreStartingOrStoppingNeverStopped() {
        assertEquals(LauncherStatus.Kind.STARTING, status(RuntimeState.READY,
                GuestSshUiState.installed(), SessionState.STARTING).getKind());
        assertEquals(LauncherStatus.Kind.STARTING, status(RuntimeState.READY,
                GuestSshUiState.installed(), SessionState.RECOVERING).getKind());
        assertEquals(LauncherStatus.Kind.STOPPING, status(RuntimeState.READY,
                GuestSshUiState.installed(), SessionState.STOPPING).getKind());
    }

    /**
     * The product rule this test protects: a stopped session states that Linux
     * starts again from the next app launch, and the pill has no action to offer.
     */
    @Test
    public void aStoppedSessionSaysHowLinuxStartsAgain() {
        for (SessionState state : new SessionState[]{
                SessionState.NOT_STARTED, SessionState.STOPPED, SessionState.CANCELLED}) {
            LauncherStatus status = status(
                    RuntimeState.READY, GuestSshUiState.installed(), state);

            assertEquals(state.name(), LauncherStatus.Kind.STOPPED, status.getKind());
            assertNotEquals(state.name(), 0, status.getDetailRes());
            assertFalse(state.name(), status.isReady());
        }
    }

    @Test
    public void aFailedSessionCarriesTheHostReasonAndKeepsItOptional() {
        LauncherStatus failed = LauncherStatus.of(
                runtime(RuntimeState.READY, ""), GuestSshUiState.installed(),
                session(SessionState.FAILED, "fixed port already in use"));

        assertEquals(LauncherStatus.Kind.FAILED, failed.getKind());
        assertEquals("fixed port already in use", failed.getFailureReason());
        assertNull(LauncherStatus.of(
                runtime(RuntimeState.READY, ""), GuestSshUiState.installed(),
                session(SessionState.FAILED, "   ")).getFailureReason());
    }

    @Test
    public void aMissingSessionStatusIsTheHonestStoppedState() {
        LauncherStatus status = LauncherStatus.of(
                runtime(RuntimeState.READY, ""), GuestSshUiState.installed(), null);

        assertEquals(LauncherStatus.Kind.STOPPED, status.getKind());
    }

    @Test
    public void everyKindIsRenderableInBothThemes() {
        for (LauncherStatus.Kind kind : LauncherStatus.Kind.values()) {
            LauncherStatus status = forKind(kind);

            assertNotEquals(kind.name(), 0, status.getLabelRes());
            assertNotEquals(kind.name(), 0, status.getDetailRes());
            assertNotEquals(kind.name(), 0, status.getBackgroundColorRes());
            assertNotEquals(kind.name(), 0, status.getForegroundColorRes());
        }
    }

    /** A regression guard: the readiness indicator must never grow an action. */
    @Test
    public void theReadinessIndicatorExposesNoAction() {
        for (Method method : LauncherStatus.class.getMethods()) {
            assertNotEquals("LauncherStatus must not expose a session action",
                    "getAction", method.getName());
        }
        for (Class<?> nested : LauncherStatus.class.getDeclaredClasses()) {
            assertNotEquals("LauncherStatus must not carry an Action vocabulary",
                    "Action", nested.getSimpleName());
        }
    }

    private static LauncherStatus forKind(LauncherStatus.Kind kind) {
        switch (kind) {
            case SETUP:
                return LauncherStatus.of(
                        runtime(RuntimeState.NOT_INSTALLED, ""), GuestSshUiState.missing(), null);
            case STARTING:
                return status(RuntimeState.READY, GuestSshUiState.installed(),
                        SessionState.STARTING);
            case READY:
                return status(RuntimeState.READY, GuestSshUiState.installed(),
                        SessionState.RUNNING);
            case STOPPING:
                return status(RuntimeState.READY, GuestSshUiState.installed(),
                        SessionState.STOPPING);
            case FAILED:
                return status(RuntimeState.READY, GuestSshUiState.installed(),
                        SessionState.FAILED);
            case STOPPED:
            default:
                return status(RuntimeState.READY, GuestSshUiState.installed(),
                        SessionState.STOPPED);
        }
    }
}
