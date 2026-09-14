package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpHealthProbe;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpUrlConnectionHealthProbe;

import java.io.IOException;

/**
 * One bounded HTTP observation of a user-defined web app's loopback endpoint,
 * made before the WebView is attached.
 *
 * <p>A registered app is only a launcher entry; it says nothing about whether the
 * app's server inside the guest is actually listening. Attaching the WebView
 * first would show a browser error page instead of an honest "not running yet"
 * state, so the caller observes the endpoint first and only loads
 * {@code http://127.0.0.1:<port>/} once it is reachable.</p>
 *
 * <p>The observation is deliberately shallow. A user-defined app has no
 * standardized health endpoint, so the contract is the root path and <em>any</em>
 * valid HTTP response proves that something is serving HTTP there — a {@code 404}
 * or {@code 500} is reachable, an {@code IOException} is not. This proves
 * reachability, never application health, and never that the listener is the app
 * the user registered.</p>
 *
 * <p>It is bounded: one attempt, with explicit connect and read timeouts and no
 * redirect following. The caller owns retry, cancellation, and any progress UI,
 * and must not call {@link #observe(int)} on the main thread.</p>
 *
 * <p>Reuses the project's single bounded-probe port rather than adding a second
 * HTTP client, so the runtime and the web-app slice share one implementation.</p>
 */
public final class WebAppReadinessObserver {
    /** The only path observed. Secret-free by construction; no token is ever added. */
    public static final String HEALTH_PATH = "/";
    /** Connect timeout used by the no-argument constructor. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_000;
    /** Read timeout used by the no-argument constructor. */
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 1_500;

    private static final String SCHEME = "http";
    private static final String HOST = "127.0.0.1";

    /** Whether the endpoint answered HTTP at all. */
    public enum Outcome {
        /** A valid HTTP response came back, whatever its status code. */
        REACHABLE,
        /** Nothing answered HTTP within the timeouts. */
        UNREACHABLE
    }

    /** Immutable observation result. */
    public static final class Result {
        private final Outcome outcome;
        private final int httpStatus;
        private final String detail;

        Result(Outcome outcome, int httpStatus, String detail) {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
            this.outcome = outcome;
            this.httpStatus = httpStatus;
            this.detail = detail == null ? "" : detail;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        /** The HTTP status, or {@code 0} when nothing answered. */
        public int getHttpStatus() {
            return httpStatus;
        }

        /** Short reason for the outcome; never null, never a response body. */
        public String getDetail() {
            return detail;
        }

        public boolean isReachable() {
            return outcome == Outcome.REACHABLE;
        }
    }

    private final HttpHealthProbe probe;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /** Observes with {@link HttpUrlConnectionHealthProbe} and the default timeouts. */
    public WebAppReadinessObserver() {
        this(new HttpUrlConnectionHealthProbe(),
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /**
     * @param probe                performs the bounded GET
     * @param connectTimeoutMillis connect timeout; non-negative
     * @param readTimeoutMillis    read timeout; non-negative
     */
    public WebAppReadinessObserver(
            HttpHealthProbe probe, int connectTimeoutMillis, int readTimeoutMillis) {
        if (probe == null) {
            throw new IllegalArgumentException("probe must not be null");
        }
        if (connectTimeoutMillis < 0 || readTimeoutMillis < 0) {
            throw new IllegalArgumentException("timeouts must not be negative");
        }
        this.probe = probe;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Observes {@code http://127.0.0.1:<guestPort>/} once.
     *
     * @param guestPort the app's fixed guest port
     * @return the observation result; never null
     * @throws IllegalArgumentException when the port is not a usable web-app port
     */
    public Result observe(int guestPort) {
        GuestPortPolicy.validate(guestPort);
        try {
            int status = probe.probe(
                    SCHEME, HOST, guestPort, HEALTH_PATH,
                    connectTimeoutMillis, readTimeoutMillis);
            return new Result(Outcome.REACHABLE, status, "HTTP " + status);
        } catch (IOException failure) {
            String reason = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Result(Outcome.UNREACHABLE, 0, reason);
        }
    }
}
