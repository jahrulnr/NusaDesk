package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.media.MediaRecorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin {@link MediaRecorder} wrapper owning one microphone recording
 * lifecycle for the capture capability. Mirrors the upstream
 * {@code MicRecorderAPI} mapping: {@code MIC} source, one bounded encoder set
 * ({@code aac}, {@code amr_wb}, {@code amr_nb}, {@code opus}), the matching
 * output container, and a platform max-duration bound.
 *
 * <p>The recorder is created and started on the {@link CaptureService}
 * worker thread. {@code MediaRecorder} info/error callbacks are delivered on
 * that thread's looper-less environment through the framework's main-looper
 * fallback, so {@link Listener} methods are invoked on the main thread and
 * must only post back to the service worker.</p>
 *
 * <p>Every platform failure maps to a bounded typed code carried by
 * {@link RecorderException} — platform exception text never crosses the
 * bridge.</p>
 */
public final class MicrophoneRecorderSource {

    /** Bounded typed failure produced by the recorder. */
    public static final class RecorderException extends Exception {
        private final String code;

        public RecorderException(String code, String detail) {
            super(detail);
            this.code = code;
        }

        /** Lowercase-kebab wire code, e.g. {@code microphone-unavailable}. */
        public String code() {
            return code;
        }
    }

    /** Async recorder events; invoked off the service worker thread. */
    public interface Listener {
        /** The configured duration bound was reached; the file is still open. */
        void onLimitReached();

        /** The recorder hit a platform error while capturing. */
        void onError();
    }

    private final Context context;
    private final Path hostPath;
    private final long limitMs;
    private final String encoderName;
    private final long bitrateBps;
    private final long sampleRate;
    private final long channels;

    private volatile Listener listener;
    private volatile boolean recording;
    private MediaRecorder recorder;

    /**
     * {@code limitMs} is the platform max-duration bound (0 = none; the
     * module always passes the 900 s ceiling). {@code bitrateBps},
     * {@code sampleRate}, and {@code channels} are only applied when > 0 —
     * unset options keep the platform default.
     */
    public MicrophoneRecorderSource(Context context, Path hostPath,
                                    long limitMs, String encoderName,
                                    long bitrateBps, long sampleRate,
                                    long channels) {
        this.context = context;
        this.hostPath = hostPath;
        this.limitMs = limitMs;
        this.encoderName = encoderName;
        this.bitrateBps = bitrateBps;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    /** Configure and start the recorder. */
    @SuppressWarnings("deprecation")
    public void start() throws RecorderException {
        int encoder = encoderFor(encoderName);
        MediaRecorder mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(outputFormatFor(encoder));
            mediaRecorder.setAudioEncoder(encoder);
            mediaRecorder.setOutputFile(hostPath.toString());
            if (limitMs > 0) {
                mediaRecorder.setMaxDuration((int) Math.min(limitMs,
                        Integer.MAX_VALUE));
            }
            if (bitrateBps > 0) {
                mediaRecorder.setAudioEncodingBitRate(
                        (int) Math.min(bitrateBps, Integer.MAX_VALUE));
            }
            if (sampleRate > 0) {
                mediaRecorder.setAudioSamplingRate((int) sampleRate);
            }
            if (channels > 0) {
                mediaRecorder.setAudioChannels((int) channels);
            }
            mediaRecorder.setOnInfoListener((mr, what, extra) -> {
                if (what
                        == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Listener current = listener;
                    if (current != null) {
                        current.onLimitReached();
                    }
                }
            });
            mediaRecorder.setOnErrorListener((mr, what, extra) -> {
                Listener current = listener;
                if (current != null) {
                    current.onError();
                }
            });
            mediaRecorder.prepare();
            mediaRecorder.start();
        } catch (SecurityException e) {
            releaseQuietly(mediaRecorder);
            throw new RecorderException("microphone-permission-denied",
                    "record audio grant revoked");
        } catch (IOException e) {
            releaseQuietly(mediaRecorder);
            throw new RecorderException("microphone-unavailable",
                    "prepare failed");
        } catch (IllegalStateException | IllegalArgumentException e) {
            releaseQuietly(mediaRecorder);
            throw new RecorderException("microphone-unavailable",
                    "configure failed");
        } catch (RuntimeException e) {
            releaseQuietly(mediaRecorder);
            throw new RecorderException("microphone-unavailable",
                    "start failed");
        }
        recorder = mediaRecorder;
        recording = true;
    }

    /**
     * Stop and release the recorder, finalizing the container. Returns the
     * staged file size in bytes. A platform stop failure throws —
     * per the MediaRecorder contract the output is then unusable.
     */
    public long stop() throws RecorderException {
        recording = false;
        MediaRecorder mediaRecorder = recorder;
        recorder = null;
        if (mediaRecorder == null) {
            return fileSize();
        }
        try {
            mediaRecorder.stop();
        } catch (RuntimeException e) {
            throw new RecorderException("microphone-unavailable",
                    "stop failed");
        } finally {
            releaseQuietly(mediaRecorder);
        }
        return fileSize();
    }

    /**
     * Best-effort stop used on service teardown. Returns the file size, or
     * -1 when the recording could not be finalized cleanly.
     */
    public long stopQuietly() {
        try {
            return stop();
        } catch (RecorderException e) {
            return -1;
        }
    }

    /** Release a recorder that was never started (failed {@link #start()}). */
    public void release() {
        recording = false;
        releaseQuietly(recorder);
        recorder = null;
    }

    private long fileSize() {
        try {
            return Files.size(hostPath);
        } catch (IOException | SecurityException e) {
            return -1;
        }
    }

    private static void releaseQuietly(MediaRecorder mediaRecorder) {
        if (mediaRecorder == null) {
            return;
        }
        try {
            mediaRecorder.reset();
        } catch (RuntimeException ignored) {
        }
        try {
            mediaRecorder.release();
        } catch (RuntimeException ignored) {
        }
    }

    private static int encoderFor(String name) throws RecorderException {
        switch (name) {
            case "aac":
                return MediaRecorder.AudioEncoder.AAC;
            case "amr_nb":
                return MediaRecorder.AudioEncoder.AMR_NB;
            case "amr_wb":
                return MediaRecorder.AudioEncoder.AMR_WB;
            case "opus":
                // AudioEncoder.OPUS exists since API 29, our minSdk.
                return MediaRecorder.AudioEncoder.OPUS;
            default:
                throw new RecorderException("microphone-unavailable",
                        "unsupported encoder");
        }
    }

    private static int outputFormatFor(int encoder) {
        switch (encoder) {
            case MediaRecorder.AudioEncoder.AMR_NB:
            case MediaRecorder.AudioEncoder.AMR_WB:
                return MediaRecorder.OutputFormat.THREE_GPP;
            case MediaRecorder.AudioEncoder.OPUS:
                return MediaRecorder.OutputFormat.OGG;
            case MediaRecorder.AudioEncoder.AAC:
            default:
                return MediaRecorder.OutputFormat.MPEG_4;
        }
    }
}
