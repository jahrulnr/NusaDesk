package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The bounded calendar result: rows are capped and reported, only a reading
 * carries payload, and an event row never exposes description, attendee, or
 * organizer data.
 */
public class CalendarEventSnapshotTest {

    private static final long BEGIN = 1_760_000_000_000L;

    private static CalendarEventSnapshot.EventEntry entry(long id) {
        return new CalendarEventSnapshot.EventEntry(id, "Event " + id, BEGIN,
                BEGIN + 60_000L, false, 3L, "Pribadi", "Asia/Jakarta", "Ruang 1");
    }

    @Test
    public void aReadingCarriesTheBoundedFieldsAndRows() {
        CalendarEventSnapshot snapshot = CalendarEventSnapshot.reading(
                List.of(entry(1L)), false);

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertFalse(snapshot.isTruncated());
        assertEquals(Boolean.TRUE, snapshot.responseFields().get("available"));
        assertEquals(1L, snapshot.responseFields().get("count"));
        assertEquals(Boolean.FALSE, snapshot.responseFields().get("truncated"));
        MessagingReadPolicy.EncodedRows rows = snapshot.encodeRows();
        assertFalse(rows.isTruncated());
        assertTrue(rows.getJson().contains("\"title\":\"Event 1\""));
        assertTrue(rows.getJson().contains("\"timezone\":\"Asia/Jakarta\""));
    }

    @Test
    public void rowsAreCappedAtTheSharedMaximumAndReportTruncation() {
        List<CalendarEventSnapshot.EventEntry> entries = new ArrayList<>();
        for (long id = 1L; id <= MessagingReadPolicy.MAX_ROWS + 5L; id++) {
            entries.add(entry(id));
        }

        CalendarEventSnapshot snapshot = CalendarEventSnapshot.reading(entries, false);

        assertEquals(MessagingReadPolicy.MAX_ROWS, snapshot.getEntries().size());
        assertTrue("dropped rows must be reported", snapshot.isTruncated());
    }

    @Test
    public void anEmptyReadingIsStillAReading() {
        CalendarEventSnapshot snapshot = CalendarEventSnapshot.reading(
                Collections.emptyList(), false);

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertTrue(snapshot.getEntries().isEmpty());
        assertEquals(0L, snapshot.responseFields().get("count"));
    }

    @Test
    public void aNonReadingStateCarriesNoPayload() {
        for (CalendarEventSnapshot snapshot : List.of(
                CalendarEventSnapshot.permissionRequired(),
                CalendarEventSnapshot.permissionDenied(),
                CalendarEventSnapshot.unavailable(),
                CalendarEventSnapshot.error())) {
            assertTrue(snapshot.getEntries().isEmpty());
            assertThrows(IllegalStateException.class, snapshot::responseFields);
            assertThrows(IllegalStateException.class, snapshot::encodeRows);
        }
        assertEquals(MessagingReadState.PERMISSION_REQUIRED,
                CalendarEventSnapshot.permissionRequired().getState());
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                CalendarEventSnapshot.permissionDenied().getState());
        assertEquals(MessagingReadState.UNAVAILABLE,
                CalendarEventSnapshot.unavailable().getState());
        assertEquals(MessagingReadState.ERROR, CalendarEventSnapshot.error().getState());
    }

    @Test
    public void anEventEntryRejectsUnusableIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> new CalendarEventSnapshot.EventEntry(
                0L, "Title", BEGIN, BEGIN + 1_000L, false, 3L, "Pribadi", "Asia/Jakarta", ""));
        assertThrows(IllegalArgumentException.class, () -> new CalendarEventSnapshot.EventEntry(
                1L, "", BEGIN, BEGIN + 1_000L, false, 3L, "Pribadi", "Asia/Jakarta", ""));
        assertThrows(IllegalArgumentException.class, () -> new CalendarEventSnapshot.EventEntry(
                1L, "Title", BEGIN, BEGIN - 1_000L, false, 3L, "Pribadi", "Asia/Jakarta", ""));
    }

    @Test
    public void longEventTextIsTruncatedToTheSharedBounds() {
        String longTitle = "T".repeat(MessagingReadPolicy.MAX_NAME_CHARS + 40);
        CalendarEventSnapshot.EventEntry entry = new CalendarEventSnapshot.EventEntry(
                9L, longTitle, BEGIN, BEGIN + 60_000L, true, 4L,
                "N".repeat(MessagingReadPolicy.MAX_NAME_CHARS + 10), null, null);

        assertEquals(MessagingReadPolicy.MAX_NAME_CHARS, entry.getTitle().length());
        assertEquals(MessagingReadPolicy.MAX_NAME_CHARS, entry.getCalendarName().length());
        assertEquals("", entry.getTimezone());
        assertEquals("", entry.getLocation());
        assertTrue(entry.isAllDay());
        String json = CalendarEventSnapshot.reading(List.of(entry), false).encodeRows().getJson();
        assertFalse("attendees never have a column to leak through",
                json.contains("attendee") || json.contains("description"));
        assertFalse(json.contains("organizer") || json.contains("@"));
    }
}
