package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable one-shot snapshot of an Android location fix.
 *
 * <p>A complete fix carries a finite latitude/longitude, a finite non-negative
 * accuracy in meters, the provider name, and a wall-clock UTC timestamp in
 * milliseconds. Altitude, speed, and bearing are present only when the
 * platform fix carried a valid value; a missing or invalid optional value is
 * omitted, never fabricated. Permission, unavailable, timeout, and error
 * states are explicit value objects so a caller can never mistake a failed
 * read for real data; the bridge encodes only a {@link State#READING} snapshot
 * with fields and reports every other state as a typed error. The class is
 * Android-free so the protocol and its tests share one contract.</p>
 */
public final class LocationSnapshot {
    public enum State {
        /** A finite, complete platform fix was delivered. */
        READING,
        /** Location permission is absent and no denial is recorded; the later consent flow may ask. */
        PERMISSION_REQUIRED,
        /** Location permission is absent and the user previously denied it. */
        PERMISSION_DENIED,
        /** The location manager, provider, or the device location switch is absent/disabled. */
        UNAVAILABLE,
        /** No fix arrived before the bounded read deadline. */
        TIMEOUT,
        /** The platform rejected or corrupted the read; no values are fabricated. */
        ERROR
    }

    private static final int MAX_PROVIDER_LENGTH = 64;

    private final State state;
    private final double latitude;
    private final double longitude;
    private final double accuracyMeters;
    private final String provider;
    private final long timestampUtcMillis;
    private final Double altitudeMeters;
    private final Double speedMetersPerSecond;
    private final Double bearingDegrees;

    private LocationSnapshot(State state, double latitude, double longitude,
                             double accuracyMeters, String provider, long timestampUtcMillis,
                             Double altitudeMeters, Double speedMetersPerSecond,
                             Double bearingDegrees) {
        this.state = state;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.provider = requireText(provider, "provider");
        this.timestampUtcMillis = timestampUtcMillis;
        this.altitudeMeters = altitudeMeters;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.bearingDegrees = bearingDegrees;
    }

    /**
     * A finite, complete one-shot fix.
     *
     * @param altitudeMeters   altitude above the WGS84 ellipsoid, or {@code null}
     *                         when the platform fix did not carry a valid value
     * @param speedMetersPerSecond ground speed, or {@code null} when absent/invalid
     * @param bearingDegrees   heading in degrees clockwise from north, or
     *                         {@code null} when absent/invalid
     * @throws IllegalArgumentException when a required value is non-finite or
     *                                  out of range, or an optional value is
     *                                  non-finite/out of range
     */
    public static LocationSnapshot reading(double latitude, double longitude,
                                           double accuracyMeters, String provider,
                                           long timestampUtcMillis, Double altitudeMeters,
                                           Double speedMetersPerSecond, Double bearingDegrees) {
        if (!Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be finite in [-90, 90]");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be finite in [-180, 180]");
        }
        if (!Double.isFinite(accuracyMeters) || accuracyMeters < 0.0) {
            throw new IllegalArgumentException("accuracyMeters must be finite and non-negative");
        }
        if (timestampUtcMillis <= 0L) {
            throw new IllegalArgumentException("timestampUtcMillis must be positive");
        }
        if (altitudeMeters != null && !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("altitudeMeters must be finite");
        }
        if (speedMetersPerSecond != null
                && (!Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0.0)) {
            throw new IllegalArgumentException(
                    "speedMetersPerSecond must be finite and non-negative");
        }
        if (bearingDegrees != null
                && (!Double.isFinite(bearingDegrees)
                || bearingDegrees < 0.0 || bearingDegrees > 360.0)) {
            throw new IllegalArgumentException("bearingDegrees must be finite in [0, 360]");
        }
        return new LocationSnapshot(State.READING, latitude, longitude, accuracyMeters,
                provider, timestampUtcMillis, altitudeMeters, speedMetersPerSecond,
                bearingDegrees);
    }

    /** No location grant and no recorded denial; the later consent flow may ask. */
    public static LocationSnapshot permissionRequired() {
        return new LocationSnapshot(State.PERMISSION_REQUIRED, 0.0, 0.0, 0.0, "unknown",
                -1L, null, null, null);
    }

    /** No location grant and the user previously denied it. */
    public static LocationSnapshot permissionDenied() {
        return new LocationSnapshot(State.PERMISSION_DENIED, 0.0, 0.0, 0.0, "unknown",
                -1L, null, null, null);
    }

    /** The location manager, provider, or device location switch is absent/disabled. */
    public static LocationSnapshot unavailable() {
        return new LocationSnapshot(State.UNAVAILABLE, 0.0, 0.0, 0.0, "unknown",
                -1L, null, null, null);
    }

    /** No fix arrived before the bounded read deadline. */
    public static LocationSnapshot timeout() {
        return new LocationSnapshot(State.TIMEOUT, 0.0, 0.0, 0.0, "unknown",
                -1L, null, null, null);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static LocationSnapshot error() {
        return new LocationSnapshot(State.ERROR, 0.0, 0.0, 0.0, "unknown",
                -1L, null, null, null);
    }

    public State getState() {
        return state;
    }

    /** Meaningless unless {@link State#READING}; sentinel values otherwise. */
    public double getLatitude() {
        return latitude;
    }

    /** Meaningless unless {@link State#READING}; sentinel values otherwise. */
    public double getLongitude() {
        return longitude;
    }

    /** Meaningless unless {@link State#READING}; sentinel values otherwise. */
    public double getAccuracyMeters() {
        return accuracyMeters;
    }

    public String getProvider() {
        return provider;
    }

    /** Meaningless unless {@link State#READING}; {@code -1} otherwise. */
    public long getTimestampUtcMillis() {
        return timestampUtcMillis;
    }

    /** {@code null} unless the platform fix carried a valid value. */
    public Double getAltitudeMeters() {
        return altitudeMeters;
    }

    /** {@code null} unless the platform fix carried a valid value. */
    public Double getSpeedMetersPerSecond() {
        return speedMetersPerSecond;
    }

    /** {@code null} unless the platform fix carried a valid value. */
    public Double getBearingDegrees() {
        return bearingDegrees;
    }

    /**
     * Flat fields for the RPC response. Only a complete fix has a field
     * representation; other states are reported as typed errors by the
     * request handler and must not look like a real fix here.
     */
    public Map<String, Object> responseFields() {
        if (state != State.READING) {
            throw new IllegalStateException("response fields are defined only for a reading");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("available", true);
        fields.put("provider", provider);
        fields.put("latitude", latitude);
        fields.put("longitude", longitude);
        fields.put("accuracy_meters", accuracyMeters);
        fields.put("timestamp_utc_ms", timestampUtcMillis);
        if (altitudeMeters != null) {
            fields.put("altitude_meters", altitudeMeters);
        }
        if (speedMetersPerSecond != null) {
            fields.put("speed_meters_per_second", speedMetersPerSecond);
        }
        if (bearingDegrees != null) {
            fields.put("bearing_degrees", bearingDegrees);
        }
        return fields;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_PROVIDER_LENGTH) {
            throw new IllegalArgumentException(field + " must be bounded non-blank text");
        }
        return value;
    }
}
