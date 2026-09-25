package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The launcher grid is pure policy — what it lists, in what order, and what a
 * query leaves standing — so it is asserted without a device.
 */
public class LauncherModelTest {

    private static WebAppDefinition webApp(String name, int port, long createdAt) {
        return new WebAppDefinition(
                WebAppId.of(name.toLowerCase().replace(' ', '-')), name, null, port,
                createdAt, createdAt, (int) createdAt);
    }

    private static TerminalCommandApp commandApp(
            String id, String name, String command, int sortOrder) {
        return new TerminalCommandApp(
                TerminalCommandAppId.of(id), name, null,
                TerminalCommand.of(command), 1, 1, sortOrder);
    }

    private static final List<WebAppDefinition> WEB_APPS = Arrays.asList(
            webApp("Notebook", 8000, 1),
            webApp("Wiki", 8080, 2));

    private static final List<TerminalCommandApp> COMMAND_APPS = Arrays.asList(
            commandApp("cmd-codex", "Codex", "docker exec -it codex bash", 0),
            commandApp("cmd-htop", "Htop", "htop", 1));

    private static final Function<LauncherEntry, String> LABELS = entry -> {
        switch (entry.getKind()) {
            case ADD_APP:
                return "Add app";
            case CURATED:
                DesktopApp app = DesktopApp.fromId(entry.getId());
                if (app == DesktopApp.TERMINAL) {
                    return "Terminal";
                }
                return app == DesktopApp.SYSTEM ? "System" : "Logs";
            default:
                return entry.getLabel();
        }
    };

    @Test
    public void appsStayHiddenUntilLinuxAndTerminalComponentAreReady() {
        assertFalse(LauncherModel.shouldShowApps(false, false));
        assertFalse(LauncherModel.shouldShowApps(true, false));
        assertFalse(LauncherModel.shouldShowApps(false, true));
        assertTrue(LauncherModel.shouldShowApps(true, true));
    }

