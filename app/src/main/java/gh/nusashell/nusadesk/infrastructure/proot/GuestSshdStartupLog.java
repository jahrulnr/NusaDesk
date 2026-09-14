package gh.nusashell.nusadesk.infrastructure.proot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the guest {@code sshd}'s own stderr lines into bind outcomes.
 *
 * <p>OpenSSH reports its effective listen sockets at {@code LogLevel VERBOSE}
 * (set explicitly in the daemon argv) and fails a start with an explicit bind
 * error. Those two lines are the machine-readable channel that proves which
 * port the daemon actually bound — a bound port observed from the host alone
 * cannot be attributed to the daemon we launched:</p>
 *
 * <pre>
 *   Server listening on 127.0.0.1 port 44007.
 *   Bind to port 44007 on 127.0.0.1 failed: Address already in use.
 *   Cannot bind any address.
 * </pre>
 *
 * <p>Pure JVM string logic: unit-testable without a device or a process.</p>
 */
public final class GuestSshdStartupLog {

    /** What a single daemon stderr line means for the start handshake. */
    public enum Kind { LISTENING, BIND_FAILED, IGNORED }

    private static final Pattern LISTENING = Pattern.compile(
            "^Server listening on (\\S+) port (\\d+)\\.?$");

    /** Immutable parse result: the outcome plus the reported endpoint, if any. */
    public static final class Event {
        private static final Event IGNORED = new Event(Kind.IGNORED, "", 0);
        private static final Event BIND_FAILED = new Event(Kind.BIND_FAILED, "", 0);

        private final Kind kind;
        private final String host;
        private final int port;

        private Event(Kind kind, String host, int port) {
            this.kind = kind;
            this.host = host;
            this.port = port;
        }

        public Kind getKind() {
            return kind;
        }

        /** Reported listen host for {@link Kind#LISTENING}; empty otherwise. */
        public String getHost() {
            return host;
        }

        /** Reported listen port for {@link Kind#LISTENING}; {@code 0} otherwise. */
        public int getPort() {
            return port;
        }
    }

    private GuestSshdStartupLog() {
    }

    /** Classify one daemon stderr line. Never returns {@code null}. */
    public static Event parse(String line) {
        if (line == null) {
            return Event.IGNORED;
        }
        String trimmed = line.trim();
        Matcher listening = LISTENING.matcher(trimmed);
        if (listening.matches()) {
            int port;
            try {
                port = Integer.parseInt(listening.group(2));
            } catch (NumberFormatException e) {
                return Event.IGNORED;
            }
            if (port <= 0 || port > 65535) {
                return Event.IGNORED;
            }
            return new Event(Kind.LISTENING, listening.group(1), port);
        }
        if (trimmed.startsWith("Bind to port ") && trimmed.contains(" failed:")) {
            return Event.BIND_FAILED;
        }
        if (trimmed.equals("Cannot bind any address.")) {
            return Event.BIND_FAILED;
        }
        return Event.IGNORED;
    }
}
