package gh.nusashell.nusadesk.presentation.webapp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import gh.nusashell.nusadesk.application.webapp.WebAppCommandSession;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsBus;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsRegistry;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppRestartSessions;
import gh.nusashell.nusadesk.presentation.DesktopDestination;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView;
import gh.nusashell.nusadesk.presentation.terminal.TerminalAppView;
import gh.nusashell.nusadesk.presentation.terminal.TerminalSurfaceScope;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The per-app command surfaces and the web-app server lifecycle (ADR-0072).
 *
 * <p>A command app's session is the window: each registered terminal-command
 * app — and each web app's own startup command — gets one retained
 * {@link TerminalAppView} scoped to it, never a tab inside the built-in
 * terminal (ADR-0054, isolation amendment). This class owns that retained
 * surface set and the surface-switch that shows one; the shell keeps the
 * navigation state it records through {@link Host#commandSurfaceShown}.</p>
 *
 * <p>It also owns the restart watching for a web app's server: a restart is
 * requested through the process-lived {@link WebAppRestartSessions}, and the
 * completion either re-opens the still-visible app or reports the failure on
 * its surface.</p>
 */
public final class WebAppServerCoordinator {

    /**
     * The navigation and registry access the owning shell keeps. Every
     * callback is invoked on the UI thread.
     */
    public interface Host {
        /** The registry's current definition for an id, or {@code null} when it is gone. */
        WebAppDefinition definitionFor(String webAppId);

        /** Opens one user web app surface. */
        void showWebApp(WebAppDefinition definition);

        /** Shows the launcher. */
        void showShell();

        /** Settles the soft keyboard for a surface about to be shown. */
        void settleKeyboardFor(View surface);

        /** Re-renders one command surface's options menu. */
        void renderTerminalAppMenu(String appId, TerminalTabsSnapshot snapshot);

        /** Records {@code id} as the visible command surface. */
        void commandSurfaceShown(String id);

        /** True while the {@code id} command surface is the visible one. */
        boolean isCommandSurfaceActive(String id);

        /** True while {@code webAppId}'s surface is the visible one. */
        boolean isShowingWebApp(String webAppId);

        /** True once the owning Activity was destroyed. */
        boolean isDestroyed();
    }

    private final Context context;
    private final DesktopHomeView desktopHome;
    private final AppSurfaceHostView appSurfaceHost;
    private final Map<DesktopDestination, View> fixedSurfaces;
    private final Map<String, WebAppSurfaceView> webAppSurfaces;
    private final Map<String, TerminalAppView> commandSurfaces;
    private final Host host;
    private final Map<String, CompletableFuture<Void>> watchedRestarts = new HashMap<>();

    public WebAppServerCoordinator(Context context, DesktopHomeView desktopHome,
            AppSurfaceHostView appSurfaceHost,
            Map<DesktopDestination, View> fixedSurfaces,
            Map<String, WebAppSurfaceView> webAppSurfaces,
            Map<String, TerminalAppView> commandSurfaces, Host host) {
        this.context = context;
        this.desktopHome = desktopHome;
        this.appSurfaceHost = appSurfaceHost;
        this.fixedSurfaces = fixedSurfaces;
        this.webAppSurfaces = webAppSurfaces;
        this.commandSurfaces = commandSurfaces;
        this.host = host;
    }

    /** Shows one app-scoped command surface, hiding every other surface. */
    public void showCommandSurface(String id, String title, TerminalAppView surface) {
        host.settleKeyboardFor(surface);
        host.commandSurfaceShown(id);
        desktopHome.setVisibility(View.GONE);
        appSurfaceHost.setVisibility(View.VISIBLE);
        for (View other : fixedSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (WebAppSurfaceView other : webAppSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (TerminalAppView other : commandSurfaces.values()) {
            other.setVisibility(other == surface ? View.VISIBLE : View.GONE);
        }
        appSurfaceHost.setAppTitle(title);
        host.renderTerminalAppMenu(id, TerminalTabsBus.getInstance().current());
        // A terminal session is not reloadable content; no refresh action.
        appSurfaceHost.setRefreshAction(null);
        appSurfaceHost.setMinimizeAction(null);
        appSurfaceHost.setCloseAction(null);
    }

    /** The terminal carrying one web app's own server command, as its window. */
    public void showWebAppTerminal(WebAppDefinition app) {
        String id = WebAppCommandSession.ownerId(app.getId());
        showCommandSurface(id, app.getDisplayName(), commandSurfaceFor(id));
    }

    /**
     * Restarts one app's server command and re-shows its surface: the page
     * waits behind the "server starting" state until the restart future
     * settles.
     */
    public void restartWebAppServer(WebAppDefinition app) {
        CompletableFuture<Void> future = WebAppRestartSessions.get(context).restart(app);
        host.showWebApp(app);
        watchServerRestart(app, future);
    }

    /**
     * Watches one restart future. The same future is watched once; on success
     * a still-visible app is re-opened against its fresh server, on failure
     * the surface shows it. A stale app — edited or deleted mid-restart — is
     * ignored.
     */
    public void watchServerRestart(WebAppDefinition app, CompletableFuture<Void> future) {
        String id = app.getId().value();
        if (watchedRestarts.get(id) == future) {
            return;
        }
        watchedRestarts.put(id, future);
        future.whenComplete((unused, error) -> {
            watchedRestarts.remove(id);
            if (host.isDestroyed() || !app.equals(host.definitionFor(id))) {
                return;
            }
            WebAppSurfaceView surface = webAppSurfaces.get(id);
            if (surface == null) {
                return;
            }
            if (error != null) {
                surface.showStartupFailure(error.getMessage());
            } else if (host.isShowingWebApp(id)) {
                host.showWebApp(app);
            }
        });
    }

    /** Creates or reuses the retained surface for one command scope id. */
    public TerminalAppView commandSurfaceFor(String id) {
        TerminalAppView surface = commandSurfaces.get(id);
        if (surface == null) {
            surface = new TerminalAppView(context);
            surface.setTabScope(TerminalSurfaceScope.commandApp(id));
            surface.setOnGoToDesktopListener(view -> host.showShell());
            surface.setOnRestartLinuxListener(view -> RuntimeHostService.requestRestart(context));
            surface.setOnTabsChangedListener(snapshot -> {
                if (host.isCommandSurfaceActive(id)) {
                    host.renderTerminalAppMenu(id, snapshot);
                }
            });
            surface.setTerminalDependencies(
                    TerminalTabsRegistry.getInstance(), TerminalTabsBus.getInstance());
            commandSurfaces.put(id, surface);
            appSurfaceHost.getSurfaceContainer().addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return surface;
    }
}
