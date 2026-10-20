package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowInputMethodManager;

import java.lang.reflect.Method;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.presentation.desktop.AppFormView;
import gh.nusashell.nusadesk.presentation.system.SystemScreenView;

/**
 * Guards the ADR-0065 keyboard settlement: every surface switch drops the IME,
 * whichever way it was raised. The shadow cannot prove a real OEM keyboard
 * closes — it only records the {@code hideSoftInputFromWindow} call — so the
 * device rows in {@code docs/test-plan.md} remain the real evidence for token
 * routing. What the JVM proves here is the part a source contract cannot: the
 * call is actually made on the transition, including the path where nothing
 * holds view focus (the WebView case) and the path back to the launcher that
 * previously dismissed nothing at all.
 *
 * <p>Two environment caveats. The launcher grid only renders entries while the
 * runtime reports READY, which no Robolectric environment produces, so the
 * form is opened through {@code openAppForm} — the same method the Add tile's
 * listener calls. And a shadow window never takes window focus, so
 * {@code getCurrentFocus()} stays null here even when a view has focus; the
 * focused-token branch of {@code settleKeyboardFor} is exercised the same way
 * the null-focus branch is, and real device runs distinguish the two.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31})
public class MainActivityKeyboardTest {

    @Before
    public void keepUpdateCheckHermetic() throws Exception {
        // A fresh process always polls GitHub once (ADR-0046); pin the check as
        // already consumed plus a fresh timestamp so the test makes no network.
        java.lang.reflect.Field consumed =
                MainActivity.class.getDeclaredField("processStartCheckConsumed");
        consumed.setAccessible(true);
        consumed.setBoolean(null, true);
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("update_check", 0)
                .edit()
                .putLong("last_check_at", System.currentTimeMillis())
                .apply();
    }

    @Test
    public void backFromTheFormSettlesTheKeyboard() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        openAddAppForm(activity);
        AppFormView form = findSurface(activity, AppFormView.class);
        assertNotNull("the form is hosted", form);
        View field = form.findFocus();
        assertNotNull("the form focused its first field", field);
        InputMethodManager inputMethodManager =
                activity.getSystemService(InputMethodManager.class);
        inputMethodManager.showSoftInput(field, 0);
        ShadowInputMethodManager shadow = Shadow.extract(inputMethodManager);
        assertTrue(shadow.isSoftInputVisible());

        activity.onBackPressed();

        assertFalse("returning to the launcher settles the keyboard",
                shadow.isSoftInputVisible());
    }

    @Test
    public void surfaceSwitchSettlesAKeyboardThatOwnsNoFocusedView() throws Exception {
        // The WebView case: an IME raised for a page field can outlive the
        // view's focus, so getCurrentFocus() is null while the keyboard is up.
        // The settle must still reach a window token — the decor view's — or
        // the keyboard rides across the transition.
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        InputMethodManager inputMethodManager =
                activity.getSystemService(InputMethodManager.class);
        inputMethodManager.showSoftInput(activity.getWindow().getDecorView(), 0);
        ShadowInputMethodManager shadow = Shadow.extract(inputMethodManager);
        assertTrue(shadow.isSoftInputVisible());
        assertNull(activity.getCurrentFocus());

        openAddAppForm(activity);

        assertFalse("a surface switch settles the keyboard without a focused view",
                shadow.isSoftInputVisible());
    }

    @Test
    public void aNoInputSurfaceHoldsNoStrayFocus() throws Exception {
        // A surface declaring ownsTextInput() == false gets its focus swept on
        // entry. No shipped control there is focusable in touch mode, so the
        // staging marks the surface itself — the check is that the sweep
        // runs, which is the guard for a no-input surface that later gains a
        // focusable view.
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        showSurface(activity, DesktopDestination.SYSTEM);
        SystemScreenView system = findSurface(activity, SystemScreenView.class);
        assertNotNull("the system surface is hosted", system);
        system.setFocusable(true);
        system.setFocusableInTouchMode(true);
        assertTrue(system.requestFocus());
        assertNotNull(system.findFocus());

        activity.onBackPressed();
        showSurface(activity, DesktopDestination.SYSTEM);

        assertNull("re-entering a no-input surface sweeps stray focus",
                system.findFocus());
    }

    @Test
    public void theFormOpensWithItsFirstFieldFocused() throws Exception {
        // The ordering contract of settleKeyboardFor: it runs before the
        // surface's own focus setup, so input-owning surfaces still get their
        // field focused — the keyboard just starts closed.
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        openAddAppForm(activity);

        AppFormView form = findSurface(activity, AppFormView.class);
        assertNotNull("the form is hosted", form);
        assertNotNull("the form owns its first field's focus",
                form.findFocus());
    }

    private static void openAddAppForm(MainActivity activity) throws Exception {
        Method open = MainActivity.class.getDeclaredMethod(
                "openAppForm", WebAppDefinition.class, TerminalCommandApp.class);
        open.setAccessible(true);
        open.invoke(activity, null, null);
    }

    private static void showSurface(MainActivity activity, DesktopDestination destination)
            throws Exception {
        Method show = MainActivity.class.getDeclaredMethod(
                "showSurface", DesktopDestination.class);
        show.setAccessible(true);
        show.invoke(activity, destination);
    }

    private static <T extends View> T findSurface(View root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findSurface(group.getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends View> T findSurface(MainActivity activity, Class<T> type) {
        return findSurface(activity.getWindow().getDecorView(), type);
    }
}
