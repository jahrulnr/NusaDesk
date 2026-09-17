package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Fixed, host-owned query for one {@code calendar.list} read, Android-free.
 *
 * <p>There is deliberately no per-request window or row limit on the guest
 * surface: the bridge owns the defaults, so a guest cannot ask for the whole
 * calendar history. The window is capped at
 * {@link #MAX_WINDOW_MILLIS} and the row limit at
 * {@link MessagingReadPolicy#MAX_ROWS}.</p>
 */
public final class CalendarQuery {
    /** Longest window one read may cover (31 days). */
    public static final long MAX_WINDOW_MILLIS = 31L * 24L * 60L * 60L * 1_000L;
    /** Default window of the guest contract: the next seven days. */
    public static final long DEFAULT_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L;
    /** Default row cap; also the hard cap enforced by the snapshot. */
    public static final int DEFAULT_LIMIT = MessagingReadPolicy.MAX_ROWS;

    private final long beginMillis;
    private final long endMillis;
    private final int limit;

    private CalendarQuery(long beginMillis, long endMillis, int limit) {
        this.beginMillis = beginMillis;
        this.endMillis = endMillis;
        this.limit = limit;
    }

    /** The product default: from {@code nowMillis} to seven days later. */
    public static CalendarQuery upcoming(long nowMillis) {
        return of(nowMillis, nowMillis + DEFAULT_WINDOW_MILLIS, DEFAULT_LIMIT);
    }

    /** A bounded window; rejects an inverted, empty, or oversized window. */
    public static CalendarQuery of(long beginMillis, long endMillis, int limit) {
        if (endMillis <= beginMillis) {
            throw new IllegalArgumentException("window must not be empty or inverted");
        }
        if (endMillis - beginMillis > MAX_WINDOW_MILLIS) {
            throw new IllegalArgumentException("window exceeds the bounded maximum");
        }
        if (limit < 1 || limit > MessagingReadPolicy.MAX_ROWS) {
            throw new IllegalArgumentException("limit out of bounds");
        }
        return new CalendarQuery(beginMillis, endMillis, limit);
    }

    public long getBeginMillis() {
        return beginMillis;
    }

    public long getEndMillis() {
        return endMillis;
    }

    public int getLimit() {
        return limit;
    }
}
