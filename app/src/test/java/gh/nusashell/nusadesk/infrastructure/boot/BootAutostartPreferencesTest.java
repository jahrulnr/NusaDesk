package gh.nusashell.nusadesk.infrastructure.boot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The opt-in store contract (ADR-0037): default OFF, the toggle round-trips
 * through its own {@code boot_autostart} file, and a fresh instance — what
 * the boot receiver constructs — reads what the system screen wrote.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class BootAutostartPreferencesTest {

    @Test
    public void defaultsToDisabled() {
        assertFalse("a stock install must never wake at boot",
                preferences().enabled());
    }

    @Test
    public void setEnabledRoundTripsAcrossInstances() {
        BootAutostartPreferences preferences = preferences();
        preferences.setEnabled(true);
        assertTrue(preferences().enabled());
        preferences.setEnabled(false);
        assertFalse(preferences().enabled());
    }

    @Test
    public void livesInItsOwnPreferencesFile() {
        preferences().setEnabled(true);
        SharedPreferences direct = RuntimeEnvironment.getApplication()
                .getSharedPreferences("boot_autostart", Context.MODE_PRIVATE);
        assertTrue("the toggle writes the boot_autostart store, not another file",
                direct.getBoolean("enabled", false));
    }

    private static BootAutostartPreferences preferences() {
        return new BootAutostartPreferences(RuntimeEnvironment.getApplication());
    }
}
