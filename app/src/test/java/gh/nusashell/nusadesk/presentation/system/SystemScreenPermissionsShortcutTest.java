package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;

import gh.nusashell.nusadesk.R;

/**
 * The System screen's app-permissions shortcut stays a real, labelled button
 * whose tap reaches the host. Verified on the same API levels the launch guard
 * covers, because the custom view is inflated through real framework code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class SystemScreenPermissionsShortcutTest {

    private static SystemScreenView screen() {
        Context context = RuntimeEnvironment.getApplication();
        return new SystemScreenView(context);
    }

    @Test
    public void theShortcutIsARenderedButtonWithVisibleText() {
        SystemScreenView screen = screen();
        Button button = screen.findViewById(R.id.system_permissions_action);

        assertEquals(screen.getContext().getString(R.string.system_permissions_action),
                button.getText().toString());
        assertTrue(button.getVisibility() == android.view.View.VISIBLE);
    }

    @Test
    public void tappingTheButtonReachesTheHostListener() {
        SystemScreenView screen = screen();
        AtomicBoolean reached = new AtomicBoolean(false);

        screen.setOnOpenAppSettingsListener(view -> reached.set(true));
        Button button = screen.findViewById(R.id.system_permissions_action);

        button.performClick();

        assertTrue("the tapped button must reach the wired host listener", reached.get());
    }

    @Test
    public void theOtherCardsStillKeepTheirButtons() {
        SystemScreenView screen = screen();
        Button how = screen.findViewById(R.id.system_how_button);
        Button workspace = screen.findViewById(R.id.system_workspace_action);

        assertFalse("the How it works button must survive the new card",
                how == null);
        assertFalse("the workspace action must survive the new card",
                workspace == null);
    }
}