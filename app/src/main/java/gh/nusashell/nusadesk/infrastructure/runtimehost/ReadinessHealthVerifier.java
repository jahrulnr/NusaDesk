package gh.nusashell.nusadesk.infrastructure.runtimehost;

import gh.nusashell.nusadesk.domain.session.ReadinessFrame;

import java.io.IOException;
import java.util.Locale;

/**
 * Validates a guest {@link ReadinessFrame} identity, then performs a bounded
 * HTTP health probe before authorising the host to treat the runtime as
 * {@code RUNNING}.
 *
 * <p>Readiness is not merely a PID or a parsed line: the host confirms the
 * frame's app id, version, and schema match the expected runtime, that the
 * guest self-reports {@link gh.nusashell.nusadesk.domain.session.ReadinessHealth#HEALTHY},
 * and that an independent GET to the published loopback health endpoint returns
 * a 2xx status. A self-reported healthy frame that fails the probe, or a probe
 * success against a frame with the wrong app id, must not produce
 * {@link Outcome#HEALTHY}.</p>
 *
 * <p>The health path is secret-free (e.g. {@code /healthz}); no tokens are placed
 * in the URL. Loopback is a reachability restriction, not authentication; a
 * separate app-token header boundary would be added by the caller for sensitive
 * endpoints, never through this verifier's URL.</p>
 *
 * <p>The verification logic is pure with respect to the injected
 * {@link HttpHealthProbe}, so it is unit-testable with a fake probe and no real
 * network or Linux process.</p>
 */
public final class ReadinessHealthVerifier {
    /** Outcome of verifying a readiness frame. */
    public enum Outcome {
        /** Identity matched and the health probe returned 2xx. */
        HEALTHY,
        /** Identity matched but the guest or the endpoint reported non-healthy. */
        UNHEALTHY,
        /** The health endpoint could not be reached within the timeouts. */
        UNREACHABLE,
        /** The frame does not match the expected app id, version, or schema. */
        IDENTITY_MISMATCH
    }

    /** Immutable verification result. */
    public static final class Result {
        private final Outcome outcome;
        private final String detail;

        public Result(Outcome outcome, String detail) {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public String getDetail() {
            return detail;
        }
    }

    private final HttpHealthProbe probe;
    private final String healthPath;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /**
     * @param probe                the HTTP health probe port
     * @param healthPath           secret-free path beginning with {@code /}, e.g. {@code /healthz}
     * @param connectTimeoutMillis connect timeout for the probe; non-negative
     * @param readTimeoutMillis    read timeout for the probe; non-negative
     */
    public ReadinessHealthVerifier(
            HttpHealthProbe probe, String healthPath,
            int connectTimeoutMillis, int readTimeoutMillis) {
        if (probe == null) {
            throw new IllegalArgumentException("probe must not be null");
        }
        if (healthPath == null || healthPath.isEmpty() || !healthPath.startsWith("/")) {
            throw new IllegalArgumentException("healthPath must start with '/'");
        }
        if (connectTimeoutMillis < 0 || readTimeoutMillis < 0) {
            throw new IllegalArgumentException("timeouts must not be negative");
        }
        this.probe = probe;
        this.healthPath = healthPath;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Verify a parsed readiness frame against the expected runtime identity and
     * the live health endpoint.
     *
     * @param frame           the frame parsed from the guest readiness line
     * @param expectedAppId   the app id the host expects to run
     * @param expectedVersion the app version the host expects to run
     * @return the verification result; never null
     */
    public Result verify(ReadinessFrame frame, String expectedAppId, String expectedVersion) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (expectedAppId == null || expectedAppId.trim().isEmpty()) {
            throw new IllegalArgumentException("expectedAppId must not be blank");
        }
        if (expectedVersion == null || expectedVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("expectedVersion must not be blank");
        }

        if (frame.getSchemaVersion() != ReadinessFrame.SUPPORTED_SCHEMA
                || !frame.getAppId().equals(expectedAppId)
                || !frame.getAppVersion().equals(expectedVersion)) {
            return new Result(Outcome.IDENTITY_MISMATCH,
                    "appId/version/schema mismatch for app=" + frame.getAppId()
                            + " ver=" + frame.getAppVersion()
                            + " schema=" + frame.getSchemaVersion());
        }

        if (!frame.isReady()) {
            return new Result(Outcome.UNHEALTHY,
                    "guest self-reported " + frame.getHealth().name().toLowerCase(Locale.ROOT));
        }

        try {
            int status = probe.probe("http", frame.getEndpoint().getHost(),
                    frame.getEndpoint().getPort(), healthPath,
                    connectTimeoutMillis, readTimeoutMillis);
            if (status >= 200 && status < 300) {
                return new Result(Outcome.HEALTHY, "HTTP " + status);
            }
            return new Result(Outcome.UNHEALTHY, "HTTP " + status);
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(Outcome.UNREACHABLE, reason);
        }
    }
}
