package gh.nusashell.nusadesk.infrastructure.session;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class SessionMetadataCodecTest {
    private final SessionMetadataCodec codec = new SessionMetadataCodec();

    @Test
    public void roundTripsMetadataThroughFields() {
        SessionMetadata original = new SessionMetadata(
                "ssh-prod", "10.0.0.1", 2222, "admin", 100L, 200L, true);
        Map<String, String> fields = codec.toFields(original);
        SessionMetadata rebuilt = codec.fromFields("ssh-prod", fields);
        assertEquals(original, rebuilt);
    }

    @Test
    public void trimsWhitespaceInStringFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put(SessionMetadataCodec.HOST, "  host.example  ");
        fields.put(SessionMetadataCodec.PORT, " 22 ");
        fields.put(SessionMetadataCodec.USERNAME, " user ");
        fields.put(SessionMetadataCodec.CREATED_AT, " 1 ");
        fields.put(SessionMetadataCodec.LAST_CONNECTED_AT, " 2 ");
        fields.put(SessionMetadataCodec.ACTIVE, " true ");
        SessionMetadata rebuilt = codec.fromFields("id", fields);
        assertEquals("host.example", rebuilt.getHost());
        assertEquals(22, rebuilt.getPort());
        assertEquals("user", rebuilt.getUsername());
        assertEquals(1L, rebuilt.getCreatedAtEpochMillis());
        assertEquals(2L, rebuilt.getLastConnectedAtEpochMillis());
    }

    @Test
    public void returnsNullWhenAFieldIsMissing() {
        Map<String, String> fields = new HashMap<>();
        fields.put(SessionMetadataCodec.HOST, "host");
        // port missing
        assertNull(codec.fromFields("id", fields));
    }

    @Test
    public void returnsNullWhenPortIsMalformed() {
        Map<String, String> fields = new HashMap<>();
        fields.put(SessionMetadataCodec.HOST, "host");
        fields.put(SessionMetadataCodec.PORT, "not-a-port");
        fields.put(SessionMetadataCodec.USERNAME, "user");
        fields.put(SessionMetadataCodec.CREATED_AT, "1");
        fields.put(SessionMetadataCodec.LAST_CONNECTED_AT, "0");
        fields.put(SessionMetadataCodec.ACTIVE, "false");
        assertNull(codec.fromFields("id", fields));
    }

    @Test
    public void returnsNullForOutOfRangePort() {
        Map<String, String> fields = new HashMap<>();
        fields.put(SessionMetadataCodec.HOST, "host");
        fields.put(SessionMetadataCodec.PORT, "70000");
        fields.put(SessionMetadataCodec.USERNAME, "user");
        fields.put(SessionMetadataCodec.CREATED_AT, "1");
        fields.put(SessionMetadataCodec.LAST_CONNECTED_AT, "0");
        fields.put(SessionMetadataCodec.ACTIVE, "false");
        assertNull(codec.fromFields("id", fields));
    }

    @Test
    public void rejectsBlankSessionId() {
        try {
            codec.fromFields("  ", new HashMap<String, String>());
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullMetadataOnEncode() {
        try {
            codec.toFields(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
