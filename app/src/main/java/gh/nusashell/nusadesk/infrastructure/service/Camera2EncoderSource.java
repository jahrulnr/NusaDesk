package gh.nusashell.nusadesk.infrastructure.service;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gh.nusashell.nusadesk.infrastructure.androidbridge.H264RtpPacketizer;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaDefaults;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaError;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStartException;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LoopbackRtspServer;

/**
 * Camera2 + H.264 hardware encoder source for the live media pipeline.
 *
 * <p>Opens the back camera at the largest safe size at or below 1280x720
 * (30 fps) and feeds a surface-mode {@code video/avc} encoder; the pump
 * thread splits every access unit into Annex-B NAL units and publishes them
 * to the {@link LoopbackRtspServer}. The encoder's SPS/PPS are captured from
 * the output format and exposed through {@link #awaitFormat(long)} so the
 * pipeline only reports ready once the SDP can be built.</p>
 *
 * <p>This class is device-only: JVM tests cannot open a camera or run a
 * hardware encoder, so its behavior is verified on physical devices, not
 * faked on the JVM.</p>
 */
public final class Camera2EncoderSource {
    private static final String TAG = "Camera2EncoderSource";

    /** Ready encoder metadata: raw SPS/PPS NAL units and coded dimensions. */
    public static final class VideoFormatInfo {
        public final byte[] sps;
        public final byte[] pps;
        public final int width;
        public final int height;

        VideoFormatInfo(byte[] sps, byte[] pps, int width, int height) {
            this.sps = sps;
            this.pps = pps;
            this.width = width;
            this.height = height;
        }
    }

    private final Context context;
    private final LoopbackRtspServer server;
    private final Object formatLock = new Object();
    private volatile VideoFormatInfo format;

    /**
     * Session type for the capture session. {@code SESSION_TYPE_NORMAL} (0)
     * is the regular camera+encoder session type on every API level from 28;
     * newer SDKs renamed it {@code SESSION_REGULAR} and removed the
     * {@code SESSION_TYPE_RECORD} hint, so a literal is the only constant
     * that exists across the whole minSdk-to-compileSdk range.
     */
    private static final int SESSION_TYPE_REGULAR = 0;

    private MediaCodec codec;
    private android.view.Surface encoderInputSurface;
    private CameraDevice camera;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Thread pumpThread;
    private volatile boolean running;

    public Camera2EncoderSource(Context context, LoopbackRtspServer server) {
        if (context == null || server == null) {
            throw new IllegalArgumentException("context and server must not be null");
        }
        this.context = context.getApplicationContext();
        this.server = server;
    }

