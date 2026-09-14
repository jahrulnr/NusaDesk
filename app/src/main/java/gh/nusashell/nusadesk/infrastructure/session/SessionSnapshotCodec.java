package gh.nusashell.nusadesk.infrastructure.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates and serialises {@link SessionSnapshot} into flat string fields for
 * {@link SharedPreferencesSessionStateStore}.
 *
 * <p>Pure Java with no Android or I/O. Any missing or invalid field yields
 * {@code null} so a corrupt store can never resurrect a false {@code RUNNING}
 * snapshot — the caller drops the entry and the service reconciles honestly.</p>
 */
public final class SessionSnapshotCodec {

    public static final String SESSION_ID = "sessionId";
    public static final String APP_ID = "appId";
    public static final String APP_VERSION = "appVersion";
    public static final String STATE = "state";
    public static final String ENDPOINT_HOST = "endpointHost";
    public static final String ENDPOINT_PORT = "endpointPort";
    public static final String STARTED_AT = "startedAt";
    public static final String UPDATED_AT = "updatedAt";
    public static final String FAILURE_REASON = "failureReason";
    public static final String RECONNECT_ATTEMPTS = "reconnectAttempts";

    public Map<String, String> toFields(SessionSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put(SESSION_ID, snapshot.getSessionId());
        fields.put(APP_ID, snapshot.getAppId());
        fields.put(APP_VERSION, snapshot.getAppVersion());
        fields.put(STATE, snapshot.getState().name());
        fields.put(ENDPOINT_HOST,
                snapshot.getEndpoint() == null ? "" : snapshot.getEndpoint().getHost());
        fields.put(ENDPOINT_PORT,
                snapshot.getEndpoint() == null ? "0" : Integer.toString(snapshot.getEndpoint().getPort()));
        fields.put(STARTED_AT, Long.toString(snapshot.getStartedAtEpochMillis()));
        fields.put(UPDATED_AT, Long.toString(snapshot.getUpdatedAtEpochMillis()));
        fields.put(FAILURE_REASON, snapshot.getFailureReason());
        fields.put(RECONNECT_ATTEMPTS, Integer.toString(snapshot.getReconnectAttempts()));
        return fields;
    }

    /**
     * Rebuilds a snapshot from stored fields, or returns {@code null} when any
     * field is missing or invalid.
     */
    public SessionSnapshot fromFields(Map<String, String> fields) {
        if (fields == null) {
            return null;
        }
        String sessionId = fields.get(SESSION_ID);
        String appId = fields.get(APP_ID);
        String appVersion = fields.get(APP_VERSION);
        String state = fields.get(STATE);
        String endpointHost = fields.get(ENDPOINT_HOST);
        String endpointPort = fields.get(ENDPOINT_PORT);
        String startedAt = fields.get(STARTED_AT);
        String updatedAt = fields.get(UPDATED_AT);
        String failureReason = fields.get(FAILURE_REASON);
        String reconnectAttempts = fields.get(RECONNECT_ATTEMPTS);
        if (sessionId == null || appId == null || appVersion == null || state == null
                || endpointHost == null || endpointPort == null || startedAt == null
                || updatedAt == null || failureReason == null || reconnectAttempts == null) {
            return null;
        }
        try {
            RuntimePort endpoint = endpointHost.isEmpty()
                    ? null
                    : new RuntimePort(endpointHost, Integer.parseInt(endpointPort));
            return new SessionSnapshot(
                    sessionId, appId, appVersion,
                    SessionState.valueOf(state), endpoint,
                    Long.parseLong(startedAt), Long.parseLong(updatedAt),
                    failureReason, Integer.parseInt(reconnectAttempts));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
