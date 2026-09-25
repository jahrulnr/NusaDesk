package gh.nusashell.nusadesk.domain.terminal;

/**
 * Lifecycle states of the host-owned terminal SSH session.
 *
 * <p>This is the state of the <em>client-side</em> SSH session between the
 * Android host and the guest {@code sshd} on the fixed loopback endpoint
 * (ADR-0013). It is distinct from {@code SessionState}, which governs the
 * supervised guest runtime: the runtime can stay {@code RUNNING} while its
 * terminal shell dropped or failed, which is exactly when the notification
 * must offer an explicit Reconnect action instead of silently re-attaching.</p>
 */
public enum TerminalSessionState {
    /** No terminal session is attached to the current runtime session. */
    NOT_STARTED,
    /** TCP/KEX/auth/shell-open in progress. */
    CONNECTING,
    /** Shell channel open; stdin/stdout streaming. */
    RUNNING,
    /** A bounded reconnect attempt is in progress after a drop. */
    RECONNECTING,
    /** The channel ended cleanly while the runtime session stayed up. */
    EXITED,
    /** The shell connection was lost and needs an explicit reconnect. */
    DROPPED,
    /** The shell failed and is not retrying. */
    FAILED
}
