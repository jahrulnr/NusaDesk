package gh.nusashell.nusadesk.presentation.display;

import static org.junit.Assert.*;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.Collections;
import gh.nusashell.nusadesk.application.display.LinuxDesktopCatalog;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import gh.nusashell.nusadesk.infrastructure.display.NativeDesktopSession;
import gh.nusashell.nusadesk.presentation.DesktopDestination;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView;

/** Pinned Linux entries resolve current metadata after the guest catalog reports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PinnedLinuxAppLaunchTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final Catalog catalog = new Catalog();
    private final Host host = new Host();
    private final NativeDesktopHostController controller = new NativeDesktopHostController(
            context, new DesktopHomeView(context), new AppSurfaceHostView(context), host);

    private static final class Catalog implements LinuxDesktopCatalog {
        Listener listener;
        public void refresh(Listener listener) { this.listener = listener; }
        public void close() { }
    }
    private static final class Host implements NativeDesktopHostController.Host {
        DesktopDestination destination;
        int opens;
        public void showSurface(DesktopDestination destination) {
            this.destination = destination;
            opens++;
        }
        public void showShell() { destination = DesktopDestination.HOME; }
        public boolean isShowing(DesktopDestination destination) {
            return this.destination == destination;
        }
    }
    private void connectCatalog() throws Exception {
        Field field = NativeDesktopHostController.class.getDeclaredField("linuxDesktopCatalog");
        field.setAccessible(true);
        field.set(controller, catalog);
    }
    private void reportCurrentEntry() {
        catalog.listener.onApps(Collections.singletonList(new LinuxDesktopApp(
                "editor.desktop", "Renamed Editor", "/root/.local/share/applications/editor.desktop",
                null)), null);
    }

    @Test public void coldPinWaitsForCatalogThenUsesCurrentPathAndName() throws Exception {
        controller.openPinnedLinuxApp("editor.desktop");
        assertEquals(DesktopDestination.HOME, host.destination);
        assertEquals(0, host.opens);
        connectCatalog();
        controller.refreshLinuxApps();
        reportCurrentEntry();
        assertEquals(DesktopDestination.NATIVE_GUI, host.destination);
        LinuxDesktopApp selected = NativeDesktopSession.get(context).selectedApp();
        assertEquals("Renamed Editor", selected.getName());
        assertEquals("/root/.local/share/applications/editor.desktop", selected.getPath());
        controller.refreshLinuxApps();
        reportCurrentEntry();
        assertEquals("the pending launch is consumed once", 1, host.opens);
    }

    @Test public void missingEntryDoesNotOpenTheOldDesktopFile() throws Exception {
        connectCatalog();
        controller.openPinnedLinuxApp("removed.desktop");
        reportCurrentEntry();
        assertEquals(DesktopDestination.HOME, host.destination);
        assertEquals(0, host.opens);
    }

    @Test public void userNavigationCancelsDeferredLaunch() throws Exception {
        controller.openPinnedLinuxApp("editor.desktop");
        controller.cancelPinnedLaunch();
        connectCatalog();
        controller.refreshLinuxApps();
        reportCurrentEntry();
        assertEquals(0, host.opens);
    }

    @Test public void scanFailureDoesNotLaunchLaterWithoutANewTap() throws Exception {
        connectCatalog();
        controller.openPinnedLinuxApp("editor.desktop");
        catalog.listener.onApps(null, "Scan failed");
        controller.refreshLinuxApps();
        reportCurrentEntry();
        assertEquals(0, host.opens);
    }
    @Test public void removedDesktopFileStillResumesItsLiveMinimizedTask() throws Exception {
        connectCatalog();
        LinuxDesktopApp live = new LinuxDesktopApp("editor.desktop", "Running Editor",
                "/root/.local/share/applications/editor.desktop", null);
        java.lang.reflect.Method tasks = NativeDesktopHostController.class.getDeclaredMethod(
                "renderNativeTasks", java.util.List.class);
        tasks.setAccessible(true);
        tasks.invoke(controller, Collections.singletonList(
                new gh.nusashell.nusadesk.domain.display.DesktopTask(live,
                        gh.nusashell.nusadesk.domain.display.DesktopTask.State.MINIMIZED, "")));
        controller.openPinnedLinuxApp("editor.desktop");
        catalog.listener.onApps(Collections.emptyList(), null);
        assertEquals("the tile still represents the retained live task", 1, host.opens);
        assertEquals("Running Editor", NativeDesktopSession.get(context).selectedApp().getName());
    }

    @Test public void removedDesktopFileDoesNotReviveAFailedTask() throws Exception {
        connectCatalog();
        LinuxDesktopApp failed = new LinuxDesktopApp("editor.desktop", "Failed Editor",
                "/root/.local/share/applications/editor.desktop", null);
        java.lang.reflect.Method tasks = NativeDesktopHostController.class.getDeclaredMethod(
                "renderNativeTasks", java.util.List.class);
        tasks.setAccessible(true);
        tasks.invoke(controller, Collections.singletonList(
                new gh.nusashell.nusadesk.domain.display.DesktopTask(failed,
                        gh.nusashell.nusadesk.domain.display.DesktopTask.State.FAILED, "missing")));
        controller.openPinnedLinuxApp("editor.desktop");
        catalog.listener.onApps(Collections.emptyList(), null);
        assertEquals(0, host.opens);
    }

}
