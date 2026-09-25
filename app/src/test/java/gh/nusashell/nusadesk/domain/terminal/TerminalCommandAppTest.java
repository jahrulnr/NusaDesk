package gh.nusashell.nusadesk.domain.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TerminalCommandAppTest {
    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";
    private static final TerminalCommand COMMAND =
            TerminalCommand.of("docker exec -it codex bash");

    private static TerminalCommandApp app(
            String id, String name, String iconUri, TerminalCommand command,
            long createdAt, long updatedAt, int sortOrder) {
        return new TerminalCommandApp(
                TerminalCommandAppId.of(id), name, iconUri, command,
                createdAt, updatedAt, sortOrder);
    }

    private static TerminalCommandApp valid() {
        return app("app-1", "Codex", ICON, COMMAND, 100L, 100L, 0);
    }

    @Test
    public void storesTheCommandVerbatim() {
        TerminalCommand complex =
                TerminalCommand.of("sh -c 'echo \"a b\" && exit' | tee /tmp/x");
        assertEquals(complex, app("a", "A", null, complex, 0L, 0L, 0).getCommand());
        assertEquals("sh -c 'echo \"a b\" && exit' | tee /tmp/x",
                app("a", "A", null, complex, 0L, 0L, 0).getCommand().value());
    }

    @Test
    public void trimsTheDisplayName() {
        assertEquals("Codex", app("a", "  Codex  ", null, COMMAND, 0L, 0L, 0).getDisplayName());
    }

    @Test
    public void rejectsBlankDisplayName() {
        assertRejected(() -> app("a", null, null, COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "", null, COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "   ", null, COMMAND, 0L, 0L, 0));
    }

    @Test
    public void rejectsDisplayNameLongerThanTheMaximum() {
        String tooLong = repeated('n', TerminalCommandApp.MAX_DISPLAY_NAME_LENGTH + 1);
        assertRejected(() -> app("a", tooLong, null, COMMAND, 0L, 0L, 0));
    }

    @Test
    public void rejectsNullId() {
        try {
            new TerminalCommandApp(null, "Codex", null, COMMAND, 0L, 0L, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullCommand() {
        assertRejected(() -> app("a", "A", null, null, 0L, 0L, 0));
    }

    @Test
    public void treatsBlankIconAsAbsent() {
        assertNull(app("a", "A", null, COMMAND, 0L, 0L, 0).getIconUri());
        assertNull(app("a", "A", "", COMMAND, 0L, 0L, 0).getIconUri());
        assertNull(app("a", "A", "   ", COMMAND, 0L, 0L, 0).getIconUri());
    }

    @Test
    public void trimsAndKeepsAValidatedContentUriToken() {
        TerminalCommandApp app = app("a", "A", "  " + ICON + "  ", COMMAND, 0L, 0L, 0);
        assertEquals(ICON, app.getIconUri());
    }

    @Test
    public void rejectsIconTokensThatAreNotContentUris() {
        assertRejected(() -> app("a", "A", "file:///data/local/tmp/icon.png",
                COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "A", "http://example.com/icon.png",
                COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "A", "https://example.com/icon.png",
                COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "A", "/data/local/tmp/icon.png",
                COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "A", "content://", COMMAND, 0L, 0L, 0));
        assertRejected(() -> app("a", "A", "ht tp://broken", COMMAND, 0L, 0L, 0));
    }

    @Test
    public void rejectsIconTokensLongerThanTheMaximum() {
        String tooLong = "content://provider/"
                + repeated('x', TerminalCommandApp.MAX_ICON_URI_LENGTH);
        assertRejected(() -> app("a", "A", tooLong, COMMAND, 0L, 0L, 0));
    }

    @Test
    public void rejectsNegativeTimestampsAndInvertedUpdateTime() {
        assertRejected(() -> app("a", "A", null, COMMAND, -1L, 0L, 0));
        assertRejected(() -> app("a", "A", null, COMMAND, 0L, -1L, 0));
        assertRejected(() -> app("a", "A", null, COMMAND, 100L, 99L, 0));
    }

    @Test
    public void rejectsNegativeSortOrder() {
        assertRejected(() -> app("a", "A", null, COMMAND, 0L, 0L, -1));
    }

    @Test
    public void withDetailsKeepsIdentityOrderAndCreationTime() {
        TerminalCommandApp original = valid();
        TerminalCommand next = TerminalCommand.of("htop");
        TerminalCommandApp updated = original.withDetails("Monitor", null, next, 500L);

        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getCreatedAtEpochMillis(), updated.getCreatedAtEpochMillis());
        assertEquals(original.getSortOrder(), updated.getSortOrder());
        assertEquals("Monitor", updated.getDisplayName());
        assertNull(updated.getIconUri());
        assertEquals(next, updated.getCommand());
        assertEquals(500L, updated.getUpdatedAtEpochMillis());
    }

    @Test
    public void withDetailsStillValidatesTheNewValues() {
        assertRejected(() -> valid().withDetails("  ", null, COMMAND, 500L));
        assertRejected(() -> valid().withDetails("A", null, null, 500L));
        assertRejected(() -> valid().withDetails("A", null, COMMAND, 50L));
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(valid(), valid());
        assertEquals(valid().hashCode(), valid().hashCode());
        assertNotEquals(valid(), app("app-2", "Codex", ICON, COMMAND, 100L, 100L, 0));
        assertNotEquals(valid(), app("app-1", "Codex", ICON,
                TerminalCommand.of("htop"), 100L, 100L, 0));
    }

    @Test
    public void toStringDoesNotEchoTheUserAuthoredCommand() {
        // The command may embed arguments the user does not want in diagnostics.
        assertFalse(valid().toString().contains("docker"));
        assertTrue(valid().toString().contains("Codex"));
    }

    private static String repeated(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void assertRejected(Runnable construction) {
        try {
            construction.run();
            fail("expected construction to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
