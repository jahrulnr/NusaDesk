package gh.nusashell.nusadesk.domain.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

import java.util.Objects;

/**
 * Immutable, persistable snapshot of one runtime session.
 *
 * <p>Carries enough state to reconcile after Activity recreation: the session
 * identity, the last known endpoint, the lifecycle state, and a failure reason.
 * The endpoint is null until the guest emits an accepted readiness frame.</p>
 */
public final class SessionSnapshot {
    private final String sessionId;
    private final String appId;
    private final String appVersion;
    private final SessionState state;
    private final RuntimePort endpoint;
    private final long startedAtEpochMillis;
    private final long updatedAtEpochMillis;
    private final String failureReason;
    private final int reconnectAttempts;

    public SessionSnapshot(
            String sessionId,
            String appId,
            String appVersion,
            SessionState state,
            RuntimePort endpoint,
            long startedAtEpochMillis,
            long updatedAtEpochMillis,
            String failureReason,
            int reconnectAttempts) {
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        this.appId = requireNonBlank(appId, "appId");
        this.appVersion = requireNonBlank(appVersion, "appVersion");
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (startedAtEpochMillis < 0 || updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("timestamps must not be negative");
        }
        if (updatedAtEpochMillis < startedAtEpochMillis) {
            throw new IllegalArgumentException("updatedAt must not precede startedAt");
        }
        if (reconnectAttempts < 0) {
            throw new IllegalArgumentException("reconnectAttempts must not be negative");
        }
        this.state = state;
        this.endpoint = endpoint;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.failureReason = failureReason == null ? "" : failureReason;
        this.reconnectAttempts = reconnectAttempts;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAppId() {
        return appId;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public SessionState getState() {
        return state;
    }

    /** Returns null until the guest emits an accepted readiness frame. */
    public RuntimePort getEndpoint() {
        return endpoint;
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    public boolean isCancelled() {
        return state == SessionState.CANCELLED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionSnapshot)) {
            return false;
        }
        SessionSnapshot that = (SessionSnapshot) o;
        return startedAtEpochMillis == that.startedAtEpochMillis
                && updatedAtEpochMillis == that.updatedAtEpochMillis
                && reconnectAttempts == that.reconnectAttempts
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(appId, that.appId)
                && Objects.equals(appVersion, that.appVersion)
                && state == that.state
                && Objects.equals(endpoint, that.endpoint)
                && Objects.equals(failureReason, that.failureReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, appId, appVersion, state, endpoint,
                startedAtEpochMillis, updatedAtEpochMillis, failureReason, reconnectAttempts);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
