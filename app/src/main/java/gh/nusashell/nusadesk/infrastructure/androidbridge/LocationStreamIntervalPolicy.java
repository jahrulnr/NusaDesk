package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Pure bounds for the continuous location stream, kept Android-free for
 * testing.
 *
 * <p>The interval a caller may request is configurable but capped: values
 * outside {@code [MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS]} are clamped at
 * request construction, never honored verbatim. The fix-silence window
 * (after which the stream emits a {@link LocationStreamEvent.State#TIMEOUT}
 * event when no fix arrived) scales with the effective interval and is never
 * shorter than {@link #MIN_FIX_SILENCE_MILLIS}, so a slow provider does not
 * produce a timeout faster than the stream's own cadence. The bounded event
 * buffer defaults to {@link #DEFAULT_BUFFER_CAPACITY} entries; a consumer
 * that polls more slowly than fixes arrive never accumulates unboundedly
 * because the buffer drops the oldest event at capacity.</p>
 */
public final class LocationStreamIntervalPolicy {
    /** Shortest permitted interval between requested location updates. */
    public static final long MIN_INTERVAL_MILLIS = 1_000L;

    /** Longest permitted interval between requested location updates. */
    public static final long MAX_INTERVAL_MILLIS = 60_000L;

    /** Shortest permitted fix-silence window for a timeout event. */
    public static final long MIN_FIX_SILENCE_MILLIS = 10_000L;

    /** Default bound on the stream event queue (oldest is dropped first). */
    public static final int DEFAULT_BUFFER_CAPACITY = 8;

    private LocationStreamIntervalPolicy() {
    }

    /**
     * Clamp a requested interval into the policy bounds.
     *
     * @param requestedMillis any finite value, including negative and zero
     * @return the effective interval in {@code [MIN_INTERVAL_MILLIS,
     *         MAX_INTERVAL_MILLIS]}
     */
    public static long clampInterval(long requestedMillis) {
        if (requestedMillis < MIN_INTERVAL_MILLIS) {
            return MIN_INTERVAL_MILLIS;
        }
        if (requestedMillis > MAX_INTERVAL_MILLIS) {
            return MAX_INTERVAL_MILLIS;
        }
        return requestedMillis;
    }

    /**
     * Bounded silence window for a stream at the given requested interval:
     * twice the effective interval, never shorter than
     * {@link #MIN_FIX_SILENCE_MILLIS}.
     */
    public static long fixSilenceMillis(long requestedIntervalMillis) {
        long interval = clampInterval(requestedIntervalMillis);
        return Math.max(interval * 2, MIN_FIX_SILENCE_MILLIS);
    }
}
