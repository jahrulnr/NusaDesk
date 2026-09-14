package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

/**
 * The single curated loopback endpoint of the in-app guest SSH daemon.
 *
 * <p>The product has exactly one SSH endpoint: the guest Linux this app owns.
 * It is not an SSH client for external hosts, so the endpoint is a documented
 * product constant rather than a discovered value (ADR-0013):</p>
 *
 * <ul>
 *   <li><b>{@value #HOST}:{@value #PORT}</b> — loopback only. The daemon is
 *       configured with {@code ListenAddress 127.0.0.1} and the host verifies
 *       the daemon's own {@code Server listening on 127.0.0.1 port N.} report
 *       before it publishes anything, so no non-loopback address can ever be
 *       reached or advertised.</li>
 *   <li><b>Why a fixed port.</b> A stable endpoint keeps the host-key pin, the
 *       readiness frame, the notification, and the terminal attachment all
 *       describing the same thing, and removes the retry loop an ephemeral
 *       candidate needed. {@value #PORT} sits below the Linux ephemeral range
 *       (typically 32768–60999), so an outgoing connection can never occupy it,
 *       and it is unassigned by IANA.</li>
 *   <li><b>Conflict policy.</b> If the port is already held, the guest daemon
 *       reports its own bind failure and the start ends in a typed honest
 *       {@code FAILED}; the host never attaches to a listener it did not start
 *       (see {@link LocalSshSessionFactory#pinnedHostKeyOnly()} for the client
 *       half of that rule).</li>
 * </ul>
 *
 * <p>Carries no Android types and performs no I/O: both the launching side
 * (the guest daemon workload) and the dialing side (the in-app client) read the
 * same constants from here.</p>
 */
public final class LocalSshEndpoint {

    /** Loopback host the guest daemon binds and the in-app client dials. */
    public static final String HOST = "127.0.0.1";

    /** Fixed guest SSH port. Documented product constant; never user-supplied. */
    public static final int PORT = 22_022;

    /** Host-key trust scope shared by the pinning and the dialing side. */
    public static final String HOST_KEY_SCOPE = HOST + ":" + PORT;

    private LocalSshEndpoint() {
    }

    /** The validated loopback endpoint the host publishes and the client dials. */
    public static RuntimePort endpoint() {
        return new RuntimePort(HOST, PORT);
    }

    /** Non-secret human label for logs, e.g. {@code "127.0.0.1:22022"}. */
    public static String label() {
        return HOST_KEY_SCOPE;
    }
}
