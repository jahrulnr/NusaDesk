package gh.nusashell.nusadesk.domain.webapp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebAppIdTest {

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals("notes", WebAppId.of("  notes  ").value());
    }

    @Test
    public void acceptsGeneratedUuidShape() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertEquals(uuid, WebAppId.of(uuid).value());
    }

    @Test
    public void rejectsNullBlankAndWhitespaceOnlyIds() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
    }

    @Test
    public void rejectsIdsWithCharactersUnsafeForAStorageKey() {
        assertRejected("a b");
        assertRejected("a/b");
        assertRejected("a:b");
        assertRejected("a#b");
    }

    @Test
    public void rejectsIdsContainingTheStorageKeySeparator() {
        assertRejected("a.b");
    }

    @Test
    public void rejectsIdsLongerThanTheMaximum() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < WebAppId.MAX_LENGTH + 1; i++) {
            tooLong.append('a');
        }
        assertRejected(tooLong.toString());
        assertEquals(WebAppId.MAX_LENGTH, WebAppId.of(tooLong.substring(0, WebAppId.MAX_LENGTH))
                .value().length());
    }

    @Test
    public void isValidMirrorsTheConstructorRules() {
        assertTrue(WebAppId.isValid("app-1"));
        assertFalse(WebAppId.isValid(" "));
        assertFalse(WebAppId.isValid(null));
        assertFalse(WebAppId.isValid("a.b"));
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(WebAppId.of("app-1"), WebAppId.of(" app-1 "));
        assertEquals(WebAppId.of("app-1").hashCode(), WebAppId.of("app-1").hashCode());
        assertNotEquals(WebAppId.of("app-1"), WebAppId.of("app-2"));
    }

    @Test
    public void toStringIsTheRawValue() {
        assertEquals("app-1", WebAppId.of("app-1").toString());
    }

    private static void assertRejected(String raw) {
        try {
            WebAppId.of(raw);
            fail("expected rejection of: " + raw);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
