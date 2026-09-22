package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sensor capability domain: the full device sensor catalog behind five bridge
 * methods.
 *
 * <p>{@code sensor.list} (no params) reports every sensor from
 * {@link SensorManager#getSensorList(int) SensorManager.getSensorList(TYPE_ALL)}
 * as one pre-encoded {@code sensors_json} array of
 * {@code {"name","type","vendor","max_range","resolution","power"}} objects,
 * bounded to {@link #MAX_LISTED_SENSORS} rows with a {@code truncated} flag.
 * {@code name} is a stable lowercase catalog name derived from the sensor type
 * ({@code accelerometer}, {@code gyroscope}, {@code magnetic_field},
 * {@code light}, {@code proximity}, {@code pressure}, {@code step_counter},
 * and so on — vendor or unmapped types fall back to their string-type tail or
 * {@code sensor_type_<int>}); duplicate types are suffixed {@code _2},
 * {@code _3}. Unlike upstream, which keys its JSON on the device-reported
 * sensor name, these names are device-independent and are the lookup keys for
 * {@code sensor.read} and {@code sensor.stream.start}.</p>
 *
 * <p>{@code sensor.read} takes {@code sensor} (a comma-separated list of
 * canonical names, numeric types, or substrings — resolved in that order,
 * substring matches prefer the shortest name, matching upstream's fuzzy
 * behaviour) or {@code all} (bool), plus {@code count} (1..10, default 1) and
 * {@code delay_ms} (0..5000, spacing between samples). It registers one
 * bounded listener set, waits up to {@link #FIRST_EVENT_MAX_MILLIS} for first
 * events, then captures {@code count} sample documents spaced
 * {@code delay_ms} apart, each shaped like upstream's
 * {@code {SENSOR: {"values": [..]}}}. A sensor that never delivers simply does
 * not appear in the documents; if nothing at all arrives the call fails
 * {@code sensor-timeout}. No sensor or no manager is
 * {@code sensor-unavailable} — values are never zero-filled.</p>
 *
 * <p>{@code sensor.stream.start} opens a pull stream over the same selection
 * ({@code delay_ms} becomes the platform sampling-period hint in
 * milliseconds). Every accepted event marks the stream dirty; one
 * {@code sensor.stream.poll} ({@code timeout_ms} 0..5000, default 2000)
 * returns one coalesced {@code sample_json} document carrying the latest
 * values of every requested sensor — a bounded latest-value queue of one,
 * so a slow consumer loses intermediate samples, never memory. An empty poll
 * window is {@code stream_empty}, a stopped stream is {@code stream_stopped},
 * and a poll before any start is {@code sensor-stream-not-started}.
 * {@code sensor.stream.stop} is idempotent and reports {@code was_running}.
 * One stream runs at a time; a second start is
 * {@code sensor-stream-already-started}.</p>
 */
public final class SensorCatalogModule implements CapabilityModule {
    private static final String TAG = "SensorCatalogModule";

    private static final String METHOD_LIST = "sensor.list";
    private static final String METHOD_READ = "sensor.read";
    private static final String METHOD_STREAM_START = "sensor.stream.start";
    private static final String METHOD_STREAM_POLL = "sensor.stream.poll";
    private static final String METHOD_STREAM_STOP = "sensor.stream.stop";

    private static final int MAX_LISTED_SENSORS = 64;
    private static final int MAX_SELECTED_SENSORS = 32;
    private static final int MAX_SENSOR_TOKENS = 8;
    private static final int SENSOR_PARAM_MAX_CHARS = 256;
    private static final long COUNT_MIN = 1L;
    private static final long COUNT_MAX = 10L;
    private static final long DELAY_MAX_MILLIS = 5_000L;
    private static final long POLL_DEFAULT_MILLIS = 2_000L;
    private static final long POLL_MAX_MILLIS = 5_000L;
    /**
     * A read waits for first events at least this long and at most
     * {@link #FIRST_EVENT_MAX_MILLIS}; a caller-supplied {@code delay_ms}
     * between the two extends the window so a slow first sample is still
     * honoured.
     */
    private static final long FIRST_EVENT_MIN_MILLIS = 3_000L;
    private static final long FIRST_EVENT_MAX_MILLIS = 8_000L;
    private static final String THREAD_NAME = "android-capability-sensor-catalog";

    private final Context context;
    private final Object lock = new Object();

    private HandlerThread thread;
    /** The active stream's readout; null until the first start, kept after stop. */
    private StreamSession stream;
    private boolean closed;

    public SensorCatalogModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_LIST, METHOD_READ, METHOD_STREAM_START,
                METHOD_STREAM_POLL, METHOD_STREAM_STOP);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_READ, METHOD_STREAM_START, METHOD_STREAM_POLL);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_LIST:
                return list(request);
            case METHOD_READ:
                return read(request);
            case METHOD_STREAM_START:
                return streamStart(request);
            case METHOD_STREAM_POLL:
                return streamPoll(request);
            case METHOD_STREAM_STOP:
                return streamStop(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * Stop the stream and the delivery thread when the bridge closes.
     * Idempotent; a read already in flight still finishes its bounded window.
     */
    @Override
    public void close() {
        HandlerThread threadToStop;
        synchronized (lock) {
            closed = true;
            if (stream != null) {
                stream.readout.release(sensorManager());
                stream = null;
            }
            threadToStop = thread;
            thread = null;
        }
        if (threadToStop != null) {
            threadToStop.quitSafely();
        }
    }

    /** {@code sensor.list} — no params; the bounded catalog as {@code sensors_json}. */
    private AndroidCapabilityProtocol.Response list(
            AndroidCapabilityProtocol.Request request) {
        SensorManager manager = sensorManager();
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }
        List<CatalogEntry> entries;
        try {
            entries = catalog(manager);
        } catch (RuntimeException e) {
            Log.w(TAG, "sensor.list failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }
        boolean truncated = entries.size() > MAX_LISTED_SENSORS;
        if (truncated) {
            entries = entries.subList(0, MAX_LISTED_SENSORS);
        }
        StringBuilder json = new StringBuilder(entries.size() * 128);
        json.append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CatalogEntry entry = entries.get(i);
            json.append("{\"name\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(entry.name))
                    .append(",\"type\":").append(entry.sensor.getType())
                    .append(",\"vendor\":").append(encodeNullable(entry.sensor.getVendor()))
                    .append(",\"max_range\":").append(finiteOrNull(entry.sensor.getMaximumRange()))
                    .append(",\"resolution\":").append(finiteOrNull(entry.sensor.getResolution()))
                    .append(",\"power\":").append(finiteOrNull(entry.sensor.getPower()))
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sensors_json", json.toString());
        fields.put("count", (long) entries.size());
        if (truncated) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code sensor.read} — one bounded capture of {@code count} samples over
     * the selected sensors. The whole call is bounded: the first-event window
     * is capped and each extra sample waits at most {@code delay_ms}.
     */
    private AndroidCapabilityProtocol.Response read(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("sensor", "all", "count", "delay_ms"));
        boolean all = params.optionalBoolean("all", false);
        String sensorParam = params.optionalString("sensor", SENSOR_PARAM_MAX_CHARS, "");
        long count = params.optionalLong("count", COUNT_MIN, COUNT_MAX, 1L);
        long delayMs = params.optionalLong("delay_ms", 0L, DELAY_MAX_MILLIS, 0L);
        if (all && !sensorParam.isEmpty()) {
            throw new CapabilityParams.Invalid(
                    "parameters sensor and all are mutually exclusive");
        }

        SensorManager manager = sensorManager();
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }
        Selection selection;
        try {
            selection = select(catalog(manager), all, sensorParam);
        } catch (RuntimeException e) {
            Log.w(TAG, "sensor.read catalog failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }
        if (selection.entries.isEmpty()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable:no matching sensor");
        }
        Handler handler = sensorHandler();
        if (handler == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }

        SensorReadout readout = new SensorReadout();
        StringBuilder samples = new StringBuilder(128);
        long emitted = 0L;
        try {
            for (CatalogEntry entry : selection.entries) {
                readout.track(manager, entry, SensorManager.SENSOR_DELAY_GAME, handler);
            }
            if (readout.trackedCount() == 0) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-unavailable");
            }
            long firstWindow = Math.min(
                    Math.max(FIRST_EVENT_MIN_MILLIS, delayMs), FIRST_EVENT_MAX_MILLIS);
            if (readout.awaitAll(firstWindow) == 0) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-timeout");
            }
            samples.append('[');
            for (long i = 0; i < count; i++) {
                if (i > 0 && delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (emitted > 0) {
                    samples.append(',');
                }
                samples.append(readout.document());
                emitted++;
            }
            samples.append(']');
        } finally {
            readout.release(manager);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("samples_json", samples.toString());
        fields.put("count", emitted);
        fields.put("sensors", joinNames(selection.entries));
        if (!selection.unmatched.isEmpty()) {
            fields.put("unmatched", String.join(",", selection.unmatched));
        }
        if (selection.truncated) {
            fields.put("truncated", true);
        }
        List<String> silent = readout.silent(selection.entries);
        if (!silent.isEmpty()) {
            fields.put("silent", String.join(",", silent));
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code sensor.stream.start} — open the single pull stream over the
     * selected sensors. The stream keeps delivering events until stopped or
     * the module closes; one stream at a time.
     */
    private AndroidCapabilityProtocol.Response streamStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("sensor", "all", "delay_ms"));
        boolean all = params.optionalBoolean("all", false);
        String sensorParam = params.optionalString("sensor", SENSOR_PARAM_MAX_CHARS, "");
        long delayMs = params.optionalLong("delay_ms", 0L, DELAY_MAX_MILLIS, 0L);
        if (all && !sensorParam.isEmpty()) {
            throw new CapabilityParams.Invalid(
                    "parameters sensor and all are mutually exclusive");
        }

        SensorManager manager = sensorManager();
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }
        Selection selection;
        try {
            selection = select(catalog(manager), all, sensorParam);
        } catch (RuntimeException e) {
            Log.w(TAG, "sensor.stream.start catalog failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable");
        }
        if (selection.entries.isEmpty()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-unavailable:no matching sensor");
        }
        int samplingUs = delayMs <= 0
                ? SensorManager.SENSOR_DELAY_GAME
                : (int) TimeUnit.MILLISECONDS.toMicros(delayMs);
        synchronized (lock) {
            if (closed) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-stream-closed");
            }
            if (stream != null && !stream.readout.isStopped()) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-stream-already-started");
            }
            Handler handler = handlerLocked();
            if (handler == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-unavailable");
            }
            SensorReadout readout = new SensorReadout();
            for (CatalogEntry entry : selection.entries) {
                readout.track(manager, entry, samplingUs, handler);
            }
            if (readout.trackedCount() == 0) {
                readout.release(manager);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-unavailable");
            }
            stream = new StreamSession(readout);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stream_started", true);
        fields.put("sensors", joinNames(selection.entries));
        if (!selection.unmatched.isEmpty()) {
            fields.put("unmatched", String.join(",", selection.unmatched));
        }
        if (selection.truncated) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code sensor.stream.poll} — bounded wait for one coalesced document of
     * the latest values per requested sensor. New events since the last poll
     * produce {@code sample_json}; a quiet window is {@code stream_empty}; a
     * stopped stream is {@code stream_stopped}.
     */
    private AndroidCapabilityProtocol.Response streamPoll(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("timeout_ms"));
        long timeoutMs = params.optionalLong(
                "timeout_ms", 0L, POLL_MAX_MILLIS, POLL_DEFAULT_MILLIS);

        StreamSession session;
        synchronized (lock) {
            session = stream;
        }
        if (session == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sensor-stream-not-started");
        }
        long generation = session.readout.awaitChange(session.seenGeneration, timeoutMs);
        if (generation < 0) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("stream_stopped", true);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
        if (generation == session.seenGeneration) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("stream_empty", true);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
        session.seenGeneration = generation;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sample_json", session.readout.document());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code sensor.stream.stop} — idempotent; reports whether a stream was live. */
    private AndroidCapabilityProtocol.Response streamStop(
            AndroidCapabilityProtocol.Request request) {
        SensorManager manager = sensorManager();
        boolean wasRunning;
        synchronized (lock) {
            wasRunning = stream != null && !stream.readout.isStopped();
            if (stream != null) {
                stream.readout.release(manager);
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stream_stopped", true);
        fields.put("was_running", wasRunning);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private SensorManager sensorManager() {
        try {
            return (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Handler on the shared delivery thread, or null once closed. */
    private Handler sensorHandler() {
        synchronized (lock) {
            return handlerLocked();
        }
    }

    /** Caller must hold {@link #lock}. */
    private Handler handlerLocked() {
        if (closed) {
            return null;
        }
        if (thread == null || !thread.isAlive()) {
            thread = new HandlerThread(THREAD_NAME);
            thread.start();
        }
        return new Handler(thread.getLooper());
    }

    /** The catalog: every platform sensor with a unique canonical name. */
    private static List<CatalogEntry> catalog(SensorManager manager) {
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ALL);
        List<CatalogEntry> entries = new ArrayList<>(sensors.size());
        Map<String, Integer> used = new LinkedHashMap<>();
        for (Sensor sensor : sensors) {
            String base = canonicalName(sensor);
            Integer seen = used.get(base);
            String name = seen == null ? base : base + "_" + (seen + 1);
            used.put(base, seen == null ? 1 : seen + 1);
            entries.add(new CatalogEntry(sensor, name));
        }
        return entries;
    }

    /**
     * Stable lowercase wire name for one sensor: the known type name, else the
     * tail of its {@code getStringType()} sanitized to {@code [a-z0-9_]}, else
     * {@code sensor_type_<int>}.
     */
    private static String canonicalName(Sensor sensor) {
        String known = knownName(sensor.getType());
        if (known != null) {
            return known;
        }
        String stringType = sensor.getStringType();
        if (stringType != null) {
            int dot = stringType.lastIndexOf('.');
            String tail = dot >= 0 ? stringType.substring(dot + 1) : stringType;
            String sanitized = sanitizeName(tail);
            if (!sanitized.isEmpty()) {
                return sanitized;
            }
        }
        return "sensor_type_" + sensor.getType();
    }

    private static String sanitizeName(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            out.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' ? c : '_');
        }
        return out.toString();
    }

    /** Canonical lowercase names for the known platform sensor types. */
    private static String knownName(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnetic_field";
            case Sensor.TYPE_ORIENTATION: return "orientation";
            case Sensor.TYPE_GYROSCOPE: return "gyroscope";
            case Sensor.TYPE_LIGHT: return "light";
            case Sensor.TYPE_PRESSURE: return "pressure";
            case Sensor.TYPE_TEMPERATURE: return "temperature";
            case Sensor.TYPE_PROXIMITY: return "proximity";
            case Sensor.TYPE_GRAVITY: return "gravity";
            case Sensor.TYPE_LINEAR_ACCELERATION: return "linear_acceleration";
            case Sensor.TYPE_ROTATION_VECTOR: return "rotation_vector";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "relative_humidity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "ambient_temperature";
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                return "magnetic_field_uncalibrated";
            case Sensor.TYPE_GAME_ROTATION_VECTOR: return "game_rotation_vector";
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED: return "gyroscope_uncalibrated";
            case Sensor.TYPE_SIGNIFICANT_MOTION: return "significant_motion";
            case Sensor.TYPE_STEP_DETECTOR: return "step_detector";
            case Sensor.TYPE_STEP_COUNTER: return "step_counter";
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                return "geomagnetic_rotation_vector";
            case Sensor.TYPE_HEART_RATE: return "heart_rate";
            case 22: return "tilt_detector"; // TYPE_TILT_DETECTOR (@SystemApi)
            case 23: return "wake_gesture"; // TYPE_WAKE_GESTURE (@SystemApi)
            case 24: return "glance_gesture"; // TYPE_GLANCE_GESTURE (@SystemApi)
            case 25: return "pick_up_gesture"; // TYPE_PICK_UP_GESTURE (@SystemApi)
            case 26: return "wrist_tilt_gesture"; // TYPE_WRIST_TILT_GESTURE (@SystemApi)
            case 27: return "device_orientation"; // TYPE_DEVICE_ORIENTATION (@SystemApi)
            case Sensor.TYPE_POSE_6DOF: return "pose_6dof";
            case Sensor.TYPE_STATIONARY_DETECT: return "stationary_detect";
            case Sensor.TYPE_MOTION_DETECT: return "motion_detect";
            case Sensor.TYPE_HEART_BEAT: return "heart_beat";
            case 32: return "dynamic_sensor_meta"; // TYPE_DYNAMIC_SENSOR_META (@SystemApi)
            case 33: return "additional_info"; // TYPE_ADDITIONAL_INFO (@SystemApi)
            case Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT:
                return "low_latency_offbody_detect";
            case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED:
                return "accelerometer_uncalibrated";
            case 36: return "hinge_angle"; // Sensor.TYPE_HINGE_ANGLE (API 30)
            case 37: return "head_tracker"; // Sensor.TYPE_HEAD_TRACKER (API 33)
            case 38: return "accelerometer_limited_axes"; // (API 33)
            case 39: return "gyroscope_limited_axes"; // (API 33)
            case 40: return "accelerometer_limited_axes_uncalibrated"; // (API 33)
            case 41: return "gyroscope_limited_axes_uncalibrated"; // (API 33)
            case 42: return "heading"; // Sensor.TYPE_HEADING (API 35)
            default: return null;
        }
    }

    /**
     * Resolve the requested sensors: all catalog entries when {@code all}, or
     * each comma-separated token resolved to one entry by exact canonical
     * name, numeric type, canonical substring, then device-name substring
     * (shortest match wins, like upstream). Tokens that resolve to nothing are
     * collected in {@code unmatched} rather than failing the selection.
     */
    private static Selection select(List<CatalogEntry> catalog, boolean all,
                                    String sensorParam) {
        Selection selection = new Selection();
        if (all) {
            int kept = Math.min(catalog.size(), MAX_SELECTED_SENSORS);
            selection.entries.addAll(catalog.subList(0, kept));
            selection.truncated = catalog.size() > kept;
            return selection;
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : sensorParam.split(",")) {
            String token = raw.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty()) {
            throw new CapabilityParams.Invalid("missing string parameter: sensor");
        }
        if (tokens.size() > MAX_SENSOR_TOKENS) {
            throw new CapabilityParams.Invalid("too many sensors requested: sensor");
        }
        for (String token : tokens) {
            CatalogEntry entry = resolve(catalog, token);
            if (entry == null) {
                selection.unmatched.add(token);
            } else if (!selection.entries.contains(entry)) {
                selection.entries.add(entry);
            }
        }
        return selection;
    }

    /** One token to one catalog entry, or null when nothing matches. */
    private static CatalogEntry resolve(List<CatalogEntry> catalog, String rawToken) {
        String token = rawToken.toLowerCase(Locale.US);
        for (CatalogEntry entry : catalog) {
            if (entry.name.equals(token)) {
                return entry;
            }
        }
        Long numericType = parseLong(token);
        if (numericType != null) {
            for (CatalogEntry entry : catalog) {
                if (entry.sensor.getType() == numericType) {
                    return entry;
                }
            }
        }
        CatalogEntry best = null;
        for (CatalogEntry entry : catalog) {
            if (entry.name.contains(token)
                    && (best == null || entry.name.length() < best.name.length())) {
                best = entry;
            }
        }
        if (best != null) {
            return best;
        }
        int bestNameLength = Integer.MAX_VALUE;
        for (CatalogEntry entry : catalog) {
            String deviceName = entry.sensor.getName();
            if (deviceName != null
                    && deviceName.toLowerCase(Locale.US).contains(token)
                    && deviceName.length() < bestNameLength) {
                best = entry;
                bestNameLength = deviceName.length();
            }
        }
        return best;
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String joinNames(List<CatalogEntry> entries) {
        StringBuilder names = new StringBuilder();
        for (CatalogEntry entry : entries) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(entry.name);
        }
        return names.toString();
    }

    /** JSON literal for one nullable metadata number; non-finite is null, never 0. */
    private static String finiteOrNull(float value) {
        return Float.isFinite(value) ? Float.toString(value) : "null";
    }

    /** JSON literal for one nullable metadata string. */
    private static String encodeNullable(String value) {
        return value == null || value.isEmpty()
                ? "null" : AndroidCapabilityProtocol.encodeStringValue(value);
    }

    /** One catalog row: the platform sensor plus its unique canonical name. */
    private static final class CatalogEntry {
        final Sensor sensor;
        final String name;

        CatalogEntry(Sensor sensor, String name) {
            this.sensor = sensor;
            this.name = name;
        }
    }

    /** The outcome of resolving a request's sensor tokens. */
    private static final class Selection {
        final List<CatalogEntry> entries = new ArrayList<>();
        final List<String> unmatched = new ArrayList<>();
        boolean truncated;
    }

    /** A live stream: the readout plus the generation the last poll consumed. */
    private static final class StreamSession {
        final SensorReadout readout;
        long seenGeneration;

        StreamSession(SensorReadout readout) {
            this.readout = readout;
        }
    }

    /**
     * Latest-values readout behind one listener per selected sensor. Every
     * accepted event bumps {@code generation}; events carrying a non-finite
     * value are dropped whole rather than fabricating a sample. {@link
     * #release(SensorManager)} unregisters every tracked listener and marks
     * the readout stopped, after which it emits nothing else.
     */
    private static final class SensorReadout {
        private final List<SensorEventListener> listeners = new ArrayList<>();
        private final Map<String, double[]> latest = new LinkedHashMap<>();
        private final Object monitor = new Object();
        private long generation;
        private boolean stopped;

        void track(SensorManager manager, CatalogEntry entry, int samplingUs,
                   Handler handler) {
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    offer(entry.name, event);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            boolean registered;
            try {
                registered = manager.registerListener(
                        listener, entry.sensor, samplingUs, handler);
            } catch (RuntimeException e) {
                registered = false;
            }
            if (registered) {
                listeners.add(listener);
            }
        }

        int trackedCount() {
            return listeners.size();
        }

        boolean isStopped() {
            synchronized (monitor) {
                return stopped;
            }
        }

        void release(SensorManager manager) {
            if (manager != null) {
                for (SensorEventListener listener : listeners) {
                    try {
                        manager.unregisterListener(listener);
                    } catch (RuntimeException ignored) {
                        // Unregistration is best-effort; the state is terminal anyway.
                    }
                }
            }
            listeners.clear();
            synchronized (monitor) {
                stopped = true;
                monitor.notifyAll();
            }
        }

        /**
         * Wait until every tracked sensor delivered at least one event or the
         * bounded deadline passes; returns the number of sensors with values.
         */
        int awaitAll(long deadlineMillis) {
            synchronized (monitor) {
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
                int expected = listeners.size();
                while (latest.size() < expected && !stopped) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return latest.size();
            }
        }

        /**
         * Wait for a generation newer than {@code seenGeneration}, a stop, or
         * the bounded deadline.
         *
         * @return the new generation, {@code seenGeneration} on timeout, or
         *         {@code -1} when the readout stopped
         */
        long awaitChange(long seenGeneration, long timeoutMillis) {
            synchronized (monitor) {
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                while (!stopped && generation == seenGeneration) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return generation;
                    }
                    try {
                        monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return generation;
                    }
                }
                return stopped ? -1L : generation;
            }
        }

        /** One merged {@code {name:{"values":[..]},...}} document of latest values. */
        String document() {
            synchronized (monitor) {
                StringBuilder doc = new StringBuilder(64);
                doc.append('{');
                boolean first = true;
                for (Map.Entry<String, double[]> entry : latest.entrySet()) {
                    if (!first) {
                        doc.append(',');
                    }
                    first = false;
                    doc.append(AndroidCapabilityProtocol.encodeStringValue(entry.getKey()))
                            .append(":{\"values\":[");
                    double[] values = entry.getValue();
                    for (int i = 0; i < values.length; i++) {
                        if (i > 0) {
                            doc.append(',');
                        }
                        doc.append(Double.toString(values[i]));
                    }
                    doc.append("]}");
                }
                return doc.append('}').toString();
            }
        }

        /** Selected sensors that never delivered a value. */
        List<String> silent(List<CatalogEntry> entries) {
            List<String> silent = new ArrayList<>();
            synchronized (monitor) {
                for (CatalogEntry entry : entries) {
                    if (!latest.containsKey(entry.name)) {
                        silent.add(entry.name);
                    }
                }
            }
            return silent;
        }

        private void offer(String name, SensorEvent event) {
            if (event == null || event.values == null) {
                return;
            }
            double[] values = new double[event.values.length];
            for (int i = 0; i < event.values.length; i++) {
                float value = event.values[i];
                if (!Float.isFinite(value)) {
                    return;
                }
                values[i] = value;
            }
            synchronized (monitor) {
                latest.put(name, values);
                generation++;
                monitor.notifyAll();
            }
        }
    }
}
