package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

/**
 * Typed start failure: the guest SSH daemon could not bind the fixed loopback
 * port.
 *
 * <p>Guest {@code sshd} reports its own bind failure ({@code Bind to port N on
 * 127.0.0.1 failed: Address already in use.} / {@code Cannot bind any
 * address.}), which is the only report that attributes the endpoint to the
 * process this host launched. A fixed port turns that report into a product
 * decision instead of a retry: the port is part of the documented contract
 * (ADR-0013), so the start fails honestly rather than moving to another port
 * the rest of the app does not know about.</p>
 *
 * <p>{@link #isListenerAlreadyPresent()} records whether the fixed endpoint
 * answered a probe at failure time. It only distinguishes "something is holding
 * the port" from "the port looked free but the daemon still failed"; in both
 * cases the host publishes no endpoint and never attaches to a listener it did
 * not start.</p>
 */
public final class GuestSshdBindFailureException extends Exception {

    private final RuntimePort endpoint;
    private final boolean listenerAlreadyPresent;

    /**
     * @param endpoint              the fixed loopback endpoint the daemon had to bind
     * @param listenerAlreadyPresent whether a probe found something already
     *                               listening on that endpoint
     */
    public GuestSshdBindFailureException(RuntimePort endpoint, boolean listenerAlreadyPresent) {
        super(reason(endpoint, listenerAlreadyPresent));
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        this.endpoint = endpoint;
        this.listenerAlreadyPresent = listenerAlreadyPresent;
    }

    /** The fixed endpoint the daemon could not bind. */
    public RuntimePort getEndpoint() {
        return endpoint;
    }

    /** Whether the fixed endpoint was already answering when the bind failed. */
    public boolean isListenerAlreadyPresent() {
        return listenerAlreadyPresent;
    }

    /** Deterministic, non-secret reason suitable for a user-visible failure. */
    public String getReason() {
        return getMessage();
    }

    private static String reason(RuntimePort endpoint, boolean listenerAlreadyPresent) {
        String where = endpoint == null
                ? "the fixed local SSH port"
                : endpoint.getHost() + ":" + endpoint.getPort();
        if (listenerAlreadyPresent) {
            return "guest sshd could not bind " + where + ": another listener already holds it, "
                    + "and the app never attaches to a listener it did not start";
        }
        return "guest sshd could not bind " + where + ": the fixed local SSH port is already in use";
    }
}
