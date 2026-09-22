package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CaptureForegroundOperation;
import gh.nusashell.nusadesk.infrastructure.service.CaptureRegistry;
import gh.nusashell.nusadesk.infrastructure.service.CaptureService;

/**
 * The capture capability: {@code camera.info}, {@code camera.photo}, and the
 * stateful {@code microphone.record.start|info|stop}.
 *
 * <p>{@code camera.info} enumerates the Camera2 camera list — an operation
 * that needs no CAMERA grant — and answers a pre-encoded
 * {@code camera_json} array in the upstream {@code termux-camera-info} shape
 * ({@code id}, {@code facing}, {@code jpeg_output_sizes}).</p>
 *
 * <p>{@code camera.photo} and {@code microphone.record.start} must run while
 * the app is while-in-use: device evidence (S10e, API 31) shows a camera FGS
 * started from the background is accepted by the platform but refused device
 * access. Both therefore go through the {@code capture} foreground
 * operation, which starts {@link CaptureService} only once the transparent
 * host activity is resumed. Any remaining platform/while-in-use refusal maps
 * to {@code camera-foreground-required} /
 * {@code microphone-foreground-required} — never {@code -unavailable}.</p>
 *
 * <p>The recording is stateful and survives the guest command that started
 * it: {@link CaptureService} holds the recorder and
 * {@link CaptureRegistry} holds the state across bridge calls. A second
 * {@code record.start} while one runs answers {@code microphone-busy}.
 * Duration is bounded to {@code 0..900} seconds ({@code 0} selects the
 * ceiling) and the encoder set to {@code aac|amr_wb|amr_nb|opus}.</p>
 *
 * <p>File-carrying methods obey bridge-v2 §8.1: the guest stages the output
 * inside the rootfs, this module resolves it through
 * {@link GuestFilePathResolver}, and the guest moves the result itself.</p>
 *
 * <p>The module never throws: expected failures return typed lowercase-kebab
 * errors and unexpected platform exceptions collapse to
 * {@code camera-unavailable} / {@code microphone-unavailable} without
 * leaking the platform message.</p>
 */
public final class CaptureModule implements CapabilityModule {

    private static final String TAG = "CaptureModule";

    private static final String METHOD_CAMERA_INFO = "camera.info";
    private static final String METHOD_CAMERA_PHOTO = "camera.photo";
    private static final String METHOD_RECORD_START = "microphone.record.start";
    private static final String METHOD_RECORD_INFO = "microphone.record.info";
    private static final String METHOD_RECORD_STOP = "microphone.record.stop";

    private static final long PHOTO_FOREGROUND_TIMEOUT_MS = 30_000;
    private static final long RECORD_FOREGROUND_TIMEOUT_MS = 20_000;
    private static final long RECORD_STOP_TIMEOUT_MS = 10_000;

    private static final int CAMERA_ID_MAX_CHARS = 64;
    private static final int PATH_MAX_CHARS = 4096;
    private static final int ENCODER_MAX_CHARS = 16;
    private static final long LIMIT_MAX_SECONDS = 900;
    private static final long BITRATE_MAX_KBPS = 4096;
    private static final long SAMPLE_RATE_MIN = 1_000;
    private static final long SAMPLE_RATE_MAX = 192_000;
    private static final long CHANNELS_MAX = 8;
    private static final int MAX_CAMERAS = 16;
    private static final int MAX_JPEG_SIZES = 64;

    private static final Set<String> ENCODERS =
            Set.of("aac", "amr_wb", "amr_nb", "opus");

    private static final String FOREGROUND_HINT =
            ":open the NusaDesk app and retry";

    private final Context context;
    private final GuestFilePathResolver paths;
    private final CapabilityForegroundHost foregroundHost;
    private final AndroidPermissionChecker permissions;
    private final CaptureRegistry registry;

