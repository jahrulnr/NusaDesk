package gh.nusashell.nusadesk.infrastructure.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionMetadataTest {
    @Test
    public void rejectsBlankSessionId() {
        try {
            new SessionMetadata(" ", "host", 22, "user", 1L, 0L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsBlankHostAndUsername() {
        try {
            new SessionMetadata("id", "  ", 22, "user", 1L, 0L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new SessionMetadata("id", "host", 22, "", 1L, 0L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsOutOfRangePort() {
        try {
            new SessionMetadata("id", "host", 0, "user", 1L, 0L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new SessionMetadata("id", "host", 65536, "user", 1L, 0L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNegativeTimestamps() {
        try {
            new SessionMetadata("id", "host", 22, "user", -1L, 0L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new SessionMetadata("id", "host", 22, "user", 1L, -1L, true);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void valueEqualityCoversAllFields() {
        SessionMetadata a = new SessionMetadata("id", "host", 22, "user", 10L, 20L, true);
        SessionMetadata b = new SessionMetadata("id", "host", 22, "user", 10L, 20L, true);
        SessionMetadata different = new SessionMetadata("id", "host", 2222, "user", 10L, 20L, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, different);
        assertTrue(a.isActive());
        assertFalse(different.isActive());
        assertEquals(20L, a.getLastConnectedAtEpochMillis());
    }
}
