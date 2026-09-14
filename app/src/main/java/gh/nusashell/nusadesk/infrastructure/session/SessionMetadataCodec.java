package gh.nusashell.nusadesk.infrastructure.session;

import java.util.HashMap;
import java.util.Map;

/**
 * Android-free encoder/decoder between {@link SessionMetadata} and a flat
 * string field map (the shape stored in SharedPreferences).
 *
 * <p>Kept separate from the {@link android.content.SharedPreferences} adapter so
 * the defensive parsing and validation logic can be unit-tested on the JVM
 * without an Android runtime. Corrupt or partial fields yield {@code null}
 * from {@link #fromFields(String, Map)} so the caller can drop the entry
 * rather than surface a half-built session.
 */
final class SessionMetadataCodec {
    static final String HOST = "host";
    static final String PORT = "port";
    static final String USERNAME = "username";
    static final String CREATED_AT = "createdAt";
    static final String LAST_CONNECTED_AT = "lastConnectedAt";
    static final String ACTIVE = "active";

    /** Serializes metadata into the field map shape persisted on disk. */
    Map<String, String> toFields(SessionMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put(HOST, metadata.getHost());
        fields.put(PORT, Integer.toString(metadata.getPort()));
        fields.put(USERNAME, metadata.getUsername());
        fields.put(CREATED_AT, Long.toString(metadata.getCreatedAtEpochMillis()));
        fields.put(LAST_CONNECTED_AT, Long.toString(metadata.getLastConnectedAtEpochMillis()));
        fields.put(ACTIVE, Boolean.toString(metadata.isActive()));
        return fields;
    }

    /**
     * Reconstructs metadata from a field map. Returns {@code null} when any
     * required field is missing or malformed so the caller can treat the
     * stored entry as corrupt and clear it.
     *
     * @param sessionId identity of the owning session (kept out of the map)
     */
    SessionMetadata fromFields(String sessionId, Map<String, String> fields) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (fields == null) {
            return null;
        }
        String host = fields.get(HOST);
        String portText = fields.get(PORT);
        String username = fields.get(USERNAME);
        String createdAtText = fields.get(CREATED_AT);
        String lastConnectedText = fields.get(LAST_CONNECTED_AT);
        String activeText = fields.get(ACTIVE);
        if (host == null || portText == null || username == null
                || createdAtText == null || lastConnectedText == null || activeText == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(portText.trim());
            long createdAt = Long.parseLong(createdAtText.trim());
            long lastConnected = Long.parseLong(lastConnectedText.trim());
            boolean active = Boolean.parseBoolean(activeText.trim());
            return new SessionMetadata(
                    sessionId, host.trim(), port, username.trim(),
                    createdAt, lastConnected, active);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
