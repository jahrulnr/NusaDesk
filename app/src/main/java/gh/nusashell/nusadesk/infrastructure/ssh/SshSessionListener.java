package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * Callbacks delivered by {@link SshClientBridge} on a single background thread.
 *
 * <p>The bridge owns the threading; listeners must not block. Output frames
 * are delivered as copied byte ranges so the caller can forward them to a
 * terminal surface (for example an xterm.js WebView) without holding bridge
 * internals. Implementations must treat the byte arrays as read-only.</p>
 */
public interface SshSessionListener {

    /**
     * Session lifecycle state changed.
     *
     * @param state  the new {@link SshSessionState}
     * @param detail a non-null human-readable reason (empty for normal transitions)
     */
    void onState(SshSessionState state, String detail);

    /**
     * Bytes received from the remote shell stdout.
     *
     * @param data the buffer; read-only, valid for the call only
     * @param len  number of valid bytes starting at index 0
     */
    void onStdout(byte[] data, int len);

    /**
     * Bytes received from the remote shell stderr.
     *
     * @param data the buffer; read-only, valid for the call only
     * @param len  number of valid bytes starting at index 0
     */
    void onStderr(byte[] data, int len);

    /**
     * The session ended. Delivered once, after any final {@link #onState}.
     *
     * @param reason a non-null human-readable reason
     */
    void onClosed(String reason);
}
