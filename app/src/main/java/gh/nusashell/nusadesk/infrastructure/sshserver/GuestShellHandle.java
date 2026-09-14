package gh.nusashell.nusadesk.infrastructure.sshserver;

/**
 * Handle over a launched guest shell, exposing only the operations the SSH
 * bridge needs.
 *
 * <p>Local to the {@code sshserver} package so tests can substitute a
 * deterministic fake handle backed by in-memory streams instead of a real
 * Linux process. The real implementation (future PRoot adapter) wraps the
 * guest process and its stream pumps.</p>
 */
public interface GuestShellHandle {

    /** Resize the guest PTY. Best-effort; ignored if the guest cannot resize. */
    void resize(int cols, int rows);

    /**
     * Block until the guest shell exits.
     *
     * @return the guest shell exit code
     * @throws InterruptedException if the waiting thread was interrupted
     */
    int waitFor() throws InterruptedException;

    /**
     * Tear down the guest shell: graceful first, then forced after the grace
     * period. Must be bounded by {@code gracefulMillis} so the bridge's close
     * cannot hang on an unresponsive guest.
     *
     * @param gracefulMillis maximum time to wait for a graceful exit before forcing
     */
    void destroy(long gracefulMillis);
}
