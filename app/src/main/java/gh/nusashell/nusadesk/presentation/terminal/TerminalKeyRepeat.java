package gh.nusashell.nusadesk.presentation.terminal;

/**
 * Pure, Android-free state machine behind the arrow keys' hold-to-repeat.
 *
 * <p>The accessory key row wires this machine to a real {@code Handler}; the
 * timing invariants that a screenshot cannot verify — a tap emits exactly one
 * key, a hold emits one immediate key and then repeats at a fixed interval, and
 * release/cancel stops immediately — live here and are unit-tested with a
 * virtual clock. The machine owns no Android objects, no I/O, and no session
 * state: it only counts keys to emit for a given point in time.
 *
 * <p>Model: on {@link #press} the first key is emitted immediately and the next
 * repeat is scheduled {@link #INITIAL_DELAY_MS} later. Each {@link #tick} emits
 * zero or more keys for every repeat interval that has elapsed (so a delayed
 * timer tick catches up without losing or duplicating intervals) and advances
 * the schedule. {@link #release} and {@link #cancel} stop emission at once.
 */
public final class TerminalKeyRepeat {

    /** Delay before the first repeat after the immediate key. */
    static final long INITIAL_DELAY_MS = 300L;
    /** Interval between subsequent repeats. */
    static final long REPEAT_INTERVAL_MS = 80L;

    private boolean pressed;
    private long nextEmit;
    private long emittedCount;

    /** Only the four arrow keys auto-repeat; every other accessory key taps once. */
    public static boolean isRepeating(TerminalKey key) {
        return key == TerminalKey.ARROW_UP
                || key == TerminalKey.ARROW_DOWN
                || key == TerminalKey.ARROW_LEFT
                || key == TerminalKey.ARROW_RIGHT;
    }

    /**
     * Begin a gesture at {@code now}. Emits the immediate first key and returns
     * the number of keys the caller should send (always one).
     */
    public long press(long now) {
        pressed = true;
        nextEmit = now + INITIAL_DELAY_MS;
        emittedCount = 1L;
        return 1L;
    }

    /**
     * Report how many repeat keys are due at {@code now}. Returns zero when not
     * pressed or no interval has elapsed. A late tick emits one key per elapsed
     * interval so the repeat rate stays constant regardless of timer jitter.
     */
    public long tick(long now) {
        if (!pressed) {
            return 0L;
        }
        long count = 0L;
        while (now >= nextEmit) {
            count++;
            nextEmit += REPEAT_INTERVAL_MS;
        }
        emittedCount += count;
        return count;
    }

    /** Stop repeating on finger lift. Subsequent ticks emit nothing. */
    public void release() {
        pressed = false;
    }

    /** Stop repeating on gesture cancellation. Equivalent to {@link #release}. */
    public void cancel() {
        pressed = false;
    }

    /** Whether a gesture is still in progress (between press and release/cancel). */
    public boolean isPressed() {
        return pressed;
    }

    /** Scheduled time of the next repeat, for the view's timer. Only valid while pressed. */
    public long nextEmitAt() {
        return nextEmit;
    }

    /** Total keys emitted since the current {@link #press} (immediate + repeats). */
    public long emittedCount() {
        return emittedCount;
    }
}
