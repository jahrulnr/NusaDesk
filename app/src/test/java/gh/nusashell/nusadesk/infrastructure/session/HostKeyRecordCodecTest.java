package gh.nusashell.nusadesk.infrastructure.session;

import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.HostKeyTrust;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HostKeyRecordCodecTest {

    private static final String FINGERPRINT = "SHA256:abcDEF123+/xyz==";
    private final HostKeyRecordCodec codec = new HostKeyRecordCodec();

    @Test
    public void roundTripPreservesAllFields() {
        HostKeyRecord record = new HostKeyRecord(
                "127.0.0.1:22222", new HostKeyFingerprint(FINGERPRINT),
                HostKeyTrust.VERIFIED, 1_700_000_000_000L);
        Map<String, String> fields = codec.toFields(record);
        HostKeyRecord restored = codec.fromFields("127.0.0.1:22222", fields);
        assertEquals(record, restored);
    }

    @Test
    public void missingFingerprintFailsClosed() {
        Map<String, String> fields = new HashMap<>();
        fields.put(HostKeyRecordCodec.TRUST, HostKeyTrust.VERIFIED.name());
        fields.put(HostKeyRecordCodec.TRUSTED_SINCE, "1000");
        assertNull(codec.fromFields("h:1", fields));
    }

    @Test
    public void malformedFingerprintFailsClosed() {
        Map<String, String> fields = new HashMap<>();
        fields.put(HostKeyRecordCodec.FINGERPRINT, "not-a-fingerprint");
        fields.put(HostKeyRecordCodec.TRUST, HostKeyTrust.VERIFIED.name());
        fields.put(HostKeyRecordCodec.TRUSTED_SINCE, "1000");
        assertNull(codec.fromFields("h:1", fields));
    }

    @Test
    public void unknownTrustEnumFailsClosed() {
        Map<String, String> fields = new HashMap<>();
        fields.put(HostKeyRecordCodec.FINGERPRINT, FINGERPRINT);
        fields.put(HostKeyRecordCodec.TRUST, "TRUSTED_FOREVER");
        fields.put(HostKeyRecordCodec.TRUSTED_SINCE, "1000");
        assertNull(codec.fromFields("h:1", fields));
    }

    @Test
    public void negativeTimestampFailsClosed() {
        Map<String, String> fields = new HashMap<>();
        fields.put(HostKeyRecordCodec.FINGERPRINT, FINGERPRINT);
        fields.put(HostKeyRecordCodec.TRUST, HostKeyTrust.VERIFIED.name());
        fields.put(HostKeyRecordCodec.TRUSTED_SINCE, "-5");
        assertNull(codec.fromFields("h:1", fields));
    }

    @Test
    public void blankHostFailsClosed() {
        Map<String, String> fields = codec.toFields(new HostKeyRecord(
                "h:1", new HostKeyFingerprint(FINGERPRINT), HostKeyTrust.VERIFIED, 1L));
        assertNull(codec.fromFields(" ", fields));
        assertNull(codec.fromFields(null, fields));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullRecordToFieldsThrows() {
        codec.toFields(null);
    }
}
