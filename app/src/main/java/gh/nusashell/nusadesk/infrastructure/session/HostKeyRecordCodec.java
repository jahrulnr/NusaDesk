package gh.nusashell.nusadesk.infrastructure.session;

import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.HostKeyTrust;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates and serialises {@link HostKeyRecord} into flat string fields for
 * {@link SharedPreferencesHostKeyTrustStore}.
 *
 * <p>Pure Java with no Android or I/O so the corruption and validation rules
 * are unit-testable. A malformed field yields {@code null} rather than a
 * partially trusted record — a corrupt store must never silently authorise a
 * host key.</p>
 */
public final class HostKeyRecordCodec {

    public static final String FINGERPRINT = "fingerprint";
    public static final String TRUST = "trust";
    public static final String TRUSTED_SINCE = "trustedSince";

    public Map<String, String> toFields(HostKeyRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put(FINGERPRINT, record.getFingerprint().getValue());
        fields.put(TRUST, record.getTrust().name());
        fields.put(TRUSTED_SINCE, Long.toString(record.getTrustedSinceEpochMillis()));
        return fields;
    }

    /**
     * Rebuilds a record for {@code host} from stored fields, or returns
     * {@code null} when any field is missing or invalid.
     */
    public HostKeyRecord fromFields(String host, Map<String, String> fields) {
        if (host == null || host.trim().isEmpty() || fields == null) {
            return null;
        }
        String fingerprint = fields.get(FINGERPRINT);
        String trust = fields.get(TRUST);
        String trustedSince = fields.get(TRUSTED_SINCE);
        if (fingerprint == null || trust == null || trustedSince == null) {
            return null;
        }
        try {
            long since = Long.parseLong(trustedSince);
            if (since < 0) {
                return null;
            }
            return new HostKeyRecord(
                    host,
                    new HostKeyFingerprint(fingerprint),
                    HostKeyTrust.valueOf(trust),
                    since);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
