package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidPermissionChecker;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CapabilityPermission;
import gh.nusashell.nusadesk.infrastructure.service.CaptureRegistry;
import gh.nusashell.nusadesk.infrastructure.service.CaptureService;

/**
 * The {@code capture} foreground operation behind {@code camera.photo} and
 * {@code microphone.record.start}.
 *
 * <p>Params: {@code op} — {@code photo} or {@code record_start}.
 * {@code photo} takes {@code host_path} (the resolved staging file) and
 * {@code camera_id}; {@code record_start} takes {@code host_path},
 * {@code guest_path} (the path reported back by info/stop), {@code limit_ms},
 * {@code encoder}, {@code bitrate_bps}, {@code sample_rate}, and
 * {@code channels}.</p>
 *
 * <p>Device evidence (S10e, API 31): starting a camera FGS from a background
 * bridge thread is accepted by the platform, but the camera service rejects
 * device access because the app is not while-in-use. This operation
 * therefore does not start {@link CaptureService} until the host activity is
 * resumed; a platform refusal at that point reports
 * {@code camera-foreground-required} / {@code microphone-foreground-required}
 * rather than {@code -unavailable}.</p>
 *
 * <p>The service performs the capture on its own worker and publishes the
 * typed outcome into {@link CaptureRegistry}; the operation waits on a
 * spawned thread (never the activity's main thread) and reports through
 * {@code sink} exactly once.</p>
 *
 * <p>The instance is a shared catalog entry: per-request state lives in the
 * {@code run} call and its closures, never in fields.</p>
 */
public final class CaptureForegroundOperation implements ForegroundOperation {

    /** Catalog kind and the foreground-operation key the module requests. */
    public static final String KIND = "capture";

    /** {@code op} value for the single-JPEG photo capture. */
    public static final String OP_PHOTO = "photo";

    /** {@code op} value for the stateful recording start. */
    public static final String OP_RECORD_START = "record_start";

    private static final long PHOTO_RESULT_TIMEOUT_MS = 20_000;
    private static final long RECORD_RESULT_TIMEOUT_MS = 10_000;

    private static final int MAX_PATH_CHARS = 8192;
    private static final int MAX_CAMERA_ID_CHARS = 64;
    private static final int MAX_ENCODER_CHARS = 16;
    private static final long MAX_LIMIT_MS = 900_000;
    private static final long MAX_BITRATE_BPS = 4_096_000;
    private static final long MAX_SAMPLE_RATE = 192_000;
    private static final long MAX_CHANNELS = 8;

