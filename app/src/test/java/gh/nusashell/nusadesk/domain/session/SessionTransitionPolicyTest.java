package gh.nusashell.nusadesk.domain.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SessionTransitionPolicyTest {
    private SessionSnapshot notStarted() {
        return new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "0.1.0",
                SessionState.NOT_STARTED, null,
                10L, 10L, "", 0);
    }

    @Test
    public void advancesThroughValidLifecycle() {
        SessionSnapshot starting = SessionTransitionPolicy.attempt(
                notStarted(), SessionState.STARTING, 20L, "");
        assertEquals(SessionState.STARTING, starting.getState());

        SessionSnapshot running = SessionTransitionPolicy.attempt(
                starting, SessionState.RUNNING, 30L, "");
        assertEquals(SessionState.RUNNING, running.getState());
        assertEquals(0, running.getReconnectAttempts());
    }

    @Test
    public void reconnectIncrementsAttemptsAndRunningResetsThem() {
        SessionSnapshot starting = SessionTransitionPolicy.attempt(
                notStarted(), SessionState.STARTING, 20L, "");
        SessionSnapshot running = SessionTransitionPolicy.attempt(
                starting, SessionState.RUNNING, 30L, "");
        SessionSnapshot reconnecting = SessionTransitionPolicy.attempt(
                running, SessionState.RECONNECTING, 40L, "");
        assertEquals(1, reconnecting.getReconnectAttempts());

        SessionSnapshot runningAgain = SessionTransitionPolicy.attempt(
                reconnecting, SessionState.RUNNING, 50L, "");
        assertEquals(0, runningAgain.getReconnectAttempts());
    }

    @Test
    public void failedTransitionRecordsReason() {
        SessionSnapshot starting = SessionTransitionPolicy.attempt(
                notStarted(), SessionState.STARTING, 20L, "");
        SessionSnapshot failed = SessionTransitionPolicy.attempt(
                starting, SessionState.FAILED, 30L, "guest exited");
        assertEquals("guest exited", failed.getFailureReason());
    }

    @Test
    public void nonFailedTransitionClearsReason() {
        SessionSnapshot starting = SessionTransitionPolicy.attempt(
                notStarted(), SessionState.STARTING, 20L, "");
        SessionSnapshot failed = SessionTransitionPolicy.attempt(
                starting, SessionState.FAILED, 30L, "guest exited");
        SessionSnapshot recovering = SessionTransitionPolicy.attempt(
                failed, SessionState.RECOVERING, 40L, "");
        assertEquals("", recovering.getFailureReason());
    }

    @Test
    public void cancelTransitionsToCancelled() {
        SessionSnapshot starting = SessionTransitionPolicy.attempt(
                notStarted(), SessionState.STARTING, 20L, "");
        SessionSnapshot cancelled = SessionTransitionPolicy.cancel(starting, 30L, "user");
        assertEquals(SessionState.CANCELLED, cancelled.getState());
        assertTrue(cancelled.isCancelled());
    }

    @Test
    public void invalidLifecycleTransitionThrows() {
        SessionSnapshot snapshot = notStarted();
        assertThrows(IllegalStateException.class, () ->
                SessionTransitionPolicy.attempt(snapshot, SessionState.RUNNING, 20L, ""));
    }

    @Test
    public void cannotCancelTerminalState() {
        SessionSnapshot stopped = new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "0.1.0",
                SessionState.STOPPED, new RuntimePort("127.0.0.1", 10994),
                10L, 10L, "", 0);
        assertThrows(IllegalStateException.class, () ->
                SessionTransitionPolicy.cancel(stopped, 20L, "user"));
    }

    @Test
    public void preservesEndpointAcrossTransitions() {
        RuntimePort endpoint = new RuntimePort("127.0.0.1", 10994);
        SessionSnapshot running = new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "0.1.0",
                SessionState.RUNNING, endpoint,
                10L, 10L, "", 0);
        SessionSnapshot reconnecting = SessionTransitionPolicy.attempt(
                running, SessionState.RECONNECTING, 20L, "");
        assertEquals(endpoint, reconnecting.getEndpoint());
    }
}
