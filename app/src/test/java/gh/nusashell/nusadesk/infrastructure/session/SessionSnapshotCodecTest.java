package gh.nusashell.nusadesk.infrastructure.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SessionSnapshotCodecTest {

    private final SessionSnapshotCodec codec = new SessionSnapshotCodec();

    @Test
    public void roundTripPreservesAllFieldsWithEndpoint() {
        SessionSnapshot snapshot = new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "24.04.5", SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 43210), 100L, 200L, "", 2);
        SessionSnapshot restored = codec.fromFields(codec.toFields(snapshot));
        // RuntimePort is a value object without equals; compare fields directly.
        assertEquals(snapshot.getSessionId(), restored.getSessionId());
        assertEquals(snapshot.getAppId(), restored.getAppId());
        assertEquals(snapshot.getAppVersion(), restored.getAppVersion());
        assertEquals(snapshot.getState(), restored.getState());
        assertEquals("127.0.0.1", restored.getEndpoint().getHost());
        assertEquals(43210, restored.getEndpoint().getPort());
        assertEquals(snapshot.getStartedAtEpochMillis(), restored.getStartedAtEpochMillis());
        assertEquals(snapshot.getUpdatedAtEpochMillis(), restored.getUpdatedAtEpochMillis());
        assertEquals(snapshot.getFailureReason(), restored.getFailureReason());
        assertEquals(snapshot.getReconnectAttempts(), restored.getReconnectAttempts());
    }

    @Test
    public void roundTripPreservesAllFieldsWithoutEndpoint() {
        SessionSnapshot snapshot = new SessionSnapshot(
                "sess-2", "ubuntu-base-arm64", "24.04.5", SessionState.STARTING,
                null, 100L, 100L, "", 0);
        SessionSnapshot restored = codec.fromFields(codec.toFields(snapshot));
        assertEquals(snapshot, restored);
        assertNull(restored.getEndpoint());
    }

    @Test
    public void roundTripPreservesFailureReason() {
        SessionSnapshot snapshot = new SessionSnapshot(
                "sess-3", "ubuntu-base-arm64", "24.04.5", SessionState.FAILED,
                null, 100L, 300L, "bridge did not become healthy", 0);
        assertEquals(snapshot, codec.fromFields(codec.toFields(snapshot)));
    }

    @Test
    public void missingFieldFailsClosed() {
        Map<String, String> fields = codec.toFields(new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "24.04.5", SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 43210), 100L, 200L, "", 0));
        fields.remove(SessionSnapshotCodec.STATE);
        assertNull(codec.fromFields(fields));
    }

    @Test
    public void unknownStateFailsClosed() {
        Map<String, String> fields = runningFields();
        fields.put(SessionSnapshotCodec.STATE, "DEFINITELY_RUNNING_FOREVER");
        assertNull(codec.fromFields(fields));
    }

    @Test
    public void nonLoopbackEndpointFailsClosed() {
        Map<String, String> fields = runningFields();
        fields.put(SessionSnapshotCodec.ENDPOINT_HOST, "192.168.1.5");
        assertNull(codec.fromFields(fields));
    }

    @Test
    public void updatedBeforeStartedFailsClosed() {
        Map<String, String> fields = runningFields();
        fields.put(SessionSnapshotCodec.UPDATED_AT, "1");
        assertNull(codec.fromFields(fields));
    }

    @Test
    public void nullFieldsReturnNull() {
        assertNull(codec.fromFields(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSnapshotToFieldsThrows() {
        codec.toFields(null);
    }

    private Map<String, String> runningFields() {
        return codec.toFields(new SessionSnapshot(
                "sess-1", "ubuntu-base-arm64", "24.04.5", SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 43210), 100L, 200L, "", 0));
    }
}
