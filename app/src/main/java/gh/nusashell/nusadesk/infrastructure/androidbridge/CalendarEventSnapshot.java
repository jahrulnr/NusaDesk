package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable bounded result of a calendar read, kept Android-free for testing.
 *
 * <p>Each event carries only what a Linux consumer needs to act on an
 * appointment: title, begin/end in UTC milliseconds, the all-day flag, the
 * calendar id and display name, the event timezone, and an optional location.
 * The description, attendees, organizer address, reminders, and every other
 * provider column are deliberately omitted — attendee addresses are other
 * people's personal data and never cross the bridge. Rows are capped at
 * {@link MessagingReadPolicy#MAX_ROWS} and the cap is reported through
 * {@link #isTruncated()}, so a bounded answer can never look complete.</p>
 */
public final class CalendarEventSnapshot {
    private final MessagingReadState state;
    private final List<EventEntry> entries;
    private final boolean truncated;

    private CalendarEventSnapshot(MessagingReadState state, List<EventEntry> entries,
                                  boolean truncated) {
        this.state = state;
        List<EventEntry> bounded = new ArrayList<>();
        boolean capped = truncated;
        if (entries != null) {
            for (EventEntry entry : entries) {
                if (bounded.size() >= MessagingReadPolicy.MAX_ROWS) {
                    capped = true;
                    break;
                }
                bounded.add(entry);
            }
        }
        this.entries = Collections.unmodifiableList(bounded);
        this.truncated = capped;
    }

    /** A bounded event list; extra rows are dropped and reported via {@link #isTruncated()}. */
    public static CalendarEventSnapshot reading(List<EventEntry> entries, boolean truncated) {
        return new CalendarEventSnapshot(MessagingReadState.READING, entries, truncated);
    }

    /** No read-calendar grant and no recorded denial; a later consent flow may ask. */
    public static CalendarEventSnapshot permissionRequired() {
        return new CalendarEventSnapshot(MessagingReadState.PERMISSION_REQUIRED, null, false);
    }

    /** No read-calendar grant and the user previously denied it. */
    public static CalendarEventSnapshot permissionDenied() {
        return new CalendarEventSnapshot(MessagingReadState.PERMISSION_DENIED, null, false);
    }

    /** The calendar provider is absent or returned no cursor. */
    public static CalendarEventSnapshot unavailable() {
        return new CalendarEventSnapshot(MessagingReadState.UNAVAILABLE, null, false);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static CalendarEventSnapshot error() {
        return new CalendarEventSnapshot(MessagingReadState.ERROR, null, false);
    }

    public MessagingReadState getState() {
        return state;
    }

    /** Bounded event entries; empty unless {@link MessagingReadState#READING}. */
    public List<EventEntry> getEntries() {
        return entries;
    }

    /** True when rows were dropped to enforce the row cap. */
    public boolean isTruncated() {
        return truncated;
    }

    /** Flat envelope fields for a reading; other states are typed errors. */
    public Map<String, Object> responseFields() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("response fields are defined only for a reading");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("available", true);
        fields.put("count", (long) entries.size());
        fields.put("truncated", truncated);
        return fields;
    }

    /**
     * The bounded row array for the RPC payload: one flat object per event.
     * Rows that do not fit the frame budget are dropped deterministically and
     * reported through {@link MessagingReadPolicy.EncodedRows#isTruncated()}.
     */
    public MessagingReadPolicy.EncodedRows encodeRows() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("rows are defined only for a reading");
        }
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (EventEntry entry : entries) {
            rows.add(entry.rowFields());
        }
        return MessagingReadPolicy.encodeRows(rows);
    }

    /** One bounded event occurrence. */
    public static final class EventEntry {
        private final long eventId;
        private final String title;
        private final long beginMillis;
        private final long endMillis;
        private final boolean allDay;
        private final long calendarId;
        private final String calendarName;
        private final String timezone;
        private final String location;

        public EventEntry(long eventId, String title, long beginMillis, long endMillis,
                          boolean allDay, long calendarId, String calendarName,
                          String timezone, String location) {
            if (eventId <= 0L) {
                throw new IllegalArgumentException("eventId must be positive");
            }
            if (title == null || title.isEmpty()) {
                throw new IllegalArgumentException("title must not be empty");
            }
            if (endMillis < beginMillis) {
                throw new IllegalArgumentException("end must not precede begin");
            }
            this.eventId = eventId;
            this.title = MessagingReadPolicy.truncate(title,
                    MessagingReadPolicy.MAX_NAME_CHARS);
            this.beginMillis = beginMillis;
            this.endMillis = endMillis;
            this.allDay = allDay;
            this.calendarId = calendarId;
            this.calendarName = MessagingReadPolicy.truncate(calendarName,
                    MessagingReadPolicy.MAX_NAME_CHARS);
            this.timezone = MessagingReadPolicy.truncate(timezone,
                    MessagingReadPolicy.MAX_NUMBER_CHARS);
            this.location = MessagingReadPolicy.truncate(location,
                    MessagingReadPolicy.MAX_NAME_CHARS);
        }

        public long getEventId() {
            return eventId;
        }

        public String getTitle() {
            return title;
        }

        public long getBeginMillis() {
            return beginMillis;
        }

        public long getEndMillis() {
            return endMillis;
        }

        public boolean isAllDay() {
            return allDay;
        }

        public long getCalendarId() {
            return calendarId;
        }

        public String getCalendarName() {
            return calendarName;
        }

        public String getTimezone() {
            return timezone;
        }

        public String getLocation() {
            return location;
        }

        private Map<String, Object> rowFields() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_id", eventId);
            row.put("title", title);
            row.put("begin_utc_ms", beginMillis);
            row.put("end_utc_ms", endMillis);
            row.put("all_day", allDay);
            row.put("calendar_id", calendarId);
            row.put("calendar_name", calendarName);
            row.put("timezone", timezone);
            if (!location.isEmpty()) {
                row.put("location", location);
            }
            return row;
        }
    }
}
