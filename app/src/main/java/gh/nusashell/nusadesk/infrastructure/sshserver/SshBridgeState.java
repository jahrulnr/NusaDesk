package gh.nusashell.nusadesk.infrastructure.sshserver;

/**
 * Lifecycle states of the SSH bridge server.
 *
 * <p>These are the bridge's own states, distinct from the domain
 * {@code SessionState}. The bridge is infrastructure; the host service maps
 * these into the domain session state when it wires the bridge into the
 * runtime controller.</p>
 */
public enum SshBridgeState {
    /** The server is not running and has not been started. */
    STOPPED,
    /** The server is binding and running its health check. */
    STARTING,
    /** The server is bound, healthy, and accepting authenticated shell channels. */
    RUNNING,
    /** The server is shutting down and tearing down active shells. */
    STOPPING,
    /** The last start or run failed; the server is not accepting connections. */
    FAILED;
}
