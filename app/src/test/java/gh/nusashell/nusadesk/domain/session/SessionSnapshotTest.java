package gh.nusashell.nusadesk.domain.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class SessionSnapshotTest {
    private RuntimePort endpoint() {
        return new RuntimePort("127.0.0.1", 10994);
    }

    @Test
    public void acceptsValidSnapshotWithoutEndpoint() {
        new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "0.1.0",
                SessionState.NOT_STARTED, null,
                10L, 10L, "", 0);
    }

    @Test
    public void acceptsValidSnapshotWithEndpoint() {
        new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "0.1.0",
                SessionState.RUNNING, endpoint(),
                10L, 20L, "", 0);
    }

    @Test
    public void rejectsBlankSessionId() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionSnapshot(
                        "", "ubuntu-base-arm64", "0.1.0",
                        SessionState.NOT_STARTED, null,
                        10L, 10L, "", 0));
    }

    @Test
    public void rejectsNullState() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionSnapshot(
                        "sess-1", "ubuntu-base-arm64", "0.1.0",
                        null, null,
                        10L, 10L, "", 0));
    }

    @Test
    public void rejectsUpdatedBeforeStarted() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionSnapshot(
                        "sess-1", "ubuntu-base-arm64", "0.1.0",
                        SessionState.RUNNING, endpoint(),
                        20L, 10L, "", 0));
    }

    @Test
    public void rejectsNegativeReconnectAttempts() {
        assertThrows(IllegalArgumentException.class, () ->
                new SessionSnapshot(
                        "sess-1", "ubuntu-base-arm64", "0.1.0",
                        SessionState.RECONNECTING, endpoint(),
                        10L, 10L, "", -1));
    }
}
