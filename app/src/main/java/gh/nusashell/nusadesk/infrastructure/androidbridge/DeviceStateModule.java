package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Device-state capability domain: vibrator, torch, volume, audio properties,
 * and screen brightness, behind six bridge methods.
 *
 * <p>Every method validates its params object with {@link CapabilityParams}
 * first (unknown keys fail closed as {@code invalid-argument}), performs its
 * platform call, and reports one bounded response. A missing service, absent
 * hardware, or a platform refusal maps to the method's typed
 * {@code <capability>-unavailable} error; a missing {@code WRITE_SETTINGS}
 * grant maps to {@code brightness-permission-required}. Platform exception
 * messages are logged, never put on the wire.</p>
 *
 * <p>{@code audio.info} reports only real platform values. The public
 * {@link AudioManager#getProperty(String)} surface covers the primary output
 * sample rate and frames-per-buffer only, so the latency fields are derived
 * buffer durations (the measurable floor of that path's latency) and are
 * omitted when their inputs are unavailable.</p>
 */
public final class DeviceStateModule implements CapabilityModule {
    private static final String TAG = "DeviceStateModule";

    private static final String METHOD_VIBRATE = "vibrate";
    private static final String METHOD_TORCH_SET = "torch.set";
    private static final String METHOD_VOLUME_GET = "volume.get";
    private static final String METHOD_VOLUME_SET = "volume.set";
    private static final String METHOD_AUDIO_INFO = "audio.info";
    private static final String METHOD_BRIGHTNESS_SET = "brightness.set";

    private static final long VIBRATE_MIN_MS = 1L;
    private static final long VIBRATE_MAX_MS = 60_000L;
    private static final long VIBRATE_DEFAULT_MS = 1_000L;
    private static final long BRIGHTNESS_MAX = 255L;
    private static final int STREAM_NAME_MAX_CHARS = 16;
    private static final int CAMERA_ID_MAX_CHARS = 64;

    /**
     * The six Termux streams in platform stream-id order, matching the order
     * {@code termux-volume} emits them.
     */
    private static final int[] STREAM_IDS = {
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION};
    private static final String[] STREAM_NAMES = {
            "call", "system", "ring", "music", "alarm", "notification"};

    private final Context context;

    public DeviceStateModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_VIBRATE, METHOD_TORCH_SET, METHOD_VOLUME_GET,
                METHOD_VOLUME_SET, METHOD_AUDIO_INFO, METHOD_BRIGHTNESS_SET);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_VIBRATE, METHOD_TORCH_SET,
                METHOD_VOLUME_SET, METHOD_BRIGHTNESS_SET);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_VIBRATE:
                return vibrate(request);
            case METHOD_TORCH_SET:
                return torchSet(request);
            case METHOD_VOLUME_GET:
                return volumeGet(request);
            case METHOD_VOLUME_SET:
                return volumeSet(request);
            case METHOD_AUDIO_INFO:
                return audioInfo(request);
            case METHOD_BRIGHTNESS_SET:
                return brightnessSet(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code vibrate} — params {@code duration_ms} (1..60000, default 1000)
     * and {@code force} (bool, default false). A SILENT ringer mode without
     * {@code force} is reported honestly as {@code vibrated=false}; a missing
     * vibrator or a platform refusal is {@code vibrate-unavailable}.
     */
    private AndroidCapabilityProtocol.Response vibrate(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("duration_ms", "force"));
        long durationMs = params.optionalLong(
                "duration_ms", VIBRATE_MIN_MS, VIBRATE_MAX_MS, VIBRATE_DEFAULT_MS);
        boolean force = params.optionalBoolean("force", false);

        Vibrator vibrator;
        AudioManager audio;
        try {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate: service lookup failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "vibrate-unavailable");
        }
        if (vibrator == null || audio == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "vibrate-unavailable");
        }

        boolean vibrated;
        try {
            if (!vibrator.hasVibrator()) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "vibrate-unavailable");
            }
            if (!force && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                vibrated = false;
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
                vibrated = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "vibrate failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "vibrate-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vibrated", vibrated);
        fields.put("duration_ms", durationMs);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code torch.set} — params {@code enabled} (bool, required) and
     * {@code camera_id} (string, optional; the first camera whose
     * {@code FLASH_INFO_AVAILABLE} is true when omitted). No flash-capable
     * camera or a platform refusal is {@code torch-unavailable}.
     */
    private AndroidCapabilityProtocol.Response torchSet(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("enabled", "camera_id"));
        if (!params.has("enabled")) {
            throw new CapabilityParams.Invalid("missing boolean parameter: enabled");
        }
        boolean enabled = params.optionalBoolean("enabled", false);
        String cameraId = params.optionalString("camera_id", CAMERA_ID_MAX_CHARS, "");

        CameraManager cameras;
        try {
            cameras = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        } catch (RuntimeException e) {
            Log.w(TAG, "torch: service lookup failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "torch-unavailable");
        }
        if (cameras == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "torch-unavailable");
        }

        try {
            if (cameraId.isEmpty()) {
                cameraId = firstFlashCameraId(cameras);
            } else if (!knownCameraId(cameras, cameraId)) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "torch-unavailable");
            }
            if (cameraId == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "torch-unavailable");
            }
            cameras.setTorchMode(cameraId, enabled);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "torch set failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "torch-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("enabled", enabled);
        fields.put("camera_id", cameraId);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** First enumerated camera advertising a flash unit, or null. */
    private static String firstFlashCameraId(CameraManager cameras)
            throws CameraAccessException {
        for (String id : cameras.getCameraIdList()) {
            Boolean flash = cameras.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) {
                return id;
            }
        }
        return null;
    }

    private static boolean knownCameraId(CameraManager cameras, String cameraId)
            throws CameraAccessException {
        for (String id : cameras.getCameraIdList()) {
            if (id.equals(cameraId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code volume.get} — no params. Reports every Termux stream as one
     * pre-encoded JSON array in {@code streams_json}; a missing audio service
     * or a platform refusal is {@code volume-unavailable}.
     */
    private AndroidCapabilityProtocol.Response volumeGet(
            AndroidCapabilityProtocol.Request request) {
        AudioManager audio = audioManager();
        if (audio == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "volume-unavailable");
        }
        String streamsJson;
        try {
            streamsJson = streamsJson(audio);
        } catch (RuntimeException e) {
            Log.w(TAG, "volume.get failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "volume-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("streams_json", streamsJson);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code volume.set} — params {@code stream} (one of the six Termux
     * stream names) and {@code volume} (0..that stream's max). An unknown
     * stream or an out-of-range volume is {@code volume-invalid-stream}; a
     * platform refusal is {@code volume-unavailable}.
     */
    private AndroidCapabilityProtocol.Response volumeSet(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("stream", "volume"));
        String streamName = params.requireString("stream", STREAM_NAME_MAX_CHARS);
        if (!params.has("volume")) {
            throw new CapabilityParams.Invalid("missing integer parameter: volume");
        }
        long volume = params.optionalLong(
                "volume", Long.MIN_VALUE, Long.MAX_VALUE, 0L);

        int streamId = streamIdFor(streamName);
        if (streamId < 0) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "volume-invalid-stream");
        }
        AudioManager audio = audioManager();
        if (audio == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "volume-unavailable");
        }
        try {
            int maxVolume = audio.getStreamMaxVolume(streamId);
            if (volume < 0 || volume > maxVolume) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "volume-invalid-stream");
            }
            audio.setStreamVolume(streamId, (int) volume, 0);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("stream", streamName);
            fields.put("volume", (long) audio.getStreamVolume(streamId));
            fields.put("max_volume", (long) maxVolume);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        } catch (RuntimeException e) {
            Log.w(TAG, "volume.set failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "volume-unavailable");
        }
    }

    /**
     * {@code audio.info} — no params. Reports the primary output's native
     * {@code sample_rate} and {@code frames_per_buffer}, the max output
     * {@code channels} the device advertises, the derived {@code
     * output_latency_ms}/{@code input_latency_ms} buffer durations, the
     * Bluetooth-A2DP/wired-headset flags Termux reports, and {@code
     * streams_json}. Any value the platform cannot supply is omitted rather
     * than zero-filled; a missing audio service is {@code audio-unavailable}.
     */
    private AndroidCapabilityProtocol.Response audioInfo(
            AndroidCapabilityProtocol.Request request) {
        AudioManager audio = audioManager();
        if (audio == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "audio-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        try {
            Long sampleRate = audioPropertyLong(audio,
                    AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            Long framesPerBuffer = audioPropertyLong(audio,
                    AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            if (sampleRate != null) {
                fields.put("sample_rate", sampleRate);
            }
            if (framesPerBuffer != null) {
                fields.put("frames_per_buffer", framesPerBuffer);
            }
            if (sampleRate != null && framesPerBuffer != null && sampleRate > 0) {
                fields.put("output_latency_ms",
                        framesPerBuffer * 1_000.0 / sampleRate);
            }
            Double inputLatency = inputLatencyMs(sampleRate);
            if (inputLatency != null) {
                fields.put("input_latency_ms", inputLatency);
            }
            long channels = maxOutputChannels(audio);
            if (channels > 0) {
                fields.put("channels", channels);
            }
            fields.put("bluetooth_a2dp_on", audio.isBluetoothA2dpOn());
            fields.put("wired_headset_on", audio.isWiredHeadsetOn());
            fields.put("streams_json", streamsJson(audio));
        } catch (RuntimeException e) {
            Log.w(TAG, "audio.info failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "audio-unavailable");
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code brightness.set} — params {@code value} (0..255). Requires the
     * {@code WRITE_SETTINGS} special access: without it the typed
     * {@code brightness-permission-required} tells the guest to grant it
     * through {@code permission.request mode=settings}; a refused write is
     * {@code brightness-unavailable}.
     */
    private AndroidCapabilityProtocol.Response brightnessSet(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("value"));
        if (!params.has("value")) {
            throw new CapabilityParams.Invalid("missing integer parameter: value");
        }
        long value = params.optionalLong("value", 0L, BRIGHTNESS_MAX, 0L);

        boolean canWrite;
        try {
            canWrite = Settings.System.canWrite(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "brightness: canWrite failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "brightness-unavailable");
        }
        if (!canWrite) {
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "brightness-permission-required:grant via"
                            + " permission.request mode=settings");
        }
        try {
            if (!Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, (int) value)) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "brightness-unavailable");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "brightness.set failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "brightness-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("value", value);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private AudioManager audioManager() {
        try {
            return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Termux stream name to platform stream id, or -1 when unknown. */
    private static int streamIdFor(String name) {
        for (int i = 0; i < STREAM_NAMES.length; i++) {
            if (STREAM_NAMES[i].equals(name)) {
                return STREAM_IDS[i];
            }
        }
        return -1;
    }

    /**
     * The six Termux streams as one JSON array text
     * ({@code {"stream":..,"volume":..,"max_volume":..,"min_volume":..,"muted":..}}),
     * matching the entries {@code termux-volume} prints.
     */
    private static String streamsJson(AudioManager audio) {
        StringBuilder json = new StringBuilder(STREAM_IDS.length * 64);
        json.append('[');
        for (int i = 0; i < STREAM_IDS.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            int stream = STREAM_IDS[i];
            json.append("{\"stream\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(STREAM_NAMES[i]))
                    .append(",\"volume\":").append(audio.getStreamVolume(stream))
                    .append(",\"max_volume\":").append(audio.getStreamMaxVolume(stream))
                    .append(",\"min_volume\":").append(audio.getStreamMinVolume(stream))
                    .append(",\"muted\":").append(audio.isStreamMute(stream))
                    .append('}');
        }
        return json.append(']').toString();
    }

    /** Parse a positive long from an {@link AudioManager#getProperty} value; null when absent. */
    private static Long audioPropertyLong(AudioManager audio, String key) {
        String raw = audio.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Minimum mono PCM-16 input buffer duration in ms at {@code sampleRate}
     * (44100 Hz when the output rate is unknown), from
     * {@link AudioRecord#getMinBufferSize}; null when the platform reports no
     * supported buffer.
     */
    private static Double inputLatencyMs(Long sampleRate) {
        long rate = sampleRate != null && sampleRate > 0 ? sampleRate : 44_100L;
        try {
            int bytes = AudioRecord.getMinBufferSize((int) rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bytes <= 0) {
                return null;
            }
            return bytes / 2.0 * 1_000.0 / rate;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Max PCM channel count any output device advertises; 0 when none reports one. */
    private static long maxOutputChannels(AudioManager audio) {
        long max = 0;
        try {
            for (AudioDeviceInfo device
                    : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                for (int count : device.getChannelCounts()) {
                    if (count > max) {
                        max = count;
                    }
                }
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return max;
    }
}
