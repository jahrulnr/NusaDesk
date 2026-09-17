package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaState;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;

/**
 * Process-local registry of the live media session, mirroring the
 * {@code RuntimeWorkloadRegistry} pattern: the foreground service publishes
 * typed results and the bridge controller awaits them.
 *
 * <p>The registry carries no secret material — only the explicit
 * {@link LiveMediaStatus} value — and it is process-local, so a process death
 * resets it to {@link LiveMediaState#STOPPED} and no false {@code running}
 * survives a restart. The product owns exactly one Linux session, hence
 * exactly one live media session at a time.</p>
 */
public final class LiveMediaRegistry {

    private static final LiveMediaRegistry INSTANCE = new LiveMediaRegistry();

    public static LiveMediaRegistry getInstance() {
        return INSTANCE;
    }

    private LiveMediaStatus status = LiveMediaStatus.stopped();

    private LiveMediaRegistry() {
    }

    /** A start was accepted and the pipeline is coming up. */
    public synchronized void publishStarting() {
        status = LiveMediaStatus.starting();
        notifyAll();
    }

    /** The terminal result of a start: running with metadata, or failed with a bounded error. */
    public synchronized void publishResult(LiveMediaStatus result) {
        if (result == null
                || (result.getState() != LiveMediaState.RUNNING
                && result.getState() != LiveMediaState.FAILED)) {
            throw new IllegalArgumentException(
                    "start result must be running or failed");
        }
        status = result;
        notifyAll();
    }

    /** The session stopped (explicit stop or service teardown). Idempotent. */
    public synchronized void publishStopped() {
        status = LiveMediaStatus.stopped();
        notifyAll();
    }

    /** Current session status; never {@code null}. */
    public synchronized LiveMediaStatus currentStatus() {
        return status;
    }

    /**
     * Block until the in-flight start publishes its result, up to
     * {@code timeoutMillis}. Returns {@code null} on timeout while the state
     * is still {@link LiveMediaState#STARTING}; returns the current status
     * immediately otherwise (including a result published before the wait).
     */
    public synchronized LiveMediaStatus awaitStartResult(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (status.getState() == LiveMediaState.STARTING) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return status;
    }
}
