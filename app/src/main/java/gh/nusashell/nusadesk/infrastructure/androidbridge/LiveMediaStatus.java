package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable live media session status, Android-free.
 *
 * <p>The value carries exactly the flat fields the guest contract exposes:
 * the explicit {@link LiveMediaState}, an optional bounded error code, and —
 * only in {@link LiveMediaState#RUNNING} — the loopback RTSP URL and the
 * fixed encoder metadata. It never carries a file path, a token, or any
 * secret, and {@link #responseFields()} never emits one.</p>
 */
public final class LiveMediaStatus {
    private final LiveMediaState state;
    private final String error;
    private final String rtspUrl;
    private final String videoCodec;
    private final String audioCodec;
    private final int videoWidth;
    private final int videoHeight;
    private final int videoFps;
    private final int clientLimit;

    private LiveMediaStatus(LiveMediaState state, String error, String rtspUrl,
                            String videoCodec, String audioCodec, int videoWidth,
                            int videoHeight, int videoFps, int clientLimit) {
        this.state = state;
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
                0, 0, 0, 0);
    }

    public static LiveMediaStatus starting() {
        return new LiveMediaStatus(LiveMediaState.STARTING, null, null, null, null,
                0, 0, 0, 0);
    }

    /**
     * The running state: the RTSP listener is bound on loopback and the
     * encoder metadata below is ready. {@code clientLimit} is the fixed
     * bounded client cap of the stream.
     */
    public static LiveMediaStatus running(String rtspUrl, String videoCodec,
                                          String audioCodec, int videoWidth,
                                          int videoHeight, int videoFps,
                                          int clientLimit) {
        if (rtspUrl == null || rtspUrl.isEmpty()) {
            throw new IllegalArgumentException("rtspUrl must not be blank");
        }
        if (clientLimit < 1) {
            throw new IllegalArgumentException("clientLimit must be positive");
        }
        return new LiveMediaStatus(LiveMediaState.RUNNING, null, rtspUrl, videoCodec,
                audioCodec, videoWidth, videoHeight, videoFps, clientLimit);
    }

    public static LiveMediaStatus failed(LiveMediaError error) {
        if (error == null) {
            throw new IllegalArgumentException("error must not be null");
        }
        return new LiveMediaStatus(LiveMediaState.FAILED, error.code(), null, null,
                null, 0, 0, 0, 0);
    }

    public LiveMediaState getState() {
        return state;
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
     * Flat protocol fields for one status. {@code state} is always present;
     * {@code error} only in the failed state; the RTSP/encoder metadata only
     * in the running state. Values are protocol-safe (String and Long).
     */
    public Map<String, Object> responseFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", state.name().toLowerCase(java.util.Locale.ROOT));
        if (state == LiveMediaState.FAILED) {
            fields.put("error", error);
        } else if (state == LiveMediaState.RUNNING) {
            fields.put("rtsp_url", rtspUrl);
            fields.put("video_codec", videoCodec);
            fields.put("audio_codec", audioCodec);
            fields.put("video_width", (long) videoWidth);
            fields.put("video_height", (long) videoHeight);
            fields.put("video_fps", (long) videoFps);
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
        return Objects.hash(state, error, rtspUrl, videoCodec, audioCodec,
                videoWidth, videoHeight, videoFps, clientLimit);
    }

    @Override
    public String toString() {
        return "LiveMediaStatus{state=" + state
                + (error != null ? ", error=" + error : "")
                + (rtspUrl != null ? ", rtspUrl=" + rtspUrl : "")
                + "}";
    }
}
