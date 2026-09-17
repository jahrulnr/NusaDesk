package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Explicit live media session states exposed to the guest by
 * {@code media.start} / {@code media.status} / {@code media.stop}.
 *
 * <p>The wire contract fixes exactly these four tokens ({@code stopped},
 * {@code starting}, {@code running}, {@code failed}); internal teardown
 * between {@code running} and {@code stopped} is never reported as a fifth
 * state. A state is only ever {@link #RUNNING} when the RTSP listener is
 * actually bound and the encoder metadata is ready — never from a PID or a
 * bare service existence.</p>
 */
public enum LiveMediaState {
    /** No session is active; {@code media.stop} is an idempotent success here. */
    STOPPED,
    /** A start was accepted and the foreground service is bringing the pipeline up. */
    STARTING,
    /** The stream is live: RTSP listening on loopback and encoders producing. */
    RUNNING,
    /** The last start failed; a bounded {@code error} code accompanies the state. */
    FAILED
}
