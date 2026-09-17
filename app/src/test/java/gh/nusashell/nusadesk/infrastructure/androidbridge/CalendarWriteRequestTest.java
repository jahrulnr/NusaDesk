package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bounded {@code params} validation for the calendar write methods: every
 * rejected input becomes the typed {@code calendar-invalid-argument} and never
 * reaches a provider, and the audit view never carries event content.
 */
public class CalendarWriteRequestTest {

    private static final long NOW = 1_760_000_000_000L;
    private static final long DAY = 24L * 60L * 60L * 1_000L;
    private static final long HOUR = 60L * 60L * 1_000L;

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put((String) pairs[i], pairs[i + 1]);
        }
        return params;
    }

    @Test
    public void insertAcceptsTheMinimalBoundedRequest() {
        CalendarWriteRequest.Parse parse = CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR), NOW);

        assertTrue(parse.isOk());
        CalendarWriteRequest request = parse.getRequest();
        assertEquals(CalendarWriteRequest.Op.INSERT, request.getOp());
        assertEquals(0L, request.getEventId());
        assertEquals("Rapat", request.getTitle());
        assertEquals(Long.valueOf(NOW + HOUR), request.getBeginMillis());
        assertFalse("all-day defaults to off", request.isAllDay());
        assertTrue("an insert always writes the all-day flag", request.setsAllDay());
        assertEquals("", request.getLocation());
        assertNull("no explicit calendar means the primary one", request.getCalendarId());
    }

    @Test
    public void insertRejectsMissingOrMistypedRequiredFields() {
        assertInvalid(CalendarWriteRequest.insert(params(), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("title", "Rapat", "end_ms", NOW + 2 * HOUR), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW + HOUR), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", "soon", "end_ms", NOW + 2 * HOUR), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("title", "   ", "begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("title", "Bad\nTitle", "begin_ms", NOW + HOUR,
                        "end_ms", NOW + 2 * HOUR), NOW));
        assertInvalid(CalendarWriteRequest.insert(
                params("title", "x".repeat(CalendarWriteRequest.MAX_TITLE_CHARS + 1),
                        "begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR), NOW));
    }

    @Test
    public void insertKeepsTheRangeAndDurationBounded() {
        assertInvalid("end must follow begin", CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW + 2 * HOUR, "end_ms", NOW + HOUR), NOW));
        assertInvalid("zero-length event", CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW + HOUR, "end_ms", NOW + HOUR), NOW));
        assertInvalid("longer than the duration cap", CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW + HOUR,
                        "end_ms", NOW + HOUR + CalendarWriteRequest.MAX_DURATION_MILLIS + 1L),
                NOW));
        assertInvalid("start older than the past window", CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW - CalendarWriteRequest.WINDOW_PAST_MILLIS - 1L,
                        "end_ms", NOW - CalendarWriteRequest.WINDOW_PAST_MILLIS + HOUR), NOW));
        assertInvalid("start beyond the future window", CalendarWriteRequest.insert(
                params("title", "Rapat",
                        "begin_ms", NOW + CalendarWriteRequest.WINDOW_FUTURE_MILLIS + 1L,
                        "end_ms", NOW + CalendarWriteRequest.WINDOW_FUTURE_MILLIS + HOUR), NOW));
    }

    @Test
    public void insertValidatesTheAllDayFlagAndCalendarId() {
        // A UTC-midnight aligned day window is the only shape an all-day event
        // may take; the aligned values below are whole multiples of a day.
        long midnight = (NOW / DAY) * DAY;
        long alignedBegin = midnight;
        long alignedEnd = midnight + DAY;

        assertInvalid("misaligned all-day range", CalendarWriteRequest.insert(
                params("title", "Cuti", "begin_ms", NOW + HOUR, "end_ms", NOW + HOUR + DAY,
                        "all_day", true), NOW));
        assertInvalid("the all-day flag must be a JSON boolean",
                CalendarWriteRequest.insert(params("title", "Cuti", "begin_ms", alignedBegin,
                        "end_ms", alignedEnd, "all_day", "yes"), NOW));
        assertInvalid("non-positive calendar id", CalendarWriteRequest.insert(
                params("title", "Rapat", "begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR,
                        "calendar_id", 0L), NOW));

        CalendarWriteRequest.Parse aligned = CalendarWriteRequest.insert(
                params("title", "Cuti", "begin_ms", alignedBegin, "end_ms", alignedEnd,
                        "all_day", true, "calendar_id", 7L), NOW);
        assertTrue(aligned.isOk());
        assertTrue(aligned.getRequest().isAllDay());
        assertEquals(Long.valueOf(7L), aligned.getRequest().getCalendarId());
        assertInvalid("an aligned range still respects the past window",
                CalendarWriteRequest.insert(params("title", "Cuti",
                        "begin_ms", alignedBegin - 2L * DAY, "end_ms", alignedBegin - DAY,
                        "all_day", true), NOW));
    }

    @Test
    public void unknownKeysFailClosed() {
        assertInvalid("attendee input must never be expressible",
                CalendarWriteRequest.insert(params("title", "Rapat", "begin_ms", NOW + HOUR,
                        "end_ms", NOW + 2 * HOUR, "attendees", "a@b.c"), NOW));
        assertInvalid(CalendarWriteRequest.update(params("event_id", 5L, "dtstart", 1L), NOW));
        assertInvalid(CalendarWriteRequest.delete(params("event_id", 5L, "all", true)));
    }

    @Test
    public void updateRequiresATargetAndAtLeastOneChange() {
        assertInvalid("no event id", CalendarWriteRequest.update(
                params("title", "Rapat"), NOW));
        assertInvalid("non-positive event id", CalendarWriteRequest.update(
                params("event_id", 0L, "title", "Rapat"), NOW));
        assertInvalid("nothing to change", CalendarWriteRequest.update(
                params("event_id", 5L), NOW));
        assertInvalid("half a range", CalendarWriteRequest.update(
                params("event_id", 5L, "begin_ms", NOW + HOUR), NOW));
        assertInvalid("half a range", CalendarWriteRequest.update(
                params("event_id", 5L, "end_ms", NOW + HOUR), NOW));
        assertInvalid("blank replacement title is not a change", CalendarWriteRequest.update(
                params("event_id", 5L, "title", "  "), NOW));

        CalendarWriteRequest.Parse parse = CalendarWriteRequest.update(
                params("event_id", 5L, "title", "Rapat baru"), NOW);
        assertTrue(parse.isOk());
        assertEquals(CalendarWriteRequest.Op.UPDATE, parse.getRequest().getOp());
        assertEquals(5L, parse.getRequest().getEventId());
        assertEquals("Rapat baru", parse.getRequest().getTitle());
        assertFalse("an update only writes all_day when it was sent",
                parse.getRequest().setsAllDay());
        assertNull("an update does not retarget the calendar",
                parse.getRequest().getCalendarId());
    }

    @Test
    public void updateValidatesAChangedRange() {
        assertInvalid("inverted range", CalendarWriteRequest.update(params("event_id", 5L,
                "begin_ms", NOW + 2 * HOUR, "end_ms", NOW + HOUR), NOW));
        assertInvalid("over-long range", CalendarWriteRequest.update(params("event_id", 5L,
                "begin_ms", NOW + HOUR, "end_ms",
                NOW + HOUR + CalendarWriteRequest.MAX_DURATION_MILLIS + 1L), NOW));
        assertInvalid("all-day flag without a range cannot be aligned",
                CalendarWriteRequest.update(params("event_id", 5L, "all_day", true), NOW));

        CalendarWriteRequest.Parse parse = CalendarWriteRequest.update(params("event_id", 5L,
                "begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR, "all_day", false), NOW);
        assertTrue(parse.isOk());
        assertTrue(parse.getRequest().setsAllDay());
        assertFalse(parse.getRequest().isAllDay());
    }

    @Test
    public void deleteNeedsOnlyAPositiveEventId() {
        assertInvalid(CalendarWriteRequest.delete(params()));
        assertInvalid(CalendarWriteRequest.delete(params("event_id", -1L)));
        assertInvalid(CalendarWriteRequest.delete(params("event_id", "7")));

        CalendarWriteRequest.Parse parse = CalendarWriteRequest.delete(params("event_id", 11L));
        assertTrue(parse.isOk());
        assertEquals(CalendarWriteRequest.Op.DELETE, parse.getRequest().getOp());
        assertEquals(11L, parse.getRequest().getEventId());
        assertEquals("", parse.getRequest().getTitle());
    }

    @Test
    public void theAuditViewCarriesNoEventContent() {
        CalendarWriteRequest request = CalendarWriteRequest.insert(params("title", "Rahasia",
                "begin_ms", NOW + HOUR, "end_ms", NOW + 2 * HOUR,
                "location", "Ruang Direktur"), NOW).getRequest();

        Map<String, Object> audit = request.auditFields();

        assertEquals("insert", audit.get("op"));
        assertEquals(0L, audit.get("event_id"));
        assertFalse("the title stays out of the log line", audit.containsValue("Rahasia"));
        assertFalse("the location stays out of the log line",
                audit.containsValue("Ruang Direktur"));
        assertEquals(3, audit.size());
    }

    @Test
    public void aRejectedRequestCarriesTheTypedCodeAndNoRequest() {
        CalendarWriteRequest.Parse parse = CalendarWriteRequest.insert(params(), NOW);

        assertFalse(parse.isOk());
        assertEquals(CalendarWriteRequest.ERROR_INVALID_ARGUMENT, parse.getErrorCode());
        assertNull(parse.getRequest());
    }

    private static void assertInvalid(CalendarWriteRequest.Parse parse) {
        assertInvalid("expected a typed rejection", parse);
    }

    private static void assertInvalid(String message, CalendarWriteRequest.Parse parse) {
        assertFalse(message, parse.isOk());
        assertEquals(message, CalendarWriteRequest.ERROR_INVALID_ARGUMENT,
                parse.getErrorCode());
    }
}
