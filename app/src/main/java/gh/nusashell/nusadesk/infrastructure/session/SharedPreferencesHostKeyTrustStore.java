package gh.nusashell.nusadesk.infrastructure.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * App-private {@link SharedPreferences} implementation of the
 * {@link HostKeyTrustStore} port.
 *
 * <p>Trust decisions are not secrets: they persist as validated plain metadata
 * keyed by the verifier's host scope ({@code host:port}). Corrupt entries are
 * dropped and reported as absent so a damaged store falls back to the explicit
 * user trust path rather than silently authorising a key. Writes use
 * {@link SharedPreferences.Editor#commit()} so a crash cannot leave a
 * half-written trust record.</p>
 */
public final class SharedPreferencesHostKeyTrustStore implements HostKeyTrustStore {

    private static final String PREFERENCES = "host_key_trust";

    private final SharedPreferences preferences;
    private final HostKeyRecordCodec codec;

    public SharedPreferencesHostKeyTrustStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        this.codec = new HostKeyRecordCodec();
    }

    /** Returns the stored record for {@code host}, or {@code null} if absent/corrupt. */
    @Override
    public HostKeyRecord load(String host) {
        String prefix = prefix(host);
        Map<String, String> fields = new HashMap<>();
        fields.put(HostKeyRecordCodec.FINGERPRINT,
                preferences.getString(prefix + HostKeyRecordCodec.FINGERPRINT, null));
        fields.put(HostKeyRecordCodec.TRUST,
                preferences.getString(prefix + HostKeyRecordCodec.TRUST, null));
        fields.put(HostKeyRecordCodec.TRUSTED_SINCE,
                preferences.getString(prefix + HostKeyRecordCodec.TRUSTED_SINCE, null));
        if (fields.get(HostKeyRecordCodec.FINGERPRINT) == null) {
            return null;
        }
        HostKeyRecord record = codec.fromFields(host, fields);
        if (record == null) {
            // Corrupt trust data must never act as a verified record.
            clear(host);
            return null;
        }
        return record;
    }

    @Override
    @SuppressLint("ApplySharedPref")
    public void save(HostKeyRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        Map<String, String> fields = codec.toFields(record);
        String prefix = prefix(record.getHost());
        boolean committed = preferences.edit()
                .putString(prefix + HostKeyRecordCodec.FINGERPRINT,
                        fields.get(HostKeyRecordCodec.FINGERPRINT))
                .putString(prefix + HostKeyRecordCodec.TRUST,
                        fields.get(HostKeyRecordCodec.TRUST))
                .putString(prefix + HostKeyRecordCodec.TRUSTED_SINCE,
                        fields.get(HostKeyRecordCodec.TRUSTED_SINCE))
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist host key trust record");
        }
    }

    @Override
    @SuppressLint("ApplySharedPref")
    public void clear(String host) {
        String prefix = prefix(host);
        preferences.edit()
                .remove(prefix + HostKeyRecordCodec.FINGERPRINT)
                .remove(prefix + HostKeyRecordCodec.TRUST)
                .remove(prefix + HostKeyRecordCodec.TRUSTED_SINCE)
                .commit();
    }

    private static String prefix(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        return host.replaceAll("[^A-Za-z0-9._-]", "_") + ".";
    }
}
