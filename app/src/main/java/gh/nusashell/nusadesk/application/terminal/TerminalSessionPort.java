package gh.nusashell.nusadesk.application.terminal;

/**
 * The presentation-facing boundary of the host-owned terminal SSH session.
 *
 * <p>The session itself lives in the host service (ADR-0033), not in the
 * terminal view, so its lifetime follows the runtime session instead of the
 * view. A surface only drives input and geometry through this port and renders
 * the session's state from the terminal session bus; it never opens or closes
 * the SSH connection itself.</p>
 */
public interface TerminalSessionPort {

    /**
     * Forward terminal input to the open shell stdin. Safe to call from any
     * thread; silently dropped when no shell channel is open.
     */
    void write(byte[] data);

    /**
     * Report the terminal's current size so the shell PTY stays in agreement.
     * The size is cached by the session owner and applied to a shell opened
     * later, so reporting it on every attach is safe.
     */
    void resize(int cols, int rows);

    /**
     * Explicitly re-attach to the running runtime session after the shell
     * dropped or failed. A no-op when no runtime session is running; the
     * guest daemon is still up, so a fresh shell can open.
     */
    void reconnect();

    /**
     * Register the streaming output sink for the current session. A single
     * listener at a time: registering replaces the previous one, and
     * {@code null} detaches. Output is delivered only while the listener is
     * registered.
     */
    void setOutputListener(TerminalOutputListener listener);
}
