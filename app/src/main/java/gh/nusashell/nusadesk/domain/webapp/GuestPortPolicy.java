package gh.nusashell.nusadesk.domain.webapp;

/**
 * The guest-side port rules for a user-defined local web app.
 *
 * <p>A web app listens inside the single Linux guest, so its port is a guest
 * port, not an Android host port. The host never binds it; it only generates the
 * loopback endpoint the WebView loads and observes readiness against.</p>
 *
 * <p>Port {@link #RESERVED_GUEST_SSH_PORT} is excluded because the guest
 * OpenSSH service owns it for the in-app terminal. Two services cannot share one
 * guest port, so accepting it would produce a web app that can never serve.</p>
 */
public final class GuestPortPolicy {
    /** Lowest accepted TCP port. */
    public static final int MIN_PORT = 1;
    /** Highest accepted TCP port. */
    public static final int MAX_PORT = 65535;
    /** Fixed loopback port owned by the guest OpenSSH service. */
    public static final int RESERVED_GUEST_SSH_PORT = 22022;

    private GuestPortPolicy() {
    }

    /** True when {@code port} is a real TCP port number. */
    public static boolean isInRange(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    /** True when {@code port} is already owned by a guest service. */
    public static boolean isReserved(int port) {
        return port == RESERVED_GUEST_SSH_PORT;
    }

    /** True when a user-defined web app may use {@code port}. */
    public static boolean isAllowed(int port) {
        return isInRange(port) && !isReserved(port);
    }

    /**
     * @return {@code port} unchanged when it is allowed
     * @throws IllegalArgumentException when the port is out of range or reserved
     */
    public static int validate(int port) {
        if (!isInRange(port)) {
            throw new IllegalArgumentException(
                    "guest port must be between " + MIN_PORT + " and " + MAX_PORT + ": " + port);
        }
        if (isReserved(port)) {
            throw new IllegalArgumentException("guest port " + RESERVED_GUEST_SSH_PORT
                    + " is reserved for the Linux session service");
        }
        return port;
    }
}
