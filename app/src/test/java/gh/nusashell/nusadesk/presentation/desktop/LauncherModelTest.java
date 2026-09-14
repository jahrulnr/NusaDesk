package gh.nusashell.nusadesk.presentation.desktop;

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

    private static final List<WebAppDefinition> WEB_APPS = Arrays.asList(
            webApp("Notebook", 8000, 1),
            webApp("Wiki", 8080, 2));

    private static final Function<LauncherEntry, String> LABELS = entry -> {
        switch (entry.getKind()) {
            case ADD_APP:
                return "Add app";
            case CURATED:
                return DesktopApp.fromId(entry.getId()) == DesktopApp.TERMINAL
                        ? "Terminal" : "Linux System";
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
    public void addAppComesFirstThenLinuxSurfacesThenWebApps() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS);

        assertEquals(LauncherEntry.Kind.ADD_APP, entries.get(0).getKind());
        assertEquals(LauncherEntry.ADD_APP_ID, entries.get(0).getId());
        assertEquals("terminal", entries.get(1).getId());
        assertEquals("system", entries.get(2).getId());
        assertEquals("notebook", entries.get(3).getId());
        assertEquals("wiki", entries.get(4).getId());
        assertEquals(5, entries.size());
    }

    @Test
    public void theAddAppActionIsNotAnAppButStaysOpenable() {
        LauncherEntry add = LauncherModel.entries(Collections.emptyList()).get(0);

        assertFalse(add.isOpenable());
        assertFalse(add.isWebApp());
        assertTrue(add.getLabelRes() != 0);
        assertTrue(add.getGlyphRes() != 0);
    }

    @Test
    public void aWebAppEntryCarriesItsOwnDefinitionSoTheTileCanOpenIt() {
        LauncherEntry entry = LauncherModel.entries(WEB_APPS).get(3);

        assertTrue(entry.isWebApp());
        assertTrue(entry.isOpenable());
        assertEquals("Notebook", entry.getLabel());
        assertEquals(8000, entry.getWebApp().getGuestPort());
        assertEquals("http://127.0.0.1:8000/", entry.getWebApp().getEndpointUrl());
    }

    @Test
    public void noWebAppsIsAValidLauncher() {
        List<LauncherEntry> entries = LauncherModel.entries(null);

        assertEquals(3, entries.size());
        assertFalse(entries.get(1).isWebApp());
    }

    @Test
    public void anEmptyQueryKeepsTheGridUnchanged() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS);

        assertFalse(LauncherModel.isFiltering("   "));
        assertSame(entries, LauncherModel.filter(entries, "   ", LABELS));
        assertSame(entries, LauncherModel.filter(entries, null, LABELS));
    }

    @Test
    public void aQueryMatchesTheLabelTheUserSeesIgnoringCaseAndSurroundingSpace() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS);

        assertEquals(1, LauncherModel.filter(entries, "  note  ", LABELS).size());
        assertEquals("notebook",
                LauncherModel.filter(entries, "NOTE", LABELS).get(0).getId());
        assertEquals("terminal",
                LauncherModel.filter(entries, "term", LABELS).get(0).getId());
    }

    @Test
    public void aQueryWithNoMatchReturnsNothingSoTheLauncherCanSaySo() {
        List<LauncherEntry> entries = LauncherModel.entries(WEB_APPS);

        assertTrue(LauncherModel.filter(entries, "zzz", LABELS).isEmpty());
    }

    @Test
    public void filteringNeverMutatesTheGridItWasGiven() {
        List<LauncherEntry> entries = new ArrayList<>(LauncherModel.entries(WEB_APPS));
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
