package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A launcher entry is an immutable value object, so what each kind carries —
 * and what it answers for the other kinds — is asserted without a device.
 */
public class LauncherEntryTest {

    private static TerminalCommandApp commandApp(String id, String name, String iconUri) {
        return new TerminalCommandApp(
                TerminalCommandAppId.of(id), name, iconUri,
                TerminalCommand.of("docker exec -it codex bash"), 1, 1, 0);
    }

    @Test
    public void aTerminalAppEntryCarriesItsOwnNameGlyphAndApp() {
        TerminalCommandApp app =
                commandApp("cmd-codex", "Codex", "content://media/external/images/7");

        LauncherEntry entry = LauncherEntry.terminalApp(app);

        assertEquals(LauncherEntry.Kind.TERMINAL_APP, entry.getKind());
        assertEquals("cmd-codex", entry.getId());
        assertEquals(0, entry.getLabelRes());
        assertEquals("Codex", entry.getLabel());
        // The bundled terminal glyph is the tile's vector icon step, so the
        // plate is never bare even when the user picked no image.
        assertEquals(R.drawable.ic_launcher_terminal, entry.getIconRes());
        assertEquals(0, entry.getDescriptionRes());
        assertEquals("content://media/external/images/7", entry.getIconUri());
        assertTrue(entry.isTerminalApp());
        assertFalse(entry.isWebApp());
        assertTrue(entry.isOpenable());
        assertSame(app, entry.getTerminalApp());
        assertNull(entry.getWebApp());
    }

    @Test
    public void aTerminalAppEntryWithoutAnIconStillCarriesTheBundledGlyph() {
        LauncherEntry entry =
                LauncherEntry.terminalApp(commandApp("cmd-htop", "Htop", null));

        assertNull(entry.getIconUri());
        assertEquals(R.drawable.ic_launcher_terminal, entry.getIconRes());
    }

    @Test
    public void aWebAppEntryAnswersNoTerminalApp() {
        WebAppDefinition definition = new WebAppDefinition(
                WebAppId.of("notebook"), "Notebook", null, 8000, 1, 1, 0);

        LauncherEntry entry = LauncherEntry.webApp(definition);

        assertTrue(entry.isWebApp());
        assertFalse(entry.isTerminalApp());
        assertNull(entry.getTerminalApp());
        assertSame(definition, entry.getWebApp());
    }

    @Test
    public void aNullTerminalAppIsRejected() {
        try {
            LauncherEntry.terminalApp(null);
            fail("terminalApp must reject a null app");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void equalityCoversTheCarriedApp() {
        LauncherEntry first = LauncherEntry.terminalApp(commandApp("cmd-a", "Alpha", null));
        LauncherEntry same = LauncherEntry.terminalApp(commandApp("cmd-a", "Alpha", null));
        LauncherEntry other = LauncherEntry.terminalApp(commandApp("cmd-b", "Beta", null));

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
    }
}
