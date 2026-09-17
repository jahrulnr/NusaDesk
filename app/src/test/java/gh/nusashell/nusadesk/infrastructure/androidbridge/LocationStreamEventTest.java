package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Stream event construction and state mapping: only a validated reading
 * carries a snapshot; every failure state is explicit and never looks like a
 * fix.
 */
public class LocationStreamEventTest {

    private static LocationSnapshot reading(double latitude, double longitude) {
        return LocationSnapshot.reading(latitude, longitude, 5.0, "test", 42L,
                null, null, null);
    }

    @Test
    public void readingCarriesTheValidatedSnapshot() {
        LocationSnapshot snapshot = reading(-6.9175, 107.6191);
        LocationStreamEvent event = LocationStreamEvent.reading(snapshot);

        assertEquals(LocationStreamEvent.State.READING, event.getState());
        assertSame(snapshot, event.getSnapshot());
    }

    @Test
    public void readingRejectsNullAndNonReadingSnapshots() {
        try {
            LocationStreamEvent.reading(null);
            fail("null snapshot must be rejected");
        } catch (IllegalArgumentException expected) {
            // Boundary validation.
        }
        try {
            LocationStreamEvent.reading(LocationSnapshot.error());
            fail("a non-reading snapshot must be rejected");
        } catch (IllegalArgumentException expected) {
            // A failure state must never masquerade as a reading.
        }
    }

    @Test
    public void failureAndTerminalStatesCarryNoSnapshot() {
        assertBareState(LocationStreamEvent.permissionRequired(),
                LocationStreamEvent.State.PERMISSION_REQUIRED);
        assertBareState(LocationStreamEvent.permissionDenied(),
                LocationStreamEvent.State.PERMISSION_DENIED);
        assertBareState(LocationStreamEvent.unavailable(),
                LocationStreamEvent.State.UNAVAILABLE);
        assertBareState(LocationStreamEvent.timeout(), LocationStreamEvent.State.TIMEOUT);
        assertBareState(LocationStreamEvent.error(), LocationStreamEvent.State.ERROR);
        assertBareState(LocationStreamEvent.stopped(), LocationStreamEvent.State.STOPPED);
    }

    @Test
    public void equalityAndHashCodeCoverStateAndSnapshot() {
        LocationSnapshot snapshot = reading(1.0, 2.0);
        LocationStreamEvent a = LocationStreamEvent.reading(snapshot);
        LocationStreamEvent same = LocationStreamEvent.reading(snapshot);
        LocationStreamEvent otherFix = LocationStreamEvent.reading(reading(3.0, 4.0));

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, otherFix);
        assertNotEquals(a, LocationStreamEvent.timeout());
        assertEquals(LocationStreamEvent.timeout(), LocationStreamEvent.timeout());
    }

    private static void assertBareState(LocationStreamEvent event,
                                        LocationStreamEvent.State state) {
        assertEquals(state, event.getState());
        assertNull("failure states must not carry a snapshot", event.getSnapshot());
    }
}
