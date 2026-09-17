package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContactsSnapshotTest {

    private static ContactsSnapshot.ContactEntry entry(String name, String... numbers) {
        return new ContactsSnapshot.ContactEntry(name, Arrays.asList(numbers));
    }

    @Test
    public void readingMapsBoundedEnvelopeFields() {
        ContactsSnapshot snapshot = ContactsSnapshot.reading(
                List.of(entry("Alice", "+628111")), false);

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertFalse(snapshot.isTruncated());

        Map<String, Object> fields = snapshot.responseFields();
        assertEquals(true, fields.get("available"));
        assertEquals(1L, fields.get("count"));
        assertEquals(false, fields.get("truncated"));
    }

    @Test
    public void readingCapsRowsAtMaxRowsAndReportsTruncation() {
        List<ContactsSnapshot.ContactEntry> entries = new ArrayList<>();
        for (int i = 0; i < MessagingReadPolicy.MAX_ROWS + 5; i++) {
            entries.add(entry("C" + i, "+62"));
        }
        ContactsSnapshot snapshot = ContactsSnapshot.reading(entries, false);
        assertEquals(MessagingReadPolicy.MAX_ROWS, snapshot.getEntries().size());
        assertTrue(snapshot.isTruncated());
        assertEquals((long) MessagingReadPolicy.MAX_ROWS,
                snapshot.responseFields().get("count"));
    }

    @Test
    public void readingHonorsTheAdapterTruncationFlag() {
        ContactsSnapshot snapshot = ContactsSnapshot.reading(
                List.of(entry("Alice", "+628111")), true);
        assertTrue(snapshot.isTruncated());
    }

    @Test
    public void nonReadingStatesAreExplicitAndHaveNoFields() {
        assertEquals(MessagingReadState.PERMISSION_REQUIRED,
                ContactsSnapshot.permissionRequired().getState());
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                ContactsSnapshot.permissionDenied().getState());
        assertEquals(MessagingReadState.UNAVAILABLE,
                ContactsSnapshot.unavailable().getState());
        assertEquals(MessagingReadState.ERROR,
                ContactsSnapshot.error().getState());
        assertThrows(IllegalStateException.class,
                () -> ContactsSnapshot.permissionDenied().responseFields());
        assertThrows(IllegalStateException.class,
                () -> ContactsSnapshot.permissionDenied().encodeRows());
    }

    @Test
    public void entryTrimsAndCapsNameAndNumbers() {
        ContactsSnapshot.ContactEntry entry = entry("  Alice  ",
                "  +628111  ", "  +628222  ");
        assertEquals("Alice", entry.getName());
        assertEquals(List.of("+628111", "+628222"), entry.getNumbers());
    }

    @Test
    public void entryRejectsBlankNumbersAndTooManyNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContactsSnapshot.ContactEntry("A", Arrays.asList("", "1")));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < MessagingReadPolicy.MAX_NUMBERS_PER_CONTACT + 1; i++) {
            tooMany.add("+" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ContactsSnapshot.ContactEntry("A", tooMany));
    }

    @Test
    public void entryAllowsNumberOnlyContacts() {
        ContactsSnapshot.ContactEntry entry = new ContactsSnapshot.ContactEntry(null, null);
        assertEquals("", entry.getName());
        assertTrue(entry.getNumbers().isEmpty());
    }

    @Test
    public void rowFieldsCarryOnlyNameAndIndexedNumbers() {
        Map<String, Object> row = entry("Alice", "+628111", "+628222").rowFields();
        assertEquals("Alice", row.get("name"));
        assertEquals("+628111", row.get("number_1"));
        assertEquals("+628222", row.get("number_2"));
        assertFalse("no field outside the minimal contract", row.containsKey("number_3"));
        assertFalse("no provider ids or emails", row.containsKey("id"));
        assertFalse("no provider ids or emails", row.containsKey("email"));
    }

    @Test
    public void encodeRowsIsBoundedAndSingleLine() {
        ContactsSnapshot snapshot = ContactsSnapshot.reading(
                List.of(entry("Alice", "+628111")), false);
        MessagingReadPolicy.EncodedRows encoded = snapshot.encodeRows();
        assertFalse(encoded.isTruncated());
        assertEquals("[{\"name\":\"Alice\",\"number_1\":\"+628111\"}]", encoded.getJson());
    }
}
