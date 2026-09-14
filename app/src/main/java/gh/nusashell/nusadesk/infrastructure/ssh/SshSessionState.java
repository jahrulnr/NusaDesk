package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * Lifecycle state of one outbound SSH shell session managed by
 * {@link SshClientBridge}.
 *
 * <p>This is the SSH-bridge-internal state for a <em>remote</em> host client. It
 * is intentionally distinct from the domain {@code SessionState} that governs
 * the owned loopback runtime session: a user-chosen remote SSH target is a
 * different concern and must not be conflated with the supervised guest
 * runtime.</p>
 */
public enum SshSessionState {
    /** TCP/KEX connection in progress. */
    CONNECTING,
    /** Server host key is being verified against the trust store. */
    HOST_KEY_PENDING,
    /** Authentication in progress. */
    AUTHENTICATING,
    /** Shell channel is open and stdin/stdout are streaming. */
    RUNNING,
    /** A bounded reconnect attempt is in progress after a drop. */
    RECONNECTING,
    /** Session closed cleanly. */
    CLOSED,
    /** Session failed and is not retrying. */
    FAILED
}