    public CaptureModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        this.context = application;
        this.paths = new GuestFilePathResolver(application);
        this.foregroundHost = new CapabilityForegroundHost(application);
        this.permissions = new AndroidPermissionChecker(application);
        this.registry = CaptureRegistry.getInstance();
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_CAMERA_INFO, METHOD_CAMERA_PHOTO,
                METHOD_RECORD_START, METHOD_RECORD_INFO, METHOD_RECORD_STOP);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_CAMERA_PHOTO, METHOD_RECORD_START);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        try {
            switch (request.getMethod()) {
                case METHOD_CAMERA_INFO:
                    return cameraInfo(request);
                case METHOD_CAMERA_PHOTO:
                    return cameraPhoto(request);
                case METHOD_RECORD_START:
                    return recordStart(request);
                case METHOD_RECORD_INFO:
                    return recordInfo(request);
                case METHOD_RECORD_STOP:
                    return recordStop(request);
                default:
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "unsupported-method");
            }
        } catch (GuestFilePathResolver.Invalid invalid) {
            // A guest path outside the rootfs is a bad argument, not a
            // capability failure.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "invalid-argument");
        }
    }

    @Override
    public void close() {
        foregroundHost.close();
        // Session teardown stops an in-flight recording: the guest that owns
        // it is gone, and leaving a microphone FGS running would be neither
        // user-visible-stoppable nor honest.
        if (registry.recordingBusy()) {
            try {
                context.startService(CaptureService.recordStopIntent(
                        context, registry.recordingSeq()));
            } catch (RuntimeException e) {
                registry.onServiceLost();
            }
        }
    }

    // ------------------------------------------------------------------
    // camera.info — no permission required to enumerate cameras
    // ------------------------------------------------------------------

    private AndroidCapabilityProtocol.Response cameraInfo(
            AndroidCapabilityProtocol.Request request) {
        CameraManager manager =
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "camera-unavailable");
        }
        StringBuilder json = new StringBuilder(256);
        json.append('[');
        int count = 0;
        boolean truncated = false;
        try {
            for (String id : manager.getCameraIdList()) {
                if (count >= MAX_CAMERAS) {
                    truncated = true;
                    break;
                }
                CameraCharacteristics characteristics;
                try {
                    characteristics = manager.getCameraCharacteristics(id);
                } catch (RuntimeException e) {
                    // One unreadable camera (stale/secure id) is omitted
                    // rather than failing the whole enumeration.
                    continue;
                }
                if (count > 0) {
                    json.append(',');
                }
                json.append(cameraEntry(id, characteristics));
                count++;
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "camera.info enumeration failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "camera-unavailable");
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("camera_json", json.toString());
        fields.put("count", (long) count);
        if (truncated) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * One upstream-shaped camera entry: {@code id}, {@code facing}
     * ("front"/"back" or the raw platform constant), and
     * {@code jpeg_output_sizes} as {@code "WxH"} strings, bounded.
     */
    private static String cameraEntry(String id,
                                      CameraCharacteristics characteristics) {
        StringBuilder entry = new StringBuilder(192);
        entry.append("{\"id\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(id));
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null) {
            entry.append(",\"facing\":");
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                entry.append("\"front\"");
            } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                entry.append("\"back\"");
            } else {
                entry.append(facing.intValue());
            }
        }
        entry.append(",\"jpeg_output_sizes\":[");
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes != null) {
            int emitted = 0;
            for (Size size : sizes) {
                if (size == null || emitted >= MAX_JPEG_SIZES) {
                    break;
                }
                if (emitted > 0) {
                    entry.append(',');
                }
                entry.append('"').append(size.getWidth()).append('x')
                        .append(size.getHeight()).append('"');
                emitted++;
            }
        }
        entry.append("]}");
        return entry.toString();
    }

    // ------------------------------------------------------------------
    // camera.photo — CAMERA grant + foreground capture, §8.1 staging
    // ------------------------------------------------------------------

    private AndroidCapabilityProtocol.Response cameraPhoto(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("camera_id", "path"));
        String cameraId = params.optionalString("camera_id", CAMERA_ID_MAX_CHARS, "0");
        String guestPath = params.requireString("path", PATH_MAX_CHARS);
        CapabilityPermission grant = permissions.check(Manifest.permission.CAMERA);
        if (grant != CapabilityPermission.GRANTED) {
            return permissionError(request.getId(), grant, "camera", "CAMERA");
        }
        Path staging = paths.resolveForWrite(guestPath);
        if (!knownCameraId(cameraId)) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "invalid-argument");
        }
        if (registry.photoBusy()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "camera-busy");
        }
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", CaptureForegroundOperation.OP_PHOTO);
        opParams.put("camera_id", cameraId);
        opParams.put("host_path", staging.toString());
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                CaptureForegroundOperation.KIND, opParams,
                PHOTO_FOREGROUND_TIMEOUT_MS);
        if (!result.isOk()) {
            return rewrapCapture(request.getId(), result, "camera");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("path", guestPath);
        Object bytes = result.getFields().get("bytes");
        fields.put("bytes", bytes instanceof Number
                ? ((Number) bytes).longValue() : 0L);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * Whether {@code camera_id} names a real camera. Enumeration needs no
     * permission; when it fails the id is left for the capture to judge.
     */
    private boolean knownCameraId(String cameraId) {
        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return true;
            }
            for (String id : manager.getCameraIdList()) {
                if (id.equals(cameraId)) {
                    return true;
                }
            }
            return false;
        } catch (CameraAccessException | RuntimeException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // microphone.record.*
    // ------------------------------------------------------------------

    private AndroidCapabilityProtocol.Response recordStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("path", "limit_seconds", "encoder",
                "bitrate_kbps", "sample_rate", "channels"));
        String guestPath = params.requireString("path", PATH_MAX_CHARS);
        long limitSeconds = params.optionalLong("limit_seconds", 0,
                LIMIT_MAX_SECONDS, LIMIT_MAX_SECONDS);
        String encoder = params.optionalString("encoder", ENCODER_MAX_CHARS, "aac");
        if (!ENCODERS.contains(encoder)) {
            throw new CapabilityParams.Invalid("unsupported encoder");
        }
        long bitrateKbps = params.optionalLong("bitrate_kbps", 1,
                BITRATE_MAX_KBPS, 0);
        long sampleRate = params.optionalLong("sample_rate", SAMPLE_RATE_MIN,
                SAMPLE_RATE_MAX, 0);
        long channels = params.optionalLong("channels", 1, CHANNELS_MAX, 0);
        CapabilityPermission grant =
                permissions.check(Manifest.permission.RECORD_AUDIO);
        if (grant != CapabilityPermission.GRANTED) {
            return permissionError(request.getId(), grant, "microphone",
                    "RECORD_AUDIO");
        }
        Path staging = paths.resolveForWrite(guestPath);
        if (registry.recordingBusy()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "microphone-busy");
        }
        // The bridge bounds every recording; 0 selects the 900 s ceiling
        // rather than an unbounded capture.
        long limitMs = limitSeconds == 0
                ? LIMIT_MAX_SECONDS * 1000 : limitSeconds * 1000;
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", CaptureForegroundOperation.OP_RECORD_START);
        opParams.put("host_path", staging.toString());
        opParams.put("guest_path", guestPath);
        opParams.put("limit_ms", limitMs);
        opParams.put("encoder", encoder);
        if (bitrateKbps > 0) {
            opParams.put("bitrate_bps", bitrateKbps * 1000);
        }
        if (sampleRate > 0) {
            opParams.put("sample_rate", sampleRate);
        }
        if (channels > 0) {
            opParams.put("channels", channels);
        }
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                CaptureForegroundOperation.KIND, opParams,
                RECORD_FOREGROUND_TIMEOUT_MS);
        if (!result.isOk()) {
            return rewrapCapture(request.getId(), result, "microphone");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("recording", true);
        fields.put("path", guestPath);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private AndroidCapabilityProtocol.Response recordInfo(
            AndroidCapabilityProtocol.Request request) {
        CaptureRegistry.RecordingInfo info = registry.info();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("recording", info.recording);
        if (info.recording) {
            fields.put("path", info.guestPath);
            fields.put("duration_ms", info.durationMs);
            fields.put("limit_ms", info.limitMs);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code microphone.record.stop} — finalize the active recording, or
     * report the retained outcome of one that already ended on its own (a
     * duration-limit auto-stop), exactly once.
     */
    private AndroidCapabilityProtocol.Response recordStop(
            AndroidCapabilityProtocol.Request request) {
        long seq = registry.requestStopSeq();
        if (seq != 0) {
            try {
                context.startService(CaptureService.recordStopIntent(context, seq));
            } catch (RuntimeException e) {
                // The service is gone while the registry thought it recorded.
                registry.onServiceLost();
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "microphone-unavailable");
            }
            CaptureRegistry.StopOutcome outcome =
                    registry.awaitStop(seq, RECORD_STOP_TIMEOUT_MS);
            if (outcome == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "microphone-timeout");
            }
            if (outcome.error != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), outcome.error);
            }
            // This stop consumed the outcome; a second -q must not re-report it.
            registry.consumeCompleted();
            return stoppedResponse(request.getId(), outcome);
        }
        CaptureRegistry.StopOutcome completed = registry.consumeCompleted();
        if (completed != null) {
            return stoppedResponse(request.getId(), completed);
        }
        if (registry.recordingBusy()) {
            // A start or a stop is mid-transition.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "microphone-busy");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("recording", false);
        fields.put("stopped", false);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private static AndroidCapabilityProtocol.Response stoppedResponse(
            String requestId, CaptureRegistry.StopOutcome outcome) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("recording", false);
        fields.put("stopped", true);
        fields.put("path", outcome.guestPath);
        fields.put("bytes", outcome.bytes);
        return AndroidCapabilityProtocol.Response.success(requestId, fields);
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    /**
     * Re-wrap a foreground host result with the request id, translating the
     * host's generic {@code foreground-required} refusal into the
     * capability-specific code the brief requires.
     */
    private static AndroidCapabilityProtocol.Response rewrapCapture(
            String requestId, AndroidCapabilityProtocol.Response result,
            String capability) {
        if (result.isOk()) {
            return AndroidCapabilityProtocol.Response.success(
                    requestId, result.getFields());
        }
        String error = result.getError();
        if (error == null) {
            error = capability + "-unavailable";
        } else if (error.startsWith(CapabilityForegroundHost.ERROR_REQUIRED)) {
            error = capability + "-foreground-required" + FOREGROUND_HINT;
        }
        return AndroidCapabilityProtocol.Response.error(requestId, error);
    }

    private static AndroidCapabilityProtocol.Response permissionError(
            String requestId, CapabilityPermission grant, String capability,
            String androidPermission) {
        String error = grant == CapabilityPermission.DENIED
                ? capability + "-permission-denied:grant the " + capability
                        + " permission in app settings"
                : capability + "-permission-required:grant " + androidPermission
                        + " via permission.request";
        return AndroidCapabilityProtocol.Response.error(requestId, error);
    }
}
