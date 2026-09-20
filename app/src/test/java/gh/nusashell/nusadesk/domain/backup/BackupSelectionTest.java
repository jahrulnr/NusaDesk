package gh.nusashell.nusadesk.domain.backup;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The three export selections: factories validate at the domain boundary. */
public class BackupSelectionTest {

    @Test
    public void fullCarriesNoRoots() {
        BackupSelection selection = BackupSelection.full();
        assertEquals(BackupMode.FULL, selection.getMode());
        assertTrue(selection.getRoots().isEmpty());
    }

    @Test
    public void homeCarriesTheTwoFixedRoots() {
        BackupSelection selection = BackupSelection.home();
        assertEquals(BackupMode.HOME, selection.getMode());
        assertEquals(Arrays.asList("/root", "/home"), selection.getRoots());
    }

    @Test
    public void customKeepsAllowlistOrder() {
        BackupSelection selection =
                BackupSelection.custom(Arrays.asList("/var/lib", "/etc"));
        assertEquals(BackupMode.CUSTOM, selection.getMode());
        assertEquals(Arrays.asList("/etc", "/var/lib"), selection.getRoots());
    }

    @Test
    public void customRejectsEmptyAndForeignRoots() {
        try {
            BackupSelection.custom(Collections.<String>emptyList());
            fail("an empty custom selection must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            BackupSelection.custom(Collections.singletonList("/proc"));
            fail("a non-allowlisted root must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void modeWireValuesRoundTrip() {
        for (BackupMode mode : BackupMode.values()) {
            assertEquals(mode, BackupMode.fromWireValue(mode.getWireValue()));
        }
        assertEquals("full", BackupMode.FULL.getWireValue());
        assertEquals("home", BackupMode.HOME.getWireValue());
        assertEquals("custom", BackupMode.CUSTOM.getWireValue());
        assertEquals(null, BackupMode.fromWireValue("everything"));
    }
}
