package gh.nusashell.nusadesk.domain.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TerminalCommandAppIdTest {

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals("codex", TerminalCommandAppId.of("  codex  ").value());
    }

    @Test
    public void acceptsGeneratedUuidShape() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertEquals(uuid, TerminalCommandAppId.of(uuid).value());
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
        for (int i = 0; i < TerminalCommandAppId.MAX_LENGTH + 1; i++) {
            tooLong.append('a');
        }
        assertRejected(tooLong.toString());
        assertEquals(TerminalCommandAppId.MAX_LENGTH,
                TerminalCommandAppId.of(
                        tooLong.substring(0, TerminalCommandAppId.MAX_LENGTH))
                        .value().length());
    }

    @Test
    public void isValidMirrorsTheConstructorRules() {
        assertTrue(TerminalCommandAppId.isValid("app-1"));
        assertFalse(TerminalCommandAppId.isValid(" "));
        assertFalse(TerminalCommandAppId.isValid(null));
        assertFalse(TerminalCommandAppId.isValid("a.b"));
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(TerminalCommandAppId.of("app-1"), TerminalCommandAppId.of(" app-1 "));
        assertEquals(TerminalCommandAppId.of("app-1").hashCode(),
                TerminalCommandAppId.of("app-1").hashCode());
        assertNotEquals(TerminalCommandAppId.of("app-1"), TerminalCommandAppId.of("app-2"));
    }

    @Test
    public void toStringIsTheRawValue() {
        assertEquals("app-1", TerminalCommandAppId.of("app-1").toString());
    }

    private static void assertRejected(String raw) {
        try {
            TerminalCommandAppId.of(raw);
            fail("expected rejection of: " + raw);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
