package gh.nusashell.nusadesk.infrastructure.session;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Defensive persistence of non-secret {@link SessionMetadata} in app-private
 * SharedPreferences.
 *
 * <p>"Defensive" means: inputs are validated, writes are synchronous
 * ({@link SharedPreferences.Editor#commit()}) so a crash mid-save cannot leave a
 * half-written entry, and corrupt or partial stored data is dropped rather
 * than surfaced as a misleading session. This store never holds secrets; pair
 * it with {@link KeystoreCredentialVault} for passwords and private keys.
 */
public final class SharedPreferencesSessionMetadataStore {
    private static final String PREFERENCES = "session_metadata";
    private static final String KEY_ACTIVE_SESSION_ID = "activeSessionId";

    private final SharedPreferences preferences;
    private final SessionMetadataCodec codec;

    public SharedPreferencesSessionMetadataStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        this.codec = new SessionMetadataCodec();
    }

    /** Returns the stored metadata, or {@code null} if absent or corrupt. */
    public SessionMetadata load(String sessionId) {
        String prefix = prefix(sessionId);
        Map<String, String> fields = new HashMap<>();
        fields.put(SessionMetadataCodec.HOST, preferences.getString(prefix + SessionMetadataCodec.HOST, null));
        fields.put(SessionMetadataCodec.PORT, preferences.getString(prefix + SessionMetadataCodec.PORT, null));
        fields.put(SessionMetadataCodec.USERNAME, preferences.getString(prefix + SessionMetadataCodec.USERNAME, null));
        fields.put(SessionMetadataCodec.CREATED_AT, preferences.getString(prefix + SessionMetadataCodec.CREATED_AT, null));
        fields.put(SessionMetadataCodec.LAST_CONNECTED_AT, preferences.getString(prefix + SessionMetadataCodec.LAST_CONNECTED_AT, null));
        fields.put(SessionMetadataCodec.ACTIVE, preferences.getString(prefix + SessionMetadataCodec.ACTIVE, null));
        if (fields.get(SessionMetadataCodec.HOST) == null) {
            return null;
        }
        SessionMetadata metadata = codec.fromFields(sessionId, fields);
        if (metadata == null) {
            // Corrupt local metadata must not produce a fake session.
            delete(sessionId);
            return null;
        }
        return metadata;
    }

    /** Persists metadata atomically. Throws if the commit does not land. */
    @SuppressLint("ApplySharedPref")
    public void save(SessionMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        Map<String, String> fields = codec.toFields(metadata);
        String prefix = prefix(metadata.getSessionId());
        boolean committed = preferences.edit()
                .putString(prefix + SessionMetadataCodec.HOST, fields.get(SessionMetadataCodec.HOST))
                .putString(prefix + SessionMetadataCodec.PORT, fields.get(SessionMetadataCodec.PORT))
                .putString(prefix + SessionMetadataCodec.USERNAME, fields.get(SessionMetadataCodec.USERNAME))
                .putString(prefix + SessionMetadataCodec.CREATED_AT, fields.get(SessionMetadataCodec.CREATED_AT))
                .putString(prefix + SessionMetadataCodec.LAST_CONNECTED_AT, fields.get(SessionMetadataCodec.LAST_CONNECTED_AT))
                .putString(prefix + SessionMetadataCodec.ACTIVE, fields.get(SessionMetadataCodec.ACTIVE))
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist session metadata");
        }
    }

    /** Removes all fields for a session. */
    @SuppressLint("ApplySharedPref")
    public void delete(String sessionId) {
        String prefix = prefix(sessionId);
        preferences.edit()
                .remove(prefix + SessionMetadataCodec.HOST)
                .remove(prefix + SessionMetadataCodec.PORT)
                .remove(prefix + SessionMetadataCodec.USERNAME)
                .remove(prefix + SessionMetadataCodec.CREATED_AT)
                .remove(prefix + SessionMetadataCodec.LAST_CONNECTED_AT)
                .remove(prefix + SessionMetadataCodec.ACTIVE)
                .commit();
    }

    public boolean exists(String sessionId) {
        String prefix = prefix(sessionId);
        return preferences.getString(prefix + SessionMetadataCodec.HOST, null) != null;
    }

    /**
     * Persists metadata and records it as the session a recreated view should
     * try to resume. Atomic with {@link #save(SessionMetadata)} semantics.
     */
    @SuppressLint("ApplySharedPref")
    public void saveActive(SessionMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        Map<String, String> fields = codec.toFields(metadata);
        String prefix = prefix(metadata.getSessionId());
        boolean committed = preferences.edit()
                .putString(prefix + SessionMetadataCodec.HOST, fields.get(SessionMetadataCodec.HOST))
                .putString(prefix + SessionMetadataCodec.PORT, fields.get(SessionMetadataCodec.PORT))
                .putString(prefix + SessionMetadataCodec.USERNAME, fields.get(SessionMetadataCodec.USERNAME))
                .putString(prefix + SessionMetadataCodec.CREATED_AT, fields.get(SessionMetadataCodec.CREATED_AT))
                .putString(prefix + SessionMetadataCodec.LAST_CONNECTED_AT, fields.get(SessionMetadataCodec.LAST_CONNECTED_AT))
                .putString(prefix + SessionMetadataCodec.ACTIVE, fields.get(SessionMetadataCodec.ACTIVE))
                .putString(KEY_ACTIVE_SESSION_ID, metadata.getSessionId())
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist active session metadata");
        }
    }

    /**
     * Returns the metadata of the last session marked active, or {@code null}
     * when no session is recorded or the record is corrupt. Callers must still
     * check {@link SessionMetadata#isActive()} and reconcile against the live
     * runtime status before reconnecting.
     */
    public SessionMetadata loadActive() {
        String sessionId = preferences.getString(KEY_ACTIVE_SESSION_ID, null);
        return sessionId == null ? null : load(sessionId);
    }

    /** Drops the active-session pointer without touching per-session records. */
    @SuppressLint("ApplySharedPref")
    public void clearActive() {
        preferences.edit().remove(KEY_ACTIVE_SESSION_ID).commit();
    }

    private static String prefix(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId.replaceAll("[^A-Za-z0-9._-]", "_") + ".";
    }
}
