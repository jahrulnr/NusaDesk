package gh.nusashell.nusadesk.presentation.display;

import android.content.Context;
import android.util.Log;
import android.view.View;

import gh.nusashell.nusadesk.application.display.LinuxDesktopCatalog;
import gh.nusashell.nusadesk.domain.display.DesktopTask;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import gh.nusashell.nusadesk.infrastructure.display.GuestDesktopCatalog;
import gh.nusashell.nusadesk.infrastructure.display.NativeDesktopSession;
import gh.nusashell.nusadesk.infrastructure.display.X11Surface;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.presentation.DesktopDestination;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Activity-lifecycle glue of the shipped native desktop (ADR-0068, 0069,
 * 0070).
 *
 * <p>Owns the pieces that follow the Activity's foreground edge — the guest
 * desktop-entry catalog and its refresh on runtime start, the launcher's
 * live-task badges, and the {@code NATIVE_GUI} surface's construction — while
 * the shell keeps navigation. {@link #start()} and {@link #stop()} pair the
 * foreground callbacks; the catalog listens to {@link RuntimeStatusBus} so a
 * runtime that comes up while the launcher is visible fills the grid without
 * another user action.</p>
 */
public final class NativeDesktopHostController {

    /** The navigation the owning shell keeps for this surface. */
    public interface Host {
        /** Shows one fixed surface. */
        void showSurface(DesktopDestination destination);

        /** Shows the launcher. */
        void showShell();

        /** True while {@code destination} is the visible surface. */
        boolean isShowing(DesktopDestination destination);
    }

    private final Context context;
    private final DesktopHomeView desktopHome;
    private final AppSurfaceHostView appSurfaceHost;
    private final Host host;

    private final Consumer<List<DesktopTask>> nativeTasksListener = this::renderNativeTasks;
    private final RuntimeStatusBus.Listener desktopCatalogStatus = status -> {
        if (status.isRuntimeRunning()) {
            refreshLinuxApps();
        }
    };
    private LinuxDesktopCatalog linuxDesktopCatalog;

    public NativeDesktopHostController(Context context, DesktopHomeView desktopHome,
            AppSurfaceHostView appSurfaceHost, Host host) {
        this.context = context;
        this.desktopHome = desktopHome;
        this.appSurfaceHost = appSurfaceHost;
        this.host = host;
    }

    /** Foreground half: owns the catalog and the task-badge subscription. */
    public void start() {
        linuxDesktopCatalog = new GuestDesktopCatalog(context);
        RuntimeStatusBus.getInstance().register(desktopCatalogStatus);
        refreshLinuxApps();
        NativeDesktopSession.get(context).subscribeTasks(nativeTasksListener);
    }

    /** Mirrors {@link #start()}: unsubscribes and closes the catalog. */
    public void stop() {
        RuntimeStatusBus.getInstance().unregister(desktopCatalogStatus);
        NativeDesktopSession.get(context).unsubscribeTasks(nativeTasksListener);
        if (linuxDesktopCatalog != null) {
            linuxDesktopCatalog.close();
            linuxDesktopCatalog = null;
        }
    }

    /** Re-scans installed guest desktop entries into the launcher grid. */
    public void refreshLinuxApps() {
        if (linuxDesktopCatalog == null) {
            return;
        }
        linuxDesktopCatalog.refresh((apps, error) -> {
            if (error == null) {
                desktopHome.setLinuxApps(apps);
            } else {
                Log.w("NusaDeskGUI", "Desktop scan: " + error);
            }
        });
    }

    private void renderNativeTasks(List<DesktopTask> tasks) {
        desktopHome.setRunningLinuxApps(tasks);
    }

    /** Opens a discovered Linux app: selects its task, shows the surface, opens it. */
    public void openLinuxApp(LinuxDesktopApp app) {
        NativeDesktopSession session = NativeDesktopSession.get(context);
        session.selectApp(app);
        host.showSurface(DesktopDestination.NATIVE_GUI);
        appSurfaceHost.setAppTitle(app.getName());
        session.open();
    }

    /**
     * Gives the shared task bar the native surface's app name and its two
     * window actions: minimize keeps the task's channel and document, close
     * asks the application itself to exit. Only valid for the
     * {@code NATIVE_GUI} destination.
     */
    public void wireSurfaceActions() {
        NativeDesktopSession session = NativeDesktopSession.get(context);
        LinuxDesktopApp selected = session.selectedApp();
        if (selected != null) {
            appSurfaceHost.setAppTitle(selected.getName());
        }
        appSurfaceHost.setMinimizeAction(() -> {
            session.minimize();
            host.showShell();
        });
        appSurfaceHost.setCloseAction(session::closeSelected);
    }

    /** Builds the retained native surface: X11 renderer plus its status/orb view. */
    public View createSurface() {
        NativeDesktopSession session = NativeDesktopSession.get(context);
        X11Surface output = new X11Surface(context);
        session.attach(output);
        NativeDesktopAppView gui = new NativeDesktopAppView(context, session, output, output);
        gui.setOnDisposed(() -> session.detach(output));
        gui.setOnAppClosed(() -> {
            if (host.isShowing(DesktopDestination.NATIVE_GUI)) {
                host.showShell();
            }
        });
        return gui;
    }
}
