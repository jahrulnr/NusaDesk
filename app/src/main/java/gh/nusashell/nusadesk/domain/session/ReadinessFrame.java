package gh.nusashell.nusadesk.domain.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

import java.util.Objects;

/**
 * Immutable, versioned readiness frame emitted by the guest runtime after it
 * binds a loopback listener and performs its health check.
 *
 * <p>The host validates the structure of this frame before trusting it. A
 * frame is <em>ready</em> only when its health is {@link ReadinessHealth#HEALTHY};
 * a structurally valid but unhealthy frame must not cause the WebView to load.</p>
 *
 * <p>This is pure domain data: it carries no Android types and performs no
 * network or process I/O.</p>
 */
public final class ReadinessFrame {
    /** Schema versions the host currently understands. */
    public static final int SUPPORTED_SCHEMA = 1;

    private final int schemaVersion;
    private final String appId;
    private final String appVersion;
    private final String sessionId;
    private final RuntimePort endpoint;
    private final ReadinessHealth health;
    private final long emittedAtEpochMillis;

    public ReadinessFrame(
            int schemaVersion,
            String appId,
            String appVersion,
            String sessionId,
            RuntimePort endpoint,
            ReadinessHealth health,
            long emittedAtEpochMillis) {
        if (schemaVersion != SUPPORTED_SCHEMA) {
            throw new IllegalArgumentException(
                    "unsupported readiness frame schema: " + schemaVersion);
        }
        String validatedAppId = requireNonBlank(appId, "appId");
        String validatedAppVersion = requireNonBlank(appVersion, "appVersion");
        String validatedSessionId = requireNonBlank(sessionId, "sessionId");
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        if (health == null) {
            throw new IllegalArgumentException("health must not be null");
        }
        if (emittedAtEpochMillis < 0) {
            throw new IllegalArgumentException("emittedAtEpochMillis must not be negative");
        }
        this.schemaVersion = schemaVersion;
        this.appId = validatedAppId;
        this.appVersion = validatedAppVersion;
        this.sessionId = validatedSessionId;
        this.endpoint = endpoint;
        this.health = health;
        this.emittedAtEpochMillis = emittedAtEpochMillis;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getAppId() {
        return appId;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public RuntimePort getEndpoint() {
        return endpoint;
    }

    public ReadinessHealth getHealth() {
        return health;
    }

    public long getEmittedAtEpochMillis() {
        return emittedAtEpochMillis;
    }

    /**
     * A frame authorises the WebView only when the guest reports healthy.
     * The loopback endpoint is already enforced by {@link RuntimePort}.
     */
    public boolean isReady() {
        return health == ReadinessHealth.HEALTHY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReadinessFrame)) {
            return false;
        }
        ReadinessFrame that = (ReadinessFrame) o;
        return schemaVersion == that.schemaVersion
                && emittedAtEpochMillis == that.emittedAtEpochMillis
                && Objects.equals(appId, that.appId)
                && Objects.equals(appVersion, that.appVersion)
                && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(endpoint, that.endpoint)
                && health == that.health;
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, appId, appVersion, sessionId,
                endpoint, health, emittedAtEpochMillis);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
