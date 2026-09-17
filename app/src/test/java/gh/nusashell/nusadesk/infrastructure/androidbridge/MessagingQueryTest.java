package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MessagingQueryTest {

    @Test
    public void rejectsLimitsOutsideTheAllowlist() {
        assertThrows(IllegalArgumentException.class, () -> MessagingQuery.of(0));
        assertThrows(IllegalArgumentException.class,
                () -> MessagingQuery.of(MessagingReadPolicy.MAX_ROWS + 1));
        assertThrows(IllegalArgumentException.class, () -> MessagingQuery.of(-3));
        assertThrows(IllegalArgumentException.class,
                () -> MessagingQuery.of(MessagingReadPolicy.MAX_ROWS + 1, "q"));
    }

    @Test
    public void acceptsBoundedLimitsAndDefaults() {
        assertEquals(MessagingReadPolicy.DEFAULT_LIMIT, MessagingQuery.all().getLimit());
        assertEquals(1, MessagingQuery.of(1).getLimit());
        assertEquals(MessagingReadPolicy.MAX_ROWS,
                MessagingQuery.of(MessagingReadPolicy.MAX_ROWS).getLimit());
        assertFalse(MessagingQuery.of(5).hasQuery());
        assertNull(MessagingQuery.of(5).likePattern());
    }

    @Test
    public void sanitizesTheQueryAtTheBoundary() {
        MessagingQuery query = MessagingQuery.of(10, "  a\u0000b\t");
        assertEquals("ab", query.getQuery());
        assertTrue(query.hasQuery());
    }

    @Test
    public void blankOrNullQueryMeansNoFilter() {
        assertFalse(MessagingQuery.of(10, null).hasQuery());
        assertFalse(MessagingQuery.of(10, "   ").hasQuery());
        assertNull(MessagingQuery.of(10, "  ").likePattern());
    }

    @Test
    public void likePatternEscapesWildcardsInTheQuery() {
        assertEquals("%50\\%%", MessagingQuery.of(10, "50%").likePattern());
        assertEquals("%a\\_b%", MessagingQuery.of(10, "a_b").likePattern());
        assertEquals("%plain%", MessagingQuery.of(10, "plain").likePattern());
    }
}
