package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.R;

/**
 * The System screen's sub-page state round-trips through its persisted id, so
 * an Activity recreation — a SAF picker round trip on a device that destroys
 * the covered Activity, a configuration change — returns the user to the page
 * they left, and a stale id can never blank the screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 33})
public class SystemScreenPageStateTest {

    private static SystemScreenView screen() {
        return new SystemScreenView(RuntimeEnvironment.getApplication());
    }

    @Test
    public void theHubIsTheDefaultState() {
        assertNull("no sub-page is open on a fresh screen", screen().activePageId());
    }

    @Test
    public void everyPageIdRoundTrips() {
        SystemScreenView screen = screen();
        String[] ids = {SystemScreenView.PAGE_SETTINGS, SystemScreenView.PAGE_INSTALL,
                SystemScreenView.PAGE_BACKUP, SystemScreenView.PAGE_ABOUT};

        for (String id : ids) {
            screen.restorePage(id);
            assertEquals(id, screen.activePageId());
        }
    }

    @Test
    public void restoringTheBackupPageShowsItAndBackReturnsToTheHub() {
        SystemScreenView screen = screen();
        screen.restorePage(SystemScreenView.PAGE_BACKUP);

        assertEquals(View.VISIBLE, screen.findViewById(R.id.system_backup_page).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.system_hub_pane).getVisibility());

        assertTrue("Back steps from a sub-page to the hub", screen.navigateBack());
        assertNull(screen.activePageId());
        assertEquals(View.VISIBLE, screen.findViewById(R.id.system_hub_pane).getVisibility());
    }

    @Test
    public void aStaleIdLeavesTheHubVisible() {
        SystemScreenView screen = screen();
        screen.restorePage("some-page-from-an-older-build");

        assertNull(screen.activePageId());
        assertEquals(View.VISIBLE, screen.findViewById(R.id.system_hub_pane).getVisibility());
    }
}
