package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CallLogSnapshotTest {

    private static CallLogSnapshot.CallLogEntry entry() {
        return new CallLogSnapshot.CallLogEntry("+628111", "Alice",
                CallLogSnapshot.CallType.INCOMING, 1_000L, 30L);
    }

    @Test
    public void readingMapsBoundedEnvelopeFields() {
        CallLogSnapshot snapshot = CallLogSnapshot.reading(List.of(entry()), false);
        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());

        Map<String, Object> fields = snapshot.responseFields();
        assertEquals(true, fields.get("available"));
        assertEquals(1L, fields.get("count"));
        assertEquals(false, fields.get("truncated"));
    }

    @Test
    public void readingCapsRowsAtMaxRowsAndReportsTruncation() {
        List<CallLogSnapshot.CallLogEntry> entries = new ArrayList<>();
        for (int i = 0; i < MessagingReadPolicy.MAX_ROWS + 5; i++) {
            entries.add(new CallLogSnapshot.CallLogEntry("+62", null,
                    CallLogSnapshot.CallType.MISSED, i + 1L, 0L));
        }
        CallLogSnapshot snapshot = CallLogSnapshot.reading(entries, false);
        assertEquals(MessagingReadPolicy.MAX_ROWS, snapshot.getEntries().size());
        assertTrue(snapshot.isTruncated());
    }

    @Test
    public void nonReadingStatesAreExplicit() {
        assertEquals(MessagingReadState.PERMISSION_REQUIRED,
                CallLogSnapshot.permissionRequired().getState());
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                CallLogSnapshot.permissionDenied().getState());
        assertEquals(MessagingReadState.UNAVAILABLE,
                CallLogSnapshot.unavailable().getState());
        assertEquals(MessagingReadState.ERROR,
                CallLogSnapshot.error().getState());
        assertThrows(IllegalStateException.class,
                () -> CallLogSnapshot.unavailable().responseFields());
        assertThrows(IllegalStateException.class,
                () -> CallLogSnapshot.unavailable().encodeRows());
    }

    @Test
    public void entryRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CallLogSnapshot.CallLogEntry("  ", "A",
                        CallLogSnapshot.CallType.INCOMING, 1L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new CallLogSnapshot.CallLogEntry("+62", "A",
                        null, 1L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new CallLogSnapshot.CallLogEntry("+62", "A",
                        CallLogSnapshot.CallType.INCOMING, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new CallLogSnapshot.CallLogEntry("+62", "A",
                        CallLogSnapshot.CallType.INCOMING, 1L, -1L));
    }

    @Test
    public void rowFieldsCarryOnlyTheMinimalContract() {
        Map<String, Object> row = entry().rowFields();
        assertEquals("+628111", row.get("number"));
        assertEquals("Alice", row.get("name"));
        assertEquals("incoming", row.get("type"));
        assertEquals(1_000L, row.get("timestamp_utc_ms"));
        assertEquals(30L, row.get("duration_seconds"));
        assertEquals(5, row.size());
        assertFalse("geocoded location must never cross", row.containsKey("geo_location"));
        assertFalse("provider ids must never cross", row.containsKey("id"));
    }

    @Test
    public void rowFieldsOmitAbsentCachedName() {
        Map<String, Object> row = new CallLogSnapshot.CallLogEntry(
                "+628111", null, CallLogSnapshot.CallType.MISSED, 1L, 0L).rowFields();
        assertNull(row.get("name"));
        assertEquals("missed", row.get("type"));
        assertEquals(4, row.size());
    }

    @Test
    public void entryTrimsAndCapsTextFields() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('n');
        }
        CallLogSnapshot.CallLogEntry entry = new CallLogSnapshot.CallLogEntry(
                "  +628111  ", longName.toString(), CallLogSnapshot.CallType.OUTGOING,
                1L, 0L);
        assertEquals("+628111", entry.getNumber());
        assertEquals(MessagingReadPolicy.MAX_NAME_CHARS, entry.getCachedName().length());
    }

    @Test
    public void encodeRowsIsBoundedAndSingleLine() {
        CallLogSnapshot snapshot = CallLogSnapshot.reading(List.of(entry()), false);
        MessagingReadPolicy.EncodedRows encoded = snapshot.encodeRows();
        assertFalse(encoded.isTruncated());
        assertTrue(encoded.getJson().startsWith("[{\"number\":\"+628111\""));
        assertTrue(encoded.getJson().endsWith("}]"));
    }
}
