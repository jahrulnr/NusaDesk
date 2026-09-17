package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Cross-API-level launch guard.
 *
 * <p>Robolectric executes the real framework classes of each requested API
 * level on the JVM, so this single test covers Android 10 through 13 in the
 * normal unit-test task. It exists because the status-bar work in
 * {@code MainActivity.applySystemBars()} once crashed the whole app on Android
 * 11/12 only: {@code Window.getInsetsController()} dereferences a decor view
 * that does not exist yet during {@code onCreate}, and the identical code path
 * is unreachable on Android 10.</p>
 *
 * <p>{@link #activityCreatesOnEverySupportedApiLevel()} therefore builds the
 * real Activity through {@code onCreate}. A future API-level regression in the
 * window/insets setup fails here without any device attached.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class MainActivityApiLevelLaunchTest {

    @Test
    public void activityCreatesOnEverySupportedApiLevel() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);

        controller.create();

        assertNotNull("onCreate must complete on every supported API level",
                controller.get());
    }
}
