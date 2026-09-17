package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Host-owned live media session control, Android-free for testing.
 *
 * <p>A controller owns exactly the current guest bridge session's live media
 * stream. {@link #start()} is a bounded blocking call: it returns only when
 * the stream is actually {@link LiveMediaState#RUNNING} (RTSP listener bound
 * on loopback and encoder metadata ready) or {@link LiveMediaState#FAILED}
 * with a bounded error — never a fabricated success. {@link #stop()} is
 * idempotent, and {@link #close()} is terminal: it stops the session so no
 * camera, encoder, socket, or client outlives the owning bridge.</p>
 */
public interface LiveMediaController extends AutoCloseable {
    /**
     * Start a stream in the requested {@link LiveMediaMode} with the
     * host-owned fixed defaults.
     *
     * <p>Bounded: returns within {@link LiveMediaDefaults#START_RESULT_TIMEOUT_MILLIS}
     * of the foreground-service start. An already-running session in the same
     * mode is an idempotent success with the current status; a running
     * session in another mode is {@link LiveMediaError#MODE_CONFLICT}; a
     * start already in flight is {@link LiveMediaError#BUSY}; a closed
     * controller is {@link LiveMediaError#UNAVAILABLE}. Only the permissions
     * the mode needs are checked.</p>
     */
    LiveMediaStatus start(LiveMediaMode mode);

    /** Current session status; always a valid status, never an exception. */
    LiveMediaStatus status();

    /** Stop the session and tear the pipeline down. Idempotent. */
    void stop();

    /**
     * Terminate the session permanently: stop the pipeline and make further
     * {@link #start()} calls fail with {@link LiveMediaError#UNAVAILABLE}.
     * Idempotent; called by the owning bridge session on close.
     */
    @Override
    void close();
}
