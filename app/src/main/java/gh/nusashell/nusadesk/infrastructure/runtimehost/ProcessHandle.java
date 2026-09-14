package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.io.InputStream;

/**
 * Port over a started guest process, exposing only the operations the
 * {@link RuntimeSupervisor} needs.
 *
 * <p>Local to the runtimehost package so tests can substitute a deterministic
 * fake handle backed by an in-memory stdout buffer instead of a real Linux
 * process. The concrete {@link JdkProcessHandle} wraps {@link java.lang.Process}.</p>
 */
public interface ProcessHandle {
    /** The guest stdout stream used for the readiness handshake and bounded logs. */
    InputStream getStdout();

    /** The guest stderr stream, captured separately for diagnostics. */
    InputStream getStderr();

    /** Whether the process is still alive. */
    boolean isAlive();

    /** Request graceful termination (SIGTERM equivalent). */
    void destroyGracefully();

    /** Force immediate termination (SIGKILL equivalent). */
    void destroyForcibly();

    /**
     * Wait for the process to exit within the timeout.
     *
     * @param timeoutMillis maximum time to wait
     * @return {@code true} if the process exited within the timeout
     * @throws InterruptedException if the waiting thread was interrupted
     */
    boolean waitFor(long timeoutMillis) throws InterruptedException;

    /** The process exit code; only valid once the process has exited. */
    int exitValue();
}
