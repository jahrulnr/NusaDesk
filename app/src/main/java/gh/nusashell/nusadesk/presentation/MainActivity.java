package gh.nusashell.nusadesk.presentation;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeSnapshotReconciler;
import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestSshPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshDaemon;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidGuestSshAddonInstaller;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidRuntimeInstaller;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidRuntimeStateStore;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSecurityInitializer;
import gh.nusashell.nusadesk.infrastructure.webapp.SharedPreferencesWebAppStore;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppFaviconFetcher;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import gh.nusashell.nusadesk.presentation.desktop.DesktopApp;
import gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView;
import gh.nusashell.nusadesk.presentation.desktop.LauncherEntry;
import gh.nusashell.nusadesk.presentation.system.SystemScreenView;
import gh.nusashell.nusadesk.presentation.terminal.TerminalAppView;
import gh.nusashell.nusadesk.presentation.webapp.WebAppFormView;
import gh.nusashell.nusadesk.presentation.webapp.WebAppSurfaceView;
import gh.nusashell.nusadesk.presentation.widget.FoundationContractDialog;
import gh.nusashell.nusadesk.presentation.widget.InstallPhaseSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell host.
 *
 * <p>The launcher is the home surface: a search field, the {@code Add app}
 * action, the Linux surfaces this build ships, and the web apps the user
 * registered. Linux is background infrastructure (ADR-0013): this Activity
 * calls the idempotent {@link RuntimeHostService#ensureRunning} from an explicit
 * foreground event, and no surface — launcher, terminal, system, or web app —
 * renders a start or stop control. The foreground-service notification and its
 * Stop action remain the user-visible lifecycle, as Android requires.</p>
 *
 * <p>The host owns navigation, the install coordinator, the web-app registry,
 * and the probe executor for web-app readiness. It never runs runtime work or
 * makes security decisions locally: the WebView origin policy lives in
 * {@code WebAppWebViewBoundary}, and the terminal's endpoint lives in
 * {@code LocalSshSessionFactory}.</p>
 *
 * <p>App surfaces are created lazily on first open and then retained (switched
 * by visibility) so a live terminal keeps its WebView and scrollback, and a web
 * app keeps its page, across a trip back to the launcher.</p>
 */
public final class MainActivity extends Activity {
    private static final String STATE_DESTINATION = "shell.destination";
    private static final String STATE_WEB_APP = "shell.webApp";

    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService faviconExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);
    private final Map<DesktopDestination, View> fixedSurfaces =
            new EnumMap<>(DesktopDestination.class);
    private final Map<String, WebAppSurfaceView> webAppSurfaces = new LinkedHashMap<>();
    /** Decoded favicons by web-app id. In memory only; never written anywhere. */
    private final Map<String, Bitmap> favicons = new HashMap<>();
    /** The exact app each favicon request belongs to, so a stale result is dropped. */
    private final Map<String, String> faviconRequests = new HashMap<>();

    private DesktopHomeView desktopHome;
    private AppSurfaceHostView appSurfaceHost;
    private SystemScreenView systemScreen;
    private FoundationContractDialog contractDialog;

    private RuntimeStateStore stateStore;
    private RuntimeInstallationUseCase installer;
    private AndroidGuestSshAddonInstaller addonInstaller;
    private RuntimeCatalogEntry catalogEntry;
    private GuestSshPayloadProfile sshProfile;
    private WebAppRegistry webAppRegistry;
    private WebAppFaviconFetcher faviconFetcher;
    private Handler mainHandler;

    private RuntimeSnapshot currentSnapshot;
    private RuntimeSnapshot addonSnapshot;
    private GuestSshUiState guestSshState = GuestSshUiState.missing();
    private DesktopDestination activeDestination = DesktopDestination.HOME;
    private String activeWebAppId;
    private boolean activityStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apache MINA SSHD is not officially tested on Android; this one-time init
        // registers the Bouncy Castle provider and resolves missing user.home/user.dir
        // so the SSH bridge can load keys and select a provider on Android 10+.
        SshSecurityInitializer.initialize(this);
        // The terminal's accessory key row and the launcher's search field must
        // stay above the soft keyboard; the inset handling below consumes the
        // same inset on the API levels that report it instead of resizing.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        applySystemBars();
        setContentView(R.layout.activity_main);

        desktopHome = findViewById(R.id.desktop_home);
        appSurfaceHost = findViewById(R.id.app_surface_host);
        // Created up front so install and service state can be pushed into it
        // before the user ever opens it; it joins the surface container on first
        // open.
        systemScreen = new SystemScreenView(this);

        catalogEntry = CuratedRuntimeCatalog.ubuntuBaseArm64();
        sshProfile = CuratedRuntimeCatalog.guestSshAddon();
        stateStore = new AndroidRuntimeStateStore(this);
        installer = new AndroidRuntimeInstaller(this, stateStore);
        addonInstaller = new AndroidGuestSshAddonInstaller(this);
        webAppRegistry = new WebAppRegistry(new SharedPreferencesWebAppStore(this));
        faviconFetcher = new WebAppFaviconFetcher();
        mainHandler = new Handler(Looper.getMainLooper());
        contractDialog = new FoundationContractDialog(this);

        wireLauncher();
        wireAppSurfaceHost();
        wireSystemScreen();

        loadPersistedState();
        restoreShellState(savedInstanceState);
        refreshGuestSshState();
        refreshWebApps();
        applyWindowInsets();
        registerBackCallback();
        showRestoredShell();
    }

    /**
     * Registers the predictive-back handler on API 33+, where the platform no
     * longer routes a back gesture through {@link #onBackPressed()}.
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }

    /**
     * The one autostart path (ADR-0013). An explicit Activity foreground event is
     * the only thing that starts Linux, and the call is idempotent: a runtime that
     * is already starting or running is left alone. There is deliberately no boot
     * receiver, job, or alarm, and no in-app start control that could start a
     * second session or contradict this one.
     *
     * <p>The call is made only once the curated system is installed and its
     * terminal component is present, so a half-installed device never asks the
     * host to start a runtime it cannot run.</p>
     */
    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        continuePendingSetup();
        ensureRuntimeRunning();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        super.onStop();
    }

    /**
     * Requests the runtime host to ensure Linux is up, when the device is
     * actually ready for it. Never loops: one request per foreground event, and
     * a failure is reported by the launcher's readiness pill until the user
     * brings the app forward again.
     */
    private void ensureRuntimeRunning() {
        if (currentSnapshot == null || currentSnapshot.getState() != RuntimeState.READY) {
            return;
        }
        if (guestSshState.getKind() != GuestSshUiState.Kind.INSTALLED) {
            return;
        }
        RuntimeHostService.ensureRunning(this);
    }

    /**
     * The single setup pipeline's auto-continue path (ADR-0017). On an explicit
     * Activity foreground, if the rootfs is active but the add-on is still
     * missing — not failed, not installing, not installed — the same action
     * that starts a fresh install runs only the add-on. It is idempotent (the
     * install lock guards against a retry loop) and never starts a fresh
     * rootfs install: that still starts from the one button.
     */
    private void continuePendingSetup() {
        if (currentSnapshot == null || currentSnapshot.getState() != RuntimeState.READY) {
            return;
        }
        if (guestSshState.getKind() != GuestSshUiState.Kind.MISSING) {
            return;
        }
        startInstall();
    }

    @Override
    protected void onDestroy() {
        installExecutor.shutdownNow();
        probeExecutor.shutdownNow();
        faviconExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * Back contract: an open surface returns to the launcher, the launcher exits.
     * Without this, Back from a surface would leave the product instead of going
     * home.
     *
     * <p>Android 16+ dispatches back gestures through
     * {@code OnBackInvokedDispatcher} instead of {@code onBackPressed}, so the
     * same handler is registered there on API 33+. This override remains the
     * path for API 29–32, where no non-deprecated callback exists and the
     * project deliberately has no AndroidX dependency.</p>
     */
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    /** @return true when the shell consumed Back instead of leaving the product. */
    private boolean handleBack() {
        if (activeDestination == DesktopDestination.HOME && activeWebAppId == null) {
            return false;
        }
        showShell();
        return true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DESTINATION, activeDestination.name());
        outState.putString(STATE_WEB_APP, activeWebAppId);
    }

    /**
     * Forwards the image picker result to the form that asked for it. The
     * framework delivers activity results to the Activity, so this is the only
     * place that can route them; the form owns what the token means.
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != WebAppFormView.REQUEST_OPEN_IMAGE) {
            return;
        }
        View form = fixedSurfaces.get(DesktopDestination.ADD_WEB_APP);
        if (form instanceof WebAppFormView) {
            ((WebAppFormView) form).onImagePicked(resultCode, data);
        }
    }

    // ---- Wiring ----

    private void wireLauncher() {
        desktopHome.setOnInstallListener(view -> startInstall());
        desktopHome.setOnEntryOpenListener(this::openEntry);
        desktopHome.setOnEntryEditListener(entry -> openWebAppForm(entry.getWebApp()));
        desktopHome.setStorageRequirement(
                catalogEntry.getCompressedBytes() + catalogEntry.getUncompressedBytes());
    }

    private void wireAppSurfaceHost() {
        appSurfaceHost.setOnHomeListener(view -> showShell());
    }

    private void wireSystemScreen() {
        systemScreen.setRuntimeProfile(catalogEntry.getAppId(), catalogEntry.getVersion());
        systemScreen.setOnHowItWorksListener(view -> contractDialog.show());
    }

    private void restoreShellState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        String webAppId = savedInstanceState.getString(STATE_WEB_APP);
        if (webAppId != null && definitionFor(webAppId) != null) {
            activeWebAppId = webAppId;
            return;
        }
        String destination = savedInstanceState.getString(STATE_DESTINATION);
        if (destination == null) {
            return;
        }
        try {
            activeDestination = DesktopDestination.valueOf(destination);
        } catch (IllegalArgumentException ignored) {
            activeDestination = DesktopDestination.HOME;
        }
    }

    // ---- Navigation ----

    /**
     * Shows whatever the restored instance state asked for. A web app that was
     * deleted while the Activity was gone falls back to the launcher instead of
     * opening a stale surface.
     */
    private void showRestoredShell() {
        if (activeWebAppId != null) {
            WebAppDefinition definition = definitionFor(activeWebAppId);
            if (definition != null) {
                showWebApp(definition);
                return;
            }
        }
        if (activeDestination != DesktopDestination.HOME) {
            showSurface(activeDestination);
            return;
        }
        showShell();
    }

    /** Shows the launcher. */
    private void showShell() {
        activeDestination = DesktopDestination.HOME;
        activeWebAppId = null;
        desktopHome.setVisibility(View.VISIBLE);
        appSurfaceHost.setVisibility(View.GONE);
        requestMissingFavicons();
    }

    /** Shows one fixed surface (terminal, system, add/edit form). */
    private void showSurface(DesktopDestination destination) {
        dismissLauncherInput();
        activeDestination = destination;
        activeWebAppId = null;
        desktopHome.setVisibility(View.GONE);
        appSurfaceHost.setVisibility(View.VISIBLE);
        View surface = fixedSurfaceFor(destination);
        for (View other : fixedSurfaces.values()) {
            other.setVisibility(other == surface ? View.VISIBLE : View.GONE);
        }
        for (WebAppSurfaceView other : webAppSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        appSurfaceHost.setAppTitle(destination.getTitleRes());
        appSurfaceHost.setMenuActions(Collections.emptyList());
    }

    /** Shows one user web app surface, probing its endpoint before it loads. */
    private void showWebApp(WebAppDefinition definition) {
        dismissLauncherInput();
        activeDestination = DesktopDestination.HOME;
        activeWebAppId = definition.getId().value();
        desktopHome.setVisibility(View.GONE);
        appSurfaceHost.setVisibility(View.VISIBLE);
        WebAppSurfaceView surface = webAppSurfaceFor(definition);
        for (View other : fixedSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (WebAppSurfaceView other : webAppSurfaces.values()) {
            other.setVisibility(other == surface ? View.VISIBLE : View.GONE);
        }
        appSurfaceHost.setAppTitle(definition.getDisplayName());
        appSurfaceHost.setMenuActions(Collections.singletonList(
                new AppSurfaceHostView.MenuAction(
                        R.string.webapp_menu_edit, () -> openWebAppForm(definition))));
    }

    /**
     * Puts the launcher's search field away before another surface takes over.
     * Tapping a tile while the soft keyboard is open would otherwise leave the
     * keyboard covering the surface the user just opened.
     */
    private void dismissLauncherInput() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager inputMethodManager = getSystemService(InputMethodManager.class);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private void openEntry(LauncherEntry entry) {
        switch (entry.getKind()) {
            case ADD_APP:
                openWebAppForm(null);
                return;
            case WEB_APP:
                WebAppDefinition definition = definitionFor(entry.getId());
                if (definition != null) {
                    showWebApp(definition);
                } else {
                    // The tile was rendered from a definition that is gone;
                    // reconcile instead of opening a stale app.
                    refreshWebApps();
                }
                return;
            default:
                DesktopApp app = DesktopApp.fromId(entry.getId());
                if (app != null) {
                    showSurface(app.getDestination());
                }
        }
    }

    /**
     * Opens the add/edit form. The form is transient by design: a fresh instance
     * per open, so no stale field value or error line can survive into the next
     * app the user edits.
     */
    private void openWebAppForm(WebAppDefinition definition) {
        WebAppDefinition existing = definition == null
                ? null : definitionFor(definition.getId().value());
        View previous = fixedSurfaces.remove(DesktopDestination.ADD_WEB_APP);
        if (previous != null) {
            appSurfaceHost.getSurfaceContainer().removeView(previous);
        }
        showSurface(DesktopDestination.ADD_WEB_APP);
        View surface = fixedSurfaces.get(DesktopDestination.ADD_WEB_APP);
        if (!(surface instanceof WebAppFormView)) {
            return;
        }
        WebAppFormView form = (WebAppFormView) surface;
        if (existing == null) {
            form.bindNew();
        } else {
            form.bindExisting(existing);
            appSurfaceHost.setAppTitle(R.string.webapp_edit_title);
        }
    }

    private View fixedSurfaceFor(DesktopDestination destination) {
        View existing = fixedSurfaces.get(destination);
        if (existing != null) {
            return existing;
        }
        View created = createSurface(destination);
        fixedSurfaces.put(destination, created);
        appSurfaceHost.getSurfaceContainer().addView(created, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return created;
    }

    private View createSurface(DesktopDestination destination) {
        if (destination == DesktopDestination.TERMINAL) {
            return createTerminalSurface();
        }
        if (destination == DesktopDestination.ADD_WEB_APP) {
            return createWebAppForm();
        }
        return systemScreen;
    }

    /**
     * Builds the terminal surface. It has no target to choose: the local-only
     * factory fixes the endpoint, the credential, and the pinned-host-key-only
     * trust policy (ADR-0013).
     */
    private TerminalAppView createTerminalSurface() {
        TerminalAppView terminal = new TerminalAppView(this);
        terminal.setOnGoToDesktopListener(view -> showShell());
        terminal.setSshDependencies(
                new KeystoreVaultCredentialProvider(this),
                SshReconnectPolicy.DEFAULT,
                new SharedPreferencesHostKeyTrustStore(this),
                System::currentTimeMillis);
        return terminal;
    }

    private WebAppFormView createWebAppForm() {
        WebAppFormView form = new WebAppFormView(this, webAppRegistry);
        form.setListener(new WebAppFormView.Listener() {
            @Override
            public void onWebAppSaved(WebAppDefinition definition) {
                // The app may now point at a different endpoint, so an image
                // fetched from the old one must not stay on the tile.
                forgetFavicon(definition.getId().value());
                refreshWebApps();
                showShell();
            }

            @Override
            public void onWebAppDeleted(String webAppId) {
                discardWebAppSurface(webAppId);
                forgetFavicon(webAppId);
                refreshWebApps();
                showShell();
            }
        });
        return form;
    }

    /** Creates or reuses the retained surface for one registered web app. */
    private WebAppSurfaceView webAppSurfaceFor(WebAppDefinition definition) {
        String id = definition.getId().value();
        WebAppSurfaceView surface = webAppSurfaces.get(id);
        if (surface == null) {
            surface = new WebAppSurfaceView(this);
            // The launcher's own favicon attempt happens before the user has ever
            // opened the app, when its endpoint is usually still down. The surface
            // proves the endpoint answers, so this is the moment to ask again.
            surface.setOnReachabilityListener(this::retryFavicon);
            webAppSurfaces.put(id, surface);
            appSurfaceHost.getSurfaceContainer().addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        surface.bind(definition, probeExecutor);
        return surface;
    }

    /** Removes a deleted app's retained surface and releases its WebView. */
    private void discardWebAppSurface(String webAppId) {
        WebAppSurfaceView surface = webAppSurfaces.remove(webAppId);
        if (surface == null) {
            return;
        }
        appSurfaceHost.getSurfaceContainer().removeView(surface);
        surface.release();
    }

    /** The registry's current definition for an id, or {@code null} when it is gone. */
    private WebAppDefinition definitionFor(String webAppId) {
        if (webAppId == null) {
            return null;
        }
        for (WebAppDefinition definition : webAppRegistry.list()) {
            if (definition.getId().value().equals(webAppId)) {
                return definition;
            }
        }
        return null;
    }

    // ---- Favicons ----

    /**
     * Asks every registered app the user gave no image for its own endpoint's
     * favicon.
     *
     * <p>Called from the launcher's display, never from a render: the launcher
     * re-renders on every search keystroke, so a request per render would be a
     * request per keystroke. Each app is asked once — a request that succeeds is
     * remembered so the tile keeps its image, and one that finds nothing is not
     * repeated, because a missing favicon is not an error the user can act on.
     * An app that was not running yet gets its second, and only other, chance
     * from its own surface, which reports the endpoint reachable.</p>
     */
    private void requestMissingFavicons() {
        for (WebAppDefinition definition : webAppRegistry.list()) {
            requestFavicon(definition);
        }
    }

    /** Starts one fetch unless this exact app was already asked or already answered. */
    private void requestFavicon(WebAppDefinition definition) {
        if (definition.hasIcon()) {
            return; // the user's own image is primary and is never replaced
        }
        String webAppId = definition.getId().value();
        if (favicons.containsKey(webAppId)) {
            return;
        }
        String requestKey = faviconRequestKey(definition);
        if (requestKey.equals(faviconRequests.get(webAppId))) {
            return;
        }
        faviconRequests.put(webAppId, requestKey);
        faviconExecutor.execute(() -> {
            Bitmap favicon = faviconFetcher.fetch(definition);
            mainHandler.post(() -> applyFavicon(webAppId, requestKey, favicon));
        });
    }

    /** Asks one app again because its own surface just proved the endpoint answers. */
    private void retryFavicon(WebAppDefinition definition) {
        if (definition == null) {
            return;
        }
        faviconRequests.remove(definition.getId().value());
        requestFavicon(definition);
    }

    /**
     * Applies a fetched favicon, unless the app was edited or deleted while the
     * request was in flight: the image belongs to the endpoint it came from, not
     * to whatever now shares the id. A fetch that found nothing is dropped in
     * silence, so the tile simply keeps the monogram it already had.
     */
    private void applyFavicon(String webAppId, String requestKey, Bitmap favicon) {
        if (isDestroyed() || favicon == null
                || !requestKey.equals(faviconRequests.get(webAppId))) {
            return;
        }
        favicons.put(webAppId, favicon);
        desktopHome.setFavicons(new HashMap<>(favicons));
    }

    /** Drops an app's favicon and its attempt marker when the app changes or goes. */
    private void forgetFavicon(String webAppId) {
        favicons.remove(webAppId);
        faviconRequests.remove(webAppId);
        desktopHome.setFavicons(new HashMap<>(favicons));
    }

    /**
     * Identifies the exact app a favicon request belongs to. An edit changes it,
     * so an image fetched from the app's previous endpoint is never shown for the
     * new one.
     */
    private static String faviconRequestKey(WebAppDefinition definition) {
        return definition.getGuestPort() + ":" + definition.getUpdatedAtEpochMillis();
    }

    // ---- Install coordination ----

    private void loadPersistedState() {
        RuntimeSnapshot stored = stateStore.load(catalogEntry.getAppId());
        RuntimeSnapshot reconciled = RuntimeSnapshotReconciler.reconcile(stored);
        if (reconciled == null) {
            currentSnapshot = baseSnapshot(RuntimeState.NOT_INSTALLED);
        } else {
            currentSnapshot = reconciled;
            if (reconciled != stored) {
                stateStore.save(reconciled);
            }
        }
        renderState(currentSnapshot);
    }

    private void renderState(RuntimeSnapshot snapshot) {
        currentSnapshot = snapshot;
        desktopHome.render(snapshot);
        systemScreen.render(snapshot);
    }

    private void refreshWebApps() {
        List<WebAppDefinition> definitions = new ArrayList<>(webAppRegistry.list());
        desktopHome.setWebApps(definitions);
        systemScreen.setWebAppCount(definitions.size());
    }

    /**
     * The single setup pipeline (ADR-0017). One serialized orchestration on the
     * install executor: if the curated rootfs is not active on disk, install it;
     * when it succeeds, immediately install the guest-SSH add-on on the same
     * executor. If the rootfs is already active but the add-on is missing, the
     * same action runs only the add-on — a valid active rootfs is never
     * re-downloaded. No parallel installers, no duplicate tap.
     *
     * <p>The install lock ({@code installInProgress}) makes the action
     * idempotent: a second tap or an auto-continue while a pipeline is running
     * is a no-op. A rootfs failure throws before the add-on runs, so a failed
     * rootfs never leaves the add-on half-installed.</p>
     */
    private void startInstall() {
        if (!installInProgress.compareAndSet(false, true)) {
            return;
        }
        installExecutor.execute(() -> {
            try {
                if (!isRootfsActiveOnDisk()) {
                    installer.install(catalogEntry,
                            snapshot -> mainHandler.post(() -> onInstallSnapshot(snapshot)));
                }
                if (!isAddonActiveOnDisk()) {
                    addonInstaller.install(sshProfile, catalogEntry.getAppId(),
                            snapshot -> mainHandler.post(() -> onAddonSnapshot(snapshot)));
                }
            } catch (RuntimeInstallationException ignored) {
                // The installer has already persisted and published FAILED with its reason.
            } finally {
                mainHandler.post(() -> installInProgress.set(false));
            }
        });
    }

    private void onInstallSnapshot(RuntimeSnapshot snapshot) {
        renderState(snapshot);
        refreshGuestSshState();
        if (snapshot.getState() == RuntimeState.FAILED) {
            Toast.makeText(this, snapshot.getDetail(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Recomputes the terminal-component state from disk truth (an activated
     * overlay entrypoint or a rootfs-resident sshd) plus the latest add-on install
     * snapshot, then pushes it to every aware surface.
     */
    private void refreshGuestSshState() {
        Path filesDir = getFilesDir().toPath();
        Path rootfs = ProotPaths.activeRootfsPath(filesDir, catalogEntry.getAppId());
        Path overlay = ProotPaths.activeAddonPath(filesDir, sshProfile.getAddonId());
        if (GuestSshDaemon.detect(rootfs, overlay) != null) {
            guestSshState = GuestSshUiState.installed();
        } else if (addonSnapshot != null) {
            switch (addonSnapshot.getState()) {
                case DOWNLOADING:
                case VERIFYING:
                case EXTRACTING:
                    guestSshState = GuestSshUiState.installing(addonSnapshot.getDetail());
                    break;
                case FAILED:
                    guestSshState = GuestSshUiState.failed(addonSnapshot.getDetail());
                    break;
                default:
                    guestSshState = GuestSshUiState.missing();
                    break;
            }
        } else {
            guestSshState = GuestSshUiState.missing();
        }
        desktopHome.renderGuestSsh(guestSshState);
        systemScreen.renderGuestSsh(guestSshState);
    }

    /**
     * Receives one guest-SSH add-on install snapshot from the single setup
     * pipeline. The snapshot is display-only: it is forwarded to the unified
     * installer surface and folded into the launcher's terminal-component
     * state, but never persisted as base runtime state.
     */
    private void onAddonSnapshot(RuntimeSnapshot snapshot) {
        addonSnapshot = snapshot;
        desktopHome.renderAddonPhase(InstallPhaseSnapshot.addon(snapshot));
        refreshGuestSshState();
        if (snapshot.getState() == RuntimeState.FAILED) {
            Toast.makeText(this, snapshot.getDetail(), Toast.LENGTH_LONG).show();
        }
        if (activityStarted) {
            // A user-visible in-app install just made the runtime installable;
            // the same autostart boundary applies without waiting for the next
            // foreground event. The request stays idempotent.
            ensureRuntimeRunning();
        }
    }

    /**
     * Whether the curated rootfs is active on disk. Read from the state store
     * on the install executor so the pipeline decision is not a race with the
     * main-thread render: after a rootfs install succeeds in this task, the
     * installer has already persisted READY, so the add-on runs next without a
     * re-download.
     */
    private boolean isRootfsActiveOnDisk() {
        RuntimeSnapshot stored = stateStore.load(catalogEntry.getAppId());
        RuntimeSnapshot reconciled = RuntimeSnapshotReconciler.reconcile(stored);
        return reconciled != null && reconciled.getState() == RuntimeState.READY;
    }

    /**
     * Whether the guest-SSH add-on overlay is active on disk, derived the same
     * way as {@link #refreshGuestSshState()} so the pipeline and the launcher
     * agree on presence.
     */
    private boolean isAddonActiveOnDisk() {
        Path filesDir = getFilesDir().toPath();
        Path rootfs = ProotPaths.activeRootfsPath(filesDir, catalogEntry.getAppId());
        Path overlay = ProotPaths.activeAddonPath(filesDir, sshProfile.getAddonId());
        return GuestSshDaemon.detect(rootfs, overlay) != null;
    }

    private RuntimeSnapshot baseSnapshot(RuntimeState state) {
        return new RuntimeSnapshot(
                catalogEntry.getAppId(), state, "", 0, System.currentTimeMillis());
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(getColor(R.color.surface));
        window.setNavigationBarColor(getColor(R.color.surface));
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        int systemUiVisibility = nightMode == Configuration.UI_MODE_NIGHT_NO
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0;
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);
    }

    /**
     * Keeps every surface out from under the system bars.
     *
     * <p>Apps targeting API 35+ are drawn edge to edge whether they ask for it or
     * not, so without this the launcher header sits under the status bar. The
     * inset padding is applied to the shell container, which is the ancestor of
     * the launcher, the app surfaces, and the system screen.</p>
     */
    @SuppressLint("Deprecation")
    private void applyWindowInsets() {
        View shell = findViewById(R.id.shell_container);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // The IME is unioned in so an open soft keyboard never covers
                // the terminal's accessory keys, the launcher's search field, or
                // the web-app form's save button.
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                // API 29 has no WindowInsets.Type; these accessors are the only
                // way to read the bars there and are deprecated from API 30.
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                    || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        shell.requestApplyInsets();
    }
}
