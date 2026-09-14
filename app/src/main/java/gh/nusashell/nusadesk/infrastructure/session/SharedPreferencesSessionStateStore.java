package gh.nusashell.nusadesk.infrastructure.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.application.session.SessionStateStore;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;

import java.util.HashMap;
import java.util.Map;

/**
 * App-private {@link SharedPreferences} implementation of the
 * {@link SessionStateStore} port for the single local runtime session.
 *
 * <p>Exactly one snapshot — the current session — is persisted under a fixed
 * prefix. {@link #loadCurrent()} returns it without requiring a session id,
 * which is what the host service needs after a process restart: it can then
 * demote any live-requiring state to {@code FAILED} rather than resurrect a
 * false {@code RUNNING}. Corrupt entries are dropped and reported as absent.</p>
 */
public final class SharedPreferencesSessionStateStore implements SessionStateStore {

    private static final String PREFERENCES = "session_state";
    private static final String PREFIX = "current.";

    private final SharedPreferences preferences;
    private final SessionSnapshotCodec codec;

    public SharedPreferencesSessionStateStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        this.codec = new SessionSnapshotCodec();
    }

    /**
     * Returns the single persisted snapshot, or {@code null} if absent or
     * corrupt. Used by the host service during process-restart reconciliation.
     */
    public SessionSnapshot loadCurrent() {
        Map<String, String> fields = new HashMap<>();
        fields.put(SessionSnapshotCodec.SESSION_ID,
                preferences.getString(PREFIX + SessionSnapshotCodec.SESSION_ID, null));
        fields.put(SessionSnapshotCodec.APP_ID,
                preferences.getString(PREFIX + SessionSnapshotCodec.APP_ID, null));
        fields.put(SessionSnapshotCodec.APP_VERSION,
                preferences.getString(PREFIX + SessionSnapshotCodec.APP_VERSION, null));
        fields.put(SessionSnapshotCodec.STATE,
                preferences.getString(PREFIX + SessionSnapshotCodec.STATE, null));
        fields.put(SessionSnapshotCodec.ENDPOINT_HOST,
                preferences.getString(PREFIX + SessionSnapshotCodec.ENDPOINT_HOST, null));
        fields.put(SessionSnapshotCodec.ENDPOINT_PORT,
                preferences.getString(PREFIX + SessionSnapshotCodec.ENDPOINT_PORT, null));
        fields.put(SessionSnapshotCodec.STARTED_AT,
                preferences.getString(PREFIX + SessionSnapshotCodec.STARTED_AT, null));
        fields.put(SessionSnapshotCodec.UPDATED_AT,
                preferences.getString(PREFIX + SessionSnapshotCodec.UPDATED_AT, null));
        fields.put(SessionSnapshotCodec.FAILURE_REASON,
                preferences.getString(PREFIX + SessionSnapshotCodec.FAILURE_REASON, null));
        fields.put(SessionSnapshotCodec.RECONNECT_ATTEMPTS,
                preferences.getString(PREFIX + SessionSnapshotCodec.RECONNECT_ATTEMPTS, null));
        if (fields.get(SessionSnapshotCodec.SESSION_ID) == null) {
            return null;
        }
        SessionSnapshot snapshot = codec.fromFields(fields);
        if (snapshot == null) {
            clearCurrent();
            return null;
        }
        return snapshot;
    }

    /** Returns the snapshot only when it belongs to {@code sessionId}. */
    @Override
    public SessionSnapshot load(String sessionId) {
        SessionSnapshot snapshot = loadCurrent();
        return snapshot != null && snapshot.getSessionId().equals(sessionId)
                ? snapshot : null;
    }

    @Override
    @SuppressLint("ApplySharedPref")
    public void save(SessionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Map<String, String> fields = codec.toFields(snapshot);
        boolean committed = preferences.edit()
                .putString(PREFIX + SessionSnapshotCodec.SESSION_ID, fields.get(SessionSnapshotCodec.SESSION_ID))
                .putString(PREFIX + SessionSnapshotCodec.APP_ID, fields.get(SessionSnapshotCodec.APP_ID))
                .putString(PREFIX + SessionSnapshotCodec.APP_VERSION, fields.get(SessionSnapshotCodec.APP_VERSION))
                .putString(PREFIX + SessionSnapshotCodec.STATE, fields.get(SessionSnapshotCodec.STATE))
                .putString(PREFIX + SessionSnapshotCodec.ENDPOINT_HOST, fields.get(SessionSnapshotCodec.ENDPOINT_HOST))
                .putString(PREFIX + SessionSnapshotCodec.ENDPOINT_PORT, fields.get(SessionSnapshotCodec.ENDPOINT_PORT))
                .putString(PREFIX + SessionSnapshotCodec.STARTED_AT, fields.get(SessionSnapshotCodec.STARTED_AT))
                .putString(PREFIX + SessionSnapshotCodec.UPDATED_AT, fields.get(SessionSnapshotCodec.UPDATED_AT))
                .putString(PREFIX + SessionSnapshotCodec.FAILURE_REASON, fields.get(SessionSnapshotCodec.FAILURE_REASON))
                .putString(PREFIX + SessionSnapshotCodec.RECONNECT_ATTEMPTS, fields.get(SessionSnapshotCodec.RECONNECT_ATTEMPTS))
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist session state");
        }
    }

    /** Clears the stored snapshot when it belongs to {@code sessionId}. */
    @Override
    public void clear(String sessionId) {
        SessionSnapshot snapshot = loadCurrent();
        if (snapshot != null && snapshot.getSessionId().equals(sessionId)) {
            clearCurrent();
        }
    }

    @SuppressLint("ApplySharedPref")
    private void clearCurrent() {
        preferences.edit()
                .remove(PREFIX + SessionSnapshotCodec.SESSION_ID)
                .remove(PREFIX + SessionSnapshotCodec.APP_ID)
                .remove(PREFIX + SessionSnapshotCodec.APP_VERSION)
                .remove(PREFIX + SessionSnapshotCodec.STATE)
                .remove(PREFIX + SessionSnapshotCodec.ENDPOINT_HOST)
                .remove(PREFIX + SessionSnapshotCodec.ENDPOINT_PORT)
                .remove(PREFIX + SessionSnapshotCodec.STARTED_AT)
                .remove(PREFIX + SessionSnapshotCodec.UPDATED_AT)
                .remove(PREFIX + SessionSnapshotCodec.FAILURE_REASON)
                .remove(PREFIX + SessionSnapshotCodec.RECONNECT_ATTEMPTS)
                .commit();
    }
}
