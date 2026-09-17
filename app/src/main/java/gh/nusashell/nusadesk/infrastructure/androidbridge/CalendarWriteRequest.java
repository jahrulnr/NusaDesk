package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One validated calendar write request, Android-free.
 *
 * <p>The guest sends bounded {@code params} and this class is the only place
 * they become a request: every field is checked (types, ranges, lengths,
 * unknown keys) and a rejected input becomes the typed
 * {@link #ERROR_INVALID_ARGUMENT} code instead of reaching the provider. The
 * bounds are deliberately narrow: a title or location of at most
 * {@link #MAX_TITLE_CHARS} characters, an event of at most
 * {@link #MAX_DURATION_MILLIS}, a start no older than one day and no further
 * than {@link #WINDOW_FUTURE_MILLIS} ahead, and an all-day event whose range
 * must sit on UTC midnight boundaries — so an update that sets the all-day
 * flag must carry the range it is checked against.</p>
 */
public final class CalendarWriteRequest {
    public static final String ERROR_INVALID_ARGUMENT = "calendar-invalid-argument";
    /** Max characters of an event title or location. */
    public static final int MAX_TITLE_CHARS = 200;
    /** Max length of one event. */
    public static final long MAX_DURATION_MILLIS = 24L * 60L * 60L * 1_000L;
    /** A start may not be older than this. */
    public static final long WINDOW_PAST_MILLIS = 24L * 60L * 60L * 1_000L;
    /** A start may not be further ahead than this. */
    public static final long WINDOW_FUTURE_MILLIS = 366L * 24L * 60L * 60L * 1_000L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1_000L;

    /** Which write is being requested. */
    public enum Op {
        INSERT,
        UPDATE,
        DELETE
    }

    private final Op op;
    private final long eventId;
    private final Long calendarId;
    private final String title;
    private final Long beginMillis;
    private final Long endMillis;
    private final boolean allDay;
    private final boolean allDayProvided;
    private final String location;

    private CalendarWriteRequest(Op op, long eventId, Long calendarId, String title,
                                 Long beginMillis, Long endMillis, boolean allDay,
                                 boolean allDayProvided, String location) {
        this.op = op;
        this.eventId = eventId;
        this.calendarId = calendarId;
        this.title = title;
        this.beginMillis = beginMillis;
        this.endMillis = endMillis;
        this.allDay = allDay;
        this.allDayProvided = allDayProvided;
        this.location = location;
    }

    /** Parse and validate one insert request. */
    public static Parse insert(Map<String, Object> params, long nowMillis) {
        Set<String> allowed = Set.of("title", "begin_ms", "end_ms", "calendar_id",
                "all_day", "location");
        if (!hasOnlyAllowedKeys(params, allowed)) {
            return Parse.invalid();
        }
        String title = text(params, "title", MAX_TITLE_CHARS, true);
        if (title == null) {
            return Parse.invalid();
        }
        Long begin = number(params, "begin_ms");
        Long end = number(params, "end_ms");
        if (begin == null || end == null || !rangeValid(begin, end, nowMillis)) {
            return Parse.invalid();
        }
        Boolean allDay = bool(params, "all_day");
        if (allDay == null) {
            return Parse.invalid();
        }
        if (allDay && !allDayAligned(begin, end)) {
            return Parse.invalid();
        }
        Long calendarId = number(params, "calendar_id");
        if (calendarId != null && calendarId <= 0L) {
            return Parse.invalid();
        }
        String location = text(params, "location", MAX_TITLE_CHARS, false);
        if (location == null) {
            return Parse.invalid();
        }
        return Parse.ok(new CalendarWriteRequest(Op.INSERT, 0L, calendarId, title,
                begin, end, allDay, true, location));
    }

    /** Parse and validate one update request; at least one field must change. */
    public static Parse update(Map<String, Object> params, long nowMillis) {
        Set<String> allowed = Set.of("event_id", "title", "begin_ms", "end_ms",
                "all_day", "location");
        if (!hasOnlyAllowedKeys(params, allowed)) {
            return Parse.invalid();
        }
        Long eventId = number(params, "event_id");
        if (eventId == null || eventId <= 0L) {
            return Parse.invalid();
        }
        String title = text(params, "title", MAX_TITLE_CHARS, false);
        String location = text(params, "location", MAX_TITLE_CHARS, false);
        if (title == null || location == null) {
            return Parse.invalid();
        }
        Long begin = number(params, "begin_ms");
        Long end = number(params, "end_ms");
        if ((begin == null) != (end == null)) {
            return Parse.invalid();
        }
        if (begin != null && !rangeValid(begin, end, nowMillis)) {
            return Parse.invalid();
        }
        Boolean allDay = bool(params, "all_day");
        if (allDay == null) {
            return Parse.invalid();
        }
        if (allDay && begin == null) {
            // Marking an event all-day without sending its range would leave
            // the UTC-midnight rule unverifiable, so it is rejected instead of
            // producing an all-day event with a timed start.
            return Parse.invalid();
        }
        if (allDay && !allDayAligned(begin, end)) {
            return Parse.invalid();
        }
        boolean anyChange = !title.isEmpty() || !location.isEmpty()
                || begin != null || params.containsKey("all_day");
        if (!anyChange) {
            return Parse.invalid();
        }
        return Parse.ok(new CalendarWriteRequest(Op.UPDATE, eventId, null, title,
                begin, end, allDay, params.containsKey("all_day"), location));
    }

    /** Parse and validate one delete request. */
    public static Parse delete(Map<String, Object> params) {
        Set<String> allowed = Set.of("event_id");
        if (!hasOnlyAllowedKeys(params, allowed)) {
            return Parse.invalid();
        }
        Long eventId = number(params, "event_id");
        if (eventId == null || eventId <= 0L) {
            return Parse.invalid();
        }
        return Parse.ok(new CalendarWriteRequest(Op.DELETE, eventId, null, "",
                null, null, false, false, ""));
    }

    public Op getOp() {
        return op;
    }

    /** Target event for update/delete; zero for an insert. */
    public long getEventId() {
        return eventId;
    }

    /** Explicit target calendar for an insert, or {@code null} for the primary one. */
    public Long getCalendarId() {
        return calendarId;
    }

    /** New title, empty when the request does not change it. */
    public String getTitle() {
        return title;
    }

    /** New begin, or {@code null} when the request does not change it. */
    public Long getBeginMillis() {
        return beginMillis;
    }

    public Long getEndMillis() {
        return endMillis;
    }

    /** True when the request sets the all-day flag (always for an insert). */
    public boolean setsAllDay() {
        return allDayProvided;
    }

    public boolean isAllDay() {
        return allDay;
    }

    /** New location, empty when the request does not change it. */
    public String getLocation() {
        return location;
    }

    private static boolean rangeValid(long begin, long end, long nowMillis) {
        if (end <= begin) {
            return false;
        }
        if (end - begin > MAX_DURATION_MILLIS) {
            return false;
        }
        return begin >= nowMillis - WINDOW_PAST_MILLIS
                && begin <= nowMillis + WINDOW_FUTURE_MILLIS;
    }

    private static boolean allDayAligned(long begin, long end) {
        return begin % DAY_MILLIS == 0L && end % DAY_MILLIS == 0L
                && end - begin >= DAY_MILLIS;
    }

    private static boolean hasOnlyAllowedKeys(Map<String, Object> params,
                                              Set<String> allowed) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                return false;
            }
        }
        return true;
    }

    private static Long number(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        return value instanceof Long ? (Long) value : null;
    }

    private static Boolean bool(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            return Boolean.FALSE;
        }
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /**
     * Bounded text value: {@code ""} when absent, {@code null} when the value
     * is not a string, is over-long, carries control characters, or is blank
     * while {@code required}.
     */
    private static String text(Map<String, Object> params, String key, int maxChars,
                               boolean required) {
        Object value = params.get(key);
        if (value == null) {
            return required ? null : "";
        }
        if (!(value instanceof String)) {
            return null;
        }
        String text = ((String) value).trim();
        if ((required && text.isEmpty()) || text.length() > maxChars) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return null;
            }
        }
        return text;
    }

    /** A validated request or the typed rejection code. */
    public static final class Parse {
        private final CalendarWriteRequest request;
        private final String errorCode;

        private Parse(CalendarWriteRequest request, String errorCode) {
            this.request = request;
            this.errorCode = errorCode;
        }

        static Parse ok(CalendarWriteRequest request) {
            return new Parse(request, null);
        }

        static Parse invalid() {
            return new Parse(null, ERROR_INVALID_ARGUMENT);
        }

        public boolean isOk() {
            return request != null;
        }

        public CalendarWriteRequest getRequest() {
            return request;
        }

        /** The typed rejection code, or {@code null} for a valid request. */
        public String getErrorCode() {
            return errorCode;
        }
    }

    /** Convenience view of the fields for logging without the title. */
    public Map<String, Object> auditFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("op", op.name().toLowerCase(java.util.Locale.ROOT));
        fields.put("event_id", eventId);
        fields.put("calendar_id", calendarId == null ? 0L : calendarId);
        return fields;
    }
}
