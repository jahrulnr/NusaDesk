package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertEquals;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins the exact action and package URI of the System card's app-permissions
 * shortcut, so the Android App Info page the user lands on is fixed by a test
 * instead of by whichever device read the source.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class AppSettingsShortcutIntentTest {

    @Test
    public void theShortcutTargetsThisAppsAppInfoPage() {
        Intent intent = MainActivity.appDetailsSettingsIntent("gh.nusashell.nusadesk");

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
        assertEquals(Uri.parse("package:gh.nusashell.nusadesk"), intent.getData());
    }
}