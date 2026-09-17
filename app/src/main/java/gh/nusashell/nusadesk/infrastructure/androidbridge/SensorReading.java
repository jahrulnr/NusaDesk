package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable one-shot snapshot of an Android sensor read.
 *
 * <p>A complete reading carries three finite values, a mapped accuracy, and
 * the platform event timestamp in nanoseconds. Unavailable, timeout, and error
 * states are explicit value objects so a caller can never mistake a failed
 * read for real data; the bridge encodes only a {@link State#READING} snapshot
 * with fields, and reports every other state as a typed error. The class is
 * Android-free so the protocol and its tests share one contract.</p>
 */
public final class SensorReading {
    public enum State {
        /** A finite, complete platform snapshot was delivered. */
        READING,
        /** The requested sensor does not exist on this device. */
        UNAVAILABLE,
        /** No event arrived before the bounded read deadline. */
        TIMEOUT,
        /** The platform rejected or corrupted the read; no values are fabricated. */
        ERROR
    }

    private final String sensorName;
    private final State state;
    private final double x;
    private final double y;
    private final double z;
    private final String accuracy;
    private final long timestampNanos;

    private SensorReading(String sensorName, State state, double x, double y, double z,
                          String accuracy, long timestampNanos) {
        this.sensorName = requireText(sensorName, "sensorName");
        this.state = state;
        this.x = x;
        this.y = y;
        this.z = z;
        this.accuracy = requireText(accuracy, "accuracy");
        this.timestampNanos = timestampNanos;
    }

    /**
     * A finite, complete one-shot reading.
     *
     * @param accuracy raw {@code SensorManager.SENSOR_STATUS_*} value; mapped
     *                 to a bounded text value by {@link #accuracyText(int)}
     * @throws IllegalArgumentException when any value is NaN or infinite
     */
    public static SensorReading reading(String sensorName, double x, double y, double z,
                                        int accuracy, long timestampNanos) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("sensor values must be finite");
        }
        return new SensorReading(sensorName, State.READING, x, y, z,
                accuracyText(accuracy), timestampNanos);
    }

    /** The requested sensor does not exist on this device. */
    public static SensorReading unavailable(String sensorName) {
        return new SensorReading(sensorName, State.UNAVAILABLE, 0.0, 0.0, 0.0, "unknown", -1L);
    }

    /** No event arrived before the bounded read deadline. */
    public static SensorReading timeout(String sensorName) {
        return new SensorReading(sensorName, State.TIMEOUT, 0.0, 0.0, 0.0, "unknown", -1L);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static SensorReading error(String sensorName) {
        return new SensorReading(sensorName, State.ERROR, 0.0, 0.0, 0.0, "unknown", -1L);
    }

    public State getState() {
        return state;
    }

    public String getSensorName() {
        return sensorName;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public String getAccuracy() {
        return accuracy;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    /**
     * Flat fields for the RPC response. Only a complete reading has a field
     * representation; other states are reported as typed errors by the
     * request handler and must not look like a normal reading here.
     */
    public Map<String, Object> responseFields() {
        if (state != State.READING) {
            throw new IllegalStateException("response fields are defined only for a reading");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sensor", sensorName);
        fields.put("available", true);
        fields.put("x", x);
        fields.put("y", y);
        fields.put("z", z);
        fields.put("accuracy", accuracy);
        fields.put("timestamp", timestampNanos);
        return fields;
    }

    /** Bounded text form of the raw {@code SENSOR_STATUS_*} accuracy code. */
    static String accuracyText(int accuracy) {
        switch (accuracy) {
            case -1:
                return "no-contact";
            case 0:
                return "unreliable";
            case 1:
                return "low";
            case 2:
                return "medium";
            case 3:
                return "high";
            default:
                return "unknown";
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
