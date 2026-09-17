package gh.nusashell.nusadesk.infrastructure.service;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;

import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaDefaults;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaMode;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaError;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStartException;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaState;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LoopbackRtspServer;

/**
 * Production live media pipeline: Camera2 + H.264, microphone + AAC-LC, and
 * the loopback-only RTSP server, owned by {@link LiveMediaService}.
 *
 * <p>Start order is fixed: the RTSP listener binds {@code 127.0.0.1} on an
 * ephemeral port first (so the returned URL is the real bound port), then
 * the camera/video and audio sources come up, and only when both encoder
 * metadata sets are ready does {@link #start()} return
 * {@link LiveMediaState#RUNNING} with the loopback URL. Every failure path
 * tears the partial pipeline down and returns a bounded failed status. No
 * capture artifact is written anywhere, so there is nothing to clean up on
 * stop.</p>
 */
public final class AndroidLiveMediaPipeline implements LiveMediaPipeline {
    private static final String TAG = "AndroidLiveMediaPipeline";

    private final Context context;
    private volatile LoopbackRtspServer server;
    private volatile Camera2EncoderSource video;
    private volatile AudioEncoderSource audio;
    private volatile boolean started;

    public AndroidLiveMediaPipeline(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized LiveMediaStatus start(LiveMediaMode mode) {
        if (mode == null) {
            return LiveMediaStatus.failed(LiveMediaError.START_FAILED);
        }
        if (started) {
            return LiveMediaStatus.failed(mode, LiveMediaError.BUSY);
        }
        LoopbackRtspServer rtsp = new LoopbackRtspServer(
                loopbackAddress(), LiveMediaDefaults.CLIENT_LIMIT);
        try {
            rtsp.start();
            server = rtsp;
            Camera2EncoderSource.VideoFormatInfo videoFormat = null;
            AudioEncoderSource.AudioFormatInfo audioFormat = null;
            if (mode.hasVideo()) {
                Camera2EncoderSource videoSource =
                        new Camera2EncoderSource(context, rtsp);
                video = videoSource;
                videoSource.start();
                videoFormat = videoSource.awaitFormat(
                        LiveMediaDefaults.FORMAT_READY_TIMEOUT_MILLIS);
                if (videoFormat == null) {
                    throw new LiveMediaStartException(LiveMediaError.ENCODER_UNAVAILABLE,
                            "H.264 metadata did not arrive");
                }
            }
            if (mode.hasAudio()) {
                AudioEncoderSource audioSource = new AudioEncoderSource(context, rtsp);
                audio = audioSource;
                audioSource.start();
                audioFormat = audioSource.awaitFormat(
                        LiveMediaDefaults.FORMAT_READY_TIMEOUT_MILLIS);
                if (audioFormat == null) {
                    throw new LiveMediaStartException(LiveMediaError.ENCODER_UNAVAILABLE,
                            "AAC metadata did not arrive");
                }
            }
            if (videoFormat != null) {
                rtsp.setVideoFormat(videoFormat.sps, videoFormat.pps,
                        videoFormat.width, videoFormat.height);
            }
            if (audioFormat != null) {
                rtsp.setAudioFormat(audioFormat.config, audioFormat.sampleRate,
                        audioFormat.channels);
            }
            started = true;
            String rtspUrl = "rtsp://127.0.0.1:" + rtsp.getPort()
                    + LiveMediaDefaults.RTSP_PATH;
            Log.i(TAG, "live media running at " + rtspUrl + " mode=" + mode.wireName());
            return LiveMediaStatus.running(mode, rtspUrl,
                    mode.hasVideo() ? LiveMediaDefaults.VIDEO_CODEC : null,
                    videoFormat != null ? videoFormat.width : 0,
                    videoFormat != null ? videoFormat.height : 0,
                    mode.hasVideo() ? LiveMediaDefaults.TARGET_FPS : 0,
                    mode.hasAudio() ? LiveMediaDefaults.AUDIO_CODEC : null,
                    LiveMediaDefaults.CLIENT_LIMIT);
        } catch (LiveMediaStartException e) {
            Log.w(TAG, "live media start failed: " + e.getError().code(), e);
            stopInternal();
            return LiveMediaStatus.failed(mode, e.getError());
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "live media start failed", e);
            stopInternal();
            return LiveMediaStatus.failed(mode, LiveMediaError.START_FAILED);
        }
    }

    @Override
    public synchronized void stop() {
        if (!started && server == null) {
            return; // nothing was ever started
        }
        stopInternal();
    }

    /** Tear down every owned component; idempotent and safe mid-start. */
    private void stopInternal() {
        started = false;
        AudioEncoderSource audioSource = audio;
        audio = null;
        if (audioSource != null) {
            audioSource.stop();
        }
        Camera2EncoderSource videoSource = video;
        video = null;
        if (videoSource != null) {
            videoSource.stop();
        }
        LoopbackRtspServer rtsp = server;
        server = null;
        if (rtsp != null) {
            rtsp.close();
        }
    }

    private static InetAddress loopbackAddress() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (IOException e) {
            throw new IllegalStateException("IPv4 loopback is not resolvable", e);
        }
    }
}
