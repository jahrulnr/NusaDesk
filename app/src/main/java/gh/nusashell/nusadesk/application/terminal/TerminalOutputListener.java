package gh.nusashell.nusadesk.application.terminal;

/**
 * Streaming output sink for the host-owned terminal session.
 *
 * <p>Output is a live stream, not retained state: the listener receives only
 * the bytes produced while it is registered, so a detached surface misses
 * nothing it could render and a re-attached surface starts from its own fresh
 * terminal page. Callbacks may arrive on background threads; implementations
 * must marshal to their own thread and must not block. Byte arrays are
 * read-only and valid only for the call.</p>
 */
public interface TerminalOutputListener {

    /** Bytes received from the shell stdout. */
    void onStdout(byte[] data, int len);

    /** Bytes received from the shell stderr. */
    void onStderr(byte[] data, int len);
}
