package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LocationSnapshotTest {

    @Test
    public void readingMapsFiniteBoundedFieldsIncludingValidOptionals() {
        LocationSnapshot snapshot = LocationSnapshot.reading(
                -6.9175, 107.6191, 12.5, "gps", 1_700_000_000_000L,
                768.25, 1.75, 42.0);

        assertEquals(LocationSnapshot.State.READING, snapshot.getState());
        assertEquals(-6.9175, snapshot.getLatitude(), 0.0);
        assertEquals(107.6191, snapshot.getLongitude(), 0.0);
        assertEquals(12.5, snapshot.getAccuracyMeters(), 0.0);
        assertEquals("gps", snapshot.getProvider());
        assertEquals(1_700_000_000_000L, snapshot.getTimestampUtcMillis());
        assertEquals(768.25, snapshot.getAltitudeMeters(), 0.0);
        assertEquals(1.75, snapshot.getSpeedMetersPerSecond(), 0.0);
        assertEquals(42.0, snapshot.getBearingDegrees(), 0.0);

        Map<String, Object> fields = snapshot.responseFields();
        assertEquals(true, fields.get("available"));
        assertEquals("gps", fields.get("provider"));
        assertEquals(-6.9175, (Double) fields.get("latitude"), 0.0);
        assertEquals(107.6191, (Double) fields.get("longitude"), 0.0);
        assertEquals(12.5, (Double) fields.get("accuracy_meters"), 0.0);
        assertEquals(1_700_000_000_000L, fields.get("timestamp_utc_ms"));
        assertEquals(768.25, (Double) fields.get("altitude_meters"), 0.0);
        assertEquals(1.75, (Double) fields.get("speed_meters_per_second"), 0.0);
        assertEquals(42.0, (Double) fields.get("bearing_degrees"), 0.0);
    }

    @Test
    public void readingOmitsOptionalValuesWhenAbsent() {
        LocationSnapshot snapshot = LocationSnapshot.reading(
                1.0, 2.0, 30.0, "network", 42L, null, null, null);

        Map<String, Object> fields = snapshot.responseFields();
        assertFalse(fields.containsKey("altitude_meters"));
        assertFalse(fields.containsKey("speed_meters_per_second"));
        assertFalse(fields.containsKey("bearing_degrees"));
        assertNull(snapshot.getAltitudeMeters());
        assertNull(snapshot.getSpeedMetersPerSecond());
        assertNull(snapshot.getBearingDegrees());
    }

    @Test
    public void rejectsNonFiniteLatitudeLongitudeAndAccuracy() {
        for (double invalid : new double[]{
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            expectRejected(invalid, 0.0, 1.0, "latitude/longitude/accuracy non-finite");
            expectRejected(0.0, invalid, 1.0, "latitude/longitude/accuracy non-finite");
            expectRejected(0.0, 0.0, invalid, "latitude/longitude/accuracy non-finite");
        }
    }

    @Test
    public void rejectsOutOfRangeCoordinatesAccuracyAndOptionals() {
        expectRejected(90.1, 0.0, 1.0, "latitude above 90");
        expectRejected(-90.1, 0.0, 1.0, "latitude below -90");
        expectRejected(0.0, 180.1, 1.0, "longitude above 180");
        expectRejected(0.0, -180.1, 1.0, "longitude below -180");
        expectRejected(0.0, 0.0, -0.1, "negative accuracy");
        expectRejected(0.0, 0.0, 1.0, "negative speed",
                null, -5.0, null);
        expectRejected(0.0, 0.0, 1.0, "bearing above 360",
                null, null, 360.1);
        expectRejected(0.0, 0.0, 1.0, "bearing below 0",
                null, null, -0.1);
        expectRejected(0.0, 0.0, 1.0, "non-finite altitude",
                Double.NaN, null, null);
    }

    @Test
    public void rejectsBlankOrOversizedProvider() {
        StringBuilder longProvider = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            longProvider.append('x');
        }
        expectRejected(0.0, 0.0, 1.0, "blank provider", null, null, null, "");
        expectRejected(0.0, 0.0, 1.0, "oversized provider",
                null, null, null, longProvider.toString());
    }

    @Test
    public void rejectsNonPositiveTimestamp() {
        expectRejected(0.0, 0.0, 1.0, "zero timestamp", null, null, null, "gps", 0L);
        expectRejected(0.0, 0.0, 1.0, "negative timestamp", null, null, null, "gps", -1L);
    }

    @Test
    public void acceptsBoundaryValues() {
        LocationSnapshot snapshot = LocationSnapshot.reading(
                90.0, 180.0, 0.0, "gps", 1L, null, 0.0, 360.0);
        assertEquals(LocationSnapshot.State.READING, snapshot.getState());
        assertEquals(0.0, snapshot.getAccuracyMeters(), 0.0);
        assertEquals(0.0, snapshot.getSpeedMetersPerSecond(), 0.0);
        assertEquals(360.0, snapshot.getBearingDegrees(), 0.0);

        LocationSnapshot negative = LocationSnapshot.reading(
                -90.0, -180.0, 0.0, "gps", 1L, -400.0, 0.0, 0.0);
        assertEquals(-400.0, negative.getAltitudeMeters(), 0.0);
    }

    @Test
    public void nonReadingStatesAreExplicitAndCarryNoFields() {
        assertExplicitState(LocationSnapshot.permissionRequired(),
                LocationSnapshot.State.PERMISSION_REQUIRED);
        assertExplicitState(LocationSnapshot.permissionDenied(),
                LocationSnapshot.State.PERMISSION_DENIED);
        assertExplicitState(LocationSnapshot.unavailable(),
                LocationSnapshot.State.UNAVAILABLE);
        assertExplicitState(LocationSnapshot.timeout(),
                LocationSnapshot.State.TIMEOUT);
        assertExplicitState(LocationSnapshot.error(),
                LocationSnapshot.State.ERROR);
    }

    private static void assertExplicitState(LocationSnapshot snapshot,
                                            LocationSnapshot.State expected) {
        assertEquals(expected, snapshot.getState());
        assertEquals("unknown", snapshot.getProvider());
        assertEquals(-1L, snapshot.getTimestampUtcMillis());
        assertNull(snapshot.getAltitudeMeters());
        try {
            snapshot.responseFields();
            assertTrue("response fields must not exist for " + expected, false);
        } catch (IllegalStateException expectedFailure) {
            // Expected: non-reading states are typed errors on the wire.
        }
    }

    private static void expectRejected(double latitude, double longitude, double accuracy,
                                       String message) {
        expectRejected(latitude, longitude, accuracy, message, null, null, null);
    }

    private static void expectRejected(double latitude, double longitude, double accuracy,
                                       String message, Double altitude, Double speed,
                                       Double bearing) {
        expectRejected(latitude, longitude, accuracy, message, altitude, speed, bearing, "gps");
    }

    private static void expectRejected(double latitude, double longitude, double accuracy,
                                       String message, Double altitude, Double speed,
                                       Double bearing, String provider) {
        expectRejected(latitude, longitude, accuracy, message, altitude, speed, bearing,
                provider, 1L);
    }

    private static void expectRejected(double latitude, double longitude, double accuracy,
                                       String message, Double altitude, Double speed,
                                       Double bearing, String provider, long timestamp) {
        try {
            LocationSnapshot.reading(latitude, longitude, accuracy, provider, timestamp,
                    altitude, speed, bearing);
            assertTrue("expected rejection: " + message, false);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
