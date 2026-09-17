package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * The calendar read window is host-owned and bounded: a guest cannot widen it,
 * and the row cap cannot exceed the shared messaging policy.
 */
public class CalendarQueryTest {

    private static final long NOW = 1_760_000_000_000L;

    @Test
    public void upcomingIsTheProductDefaultOfSevenDays() {
        CalendarQuery query = CalendarQuery.upcoming(NOW);

        assertEquals(NOW, query.getBeginMillis());
        assertEquals(NOW + CalendarQuery.DEFAULT_WINDOW_MILLIS, query.getEndMillis());
        assertEquals(CalendarQuery.DEFAULT_WINDOW_MILLIS,
                query.getEndMillis() - query.getBeginMillis());
        assertEquals(MessagingReadPolicy.MAX_ROWS, query.getLimit());
    }

    @Test
    public void windowIsCappedAtThirtyOneDays() {
        CalendarQuery max = CalendarQuery.of(NOW, NOW + CalendarQuery.MAX_WINDOW_MILLIS, 10);

        assertEquals(CalendarQuery.MAX_WINDOW_MILLIS,
                max.getEndMillis() - max.getBeginMillis());
        assertThrows(IllegalArgumentException.class, () -> CalendarQuery.of(
                NOW, NOW + CalendarQuery.MAX_WINDOW_MILLIS + 1L, 10));
    }

    @Test
    public void anEmptyOrInvertedWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CalendarQuery.of(NOW, NOW, 10));
        assertThrows(IllegalArgumentException.class, () -> CalendarQuery.of(NOW, NOW - 1L, 10));
    }

    @Test
    public void theRowLimitStaysInsideTheSharedPolicy() {
        assertEquals(MessagingReadPolicy.MAX_ROWS,
                CalendarQuery.of(NOW, NOW + 1_000L, MessagingReadPolicy.MAX_ROWS).getLimit());
        assertThrows(IllegalArgumentException.class,
                () -> CalendarQuery.of(NOW, NOW + 1_000L, 0));
        assertThrows(IllegalArgumentException.class, () -> CalendarQuery.of(
                NOW, NOW + 1_000L, MessagingReadPolicy.MAX_ROWS + 1));
    }
}
