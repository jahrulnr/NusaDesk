package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;

/**
 * One live media pipeline: capture + hardware encode + loopback RTSP serve.
 *
 * <p>{@link #start()} is bounded and returns only a terminal result — a
 * {@link LiveMediaState#RUNNING} status once the RTSP listener is bound and
 * the encoder metadata is ready, or a failed status with a bounded error.
 * {@link #stop()} tears down camera, microphone, encoders, sockets, clients,
 * and threads, and is idempotent. The production implementation is
 * {@link AndroidLiveMediaPipeline}; the interface exists so the foreground
 * service can be tested on the JVM with a deterministic fake.</p>
 */
public interface LiveMediaPipeline {

    /** Start the pipeline; bounded, terminal result only (running or failed). */
    LiveMediaStatus start();

    /** Tear the pipeline down. Idempotent. */
    void stop();
}
