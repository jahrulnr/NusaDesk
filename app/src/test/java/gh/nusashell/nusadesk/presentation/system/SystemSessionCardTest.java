package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsRegistry;
import gh.nusashell.nusadesk.presentation.DesktopDestination;
import gh.nusashell.nusadesk.presentation.MainActivity;

/**
 * The Settings page's Linux session card (ADR-0085): always visible, reports
 * the live runtime state with the session badge vocabulary, and its Restart
 * Linux action reaches {@link RuntimeHostService#ACTION_RESTART} — the entry
 * point the terminal and command-app overflow menus no longer carry.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SystemSessionCardTest {

    @Before public void setup() throws Exception {
        Field f = MainActivity.class.getDeclaredField("processStartCheckConsumed");
        f.setAccessible(true);
        f.setBoolean(null, true);
        RuntimeEnvironment.getApplication().getSharedPreferences("update_check", 0).edit()
                .putLong("last_check_at", System.currentTimeMillis()).commit();
        TerminalTabsRegistry.getInstance().clear();
    }

    @Test public void tappingRestartLinuxOnTheSettingsCardSendsActionRestart() throws Exception {
        var lifecycle = Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = lifecycle.get();
            ShadowApplication shadow = Shadow.extract(RuntimeEnvironment.getApplication());
            while (shadow.getNextStartedService() != null) { }
            showSurface(activity, DesktopDestination.SYSTEM);
            activity.findViewById(R.id.system_hub_settings_row).performClick();
            Button action = activity.findViewById(R.id.system_session_card_action);
            assertEquals(View.VISIBLE, action.getVisibility());
            assertEquals(activity.getString(R.string.system_session_restart),
                    action.getText().toString());

            action.performClick();

            Intent intent = shadow.getNextStartedService();
            assertNotNull("the card tap must reach the host service", intent);
            assertEquals(RuntimeHostService.ACTION_RESTART, intent.getAction());
        } finally {
            lifecycle.destroy();
        }
    }

    @Test public void theCardReportsThePublishedSessionStateAndKeepsItsAction() {
        SystemScreenView screen = new SystemScreenView(RuntimeEnvironment.getApplication());
        TextView value = screen.findViewById(R.id.system_session_card_value);
        Button action = screen.findViewById(R.id.system_session_card_action);

        screen.renderSessionStatus(new HostRuntimeStatus(new SessionSnapshot("s",
                "ubuntu-base-arm64", "1", SessionState.RUNNING,
                new gh.nusashell.nusadesk.domain.network.RuntimePort("127.0.0.1", 22022),
                1, 2, "", 0), true, "test"));
        assertEquals(screen.getContext().getString(R.string.session_badge_running),
                value.getText().toString());
        assertEquals(View.VISIBLE, action.getVisibility());

        screen.renderSessionStatus(new HostRuntimeStatus(new SessionSnapshot("s",
                "ubuntu-base-arm64", "1", SessionState.FAILED, null,
                1, 2, "guest stopped", 0), true, "test"));
        assertEquals(screen.getContext().getString(R.string.session_badge_failed),
                value.getText().toString());
        assertEquals("a failed session is exactly when Restart stays offered",
                View.VISIBLE, action.getVisibility());

        screen.renderSessionStatus(null);
        assertEquals(screen.getContext().getString(R.string.session_badge_stopped),
                value.getText().toString());
        assertTrue("the card's detail names what restarting does",
                ((TextView) screen.findViewById(R.id.system_session_card_detail))
                        .getText().toString().contains("Linux session"));
    }

    private static void showSurface(MainActivity activity, DesktopDestination destination)
            throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("showSurface", DesktopDestination.class);
        method.setAccessible(true);
        method.invoke(activity, destination);
    }
}
