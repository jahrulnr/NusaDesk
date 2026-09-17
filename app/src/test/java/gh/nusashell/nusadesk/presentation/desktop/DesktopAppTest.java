package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.presentation.DesktopDestination;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DesktopAppTest {

    @Test
    public void catalogueIdsAreUniqueAndStable() {
        Set<String> ids = new HashSet<>();
        for (DesktopApp app : DesktopApp.curated()) {
            assertFalse(app.getId().trim().isEmpty());
            assertTrue("duplicate id " + app.getId(), ids.add(app.getId()));
            assertTrue(app.getId().matches("[a-z0-9_]+"));
        }
    }

    @Test
    public void everyEntryHasRenderableCopyAndABundledIcon() {
        for (DesktopApp app : DesktopApp.curated()) {
            assertTrue(app.getId(), app.getLabelRes() != 0);
            assertTrue(app.getId(), app.getIconRes() != 0);
            assertTrue(app.getId(), app.getDescriptionRes() != 0);
        }
        // Two surfaces must not silently share one vector.
        assertTrue(DesktopApp.TERMINAL.getIconRes() != DesktopApp.SYSTEM.getIconRes());
    }

    /**
     * Regression guard for the product rule that the launcher must not fake a
     * built-in desktop, file browser, or app catalogue: the curated list is
     * exactly the surfaces this build can route to.
     */
    @Test
    public void theCatalogueOnlyHoldsSurfacesTheProductCanOpen() {
        assertEquals(2, DesktopApp.curated().size());
        assertSame(DesktopDestination.TERMINAL, DesktopApp.TERMINAL.getDestination());
        assertSame(DesktopDestination.SYSTEM, DesktopApp.SYSTEM.getDestination());
    }

    @Test
    public void noCuratedSurfaceOffersASessionDestination() {
        for (DesktopApp app : DesktopApp.curated()) {
            assertTrue(app.getId(),
                    app.getDestination() != DesktopDestination.HOME);
        }
    }

    @Test
    public void fromIdRoundTripsAndRejectsUnknownIds() {
        for (DesktopApp app : DesktopApp.curated()) {
            assertSame(app, DesktopApp.fromId(app.getId()));
        }
        assertNull(DesktopApp.fromId("not-an-app"));
        assertNull(DesktopApp.fromId("workspace"));
        assertNull(DesktopApp.fromId("files"));
        assertNull(DesktopApp.fromId(null));
        assertNull(DesktopApp.fromId(""));
    }

    @Test
    public void curatedListIsReadOnlySoATileCannotMutateTheCatalogue() {
        List<DesktopApp> curated = DesktopApp.curated();
        assertEquals(DesktopApp.values().length, curated.size());
        try {
            curated.add(DesktopApp.TERMINAL);
            assertTrue("catalogue must be immutable", false);
        } catch (UnsupportedOperationException expected) {
            assertNotNull(expected);
        }
    }
}
