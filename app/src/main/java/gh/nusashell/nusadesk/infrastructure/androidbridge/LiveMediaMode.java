package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Which capture tracks one live media session carries, Android-free.
 *
 * <p>The guest picks the mode through the allowlisted method it calls
 * ({@code media.start} = both, {@code media.camera.start} = camera only,
 * {@code media.microphone.start} = microphone only). Everything downstream —
 * the runtime permission that is required, the foreground-service types, the
 * started sources, the SDP tracks, and the status fields — derives from this
 * value, so a camera-only session never demands a microphone grant.</p>
 */
public enum LiveMediaMode {
    /** Camera and microphone in one session (the original contract). */
    BOTH(true, true),
    /** Video only: no microphone grant, no microphone foreground-service type. */
    CAMERA(true, false),
    /** Audio only: no camera grant, no camera foreground-service type. */
    MICROPHONE(false, true);

    private final boolean video;
    private final boolean audio;

    LiveMediaMode(boolean video, boolean audio) {
        this.video = video;
        this.audio = audio;
    }

    /** True when this mode captures and serves an H.264 video track. */
    public boolean hasVideo() {
        return video;
    }

    /** True when this mode captures and serves an AAC audio track. */
    public boolean hasAudio() {
        return audio;
    }

    /** Stable lower-case token used in status responses and docs. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
