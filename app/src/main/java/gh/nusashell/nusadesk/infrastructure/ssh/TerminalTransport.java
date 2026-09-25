package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * SSH transport seam for the host-owned terminal session.
 *
 * <p>The {@code TerminalSessionController} drives the terminal session purely
 * through this interface so its lifecycle logic is unit-testable with a fake
 * transport; the production implementation wraps {@link SshClientBridge} with
 * the fixed local-endpoint configuration (ADR-0013). Only one session is ever
 * active at a time: {@link #start} replaces the previous session, so callers
 * must {@link #close} before starting a new one.</p>
 */
public interface TerminalTransport {

    /**
     * Begin a session. The listener receives lifecycle states and streamed
     * output on transport-owned threads and must not block.
     *
     * @param command {@code null} opens an interactive shell channel; a
     *                non-null command opens an exec channel (still with a
     *                PTY) that runs the command on the remote side. The
     *                command is opaque here — it is validated upstream
     *                ({@code TerminalCommand}) and is never executed on the
     *                host (ADR-0054).
     */
    void start(SshSessionConfig config, String command, SshSessionListener listener);

    /**
     * Send bytes to the shell stdin. Thread-safe; silently dropped when no
     * channel is open.
     */
    void write(byte[] data);

    /** Resize the shell PTY. Thread-safe; ignored when no channel is open. */
    void resize(int cols, int rows);

    /** Gracefully stop the session and release resources. Idempotent. */
    void close();
}
