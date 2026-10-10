package gh.nusashell.nusadesk.presentation.desktop;

import static org.junit.Assert.assertEquals;

import android.view.View;
import android.widget.ImageButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.R;

/**
 * The task bar's reload action (ADR-0061) under Robolectric: it exists only
 * while a surface actually offers one, it can be taken away again when the bar
 * switches to a surface that cannot be reloaded, and a stale click cannot reach
 * an action the bar no longer shows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AppSurfaceHostViewRefreshTest {

    @Test
    public void theReloadActionIsHiddenUntilASurfaceOffersOne() {
        AppSurfaceHostView host = host();

        assertEquals("a bar with no reloadable surface shows no reload action",
                View.GONE, refresh(host).getVisibility());

        host.setRefreshAction(null);

        assertEquals(View.GONE, refresh(host).getVisibility());
    }

    @Test
    public void theReloadActionIsVisibleAndRunsTheSurfaceReload() {
        AppSurfaceHostView host = host();
        int[] reloads = {0};

        host.setRefreshAction(() -> reloads[0]++);

        ImageButton refresh = refresh(host);
        assertEquals(View.VISIBLE, refresh.getVisibility());
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.taskbar_refresh_desc),
                refresh.getContentDescription().toString());
        refresh.performClick();
        assertEquals("the action is the surface's own reload", 1, reloads[0]);
    }

    @Test
    public void takingTheActionAwayDetachesIt() {
        AppSurfaceHostView host = host();
        int[] reloads = {0};
        host.setRefreshAction(() -> reloads[0]++);

        host.setRefreshAction(null);
        refresh(host).performClick();

        assertEquals("a surface that cannot be reloaded keeps no live action",
                0, reloads[0]);
        assertEquals(View.GONE, refresh(host).getVisibility());
    }

    private AppSurfaceHostView host() {
        return new AppSurfaceHostView(RuntimeEnvironment.getApplication());
    }

    private ImageButton refresh(AppSurfaceHostView host) {
        return host.findViewById(R.id.taskbar_refresh);
    }
}
