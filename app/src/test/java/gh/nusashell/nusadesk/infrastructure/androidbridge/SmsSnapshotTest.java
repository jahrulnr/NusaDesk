package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SmsSnapshotTest {

    private static SmsSnapshot.SmsEntry entry() {
        return new SmsSnapshot.SmsEntry("+628111", 1_000L, false, "hello\nworld");
    }

    @Test
    public void readingMapsBoundedEnvelopeFields() {
        SmsSnapshot snapshot = SmsSnapshot.reading(List.of(entry()), false);
        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());

        Map<String, Object> fields = snapshot.responseFields();
        assertEquals(true, fields.get("available"));
        assertEquals(1L, fields.get("count"));
        assertEquals(false, fields.get("truncated"));
    }

    @Test
    public void readingCapsRowsAtMaxRowsAndReportsTruncation() {
        List<SmsSnapshot.SmsEntry> entries = new ArrayList<>();
        for (int i = 0; i < MessagingReadPolicy.MAX_ROWS + 5; i++) {
            entries.add(new SmsSnapshot.SmsEntry("+62", i + 1L, true, "s"));
        }
        SmsSnapshot snapshot = SmsSnapshot.reading(entries, false);
        assertEquals(MessagingReadPolicy.MAX_ROWS, snapshot.getEntries().size());
        assertTrue(snapshot.isTruncated());
    }

    @Test
    public void nonReadingStatesAreExplicit() {
        assertEquals(MessagingReadState.PERMISSION_REQUIRED,
                SmsSnapshot.permissionRequired().getState());
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                SmsSnapshot.permissionDenied().getState());
        assertEquals(MessagingReadState.UNAVAILABLE,
                SmsSnapshot.unavailable().getState());
        assertEquals(MessagingReadState.ERROR,
                SmsSnapshot.error().getState());
        assertThrows(IllegalStateException.class,
                () -> SmsSnapshot.error().responseFields());
        assertThrows(IllegalStateException.class,
                () -> SmsSnapshot.error().encodeRows());
    }

    @Test
    public void entryRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new SmsSnapshot.SmsEntry("  ", 1L, true, "s"));
        assertThrows(IllegalArgumentException.class,
                () -> new SmsSnapshot.SmsEntry("+62", 0L, true, "s"));
    }

    @Test
    public void entrySanitizesTheSnippetAndCapsIt() {
        SmsSnapshot.SmsEntry entry = new SmsSnapshot.SmsEntry("+628111", 1L, true,
                "line1\nline2\t!   ");
        assertEquals("line1 line2 !", entry.getSnippet());
        assertFalse("snippet must be single-line", entry.getSnippet().contains("\n"));

        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longBody.append('m');
        }
        SmsSnapshot.SmsEntry capped = new SmsSnapshot.SmsEntry(
                "+628111", 1L, true, longBody.toString());
        assertEquals(MessagingReadPolicy.MAX_SNIPPET_CHARS, capped.getSnippet().length());
    }

    @Test
    public void rowFieldsCarryOnlyTheMinimalContract() {
        Map<String, Object> row = new SmsSnapshot.SmsEntry(
                "+628111", 1_000L, true, "hi").rowFields();
        assertEquals("+628111", row.get("address"));
        assertEquals(1_000L, row.get("timestamp_utc_ms"));
        assertEquals(true, row.get("read"));
        assertEquals("hi", row.get("snippet"));
        assertEquals(4, row.size());
        assertFalse("full body must never cross", row.containsKey("body"));
        assertFalse("provider ids must never cross", row.containsKey("thread_id"));
    }

    @Test
    public void encodeRowsIsBoundedAndSingleLine() {
        SmsSnapshot snapshot = SmsSnapshot.reading(List.of(entry()), false);
        MessagingReadPolicy.EncodedRows encoded = snapshot.encodeRows();
        assertFalse(encoded.isTruncated());
        assertTrue(encoded.getJson().startsWith("[{\"address\":\"+628111\""));
        assertTrue(encoded.getJson().endsWith("}]"));
    }
}
