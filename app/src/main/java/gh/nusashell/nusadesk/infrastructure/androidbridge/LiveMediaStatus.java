package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable live media session status, Android-free.
 *
 * <p>The value carries exactly the flat fields the guest contract exposes:
 * the explicit {@link LiveMediaState}, the {@link LiveMediaMode} the session
 * was started with, an optional bounded error code, and — only in
 * {@link LiveMediaState#RUNNING} — the loopback RTSP URL plus the metadata of
 * the tracks that mode actually carries. A camera-only session never reports
 * an audio codec and a microphone-only session never reports video sizes, so
 * the status cannot imply a track that does not exist. It never carries a
 * file path, a token, or any secret, and {@link #responseFields()} never
 * emits one.</p>
 */
public final class LiveMediaStatus {
    private final LiveMediaState state;
    private final LiveMediaMode mode;
    private final String error;
    private final String rtspUrl;
    private final String videoCodec;
    private final String audioCodec;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoFps;
    private final int clientLimit;

    private LiveMediaStatus(LiveMediaState state, LiveMediaMode mode, String error,
                            String rtspUrl, String videoCodec, String audioCodec,
                            int videoWidth, int videoHeight, int videoFps,
                            int clientLimit) {
        this.state = state;
        this.mode = mode;
        this.error = error;
        this.rtspUrl = rtspUrl;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoFps = videoFps;
        this.clientLimit = clientLimit;
    }

    public static LiveMediaStatus stopped() {
        return new LiveMediaStatus(LiveMediaState.STOPPED, null, null, null, null,
                null, 0, 0, 0, 0);
    }

    /** A start was accepted; the requested mode is reported while it comes up. */
    public static LiveMediaStatus starting(LiveMediaMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return new LiveMediaStatus(LiveMediaState.STARTING, mode, null, null, null,
                null, 0, 0, 0, 0);
    }

    /**
     * The running state: the RTSP listener is bound on loopback and the
     * metadata of every track the mode carries is ready. {@code clientLimit}
     * is the fixed bounded client cap of the stream.
     *
     * <p>Track metadata must match the mode exactly: a video mode needs a
     * codec and positive dimensions, an audio mode needs a codec, and a track
     * the mode does not carry must stay absent rather than be invented.</p>
     */
    public static LiveMediaStatus running(LiveMediaMode mode, String rtspUrl,
                                          String videoCodec, int videoWidth,
                                          int videoHeight, int videoFps,
                                          String audioCodec, int clientLimit) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (rtspUrl == null || rtspUrl.isEmpty()) {
            throw new IllegalArgumentException("rtspUrl must not be blank");
        }
        if (clientLimit < 1) {
            throw new IllegalArgumentException("clientLimit must be positive");
        }
        if (mode.hasVideo()) {
            if (videoCodec == null || videoCodec.isEmpty()
                    || videoWidth < 1 || videoHeight < 1 || videoFps < 1) {
                throw new IllegalArgumentException("video mode needs video metadata");
            }
        } else if (videoCodec != null || videoWidth != 0 || videoHeight != 0
                || videoFps != 0) {
            throw new IllegalArgumentException("audio-only mode carries no video metadata");
        }
        if (mode.hasAudio()) {
            if (audioCodec == null || audioCodec.isEmpty()) {
                throw new IllegalArgumentException("audio mode needs an audio codec");
            }
        } else if (audioCodec != null) {
            throw new IllegalArgumentException("video-only mode carries no audio codec");
        }
        return new LiveMediaStatus(LiveMediaState.RUNNING, mode, null, rtspUrl,
                videoCodec, audioCodec, videoWidth, videoHeight, videoFps, clientLimit);
    }

    /** Failed start for a known requested mode. */
    public static LiveMediaStatus failed(LiveMediaMode mode, LiveMediaError error) {
        if (error == null) {
            throw new IllegalArgumentException("error must not be null");
        }
        return new LiveMediaStatus(LiveMediaState.FAILED, mode, error.code(), null,
                null, null, 0, 0, 0, 0);
    }

    /** Failed state without a session mode (defensive paths, status fallback). */
    public static LiveMediaStatus failed(LiveMediaError error) {
        return failed(null, error);
    }

    public LiveMediaState getState() {
        return state;
    }

    /** The requested/active track mode, or {@code null} in the stopped state. */
    public LiveMediaMode getMode() {
        return mode;
    }

    /** Bounded wire error code, non-null only in {@link LiveMediaState#FAILED}. */
    public String getError() {
        return error;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public String getVideoCodec() {
        return videoCodec;
    }

    public String getAudioCodec() {
        return audioCodec;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public int getVideoFps() {
        return videoFps;
    }

    public int getClientLimit() {
        return clientLimit;
    }

    /**
     * Flat protocol fields for one status: {@code state} always, {@code mode}
     * whenever a session mode is known, {@code error} only in the failed
     * state, and the RTSP/track metadata only in the running state — video
     * fields only for a video mode, {@code audio_codec} only for an audio
     * mode. Values are protocol-safe (String and Long).
     */
    public Map<String, Object> responseFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", state.name().toLowerCase(java.util.Locale.ROOT));
        if (mode != null) {
            fields.put("mode", mode.wireName());
        }
        if (state == LiveMediaState.FAILED) {
            fields.put("error", error);
        } else if (state == LiveMediaState.RUNNING) {
            fields.put("rtsp_url", rtspUrl);
            if (mode.hasVideo()) {
                fields.put("video_codec", videoCodec);
                fields.put("video_width", (long) videoWidth);
                fields.put("video_height", (long) videoHeight);
                fields.put("video_fps", (long) videoFps);
            }
            if (mode.hasAudio()) {
                fields.put("audio_codec", audioCodec);
            }
            fields.put("client_limit", (long) clientLimit);
        }
        return fields;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LiveMediaStatus)) {
            return false;
        }
        LiveMediaStatus that = (LiveMediaStatus) other;
        return state == that.state
                && mode == that.mode
                && Objects.equals(error, that.error)
                && Objects.equals(rtspUrl, that.rtspUrl)
                && Objects.equals(videoCodec, that.videoCodec)
                && Objects.equals(audioCodec, that.audioCodec)
                && videoWidth == that.videoWidth
                && videoHeight == that.videoHeight
                && videoFps == that.videoFps
                && clientLimit == that.clientLimit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, mode, error, rtspUrl, videoCodec, audioCodec,
                videoWidth, videoHeight, videoFps, clientLimit);
    }

    @Override
    public String toString() {
        return "LiveMediaStatus{state=" + state
                + (mode != null ? ", mode=" + mode.wireName() : "")
                + (error != null ? ", error=" + error : "")
                + (rtspUrl != null ? ", rtspUrl=" + rtspUrl : "")
                + "}";
    }
}
