package gh.nusashell.nusadesk.infrastructure.session;

import java.util.Objects;

/**
 * Immutable, non-secret session metadata persisted by the host so a reconnect
 * can resume the last known SSH target without re-prompting the user.
 *
 * <p>This object never carries secrets. Passwords and private keys are handled
 * by {@link KeystoreCredentialVault}; only connection coordinates and
 * timestamps live here, and they are stored as plain (validated) metadata.
 */
public final class SessionMetadata {
    private final String sessionId;
    private final String host;
    private final int port;
    private final String username;
    private final long createdAtEpochMillis;
    private final long lastConnectedAtEpochMillis;
    private final boolean active;

    public SessionMetadata(
            String sessionId,
            String host,
            int port,
            String username,
            long createdAtEpochMillis,
            long lastConnectedAtEpochMillis,
            boolean active) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (createdAtEpochMillis < 0) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
        if (lastConnectedAtEpochMillis < 0) {
            throw new IllegalArgumentException("lastConnectedAtEpochMillis must not be negative");
        }
        this.sessionId = sessionId;
        this.host = host;
        this.port = port;
        this.username = username;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.lastConnectedAtEpochMillis = lastConnectedAtEpochMillis;
        this.active = active;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    /** Zero means the session was saved but never reached a connected state. */
    public long getLastConnectedAtEpochMillis() {
        return lastConnectedAtEpochMillis;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionMetadata)) {
            return false;
        }
        SessionMetadata that = (SessionMetadata) other;
        return port == that.port
                && createdAtEpochMillis == that.createdAtEpochMillis
                && lastConnectedAtEpochMillis == that.lastConnectedAtEpochMillis
                && active == that.active
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(host, that.host)
                && Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, host, port, username,
                createdAtEpochMillis, lastConnectedAtEpochMillis, active);
    }
}
