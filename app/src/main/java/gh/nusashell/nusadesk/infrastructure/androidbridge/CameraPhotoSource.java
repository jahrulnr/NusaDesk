package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot still-JPEG capture over Camera2, upstream
 * {@code CameraPhotoAPI}-style: open the requested camera, run a hidden
 * preview briefly so auto-exposure/auto-focus settle, issue one
 * {@code TEMPLATE_STILL_CAPTURE} at the sensor's largest JPEG size, and write
 * the encoded bytes to a staging file. No preview is ever shown on screen —
 * the dummy {@link SurfaceTexture} is discarded unread.
 *
 * <p>{@link #capture()} blocks the calling thread for the whole capture;
 * {@link CaptureService} runs it on its worker thread. Every platform
 * failure maps to a bounded typed code carried by {@link CaptureException} —
 * platform exception text never crosses the bridge.</p>
 */
public final class CameraPhotoSource {

    /** Bounded typed failure produced by the capture. */
    public static final class CaptureException extends Exception {
        private final String code;

        public CaptureException(String code, String detail) {
            super(detail);
            this.code = code;
        }

        /** Lowercase-kebab wire code, e.g. {@code camera-busy}. */
        public String code() {
            return code;
        }
    }

    private static final long OPEN_TIMEOUT_MS = 5_000;
    private static final long SESSION_TIMEOUT_MS = 5_000;
    private static final long IMAGE_TIMEOUT_MS = 10_000;
    private static final long PREVIEW_SETTLE_MS = 700;

    private final Context context;
    private final String cameraId;
    private final Path hostPath;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture previewTexture;
    private Surface previewSurface;

    public CameraPhotoSource(Context context, String cameraId, Path hostPath) {
        this.context = context;
        this.cameraId = cameraId;
        this.hostPath = hostPath;
    }

    /** Best-effort abort; the blocked {@link #capture()} unwinds with a typed error. */
    public void cancel() {
        cancelled.set(true);
        closeSession();
        closeCamera();
    }

    /**
     * Run the capture to completion. Returns the number of bytes written to
     * {@code hostPath}.
     */
    public long capture() throws CaptureException {
        CameraManager manager =
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            throw new CaptureException("camera-unavailable", "no camera service");
        }
        CameraCharacteristics characteristics = characteristics(manager);
        Size jpegSize = largestJpegSize(characteristics);
        int orientation = jpegOrientation(characteristics);

        thread = new HandlerThread("capture-photo");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            reader = ImageReader.newInstance(jpegSize.getWidth(),
                    jpegSize.getHeight(), ImageFormat.JPEG, 2);
            openCamera(manager);
            createSession();
            settlePreview(characteristics);
            byte[] jpeg = takeStill(characteristics, orientation);
            write(jpeg);
            return jpeg.length;
        } finally {
            closeSession();
            closeCamera();
            if (reader != null) {
                reader.close();
                reader = null;
            }
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private CameraCharacteristics characteristics(CameraManager manager)
            throws CaptureException {
        try {
            return manager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e) {
            throw accessFailure("camera-unavailable", e);
        } catch (IllegalArgumentException e) {
            throw new CaptureException("camera-unavailable",
                    "unknown camera id");
        }
    }

    private static Size largestJpegSize(CameraCharacteristics characteristics)
            throws CaptureException {
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null
                ? null : map.getOutputSizes(ImageFormat.JPEG);
        Size largest = null;
        if (sizes != null) {
            for (Size size : sizes) {
                if (size != null && (largest == null
                        || (long) size.getWidth() * size.getHeight()
                        > (long) largest.getWidth() * largest.getHeight())) {
                    largest = size;
                }
            }
        }
        if (largest == null) {
            throw new CaptureException("camera-unavailable",
                    "no jpeg output size");
        }
        return largest;
    }

    private int jpegOrientation(CameraCharacteristics characteristics) {
        Integer sensor = characteristics.get(
                CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensor == null) {
            sensor = 0;
        }
        DisplayManager displayManager =
                (DisplayManager) context.getSystemService(
                        Context.DISPLAY_SERVICE);
        Display display = displayManager == null
                ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        int deviceDegrees = 0;
        if (display != null) {
            switch (display.getRotation()) {
                case Surface.ROTATION_90: deviceDegrees = 90; break;
                case Surface.ROTATION_180: deviceDegrees = 180; break;
                case Surface.ROTATION_270: deviceDegrees = 270; break;
                default: break;
            }
        }
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        int offset = facing != null
                && facing == CameraCharacteristics.LENS_FACING_FRONT
                ? -deviceDegrees : deviceDegrees;
        return (sensor + offset + 360) % 360;
    }

    @SuppressLint("MissingPermission")
    private void openCamera(CameraManager manager) throws CaptureException {
        checkCancelled();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    latch.countDown();
                }

                @Override public void onDisconnected(CameraDevice device) {
                    failure.compareAndSet(null, "camera-unavailable");
                    closeQuietly(device);
                    latch.countDown();
                }

                @Override public void onError(CameraDevice device, int error) {
                    failure.compareAndSet(null, openErrorCode(error));
                    closeQuietly(device);
                    latch.countDown();
                }
            }, handler);
        } catch (CameraAccessException e) {
            throw accessFailure("camera-unavailable", e);
        } catch (SecurityException e) {
            throw new CaptureException("camera-permission-denied",
                    "camera grant revoked");
        }
        await(latch, OPEN_TIMEOUT_MS, "camera-timeout");
        if (camera == null) {
            throw new CaptureException(failure.get() != null
                    ? failure.get() : "camera-unavailable", "open failed");
        }
    }

    private void createSession() throws CaptureException {
        checkCancelled();
        previewTexture = new SurfaceTexture(0);
        previewTexture.setDefaultBufferSize(640, 480);
        previewSurface = new Surface(previewTexture);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        try {
            SessionConfiguration config = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(
                            new android.hardware.camera2.params.OutputConfiguration(
                                    reader.getSurface()),
                            new android.hardware.camera2.params.OutputConfiguration(
                                    previewSurface)),
                    command -> handler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(
                                CameraCaptureSession s) {
                            session = s;
                            latch.countDown();
                        }

                        @Override public void onConfigureFailed(
                                CameraCaptureSession s) {
                            failed.set(true);
                            latch.countDown();
                        }
                    });
            camera.createCaptureSession(config);
        } catch (CameraAccessException e) {
            throw accessFailure("camera-unavailable", e);
        }
        await(latch, SESSION_TIMEOUT_MS, "camera-timeout");
        if (session == null) {
            throw new CaptureException(failed.get()
                    ? "camera-unavailable" : "camera-unavailable",
                    "session failed");
        }
    }

    /**
     * Upstream settles the encoder for ~500 ms; we hold a hidden repeating
     * preview briefly so AE/AF converge, then stop it before the still.
     */
    private void settlePreview(CameraCharacteristics characteristics)
            throws CaptureException {
        checkCancelled();
        try {
            CaptureRequest.Builder builder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            applyFocusModes(builder, characteristics);
            session.setRepeatingRequest(builder.build(), null, handler);
            Thread.sleep(PREVIEW_SETTLE_MS);
            session.stopRepeating();
        } catch (CameraAccessException e) {
            throw accessFailure("camera-unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException("camera-unavailable", "interrupted");
        }
    }

    private byte[] takeStill(CameraCharacteristics characteristics,
                             int orientation) throws CaptureException {
        checkCancelled();
        CountDownLatch imageLatch = new CountDownLatch(1);
        AtomicReference<byte[]> jpeg = new AtomicReference<>();
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image == null) {
                return;
            }
            try {
                ByteBuffer buffer =
                        image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                jpeg.set(bytes);
                imageLatch.countDown();
            } finally {
                image.close();
            }
        }, handler);
        try {
            CaptureRequest.Builder builder = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            applyFocusModes(builder, characteristics);
            session.capture(builder.build(), null, handler);
        } catch (CameraAccessException e) {
            throw accessFailure("camera-unavailable", e);
        }
        await(imageLatch, IMAGE_TIMEOUT_MS, "camera-timeout");
        byte[] bytes = jpeg.get();
        if (bytes == null) {
            throw new CaptureException("camera-unavailable", "no image");
        }
        return bytes;
    }

    private void applyFocusModes(CaptureRequest.Builder builder,
                                 CameraCharacteristics characteristics) {
        int[] afModes = characteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (contains(afModes,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        int[] aeModes = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (contains(aeModes, CaptureRequest.CONTROL_AE_MODE_ON)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
        }
    }

    private static boolean contains(int[] modes, int wanted) {
        if (modes == null) {
            return false;
        }
        for (int mode : modes) {
            if (mode == wanted) {
                return true;
            }
        }
        return false;
    }

    private void write(byte[] jpeg) throws CaptureException {
        try {
            Files.write(hostPath, jpeg, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException | SecurityException e) {
            throw new CaptureException("camera-unavailable", "write failed");
        }
    }

    private void checkCancelled() throws CaptureException {
        if (cancelled.get()) {
            throw new CaptureException("camera-unavailable", "cancelled");
        }
    }

    private void await(CountDownLatch latch, long timeoutMs, String code)
            throws CaptureException {
        boolean done;
        try {
            done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException("camera-unavailable", "interrupted");
        }
        if (!done) {
            throw new CaptureException(code, "timed out");
        }
        checkCancelled();
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
            }
            session = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (previewTexture != null) {
            previewTexture.release();
            previewTexture = null;
        }
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.close();
            } catch (RuntimeException ignored) {
            }
            camera = null;
        }
    }

    /**
     * Close a device the platform handed to a failed-open callback. The
     * Camera2 contract makes the app responsible for releasing that device;
     * leaving it open keeps the camera busy for the whole process, so every
     * later open answers `camera-in-use` until the app restarts.
     */
    private static void closeQuietly(CameraDevice device) {
        if (device == null) {
            return;
        }
        try {
            device.close();
        } catch (RuntimeException ignored) {
            // The platform already tore the device down.
        }
    }

    private static String openErrorCode(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "camera-busy";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
            default:
                return "camera-unavailable";
        }
    }

    private static CaptureException accessFailure(String fallback,
                                                  CameraAccessException e) {
        switch (e.getReason()) {
            case CameraAccessException.CAMERA_IN_USE:
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                return new CaptureException("camera-busy", "camera in use");
            case CameraAccessException.CAMERA_DISABLED:
            case CameraAccessException.CAMERA_DISCONNECTED:
            case CameraAccessException.CAMERA_ERROR:
            default:
                return new CaptureException(fallback, "camera access failed");
        }
    }
}
