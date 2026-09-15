package gh.nusashell.nusadesk.presentation.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The arrow-key hold-to-repeat behaviour is touch-driven timing that a
 * screenshot cannot verify, so the pure emission state machine is exercised
 * here with a virtual clock. The view layer only wires this machine to a real
 * {@code Handler}; the invariants (tap = exactly one key, hold = one immediate
 * key then repeats, release/cancel stops immediately) are asserted below.
 */
public class TerminalKeyRepeatTest {

    @Test
    public void onlyArrowKeysRepeat() {
        assertTrue(TerminalKeyRepeat.isRepeating(TerminalKey.ARROW_UP));
        assertTrue(TerminalKeyRepeat.isRepeating(TerminalKey.ARROW_DOWN));
        assertTrue(TerminalKeyRepeat.isRepeating(TerminalKey.ARROW_LEFT));
        assertTrue(TerminalKeyRepeat.isRepeating(TerminalKey.ARROW_RIGHT));
        assertFalse(TerminalKeyRepeat.isRepeating(TerminalKey.ESC));
        assertFalse(TerminalKeyRepeat.isRepeating(TerminalKey.TAB));
        assertFalse(TerminalKeyRepeat.isRepeating(TerminalKey.HOME));
        assertFalse(TerminalKeyRepeat.isRepeating(TerminalKey.PAGE_UP));
    }

    @Test
    public void pressEmitsExactlyOneKeyImmediately() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        assertEquals(1L, repeat.press(0L));
        assertEquals(1L, repeat.emittedCount());
    }

    @Test
    public void aQuickTapEmitsExactlyOneKeyAndNoRepeats() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(0L);
        // A tap lifts well before the initial repeat delay.
        assertEquals(0L, repeat.tick(50L));
        repeat.release();
        // After release, ticks must never emit again.
        assertEquals(0L, repeat.tick(500L));
        assertEquals(1L, repeat.emittedCount());
    }

    @Test
    public void aHoldEmitsOneImmediateKeyThenRepeatsAtTheInterval() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(0L);
        // First repeat lands after the initial delay, then every interval.
        assertEquals(0L, repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS - 1));
        assertEquals(1L, repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS));
        assertEquals(1L, repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS
                + TerminalKeyRepeat.REPEAT_INTERVAL_MS));
        assertEquals(1L, repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS
                + 2 * TerminalKeyRepeat.REPEAT_INTERVAL_MS));
        assertEquals(4L, repeat.emittedCount());
    }

    @Test
    public void releaseStopsRepeatImmediately() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(0L);
        repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS);
        repeat.release();
        assertFalse(repeat.isPressed());
        assertEquals(0L, repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS
                + TerminalKeyRepeat.REPEAT_INTERVAL_MS));
    }

    @Test
    public void cancelIsTreatedAsRelease() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(0L);
        repeat.cancel();
        assertFalse(repeat.isPressed());
        assertEquals(0L, repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS));
        assertEquals(1L, repeat.emittedCount());
    }

    @Test
    public void aDelayedTickCatchesUpWithoutLosingOrDuplicatingIntervals() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(0L);
        // Two repeat intervals elapse before the view ticks again.
        long late = TerminalKeyRepeat.INITIAL_DELAY_MS
                + 2 * TerminalKeyRepeat.REPEAT_INTERVAL_MS;
        assertEquals(3L, repeat.tick(late));
        assertEquals(4L, repeat.emittedCount());
        // The next scheduled emit is one interval past the catch-up point.
        assertEquals(0L, repeat.tick(late + TerminalKeyRepeat.REPEAT_INTERVAL_MS - 1));
        assertEquals(1L, repeat.tick(late + TerminalKeyRepeat.REPEAT_INTERVAL_MS));
    }

    @Test
    public void nextEmitAtGivesTheScheduledTimeForTheViewTimer() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(100L);
        assertEquals(100L + TerminalKeyRepeat.INITIAL_DELAY_MS, repeat.nextEmitAt());
        repeat.tick(100L + TerminalKeyRepeat.INITIAL_DELAY_MS);
        assertEquals(100L + TerminalKeyRepeat.INITIAL_DELAY_MS
                + TerminalKeyRepeat.REPEAT_INTERVAL_MS, repeat.nextEmitAt());
    }

    @Test
    public void pressResetsStateFromAPriorGesture() {
        TerminalKeyRepeat repeat = new TerminalKeyRepeat();
        repeat.press(0L);
        repeat.tick(TerminalKeyRepeat.INITIAL_DELAY_MS);
        repeat.release();
        // A new press starts a fresh count at the new time.
        assertEquals(1L, repeat.press(1000L));
        assertEquals(1L, repeat.emittedCount());
        assertEquals(1000L + TerminalKeyRepeat.INITIAL_DELAY_MS, repeat.nextEmitAt());
    }
}
