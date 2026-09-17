package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Typed live media failures, kept Android-free for testing.
 *
 * <p>Each value maps to exactly one wire code. The codes are the bounded
 * contract from the guest documentation: no platform exception text, file
 * path, or secret ever crosses the bridge inside an {@code error} field.</p>
 */
public enum LiveMediaError {
    /** The runtime permission is missing and no denial is recorded. */
    PERMISSION_REQUIRED("media-permission-required"),
    /** The runtime permission is missing and was previously denied. */
    PERMISSION_DENIED("media-permission-denied"),
    /** The app was not eligible to start a camera/microphone foreground service. */
    FOREGROUND_REQUIRED("media-foreground-required"),
    /** The platform cannot serve the stream (no camera, mic busy, provider failure). */
    UNAVAILABLE("media-unavailable"),
    /** A session is already starting, or the camera is in use by another app. */
    BUSY("media-busy"),
    /** A session with a different track mode is already running. */
    MODE_CONFLICT("media-mode-conflict"),
    /** A hardware encoder or codec metadata could not be prepared. */
    ENCODER_UNAVAILABLE("media-encoder-unavailable"),
    /** The start did not complete within its bound for an unspecified reason. */
    START_FAILED("media-start-failed");

    private final String code;

    LiveMediaError(String code) {
        this.code = code;
    }

    /** The exact wire token for this error. */
    public String code() {
        return code;
    }
}
