package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Pure interval-cap and fix-silence bounds of the continuous location stream.
 */
public class LocationStreamIntervalPolicyTest {

    @Test
    public void clampsBelowMinimumToMinimum() {
        assertEquals(LocationStreamIntervalPolicy.MIN_INTERVAL_MILLIS,
                LocationStreamIntervalPolicy.clampInterval(0L));
        assertEquals(LocationStreamIntervalPolicy.MIN_INTERVAL_MILLIS,
                LocationStreamIntervalPolicy.clampInterval(-5_000L));
    }

    @Test
    public void clampsAboveMaximumToMaximum() {
        assertEquals(LocationStreamIntervalPolicy.MAX_INTERVAL_MILLIS,
                LocationStreamIntervalPolicy.clampInterval(120_000L));
        assertEquals(LocationStreamIntervalPolicy.MAX_INTERVAL_MILLIS,
                LocationStreamIntervalPolicy.clampInterval(Long.MAX_VALUE));
    }

    @Test
    public void preservesIntervalsInsideTheBounds() {
        assertEquals(1_000L, LocationStreamIntervalPolicy.clampInterval(1_000L));
        assertEquals(5_000L, LocationStreamIntervalPolicy.clampInterval(5_000L));
        assertEquals(60_000L, LocationStreamIntervalPolicy.clampInterval(60_000L));
    }

    @Test
    public void fixSilenceWindowIsNeverShorterThanTheMinimum() {
        assertEquals(LocationStreamIntervalPolicy.MIN_FIX_SILENCE_MILLIS,
                LocationStreamIntervalPolicy.fixSilenceMillis(1_000L));
        assertEquals(LocationStreamIntervalPolicy.MIN_FIX_SILENCE_MILLIS,
                LocationStreamIntervalPolicy.fixSilenceMillis(5_000L));
    }

    @Test
    public void fixSilenceWindowScalesWithTheEffectiveInterval() {
        assertEquals(60_000L, LocationStreamIntervalPolicy.fixSilenceMillis(30_000L));
        assertEquals(120_000L, LocationStreamIntervalPolicy.fixSilenceMillis(60_000L));
        // Out-of-bound requests are clamped first, so the window stays capped.
        assertEquals(120_000L, LocationStreamIntervalPolicy.fixSilenceMillis(Long.MAX_VALUE));
    }
}