    private static final String FOREGROUND_HINT =
            ":open the NusaDesk app and retry";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity,
                    Map<String, Object> params, ResultSink sink) {
        String op = stringParam(params.get("op"), "");
        if (OP_PHOTO.equals(op)) {
            photo(activity, params, sink);
        } else if (OP_RECORD_START.equals(op)) {
            recordStart(activity, params, sink);
        } else {
            sink.error("invalid-argument");
        }
    }

    // ------------------------------------------------------------------
    // photo
    // ------------------------------------------------------------------

    private void photo(CapabilityForegroundActivity activity,
                       Map<String, Object> params, ResultSink sink) {
        String hostPath = requiredString(params.get("host_path"), MAX_PATH_CHARS);
        String cameraId = params.get("camera_id") == null
                ? "0"
                : requiredString(params.get("camera_id"), MAX_CAMERA_ID_CHARS);
        if (hostPath == null || cameraId == null) {
            sink.error("invalid-argument");
            return;
        }
        String permissionError = permissionError(
                new AndroidPermissionChecker(activity)
                        .check(Manifest.permission.CAMERA), "camera");
        if (permissionError != null) {
            sink.error(permissionError);
            return;
        }
        String requestedCamera = cameraId;
        CaptureRegistry registry = CaptureRegistry.getInstance();
        whenResumed(activity, () -> {
            long seq = registry.beginPhoto();
            if (seq < 0) {
                sink.error("camera-busy");
                return;
            }
            try {
                activity.startForegroundService(CaptureService.photoIntent(
                        activity, seq, requestedCamera, hostPath));
            } catch (IllegalStateException e) {
                registry.abortPhoto(seq);
                sink.error("camera-foreground-required" + FOREGROUND_HINT);
                return;
            } catch (SecurityException e) {
                registry.abortPhoto(seq);
                sink.error("camera-permission-denied");
                return;
            } catch (RuntimeException e) {
                registry.abortPhoto(seq);
                sink.error("camera-unavailable");
                return;
            }
            new Thread(() -> {
                CaptureRegistry.PhotoOutcome outcome =
                        registry.awaitPhoto(seq, PHOTO_RESULT_TIMEOUT_MS);
                if (outcome == null) {
                    registry.abortPhoto(seq);
                    try {
                        activity.startService(CaptureService.photoCancelIntent(
                                activity, seq));
                    } catch (RuntimeException ignored) {
                        // The service is gone; the late task frees itself.
                    }
                    sink.error("camera-timeout");
                    return;
                }
                if (outcome.error != null) {
                    sink.error(outcome.error);
                    return;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("bytes", outcome.bytes);
                sink.success(fields);
            }, "capture-photo-wait").start();
        });
    }

    // ------------------------------------------------------------------
    // record_start
    // ------------------------------------------------------------------

    private void recordStart(CapabilityForegroundActivity activity,
                             Map<String, Object> params, ResultSink sink) {
        String hostPath = requiredString(params.get("host_path"), MAX_PATH_CHARS);
        String guestPath = requiredString(params.get("guest_path"), MAX_PATH_CHARS);
        Long limitMs = params.containsKey("limit_ms")
                ? longParam(params.get("limit_ms"), 0, MAX_LIMIT_MS)
                : MAX_LIMIT_MS;
        String encoder = params.get("encoder") == null
                ? "aac"
                : requiredString(params.get("encoder"), MAX_ENCODER_CHARS);
        Long bitrateBps = params.containsKey("bitrate_bps")
                ? longParam(params.get("bitrate_bps"), 1, MAX_BITRATE_BPS) : 0L;
        Long sampleRate = params.containsKey("sample_rate")
                ? longParam(params.get("sample_rate"), 1, MAX_SAMPLE_RATE) : 0L;
        Long channels = params.containsKey("channels")
                ? longParam(params.get("channels"), 1, MAX_CHANNELS) : 0L;
        if (hostPath == null || guestPath == null || limitMs == null
                || encoder == null || bitrateBps == null || sampleRate == null
                || channels == null) {
            sink.error("invalid-argument");
            return;
        }
        // The bridge bounds every recording; a 0/omitted limit selects the
        // 900 s ceiling rather than an unbounded capture.
        long effectiveLimitMs = limitMs == 0 ? MAX_LIMIT_MS : limitMs;
        String permissionError = permissionError(
                new AndroidPermissionChecker(activity)
                        .check(Manifest.permission.RECORD_AUDIO), "microphone");
        if (permissionError != null) {
            sink.error(permissionError);
            return;
        }
        CaptureRegistry registry = CaptureRegistry.getInstance();
        whenResumed(activity, () -> {
            long seq = registry.beginRecording(guestPath, effectiveLimitMs);
            if (seq < 0) {
                sink.error("microphone-busy");
                return;
            }
            try {
                activity.startForegroundService(CaptureService.recordStartIntent(
                        activity, seq, hostPath, effectiveLimitMs, encoder,
                        bitrateBps, sampleRate, channels));
            } catch (IllegalStateException e) {
                registry.abortRecording(seq);
                sink.error("microphone-foreground-required" + FOREGROUND_HINT);
                return;
            } catch (SecurityException e) {
                registry.abortRecording(seq);
                sink.error("microphone-permission-denied");
                return;
            } catch (RuntimeException e) {
                registry.abortRecording(seq);
                sink.error("microphone-unavailable");
                return;
            }
            new Thread(() -> {
                CaptureRegistry.RecordStartOutcome outcome =
                        registry.awaitRecording(seq, RECORD_RESULT_TIMEOUT_MS);
                if (outcome == null) {
                    registry.abortRecording(seq);
                    sink.error("microphone-timeout");
                    return;
                }
                if (outcome.error != null) {
                    sink.error(outcome.error);
                    return;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("recording", true);
                sink.success(fields);
            }, "capture-record-wait").start();
        });
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Run {@code task} once the host activity reaches {@code onResume} — the
     * point where the app is unambiguously while-in-use for FGS starts and
     * device access. The callback unregisters itself on resume or when the
     * activity is destroyed first (a host timeout/cancel then settles the
     * parked operation on its own).
     */
    private static void whenResumed(CapabilityForegroundActivity activity,
                                    Runnable task) {
        Application app = activity.getApplication();
        Application.ActivityLifecycleCallbacks callbacks =
                new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity resumed) {
                        if (resumed != activity) {
                            return;
                        }
                        app.unregisterActivityLifecycleCallbacks(this);
                        task.run();
                    }

                    @Override public void onActivityDestroyed(
                            Activity destroyed) {
                        if (destroyed == activity) {
                            app.unregisterActivityLifecycleCallbacks(this);
                        }
                    }

                    @Override public void onActivityCreated(
                            Activity a, Bundle b) {
                    }

                    @Override public void onActivityStarted(Activity a) {
                    }

                    @Override public void onActivityPaused(Activity a) {
                    }

                    @Override public void onActivityStopped(Activity a) {
                    }

                    @Override public void onActivitySaveInstanceState(
                            Activity a, Bundle b) {
                    }
                };
        app.registerActivityLifecycleCallbacks(callbacks);
    }

    private static String permissionError(CapabilityPermission state,
                                          String capability) {
        switch (state) {
            case GRANTED:
                return null;
            case DENIED:
                return capability + "-permission-denied:grant the "
                        + capability + " permission in app settings";
            case REQUIRED:
            default:
                return capability + "-permission-required:grant "
                        + ("camera".equals(capability) ? "CAMERA" : "RECORD_AUDIO")
                        + " via permission.request";
        }
    }

    private static String stringParam(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    /** Bounded required string; {@code null} means absent, empty, or over the cap. */
    private static String requiredString(Object value, int maxChars) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.isEmpty() || text.length() > maxChars ? null : text;
    }

    /**
     * Optional bounded number; {@code null} means absent (the caller applies
     * the platform default) or present-but-invalid — the record-start path
     * distinguishes the two by checking the raw map key.
     */
    private static Long longParam(Object value, long min, long max) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number)) {
            return null;
        }
        long number = ((Number) value).longValue();
        return number < min || number > max ? null : number;
    }
}
