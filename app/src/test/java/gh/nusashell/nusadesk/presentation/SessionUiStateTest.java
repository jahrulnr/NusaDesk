package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.domain.session.SessionState;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionUiStateTest {

    @Test
    public void everyDomainSessionStateMapsToRenderableCopy() {
        for (SessionState state : SessionState.values()) {
            SessionUiState ui = SessionUiState.from(state, null);

            assertNotEquals(state.name(), 0, ui.getLabelRes());
            assertNotEquals(state.name(), 0, ui.getBadgeRes());
            assertNotEquals(state.name(), 0, ui.getDetailRes());
        }
    }

    @Test
    public void aMissingStatusIsAnHonestStoppedState() {
        SessionUiState ui = SessionUiState.from(null, null);

        assertEquals(SessionUiState.Kind.STOPPED, ui.getKind());
        assertFalse(ui.isHealthy());
    }

    @Test
    public void onlyRunningIsHealthy() {
        for (SessionState state : SessionState.values()) {
            SessionUiState ui = SessionUiState.from(state, null);
            boolean running = state == SessionState.RUNNING || state == SessionState.RECONNECTING;

            assertEquals(state.name(), running, ui.isHealthy());
        }
    }

    /**
     * Regression guard for the product decision that Linux is background
     * infrastructure: the shared session vocabulary must not expose a lifecycle
     * action at all, so no surface can grow a start/stop control again
     * (ADR-0013/ADR-0014).
     */
    @Test
    public void theSharedSessionVocabularyExposesNoLifecycleAction() {
        for (Method method : SessionUiState.class.getMethods()) {
            assertNotEquals("SessionUiState must not expose a session action",
                    "getAction", method.getName());
            assertNotEquals("SessionUiState must not expose an action-enabled flag",
                    "isActionEnabled", method.getName());
        }
        for (Class<?> nested : SessionUiState.class.getDeclaredClasses()) {
            assertNotEquals("SessionUiState must not carry an Action vocabulary",
                    "Action", nested.getSimpleName());
        }
    }

    @Test
    public void failureCarriesTheHostReason() {
        SessionUiState ui = SessionUiState.from(SessionState.FAILED, "guest daemon exited");

        assertEquals(SessionUiState.Kind.FAILED, ui.getKind());
        assertEquals("guest daemon exited", ui.getFailureReason());
        assertFalse(ui.isHealthy());
    }

    @Test
    public void failureWithoutAReasonDoesNotInventOne() {
        assertNull(SessionUiState.from(SessionState.FAILED, null).getFailureReason());
        assertNull(SessionUiState.from(SessionState.FAILED, "").getFailureReason());
    }

    @Test
    public void unknownMatchesTheStoppedMappingSoTheLauncherNeverBlanks() {
        SessionUiState unknown = SessionUiState.unknown();

        assertEquals(SessionUiState.from(SessionState.NOT_STARTED, null).getLabelRes(),
                unknown.getLabelRes());
        assertEquals(SessionUiState.Kind.STOPPED, unknown.getKind());
    }

    @Test
    public void reconnectingStaysVisuallyRunning() {
        SessionUiState ui = SessionUiState.from(SessionState.RECONNECTING, null);

        assertEquals(SessionUiState.Kind.RUNNING, ui.getKind());
        assertTrue(ui.isHealthy());
    }
}