    /**
     * Open the camera and start the encoder and pump. Bounded: the camera
     * session must come up within {@link LiveMediaDefaults#CAMERA_OPEN_TIMEOUT_MILLIS}.
     */
    public void start() throws LiveMediaStartException {
        CameraManager manager =
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics = backCamera(manager);
        LiveMediaDefaults.Resolution resolution = pickResolution(characteristics);
        if (resolution == null) {
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "no camera size at or below 1280x720");
        }
        MediaCodec encoder = createVideoEncoder(resolution);
        try {
            encoder.start();
        } catch (RuntimeException e) {
            encoder.release();
            throw new LiveMediaStartException(LiveMediaError.ENCODER_UNAVAILABLE,
                    "video encoder start failed");
        }
        codec = encoder;
        running = true;
        startPump();
        openCameraSession(manager, encoderInputSurface, resolution);
    }

    /**
     * Wait up to {@code timeoutMillis} for the encoder metadata; {@code null}
     * when the bound expired without it.
     */
    public VideoFormatInfo awaitFormat(long timeoutMillis) {
        synchronized (formatLock) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (format == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                try {
                    formatLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return format;
        }
    }

    /** Stop the camera, encoder, and pump. Idempotent. */
    public void stop() {
        running = false;
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "could not close capture session", e);
            }
            captureSession = null;
        }
        if (camera != null) {
            camera.close();
            camera = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or never started.
            }
            codec.release();
            codec = null;
        }
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
        Thread pump = pumpThread;
        if (pump != null) {
            try {
                pump.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pumpThread = null;
        }
    }

    @SuppressLint("MissingPermission")
    private CameraCharacteristics backCamera(CameraManager manager)
            throws LiveMediaStartException {
        // Permission is enforced by the controller before start; the lint
        // suppression records that this call is never reached without a grant.
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics =
                        manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == LiveMediaDefaults.CAMERA_FACING) {
                    return characteristics;
                }
            }
        } catch (CameraAccessException e) {
            throw cameraAccessError(e);
        } catch (SecurityException e) {
            throw new LiveMediaStartException(LiveMediaError.PERMISSION_DENIED,
                    "camera grant revoked before open");
        }
        throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                "no back camera on this device");
    }

    private static LiveMediaDefaults.Resolution pickResolution(
            CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return null;
        }
        List<LiveMediaDefaults.Resolution> candidates = new ArrayList<>();
        for (Size size : map.getOutputSizes(SurfaceTexture.class)) {
            candidates.add(new LiveMediaDefaults.Resolution(size.getWidth(), size.getHeight()));
        }
        return LiveMediaDefaults.pickResolution(candidates);
    }

    private MediaCodec createVideoEncoder(LiveMediaDefaults.Resolution resolution)
            throws LiveMediaStartException {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, resolution.width, resolution.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, LiveMediaDefaults.VIDEO_BITRATE_BPS);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, LiveMediaDefaults.TARGET_FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // Sync frames carry SPS/PPS in-band (belt and suspenders on top of
        // the output-format csd capture), so every consumer gets a decodable
        // keyframe without out-of-band state.
        format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        MediaCodec encoder = null;
        try {
            // createEncoderByType declares IOException on newer SDKs (and
            // IllegalStateException when the type is absent); both mean no
            // usable H.264 encoder, which is a typed ENCODER_UNAVAILABLE.
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (IllegalArgumentException | MediaCodec.CodecException cbrRejected) {
                // A device without CBR support keeps the encoder defaults.
                format.removeKey(MediaFormat.KEY_BITRATE_MODE);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoderInputSurface = encoder.createInputSurface();
            return encoder;
        } catch (IllegalArgumentException | MediaCodec.CodecException | IOException e) {
            if (encoder != null) {
                encoder.release();
            }
            throw new LiveMediaStartException(LiveMediaError.ENCODER_UNAVAILABLE,
                    "H.264 encoder could not be configured");
        }
    }

    private void startPump() {
        pumpThread = new Thread(this::pumpLoop, "live-media-video-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    private void pumpLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            MediaCodec current = codec;
            if (current == null) {
                return;
            }
            int index;
            try {
                index = current.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureCsd(current.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                ByteBuffer buffer = current.getOutputBuffer(index);
                byte[] bytes = new byte[info.size];
                if (buffer != null) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(bytes);
                }
                current.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    captureCsd(bytes);
                    continue;
                }
                List<byte[]> nalus = H264RtpPacketizer.splitAnnexB(bytes);
                if (!nalus.isEmpty()) {
                    boolean keyframe = false;
                    for (byte[] nalu : nalus) {
                        keyframe |= H264RtpPacketizer.isIdr(nalu);
                    }
                    server.publishVideoFrame(nalus, info.presentationTimeUs, keyframe);
                }
            } catch (IllegalStateException e) {
                return; // codec or RTSP server closed: stop the pump.
            } catch (RuntimeException e) {
                Log.w(TAG, "video pump failed", e);
                return;
            }
        }
    }

    private void captureCsd(MediaFormat outputFormat) {
        ByteBuffer spsBuffer = outputFormat.getByteBuffer("csd-0");
        ByteBuffer ppsBuffer = outputFormat.getByteBuffer("csd-1");
        if (spsBuffer == null || ppsBuffer == null) {
            return;
        }
        byte[] sps = firstNal(spsBuffer);
        byte[] pps = firstNal(ppsBuffer);
        if (sps == null || pps == null) {
            return;
        }
        synchronized (formatLock) {
            if (format == null) {
                format = new VideoFormatInfo(sps, pps,
                        outputFormat.getInteger(MediaFormat.KEY_WIDTH),
                        outputFormat.getInteger(MediaFormat.KEY_HEIGHT));
                formatLock.notifyAll();
            }
        }
    }

    private void captureCsd(byte[] codecConfigBytes) {
        // Some devices deliver SPS/PPS as a CODEC_CONFIG output buffer
        // instead of (or before) the output-format csd; capture the first
        // SPS/PPS pair found there.
        List<byte[]> nalus = H264RtpPacketizer.splitAnnexB(codecConfigBytes);
        byte[] sps = null;
        byte[] pps = null;
        for (byte[] nalu : nalus) {
            int type = H264RtpPacketizer.nalType(nalu);
            if (type == H264RtpPacketizer.NAL_TYPE_SPS && sps == null) {
                sps = nalu;
            } else if (type == H264RtpPacketizer.NAL_TYPE_PPS && pps == null) {
                pps = nalu;
            }
        }
        if (sps != null && pps != null) {
            synchronized (formatLock) {
                if (format == null) {
                    format = new VideoFormatInfo(sps, pps,
                            LiveMediaDefaults.TARGET_WIDTH, LiveMediaDefaults.TARGET_HEIGHT);
                    formatLock.notifyAll();
                }
            }
        }
    }

    private static byte[] firstNal(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        List<byte[]> nalus = H264RtpPacketizer.splitAnnexB(bytes);
        return nalus.isEmpty() ? null : nalus.get(0);
    }

    @SuppressLint("MissingPermission")
    private void openCameraSession(CameraManager manager, android.view.Surface inputSurface,
                                   LiveMediaDefaults.Resolution resolution)
            throws LiveMediaStartException {
        // Permission is enforced by the controller before start; see backCamera.
        cameraThread = new HandlerThread("live-media-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<LiveMediaError> failure = new AtomicReference<>();
        try {
            String cameraId = backCameraId(manager);
            if (cameraId == null) {
                throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                        "back camera vanished between discovery and open");
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera = device;
                    opened.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    failure.compareAndSet(null, LiveMediaError.UNAVAILABLE);
                    opened.countDown();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    failure.compareAndSet(null, error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                            || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                            ? LiveMediaError.BUSY : LiveMediaError.UNAVAILABLE);
                    opened.countDown();
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            throw cameraAccessError(e);
        } catch (SecurityException e) {
            throw new LiveMediaStartException(LiveMediaError.PERMISSION_DENIED,
                    "camera grant revoked before open");
        }
        boolean openedOk;
        try {
            openedOk = opened.await(LiveMediaDefaults.CAMERA_OPEN_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "camera open interrupted");
        }
        if (!openedOk) {
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "camera open timed out");
        }
        LiveMediaError openFailure = failure.get();
        if (openFailure != null) {
            throw new LiveMediaStartException(openFailure, "camera open failed");
        }
        createCaptureSession(inputSurface, resolution);
    }

    private String backCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == LiveMediaDefaults.CAMERA_FACING) {
                return id;
            }
        }
        return null;
    }

    private void createCaptureSession(android.view.Surface inputSurface,
                                      LiveMediaDefaults.Resolution resolution)
            throws LiveMediaStartException {
        CameraDevice device = camera;
        if (device == null) {
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "camera device vanished before session creation");
        }
        CountDownLatch configured = new CountDownLatch(1);
        AtomicReference<LiveMediaError> failure = new AtomicReference<>();
        List<android.hardware.camera2.params.OutputConfiguration> outputs =
                new ArrayList<>(1);
        outputs.add(new android.hardware.camera2.params.OutputConfiguration(inputSurface));
        // The literal 0 is SESSION_TYPE_NORMAL (renamed SESSION_REGULAR in
        // newer SDKs where the old constant was removed); it is the regular
        // camera+encoder session type on every API level from 28.
        @SuppressLint("WrongConstant")
        SessionConfiguration configuration = new SessionConfiguration(
                SESSION_TYPE_REGULAR, outputs,
                cameraHandler::post, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                captureSession = session;
                try {
                    startRepeating(device, session, inputSurface);
                    configured.countDown();
                } catch (CameraAccessException | IllegalStateException e) {
                    failure.compareAndSet(null, LiveMediaError.UNAVAILABLE);
                    configured.countDown();
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                failure.compareAndSet(null, LiveMediaError.UNAVAILABLE);
                configured.countDown();
            }
        });
        try {
            device.createCaptureSession(configuration);
        } catch (CameraAccessException e) {
            throw cameraAccessError(e);
        } catch (IllegalArgumentException e) {
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "capture session rejected");
        }
        boolean ready;
        try {
            ready = configured.await(LiveMediaDefaults.CAMERA_OPEN_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiveMediaStartException(LiveMediaError.UNAVAILABLE,
                    "capture session interrupted");
        }
        if (!ready || failure.get() != null) {
            throw new LiveMediaStartException(
                    failure.get() != null ? failure.get() : LiveMediaError.UNAVAILABLE,
                    "capture session did not configure");
        }
        Log.i(TAG, "camera session live at " + resolution.width + "x"
                + resolution.height);
    }

    @SuppressLint("MissingPermission")
    private void startRepeating(CameraDevice device, CameraCaptureSession session,
                                android.view.Surface inputSurface)
            throws CameraAccessException {
        CaptureRequest.Builder request =
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        request.addTarget(inputSurface);
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        Range<Integer> fpsRange = supportedFpsRange(device);
        if (fpsRange != null) {
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        }
        session.setRepeatingRequest(request.build(), null, cameraHandler);
    }

    private Range<Integer> supportedFpsRange(CameraDevice device) {
        try {
            CameraCharacteristics characteristics = ((CameraManager) context.getSystemService(
                    Context.CAMERA_SERVICE)).getCameraCharacteristics(device.getId());
            Range<Integer>[] ranges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null) {
                return null;
            }
            Range<Integer> best = null;
            for (Range<Integer> range : ranges) {
                if (range.getLower() <= LiveMediaDefaults.TARGET_FPS
                        && range.getUpper() >= LiveMediaDefaults.TARGET_FPS) {
                    if (best == null
                            || range.getUpper() - range.getLower()
                            < best.getUpper() - best.getLower()) {
                        best = range;
                    }
                }
            }
            return best;
        } catch (CameraAccessException | RuntimeException e) {
            return null;
        }
    }

    private static LiveMediaStartException cameraAccessError(CameraAccessException e) {
        LiveMediaError error = e.getReason() == CameraAccessException.CAMERA_IN_USE
                || e.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE
                ? LiveMediaError.BUSY : LiveMediaError.UNAVAILABLE;
        return new LiveMediaStartException(error, "camera access rejected");
    }
}