    @Test
    public void addAppComesFirstThenLinuxSurfacesThenWebAppsThenCommandApps() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS, COMMAND_APPS);

        assertEquals(LauncherEntry.Kind.ADD_APP, entries.get(0).getKind());
        assertEquals(LauncherEntry.ADD_APP_ID, entries.get(0).getId());
        assertEquals("terminal", entries.get(1).getId());
        assertEquals("system", entries.get(2).getId());
        assertEquals("logs", entries.get(3).getId());
        assertEquals("notebook", entries.get(4).getId());
        assertEquals("wiki", entries.get(5).getId());
        assertEquals(LauncherEntry.Kind.TERMINAL_APP, entries.get(6).getKind());
        assertEquals("cmd-codex", entries.get(6).getId());
        assertEquals("cmd-htop", entries.get(7).getId());
        assertEquals(8, entries.size());
    }

    @Test
    public void theAddAppActionIsNotAnAppButStaysOpenable() {
        LauncherEntry add =
                LauncherModel.entries(Collections.emptyList(), null).get(0);

        assertFalse(add.isOpenable());
        assertFalse(add.isWebApp());
        assertFalse(add.isTerminalApp());
        assertTrue(add.getLabelRes() != 0);
        assertTrue(add.getIconRes() != 0);
    }

    /**
     * A web app carries no bundled vector icon: its tile walks the user image →
     * favicon → monogram order ({@link LauncherIconPolicy}) instead, and that
     * fallback chain must stay reachable through {@code iconRes == 0}.
     */
    @Test
    public void webAppsHaveNoBundledIconSoTheirOwnImagePolicyApplies() {
        for (LauncherEntry entry : LauncherModel.entries(WEB_APPS, null)) {
            if (entry.isWebApp()) {
                assertEquals(0, entry.getIconRes());
            }
        }
    }

    @Test
    public void aWebAppEntryCarriesItsOwnDefinitionSoTheTileCanOpenIt() {
        LauncherEntry entry = LauncherModel.entries(WEB_APPS, null).get(4);

        assertTrue(entry.isWebApp());
        assertTrue(entry.isOpenable());
        assertEquals("Notebook", entry.getLabel());
        assertEquals(8000, entry.getWebApp().getGuestPort());
        assertEquals("http://127.0.0.1:8000/", entry.getWebApp().getEndpointUrl());
    }

    @Test
    public void aTerminalCommandEntryCarriesItsOwnAppSoTheTileCanOpenIt() {
        LauncherEntry entry = LauncherModel.entries(null, COMMAND_APPS).get(4);

        assertTrue(entry.isTerminalApp());
        assertFalse(entry.isWebApp());
        assertTrue(entry.isOpenable());
        assertEquals("Codex", entry.getLabel());
        assertEquals("docker exec -it codex bash",
                entry.getTerminalApp().getCommand().value());
        // A command app ships its own glyph: the tile still walks the icon
        // policy, but the bundled vector always stands before the monogram.
        assertTrue(entry.getIconRes() != 0);
    }

    @Test
    public void noRegisteredAppsIsAValidLauncher() {
        List<LauncherEntry> entries = LauncherModel.entries(null, null);

        assertEquals(4, entries.size());
        assertFalse(entries.get(1).isWebApp());
        assertFalse(entries.get(1).isTerminalApp());
    }

    @Test
    public void emptyOrNullCommandAppListsAreTolerated() {
        assertEquals(6, LauncherModel.entries(WEB_APPS, null).size());
        assertEquals(6,
                LauncherModel.entries(WEB_APPS, Collections.emptyList()).size());
        List<TerminalCommandApp> withNull = new ArrayList<>(COMMAND_APPS);
        withNull.add(null);
        assertEquals(8, LauncherModel.entries(WEB_APPS, withNull).size());
    }

    @Test
    public void anEmptyQueryKeepsTheGridUnchanged() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS, COMMAND_APPS);

        assertFalse(LauncherModel.isFiltering("   "));
        assertSame(entries, LauncherModel.filter(entries, "   ", LABELS));
        assertSame(entries, LauncherModel.filter(entries, null, LABELS));
    }

    @Test
    public void aQueryMatchesTheLabelTheUserSeesIgnoringCaseAndSurroundingSpace() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS, COMMAND_APPS);

        assertEquals(1, LauncherModel.filter(entries, "  note  ", LABELS).size());
        assertEquals("notebook",
                LauncherModel.filter(entries, "NOTE", LABELS).get(0).getId());
        assertEquals("terminal",
                LauncherModel.filter(entries, "term", LABELS).get(0).getId());
        assertEquals("cmd-codex",
                LauncherModel.filter(entries, "codex", LABELS).get(0).getId());
    }

    @Test
    public void aQueryMatchesACommandAppOnlyByItsVisibleLabel() {
        List<LauncherEntry> entries = LauncherModel.entries(null, COMMAND_APPS);

        // The command text is not the tile's label, so it can never match.
        assertTrue(LauncherModel.filter(entries, "docker exec", LABELS).isEmpty());
        assertEquals("cmd-htop",
                LauncherModel.filter(entries, "htop", LABELS).get(0).getId());
    }

    @Test
    public void aQueryWithNoMatchReturnsNothingSoTheLauncherCanSaySo() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS, null);

        assertTrue(LauncherModel.filter(entries, "zzz", LABELS).isEmpty());
    }

    @Test
    public void filteringNeverMutatesTheGridItWasGiven() {
        List<LauncherEntry> entries =
                new ArrayList<>(LauncherModel.entries(WEB_APPS, null));
        int before = entries.size();

        List<LauncherEntry> matches = LauncherModel.filter(entries, "note", LABELS);

        assertEquals(before, entries.size());
        try {
            matches.add(entries.get(0));
            assertTrue("filtered result must be immutable", false);
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }
}
