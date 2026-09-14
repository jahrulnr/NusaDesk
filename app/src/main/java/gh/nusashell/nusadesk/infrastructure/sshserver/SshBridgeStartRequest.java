package gh.nusashell.nusadesk.infrastructure.sshserver;

/**
 * Immutable, validated parameters for one {@link SshBridgeServer#start} attempt.
 *
 * <p>Carries the runtime identity echoed back in the readiness frame and the
 * bounded timeouts the server applies to the health check and guest-shell
 * teardown. No secret crosses this boundary: the credential is sourced
 * separately through {@link SshBridgeCredential}.</p>
 */
public final class SshBridgeStartRequest {

    private final String appId;
    private final String appVersion;
    private final String sessionId;
    private final long healthTimeoutMillis;
    private final long shellDestroyGraceMillis;

    public SshBridgeStartRequest(
            String appId,
            String appVersion,
            String sessionId,
            long healthTimeoutMillis,
            long shellDestroyGraceMillis) {
        this.appId = requireNonBlank(appId, "appId");
        this.appVersion = requireNonBlank(appVersion, "appVersion");
        this.sessionId = requireNonBlank(sessionId, "sessionId");
        if (healthTimeoutMillis < 0) {
            throw new IllegalArgumentException("healthTimeoutMillis must not be negative");
        }
        this.healthTimeoutMillis = healthTimeoutMillis;
        if (shellDestroyGraceMillis < 0) {
            throw new IllegalArgumentException("shellDestroyGraceMillis must not be negative");
        }
        this.shellDestroyGraceMillis = shellDestroyGraceMillis;
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

    public long getHealthTimeoutMillis() {
        return healthTimeoutMillis;
    }

    public long getShellDestroyGraceMillis() {
        return shellDestroyGraceMillis;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
