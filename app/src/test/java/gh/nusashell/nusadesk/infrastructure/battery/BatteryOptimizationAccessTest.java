package gh.nusashell.nusadesk.infrastructure.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPowerManager;

/**
 * Pins the Battery card's platform surface on the JVM: the exact action and
 * package URI of the exemption request, the action-only generic settings
 * fallback, and the state probe reporting the platform's own answer —
 * including "not exempt" when the probe itself fails, because a state row must
 * never take the screen down.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class BatteryOptimizationAccessTest {

    @Test
    public void requestExemptionIntentTargetsThisAppsRequestDialog() {
        Intent intent = access().requestExemptionIntent();

        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                intent.getAction());
        assertEquals(Uri.parse("package:gh.nusashell.nusadesk"), intent.getData());
    }

    @Test
    public void optimizationSettingsIntentTargetsTheGenericListScreen() {
        Intent intent = access().optimizationSettingsIntent();

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                intent.getAction());
        assertNull("the generic list screen carries no package data",
                intent.getData());
    }

    @Test
    public void isExemptReflectsThePlatformAnswer() {
        Context app = RuntimeEnvironment.getApplication();
        assertFalse("a fresh install is not exempt", access().isExempt());

        PowerManager power =
                (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        Shadow.<ShadowPowerManager>extract(power)
                .setIgnoringBatteryOptimizations(app.getPackageName(), true);

        assertTrue("a platform-granted exemption must be reported",
                access().isExempt());
    }

    @Test
    public void isExemptReportsNotExemptWhenThePlatformProbeThrows() {
        // The class stores the application context, so the throwing wrapper
        // must stay the stored context: getApplicationContext() is overridden
        // to return the wrapper itself, then getSystemService(POWER_SERVICE)
        // stands in for the incomplete platform state observed on some builds.
        Context throwing = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public Object getSystemService(String name) {
                if (Context.POWER_SERVICE.equals(name)) {
                    throw new RuntimeException("incomplete platform power state");
                }
                return super.getSystemService(name);
            }
        };

        assertFalse("a failed probe must answer not-exempt, never crash",
                new BatteryOptimizationAccess(throwing).isExempt());
    }

    private static BatteryOptimizationAccess access() {
        return new BatteryOptimizationAccess(RuntimeEnvironment.getApplication());
    }
}
