package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MessagingReadPolicyTest {

    @Test
    public void sanitizeQueryDropsControlCharsTrimsAndCaps() {
        assertEquals("", MessagingReadPolicy.sanitizeQuery(null));
        assertEquals("", MessagingReadPolicy.sanitizeQuery("   "));
        assertEquals("ab", MessagingReadPolicy.sanitizeQuery("a\u0000b\t"));
        assertEquals("hello", MessagingReadPolicy.sanitizeQuery("  hello  "));
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longQuery.append('x');
        }
        String sanitized = MessagingReadPolicy.sanitizeQuery(longQuery.toString());
        assertEquals(MessagingReadPolicy.MAX_QUERY_CHARS, sanitized.length());
    }

    @Test
    public void escapeLikeLiteralEscapesWildcardsAndBackslash() {
        assertEquals("", MessagingReadPolicy.escapeLikeLiteral(null));
        assertEquals("50\\%", MessagingReadPolicy.escapeLikeLiteral("50%"));
        assertEquals("a\\_b", MessagingReadPolicy.escapeLikeLiteral("a_b"));
        assertEquals("a\\\\b", MessagingReadPolicy.escapeLikeLiteral("a\\b"));
        assertEquals("plain", MessagingReadPolicy.escapeLikeLiteral("plain"));
    }

    @Test
    public void truncateCapsLengthWithoutSplittingSurrogatePairs() {
        String emoji = "a\uD83D\uDE00b"; // 'a', U+1F600, 'b'
        assertEquals("", MessagingReadPolicy.truncate(null, 10));
        assertEquals("", MessagingReadPolicy.truncate("abc", 0));
        assertEquals("abc", MessagingReadPolicy.truncate("abcdef", 3));
        assertEquals("a\uD83D\uDE00", MessagingReadPolicy.truncate(emoji, 3));
        assertEquals("a", MessagingReadPolicy.truncate(emoji, 2));
        assertEquals(emoji, MessagingReadPolicy.truncate(emoji, 4));
    }

    @Test
    public void sanitizeSnippetFlattensToOneLineAndCaps() {
        assertEquals("", MessagingReadPolicy.sanitizeSnippet(null));
        assertEquals("", MessagingReadPolicy.sanitizeSnippet(" \n\t "));
        assertEquals("hello world !", MessagingReadPolicy.sanitizeSnippet("hello\nworld\t!"));
        assertEquals("a b", MessagingReadPolicy.sanitizeSnippet("a  \n  b"));
        assertEquals("hi", MessagingReadPolicy.sanitizeSnippet("\u0001hi"));
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longBody.append('y');
        }
        String snippet = MessagingReadPolicy.sanitizeSnippet(longBody.toString());
        assertEquals(MessagingReadPolicy.MAX_SNIPPET_CHARS, snippet.length());
        assertFalse("snippet must be single-line", snippet.contains("\n"));
    }

    @Test
    public void encodeRowsIsSingleLineBoundedAndOrdered() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Alice");
        row.put("number_1", "+628111");
        rows.add(row);
        rows.add(row);

        MessagingReadPolicy.EncodedRows encoded = MessagingReadPolicy.encodeRows(rows);
        assertEquals("[{\"name\":\"Alice\",\"number_1\":\"+628111\"},"
                + "{\"name\":\"Alice\",\"number_1\":\"+628111\"}]", encoded.getJson());
        assertFalse(encoded.isTruncated());
        assertFalse("row array must be single-line",
                encoded.getJson().contains("\n"));
        assertTrue("row array must stay inside the frame budget",
                encoded.getJson().length() <= MessagingReadPolicy.ROWS_BUDGET_CHARS);
    }

    @Test
    public void encodeRowsCapsRowsAtMaxRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("a", "1");
        for (int i = 0; i < MessagingReadPolicy.MAX_ROWS + 5; i++) {
            rows.add(row);
        }
        MessagingReadPolicy.EncodedRows encoded = MessagingReadPolicy.encodeRows(rows);
        assertTrue(encoded.isTruncated());
        int occurrences = 0;
        int from = 0;
        while ((from = encoded.getJson().indexOf("\"a\"", from)) != -1) {
            occurrences++;
            from += 3;
        }
        assertEquals(MessagingReadPolicy.MAX_ROWS, occurrences);
    }

    @Test
    public void encodeRowsDropsTrailingRowsThatExceedTheByteBudget() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // Rows sized against the live budget (the 64 KiB frame minus the
        // envelope margin): a quarter-budget row plus its JSON overhead
        // leaves room for only three, so six rows must drop the last three.
        int rowChars = MessagingReadPolicy.ROWS_BUDGET_CHARS / 4;
        for (int i = 0; i < 6; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", repeat('x', rowChars));
            rows.add(row);
        }
        MessagingReadPolicy.EncodedRows encoded = MessagingReadPolicy.encodeRows(rows);
        assertTrue(encoded.isTruncated());
        assertTrue("encoded rows must fit the budget",
                encoded.getJson().length() <= MessagingReadPolicy.ROWS_BUDGET_CHARS);
        // The array stays valid: starts with '[' and ends with ']'.
        assertTrue(encoded.getJson().startsWith("["));
        assertTrue(encoded.getJson().endsWith("]"));
    }

    @Test
    public void encodeRowsFailsClosedOnInvalidKeysValuesAndNonFiniteDoubles() {
        Map<String, Object> badKey = new LinkedHashMap<>();
        badKey.put("bad key", "x");
        assertThrows(IllegalArgumentException.class,
                () -> MessagingReadPolicy.encodeRows(List.of(badKey)));

        Map<String, Object> badValue = new LinkedHashMap<>();
        badValue.put("ok", new Object());
        assertThrows(IllegalArgumentException.class,
                () -> MessagingReadPolicy.encodeRows(List.of(badValue)));

        Map<String, Object> nonFinite = new LinkedHashMap<>();
        nonFinite.put("ok", Double.NaN);
        assertThrows(IllegalArgumentException.class,
                () -> MessagingReadPolicy.encodeRows(List.of(nonFinite)));

        assertThrows(IllegalArgumentException.class,
                () -> MessagingReadPolicy.encodeRows(null));
    }

    private static String repeat(char c, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            value.append(c);
        }
        return value.toString();
    }
}
