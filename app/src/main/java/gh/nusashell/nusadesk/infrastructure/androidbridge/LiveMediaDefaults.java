package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fixed, host-owned safe defaults for the unified live media stream,
 * Android-free for testing.
 *
 * <p>There is deliberately no per-request configuration surface: the guest
 * contract is one parameterless start that uses exactly these defaults. The
 * camera resolution is the largest safe size at or below 1280x720 (the
 * encoder stays within the fixed H.264 profile the pipeline targets), the
 * frame rate is fixed at 30 fps, and the stream serves at most
 * {@link #CLIENT_LIMIT} local consumers with bounded per-client queues.</p>
 */
public final class LiveMediaDefaults {
    /**
     * Back-facing camera is the only camera the stream ever uses. Value is
     * {@code CameraCharacteristics.LENS_FACING_BACK} (1), kept as a plain int
     * so this policy file stays Android-free and JVM-testable.
     */
    public static final int CAMERA_FACING = 1;
    public static final int TARGET_WIDTH = 1280;
    public static final int TARGET_HEIGHT = 720;
    public static final int TARGET_FPS = 30;
    /** H.264 average bitrate target, about 2 Mbps. */
    public static final int VIDEO_BITRATE_BPS = 2_000_000;
    /** Video codec token returned in {@code media.start} / {@code media.status}. */
    public static final String VIDEO_CODEC = "h264";
    /** AAC-LC sample rate. */
    public static final int AUDIO_SAMPLE_RATE_HZ = 44_100;
    /** AAC-LC mono. */
    public static final int AUDIO_CHANNELS = 1;
    /** AAC-LC average bitrate target, about 64 kbps. */
    public static final int AUDIO_BITRATE_BPS = 64_000;
    /** Audio codec token returned in {@code media.start} / {@code media.status}. */
    public static final String AUDIO_CODEC = "aac";
    /** Fixed bounded client cap: one local guest consumer is the product target. */
    public static final int CLIENT_LIMIT = 2;
    /** Stream path served by the loopback RTSP listener. */
    public static final String RTSP_PATH = "/";
    /** Upper bound of one RTP packet, small enough for loopback and local Wi-Fi. */
    public static final int RTP_MAX_PACKET_BYTES = 1200;
    /** Bound a {@code media.start} bridge wait for the service's typed result. */
    public static final long START_RESULT_TIMEOUT_MILLIS = 10_000L;
    /** Bound the pipeline's wait for encoder metadata before declaring ready. */
    public static final long FORMAT_READY_TIMEOUT_MILLIS = 3_000L;
    /** Bound the pipeline's wait for the camera session to come up. */
    public static final long CAMERA_OPEN_TIMEOUT_MILLIS = 3_000L;
    /** Per-client frame queue capacity; overflow disconnects that client. */
    public static final int FRAME_QUEUE_CAPACITY = 256;
    /** RTCP sender-report interval while a client is playing. */
    public static final long RTCP_INTERVAL_MILLIS = 5_000L;
    /** Bound a client's RTSP handshake (connect to PLAY); slower clients drop. */
    public static final long HANDSHAKE_TIMEOUT_MILLIS = 15_000L;

    private LiveMediaDefaults() {
    }

    /** One candidate camera output size. */
    public static final class Resolution {
        public final int width;
        public final int height;

        public Resolution(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("resolution must be positive");
            }
            this.width = width;
            this.height = height;
        }

        public long area() {
            return (long) width * height;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Resolution)) {
                return false;
            }
            Resolution that = (Resolution) other;
            return width == that.width && height == that.height;
        }

        @Override
        public int hashCode() {
            return Objects.hash(width, height);
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /**
     * Pick the largest safe camera size at or below 1280x720, preferring the
     * aspect ratio closest to 16:9 on an area tie. {@code null} when no
     * candidate fits the bound.
     */
    public static Resolution pickResolution(List<Resolution> available) {
        if (available == null) {
            return null;
        }
        Resolution best = null;
        for (Resolution candidate : available) {
            if (candidate.width > TARGET_WIDTH || candidate.height > TARGET_HEIGHT
                    || candidate.area() > (long) TARGET_WIDTH * TARGET_HEIGHT) {
                continue;
            }
            if (best == null || better(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean better(Resolution candidate, Resolution best) {
        long candidateArea = candidate.area();
        long bestArea = best.area();
        if (candidateArea != bestArea) {
            return candidateArea > bestArea;
        }
        double candidateAspectGap = Math.abs((double) candidate.width / candidate.height - 16.0 / 9.0);
        double bestAspectGap = Math.abs((double) best.width / best.height - 16.0 / 9.0);
        if (Math.abs(candidateAspectGap - bestAspectGap) > 1e-9) {
            return candidateAspectGap < bestAspectGap;
        }
        return candidate.width > best.width;
    }

    /**
     * The AudioSpecificConfig for AAC-LC 44.1 kHz mono, as the hex token the
     * SDP {@code config} field carries for ffmpeg/ffplay consumers: bytes
     * {@code 0x12 0x10} (object type 2 LC, sampling frequency index 4,
     * channel configuration 1). The encoder's own csd is preferred at
     * runtime; this is the deterministic fallback.
     */
    public static String audioSpecificConfigHex() {
        return "1210";
    }

    /** The same AudioSpecificConfig as raw bytes. */
    public static byte[] audioSpecificConfigBytes() {
        return new byte[] {(byte) 0x12, (byte) 0x10};
    }
}
