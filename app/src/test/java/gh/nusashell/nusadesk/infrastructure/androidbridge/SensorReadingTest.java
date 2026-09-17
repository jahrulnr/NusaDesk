package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SensorReadingTest {

    @Test
    public void readingCarriesFiniteValuesMappedAccuracyAndFields() {
        SensorReading reading = SensorReading.reading(
                "accelerometer", 0.5, -9.81, 0.25, 3, 1_700_000_000_123_456L);

        assertEquals(SensorReading.State.READING, reading.getState());
        assertEquals("accelerometer", reading.getSensorName());
        assertEquals(0.5, reading.getX(), 0.0);
        assertEquals(-9.81, reading.getY(), 0.0);
        assertEquals(0.25, reading.getZ(), 0.0);
        assertEquals("high", reading.getAccuracy());
        assertEquals(1_700_000_000_123_456L, reading.getTimestampNanos());

        Map<String, Object> fields = reading.responseFields();
        assertEquals("accelerometer", fields.get("sensor"));
        assertEquals(true, fields.get("available"));
        assertEquals(0.5, (Double) fields.get("x"), 0.0);
        assertEquals(-9.81, (Double) fields.get("y"), 0.0);
        assertEquals(0.25, (Double) fields.get("z"), 0.0);
        assertEquals("high", fields.get("accuracy"));
        assertEquals(1_700_000_000_123_456L, fields.get("timestamp"));
        assertEquals(7, fields.size());
    }

    @Test
    public void rejectsNonFiniteValuesAtTheDomainBoundary() {
        for (double invalid : new double[]{
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            try {
                SensorReading.reading("accelerometer", invalid, 0.0, 0.0, 0, 1L);
                assertTrue("expected rejection for " + invalid, false);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void mapsEverySensorManagerAccuracyCodeToBoundedText() {
        assertEquals("no-contact", SensorReading.accuracyText(-1));
        assertEquals("unreliable", SensorReading.accuracyText(0));
        assertEquals("low", SensorReading.accuracyText(1));
        assertEquals("medium", SensorReading.accuracyText(2));
        assertEquals("high", SensorReading.accuracyText(3));
        assertEquals("unknown", SensorReading.accuracyText(99));
    }

    @Test
    public void failureStatesAreExplicitAndNeverLookLikeReadings() {
        SensorReading unavailable = SensorReading.unavailable("accelerometer");
        assertEquals(SensorReading.State.UNAVAILABLE, unavailable.getState());
        assertEquals("accelerometer", unavailable.getSensorName());
        assertEquals(-1L, unavailable.getTimestampNanos());
        assertFalse(isFieldRepresentable(unavailable));

        SensorReading timeout = SensorReading.timeout("gyroscope");
        assertEquals(SensorReading.State.TIMEOUT, timeout.getState());
        assertEquals("gyroscope", timeout.getSensorName());
        assertFalse(isFieldRepresentable(timeout));

        SensorReading error = SensorReading.error("accelerometer");
        assertEquals(SensorReading.State.ERROR, error.getState());
        assertFalse(isFieldRepresentable(error));
    }

    @Test
    public void rejectsBlankSensorNames() {
        try {
            SensorReading.reading(" ", 0.0, 0.0, 0.0, 0, 1L);
            assertTrue("expected blank-name rejection", false);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static boolean isFieldRepresentable(SensorReading reading) {
        try {
            reading.responseFields();
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }
}
