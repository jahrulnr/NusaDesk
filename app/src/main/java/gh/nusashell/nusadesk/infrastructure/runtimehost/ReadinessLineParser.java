package gh.nusashell.nusadesk.infrastructure.runtimehost;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses a single machine-readable readiness line emitted by the guest runtime
 * into a validated {@link ReadinessFrame}, or reports why the line was rejected.
 *
 * <p>The line protocol is intentionally minimal, versioned, and parseable with the
 * JDK alone so the host can validate the guest handshake without an Android
 * runtime. The format is:</p>
 *
 * <pre>
 * READY/1 app=<appId> ver=<version> session=<sessionId> host=<loopback> port=<1-65535> health=<healthy|degraded|unhealthy> emitted=<epochMillis>
 * </pre>
 *
 * <p>The {@code READY/1} prefix carries the schema version, which must match
 * {@link ReadinessFrame#SUPPORTED_SCHEMA}. Fields are space-separated
 * {@code key=value} tokens and may appear in any order; unknown keys are ignored
 * for forward compatibility. A line that does not start with the readiness prefix
 * is {@link Outcome#IGNORED} so the supervisor can treat ordinary guest stdout as
 * noise rather than a protocol violation. A line that starts with the prefix but
 * is malformed is {@link Outcome#REJECTED} with a reason.</p>
 *
 * <p>This class performs no I/O and holds no state; it is safe to call from any
 * thread.</p>
 */
public final class ReadinessLineParser {
    /** Readiness line prefix, embedding the supported schema version. */
    public static final String LINE_PREFIX = "READY/" + ReadinessFrame.SUPPORTED_SCHEMA;

    private static final String KEY_APP = "app";
    private static final String KEY_VER = "ver";
    private static final String KEY_SESSION = "session";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_HEALTH = "health";
    private static final String KEY_EMITTED = "emitted";

    /** Outcome of parsing a single stdout line. */
    public enum Outcome {
        /** A structurally valid, healthy-or-not readiness frame was produced. */
        ACCEPTED,
        /** The line was a readiness line but malformed or invalid. */
        REJECTED,
        /** The line was not a readiness line at all (normal guest stdout). */
        IGNORED
    }

    /** Immutable parse result. Exactly one of {@link #frame} / {@link #reason} is non-null. */
    public static final class Result {
        private final Outcome outcome;
        private final ReadinessFrame frame;
        private final String reason;

        private Result(Outcome outcome, ReadinessFrame frame, String reason) {
            this.outcome = outcome;
            this.frame = frame;
            this.reason = reason;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        /** The parsed frame, or {@code null} unless {@link #isAccepted()}. */
        public ReadinessFrame getFrame() {
            return frame;
        }

        /** The rejection reason, or {@code null} when accepted or ignored. */
        public String getReason() {
            return reason;
        }

        public boolean isAccepted() {
            return outcome == Outcome.ACCEPTED;
        }

        public boolean isRejected() {
            return outcome == Outcome.REJECTED;
        }

        public boolean isIgnored() {
            return outcome == Outcome.IGNORED;
        }
    }

    private ReadinessLineParser() {
    }

    /**
     * Parse a single line. Never throws; invalid input yields a {@link Outcome#REJECTED}
     * result with a descriptive reason.
     */
    public static Result parse(String line) {
        if (line == null) {
            return new Result(Outcome.IGNORED, null, null);
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return new Result(Outcome.IGNORED, null, null);
        }
        if (!trimmed.startsWith(LINE_PREFIX)) {
            return new Result(Outcome.IGNORED, null, null);
        }
        if (trimmed.length() == LINE_PREFIX.length()) {
            return new Result(Outcome.REJECTED, null, "readiness line has no fields");
        }
        char separator = trimmed.charAt(LINE_PREFIX.length());
        if (separator != ' ' && separator != '\t') {
            return new Result(Outcome.REJECTED, null, "readiness prefix is not separated from fields");
        }
        String rest = trimmed.substring(LINE_PREFIX.length()).trim();
        Map<String, String> fields = new HashMap<>();
        if (!rest.isEmpty()) {
            String[] tokens = rest.split("\\s+");
            for (String token : tokens) {
                int eq = token.indexOf('=');
                if (eq <= 0 || eq == token.length() - 1) {
                    return new Result(Outcome.REJECTED, null, "invalid field token: " + token);
                }
                String key = token.substring(0, eq);
                String value = token.substring(eq + 1);
                fields.put(key, value);
            }
        }

        String app = fields.get(KEY_APP);
        String ver = fields.get(KEY_VER);
        String session = fields.get(KEY_SESSION);
        String host = fields.get(KEY_HOST);
        String portText = fields.get(KEY_PORT);
        String healthText = fields.get(KEY_HEALTH);
        String emittedText = fields.get(KEY_EMITTED);

        if (app == null) return rejected("missing field: " + KEY_APP);
        if (ver == null) return rejected("missing field: " + KEY_VER);
        if (session == null) return rejected("missing field: " + KEY_SESSION);
        if (host == null) return rejected("missing field: " + KEY_HOST);
        if (portText == null) return rejected("missing field: " + KEY_PORT);
        if (healthText == null) return rejected("missing field: " + KEY_HEALTH);
        if (emittedText == null) return rejected("missing field: " + KEY_EMITTED);

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return rejected("port is not an integer: " + portText);
        }

        long emitted;
        try {
            emitted = Long.parseLong(emittedText);
        } catch (NumberFormatException e) {
            return rejected("emitted is not a long: " + emittedText);
        }

        ReadinessHealth health = parseHealth(healthText);
        if (health == null) {
            return rejected("unknown health value: " + healthText);
        }

        try {
            RuntimePort endpoint = new RuntimePort(host, port);
            ReadinessFrame frame = new ReadinessFrame(
                    ReadinessFrame.SUPPORTED_SCHEMA, app, ver, session,
                    endpoint, health, emitted);
            return new Result(Outcome.ACCEPTED, frame, null);
        } catch (IllegalArgumentException e) {
            return new Result(Outcome.REJECTED, null,
                    "invalid readiness frame: " + e.getMessage());
        }
    }

    private static ReadinessHealth parseHealth(String value) {
        switch (value) {
            case "healthy":
                return ReadinessHealth.HEALTHY;
            case "degraded":
                return ReadinessHealth.DEGRADED;
            case "unhealthy":
                return ReadinessHealth.UNHEALTHY;
            default:
                return null;
        }
    }

    private static Result rejected(String reason) {
        return new Result(Outcome.REJECTED, null, reason);
    }
}
