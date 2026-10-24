package gh.nusashell.nusadesk.presentation.display;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.display.DesktopTask;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NativeTaskControlsTest {
    @Test public void minimizeIsContextualAndInvokesTheProvidedTaskAction() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AppSurfaceHostView host = new AppSurfaceHostView(activity);
        View button = host.findViewById(R.id.taskbar_minimize);
        assertEquals(View.GONE, button.getVisibility());
        AtomicInteger calls = new AtomicInteger(); host.setMinimizeAction(calls::incrementAndGet);
        assertEquals(View.VISIBLE, button.getVisibility()); button.performClick(); assertEquals(1, calls.get());
        host.setMinimizeAction(null); assertEquals(View.GONE, button.getVisibility());
    }
    @Test public void taskBadgeUpdatesOnTheSameIconAndClearsWhenTheAppExits() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        gh.nusashell.nusadesk.presentation.desktop.LauncherTileView tile =
                new gh.nusashell.nusadesk.presentation.desktop.LauncherTileView(activity);
        LinuxDesktopApp app = new LinuxDesktopApp("editor.desktop", "Editor", "/usr/share/applications/editor.desktop", null);
        for (DesktopTask.State state : DesktopTask.State.values()) {
            tile.bind(gh.nusashell.nusadesk.presentation.desktop.LauncherEntry.linuxApp(app, state), true, null);
            android.widget.TextView badge = tile.findViewById(R.id.tile_task_badge);
            assertEquals(View.VISIBLE, badge.getVisibility());
            assertEquals("linux:editor.desktop", tile.getEntry().getId());
            assertSame(app, tile.getEntry().getLinuxApp());
            assertTrue(tile.getContentDescription().toString().contains("Editor"));
            String glyph = state == DesktopTask.State.MINIMIZED ? "−" : state == DesktopTask.State.RUNNING ? "•" : state == DesktopTask.State.STARTING ? "…" : "!";
            assertEquals(glyph, badge.getText().toString());
            assertEquals(10 * activity.getResources().getDisplayMetrics().density, badge.getTranslationX(), 0.01);
            assertEquals(-10 * activity.getResources().getDisplayMetrics().density, badge.getTranslationY(), 0.01);
            assertFalse(tile.getClipChildren());
            assertFalse(((ViewGroup) badge.getParent()).getClipChildren());
            if (state == DesktopTask.State.MINIMIZED) {
                assertEquals("−", badge.getText().toString());
                assertTrue(tile.getContentDescription().toString().contains("Minimized"));
            }
        }
        tile.bind(gh.nusashell.nusadesk.presentation.desktop.LauncherEntry.linuxApp(app), true, null);
        assertEquals(View.GONE, tile.findViewById(R.id.tile_task_badge).getVisibility());
        assertFalse(tile.getContentDescription().toString().contains("Minimized"));
    }
    @Test public void tappingTheBadgedIconOpensItsExistingAppIdentity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        gh.nusashell.nusadesk.presentation.desktop.LauncherGridView grid =
                new gh.nusashell.nusadesk.presentation.desktop.LauncherGridView(activity);
        LinuxDesktopApp app = new LinuxDesktopApp("editor.desktop", "Editor", "/usr/share/applications/editor.desktop", null);
        gh.nusashell.nusadesk.presentation.desktop.LauncherEntry entry =
                gh.nusashell.nusadesk.presentation.desktop.LauncherEntry.linuxApp(app, DesktopTask.State.MINIMIZED);
        AtomicReference<LinuxDesktopApp> selected = new AtomicReference<>();
        grid.setOnEntryOpenListener(item -> selected.set(item.getLinuxApp()));
        activity.setContentView(grid);
        grid.setEntries(Collections.singletonList(entry), true, Collections.emptyMap());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        ViewGroup row = (ViewGroup) grid.getChildAt(0);
        row.getChildAt(0).performClick();
        assertSame(app, selected.get());
    }
    @Test public void closeIsContextualAndRequestsTheAppAction() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AppSurfaceHostView host = new AppSurfaceHostView(activity);
        View close = host.findViewById(R.id.taskbar_close);
        assertEquals(View.GONE, close.getVisibility());
        AtomicInteger calls = new AtomicInteger(); host.setCloseAction(calls::incrementAndGet);
        assertEquals(View.VISIBLE, close.getVisibility()); close.performClick(); assertEquals(1, calls.get());
        host.setCloseAction(null); assertEquals(View.GONE, close.getVisibility());
    }
}
