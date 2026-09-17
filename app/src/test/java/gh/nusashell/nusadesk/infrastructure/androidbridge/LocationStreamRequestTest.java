package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

/**
 * Request construction for the continuous location stream: the interval is
 * configurable but capped, the accuracy selection is explicit, and invalid
 * input is rejected at the boundary.
 */
public class LocationStreamRequestTest {

    @Test
    public void clampsRequestedIntervalIntoPolicyBounds() {
        LocationStreamRequest low = LocationStreamRequest.create(
                0L, LocationStreamRequest.Accuracy.FINE);
        assertEquals(LocationStreamIntervalPolicy.MIN_INTERVAL_MILLIS,
                low.getIntervalMillis());

        LocationStreamRequest high = LocationStreamRequest.create(
                9_999_999L, LocationStreamRequest.Accuracy.COARSE);
        assertEquals(LocationStreamIntervalPolicy.MAX_INTERVAL_MILLIS,
                high.getIntervalMillis());
    }

    @Test
    public void preservesIntervalsInsideTheBounds() {
        LocationStreamRequest request = LocationStreamRequest.create(
                5_000L, LocationStreamRequest.Accuracy.FINE);
        assertEquals(5_000L, request.getIntervalMillis());
        assertEquals(LocationStreamRequest.Accuracy.FINE, request.getAccuracy());
    }

    @Test
    public void rejectsNullAccuracy() {
        try {
            LocationStreamRequest.create(5_000L, null);
            fail("null accuracy must be rejected");
        } catch (IllegalArgumentException expected) {
            // Boundary validation, not UI-only.
        }
    }

    @Test
    public void equalityAndHashCodeCoverIntervalAndAccuracy() {
        LocationStreamRequest a = LocationStreamRequest.create(
                5_000L, LocationStreamRequest.Accuracy.FINE);
        LocationStreamRequest same = LocationStreamRequest.create(
                5_000L, LocationStreamRequest.Accuracy.FINE);
        LocationStreamRequest otherInterval = LocationStreamRequest.create(
                4_000L, LocationStreamRequest.Accuracy.FINE);
        LocationStreamRequest otherAccuracy = LocationStreamRequest.create(
                5_000L, LocationStreamRequest.Accuracy.COARSE);

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, otherInterval);
        assertNotEquals(a, otherAccuracy);
    }
}
