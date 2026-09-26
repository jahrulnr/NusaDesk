package gh.nusashell.nusadesk.presentation;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.backup.BackupFailure;
import gh.nusashell.nusadesk.application.backup.BackupResult;
import gh.nusashell.nusadesk.application.backup.GuestBackupUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;
import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationUseCase;
import gh.nusashell.nusadesk.application.runtime.RuntimeSnapshotReconciler;
import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistry;
import gh.nusashell.nusadesk.application.terminal.TerminalTabException;
import gh.nusashell.nusadesk.application.terminal.TerminalTabsPort;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.application.workspace.WorkspaceStore;
import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.BackupSelection;
import gh.nusashell.nusadesk.domain.backup.LastBackupRecord;
import gh.nusashell.nusadesk.domain.backup.LastBackupRun;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabKind;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import gh.nusashell.nusadesk.domain.update.ApkDigest;
import gh.nusashell.nusadesk.domain.update.ReleaseVersion;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;
import gh.nusashell.nusadesk.infrastructure.backup.BackupDocumentAccess;
import gh.nusashell.nusadesk.infrastructure.backup.GuestBackupTransfer;
import gh.nusashell.nusadesk.infrastructure.backup.LastBackupStore;
import gh.nusashell.nusadesk.infrastructure.battery.BatteryOptimizationAccess;
import gh.nusashell.nusadesk.infrastructure.boot.BootAutostartPreferences;
import gh.nusashell.nusadesk.infrastructure.logs.GuestLog;
import gh.nusashell.nusadesk.infrastructure.logs.GuestLogCatalog;
import gh.nusashell.nusadesk.infrastructure.logs.GuestLogTail;
import gh.nusashell.nusadesk.infrastructure.proot.GuestServiceBridge;
import gh.nusashell.nusadesk.infrastructure.proot.GuestSshDaemon;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidGuestAddonInstaller;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidRuntimeInstaller;
import gh.nusashell.nusadesk.infrastructure.runtime.AndroidRuntimeStateStore;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeHostService;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsBus;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsController;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsRegistry;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSecurityInitializer;
import gh.nusashell.nusadesk.infrastructure.terminal.SharedPreferencesTerminalCommandStore;
import gh.nusashell.nusadesk.infrastructure.update.ApkDownloader;
import gh.nusashell.nusadesk.infrastructure.update.GitHubReleaseChecker;
import gh.nusashell.nusadesk.infrastructure.update.HttpAssetSource;
import gh.nusashell.nusadesk.infrastructure.update.PackageInstallerBridge;
import gh.nusashell.nusadesk.infrastructure.update.UpdateCheckPrefs;
import gh.nusashell.nusadesk.infrastructure.update.UpdateNotifier;
import gh.nusashell.nusadesk.infrastructure.webapp.SharedPreferencesWebAppStore;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppFaviconFetcher;
import gh.nusashell.nusadesk.infrastructure.workspace.SharedPreferencesWorkspaceStore;
import gh.nusashell.nusadesk.infrastructure.workspace.WorkspaceFolderAccess;
import gh.nusashell.nusadesk.presentation.desktop.AppFormView;
import gh.nusashell.nusadesk.presentation.desktop.AppSurfaceHostView;
import gh.nusashell.nusadesk.presentation.desktop.DesktopApp;
import gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView;
import gh.nusashell.nusadesk.presentation.desktop.LauncherEntry;
import gh.nusashell.nusadesk.presentation.logs.LogsScreenView;
import gh.nusashell.nusadesk.presentation.system.SystemBackupPageView;
import gh.nusashell.nusadesk.presentation.system.SystemScreenView;
import gh.nusashell.nusadesk.presentation.terminal.TerminalAppView;
import gh.nusashell.nusadesk.presentation.terminal.TerminalSurfaceScope;
import gh.nusashell.nusadesk.presentation.update.UpdateInstallDialog;
import gh.nusashell.nusadesk.presentation.webapp.WebAppSurfaceView;
import gh.nusashell.nusadesk.presentation.webapp.WebAppTabStack;
import gh.nusashell.nusadesk.presentation.widget.FoundationContractDialog;
import gh.nusashell.nusadesk.presentation.widget.InstallPhaseSnapshot;
import gh.nusashell.nusadesk.presentation.workspace.WorkspaceFolderBrowserDialog;
import gh.nusashell.nusadesk.presentation.workspace.WorkspaceUiState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell host.
 *
 * <p>The launcher is the home surface: a search field, the {@code Add app}
 * action, the Linux surfaces this build ships, and the apps the user
 * registered (local web apps and terminal-command apps). Linux is background infrastructure (ADR-0013): this Activity
 * calls the idempotent {@link RuntimeHostService#ensureRunning} from an explicit
 * foreground event, and no surface — launcher, terminal, system, or web app —
 * renders a start or stop control. The foreground-service notification and its
 * Stop action remain the user-visible lifecycle, as Android requires.</p>
 *
 * <p>The host owns navigation, the install coordinator, the user-app
 * registries (web apps and terminal-command apps), and the probe executor for
 * web-app readiness. It never runs runtime work or
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
    /** Persisted System sub-page id (ADR-0043 surfaces). */
    private static final String STATE_SYSTEM_PAGE = "shell.systemPage";
    /** Persisted export selection stashed before the create-document picker. */
    private static final String STATE_BACKUP_MODE = "shell.backupMode";
    private static final String STATE_BACKUP_ROOTS = "shell.backupRoots";
    private static final String STATE_BACKUP_NAME = "shell.backupName";

    /** Request code for the Android 10 legacy storage permissions (ADR-0047). */
    private static final int REQUEST_LEGACY_STORAGE_PERMISSIONS = 0x5706;

    /**
     * How often the foreground poll re-asks the cadence question (ADR-0046
     * amendment). The network call itself stays gated by
     * {@link UpdateCheckPrefs#MIN_INTERVAL_MILLIS}; a tick that lands early is
     * an in-memory no-op, so the effective cadence is that floor plus at most
     * one tick.
     */
    private static final long UPDATE_POLL_TICK_MILLIS = 5L * 60 * 1000;

    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService faviconExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean installInProgress = new AtomicBoolean(false);
    private final Map<DesktopDestination, View> fixedSurfaces =
            new EnumMap<>(DesktopDestination.class);
    private final Map<String, WebAppSurfaceView> webAppSurfaces = new LinkedHashMap<>();

    /**
     * One retained terminal surface per launcher terminal-command app
     * (ADR-0054, isolation amendment). The desktop isolates apps: a command
     * app's session opens in a window of its own instead of a tab inside the
     * built-in terminal, so the terminal can never present another app.
     */
    private final Map<String, TerminalAppView> terminalAppSurfaces = new LinkedHashMap<>();
    /** Decoded favicons by web-app id. In memory only; never written anywhere. */
    private final Map<String, Bitmap> favicons = new HashMap<>();
    /** The exact app each favicon request belongs to, so a stale result is dropped. */
    private final Map<String, String> faviconRequests = new HashMap<>();

    private DesktopHomeView desktopHome;
    private AppSurfaceHostView appSurfaceHost;
    private SystemScreenView systemScreen;
    /** Created lazily on first open: the view owns a WebView, so it costs. */
    private LogsScreenView logsScreen;
    private FoundationContractDialog contractDialog;
    /** The tail feeding the Logs viewer, if one is open. */
    private GuestLogTail.TailHandle logTail;
    /** Monotonic token so a superseded tail's late output is dropped. */
    private int logTailGeneration;

    private RuntimeStateStore stateStore;
    private RuntimeInstallationUseCase installer;
    private AndroidGuestAddonInstaller addonInstaller;
    private RuntimeCatalogEntry catalogEntry;
    private GuestAddonPayloadProfile sshProfile;
    private GuestAddonPayloadProfile serviceProfile;
    private WebAppRegistry webAppRegistry;
    private TerminalCommandRegistry terminalCommandRegistry;
    private WebAppFaviconFetcher faviconFetcher;
    private WorkspaceStore workspaceStore;
    private WorkspaceFolderAccess workspaceAccess;
    private BackupDocumentAccess backupDocumentAccess;
    private GuestBackupUseCase backupTransfer;
    private LastBackupStore lastBackupStore;
    /** Selection stashed between the create-document request and its result. */
    private BackupSelection pendingBackupSelection;
    private String pendingBackupName;
    private BatteryOptimizationAccess batteryAccess;
    private BootAutostartPreferences bootPreferences;
    private UpdateCheckPrefs updatePrefs;
    private UpdateNotifier updateNotifier;
    /** Release page of the tag currently known; null falls back to /releases/latest. */
    private String pendingReleaseUrl;
    /** The tag currently rendered in the launcher banner, or null. */
    private String visibleUpdateTag;

    /**
     * True once this process's first foreground event has asked for a check.
     * Static on purpose: it describes the process, not the activity, so an
     * activity recreation cannot force a second check, while a reboot, a
     * force-stop and a fresh launch each get one check of their own (ADR-0046
     * amendment).
     */
    private static boolean processStartCheckConsumed;

    /** One foreground poll tick: re-asks the cadence question, cheap when early. */
    private final Runnable updatePoll = new Runnable() {
        @Override
        public void run() {
            if (!activityStarted) {
                return;
            }
            checkForUpdate();
            mainHandler.postDelayed(this, UPDATE_POLL_TICK_MILLIS);
        }
    };
    private UpdateInstallDialog installDialog;
    private BroadcastReceiver installStatusReceiver;
    private String installToken;
    /** The one broadcast action the status receiver filters on. */
    private static final String ACTION_INSTALL_STATUS =
            "gh.nusashell.nusadesk.update.STATUS";
    /** Hard cap for a streamed release asset. */
    private static final long APK_SIZE_CAP = 64L * 1024 * 1024;
    private Handler mainHandler;

    private RuntimeSnapshot currentSnapshot;
    private RuntimeSnapshot addonSnapshot;
    private RuntimeSnapshot serviceAddonSnapshot;
    private GuestSshUiState guestSshState = GuestSshUiState.missing();
    private DesktopDestination activeDestination = DesktopDestination.HOME;
    private String activeWebAppId;

    /** Active terminal-command app surface, or {@code null} for any other. */
    private String activeTerminalAppId;
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
        serviceProfile = CuratedRuntimeCatalog.guestServiceBridge();
        stateStore = new AndroidRuntimeStateStore(this);
        installer = new AndroidRuntimeInstaller(this, stateStore);
        addonInstaller = new AndroidGuestAddonInstaller(this);
        webAppRegistry = new WebAppRegistry(new SharedPreferencesWebAppStore(this));
        terminalCommandRegistry = new TerminalCommandRegistry(
                new SharedPreferencesTerminalCommandStore(this));
        // The workspace folder is a user choice: it is stored, then bound into
        // the guest on every later start (ADR-0023). The access helper owns the
        // platform rules, so the Activity only asks for state and renders it.
        workspaceStore = new SharedPreferencesWorkspaceStore(this);
        workspaceAccess = new WorkspaceFolderAccess(this);
        backupDocumentAccess = new BackupDocumentAccess(this);
        lastBackupStore = new LastBackupStore(this);
        backupTransfer = GuestBackupTransfer.inAppStorage(
                this, stateStore, installedVersionName());
        batteryAccess = new BatteryOptimizationAccess(this);
        bootPreferences = new BootAutostartPreferences(this);
        updatePrefs = new UpdateCheckPrefs(this);
        updateNotifier = new UpdateNotifier(this);
        faviconFetcher = new WebAppFaviconFetcher();
        mainHandler = new Handler(Looper.getMainLooper());
        contractDialog = new FoundationContractDialog(this);

        wireLauncher();
        wireAppSurfaceHost();
        wireSystemScreen();
        systemScreen.renderLastBackup(lastBackupStore.load());
        systemScreen.renderLastRun(lastBackupStore.loadRun());

        loadPersistedState();
        restoreShellState(savedInstanceState);
        refreshGuestSshState();
        refreshUserApps();
        refreshWorkspace();
        refreshBattery();
        refreshBoot();
        applyWindowInsets();
        registerBackCallback();
        showRestoredShell();
        if (activeDestination == DesktopDestination.ADD_APP
                && savedInstanceState != null) {
            View surface = fixedSurfaces.get(DesktopDestination.ADD_APP);
            if (surface instanceof AppFormView) {
                ((AppFormView) surface).restoreDraft(savedInstanceState);
            }
        }
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
     * The in-app autostart boundary (ADR-0013, amended by ADR-0037). An
     * explicit Activity foreground event starts Linux when no session is live,
     * and the call is idempotent. The only other start path is the user's own
     * opt-in boot start: the BootStartReceiver runs after boot or an app update
     * and calls the same ensure-running boundary. There is no job, no alarm,
     * and no in-app start control that could start a second session or
     * contradict this one.
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
        // Returning from the all-files access or battery settings pages is a
        // foreground event: re-read both grants/states so the workspace and
        // battery cards tell the truth.
        refreshWorkspace();
        refreshBattery();
        refreshBoot();
        checkForUpdate();
        scheduleUpdatePoll();
        // Returning from the unknown-apps Settings page (assisted update,
        // ADR-0039) re-runs the gate: the popup may be waiting for it.
        if (installDialog != null && installDialog.isShowing()
                && installDialog.isWaitingForAllowance()) {
            continueInstallFlow(updatePrefs.lastSeenTag(), updatePrefs.assetUrl(),
                    updatePrefs.assetDigest(), updatePrefs.assetSizeBytes());
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        mainHandler.removeCallbacks(updatePoll);
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
        if (!serviceBridgeSettled()) {
            // The session would start without its service manager: wait for
            // the service bridge to land (or to fail, which still lets the
            // SSH-only session start) instead of opening a session that can
            // never autostart the user's enabled services.
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
        if (guestSshState.getKind() == GuestSshUiState.Kind.MISSING) {
            startInstall();
            return;
        }
        // The service bridge follows the same rule: missing and not already
        // failed continues the pipeline once; a FAILED snapshot waits for the
        // user's explicit install action rather than retrying in a loop.
        if (!isServiceBridgeActiveOnDisk()
                && (serviceAddonSnapshot == null
                        || serviceAddonSnapshot.getState() != RuntimeState.FAILED)) {
            startInstall();
        }
    }

    @Override
    protected void onDestroy() {
        // The Logs tail follows a real file on a daemon thread; without this
        // it would outlive the view it feeds.
        stopLogTail();
        contractDialog.dispose();
        if (installDialog != null && installDialog.isShowing()) {
            installDialog.dismiss();
        }
        unregisterInstallStatusReceiver();
        installExecutor.shutdownNow();
        probeExecutor.shutdownNow();
        faviconExecutor.shutdownNow();
        updateExecutor.shutdownNow();
        backupExecutor.shutdownNow();
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
        // Inside the log viewer, Back steps one level up — to the log list —
        // before a second Back returns to the launcher, matching the viewer
        // header's close icon.
        if (activeDestination == DesktopDestination.LOGS
                && logsScreen != null && logsScreen.isViewingLog()) {
            logsScreen.showLogList();
            return true;
        }
        // Inside a System sub-page, Back steps one level up — to the hub —
        // before a second Back returns to the launcher, matching the page's
        // own back row.
        if (activeDestination == DesktopDestination.SYSTEM
                && systemScreen != null && systemScreen.navigateBack()) {
            return true;
        }
        if (activeWebAppId != null) {
            WebAppSurfaceView surface = webAppSurfaces.get(activeWebAppId);
            if (surface != null && surface.handleBack()) {
                WebAppDefinition definition = definitionFor(activeWebAppId);
                if (definition != null) {
                    renderWebAppMenu(definition, surface);
                }
                return true;
            }
        }
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
        outState.putString(STATE_SYSTEM_PAGE,
                systemScreen == null ? null : systemScreen.activePageId());
        if (activeDestination == DesktopDestination.ADD_APP) {
            View surface = fixedSurfaces.get(DesktopDestination.ADD_APP);
            if (surface instanceof AppFormView) {
                // The SAF image picker can recreate this Activity while an
                // unsaved command app is being edited. Preserve its selected
                // kind and text fields with the content URI token.
                ((AppFormView) surface).saveDraft(outState);
            }
        }
        // A SAF picker round trip can destroy this Activity while the picker is
        // in front, so the export selection stashed for the create-document
        // result has to survive in the instance state.
        if (pendingBackupSelection != null) {
            outState.putString(STATE_BACKUP_MODE, pendingBackupSelection.getMode().getWireValue());
            outState.putStringArrayList(STATE_BACKUP_ROOTS,
                    new ArrayList<>(pendingBackupSelection.getRoots()));
            outState.putString(STATE_BACKUP_NAME, pendingBackupName);
        }
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
        if (requestCode == WorkspaceFolderAccess.REQUEST_PICK_FOLDER) {
            onWorkspacePicked(resultCode, data);
            return;
        }
        if (requestCode == BatteryOptimizationAccess.REQUEST_OPTIMIZATION) {
            // The dialogs return no result, and a dialog-themed screen only
            // pauses this Activity, so the state row must re-probe here.
            refreshBattery();
            return;
        }
        if (requestCode == BackupDocumentAccess.REQUEST_CREATE_BACKUP) {
            onBackupExportTargetPicked(resultCode, data);
            return;
        }
        if (requestCode == BackupDocumentAccess.REQUEST_OPEN_BACKUP) {
            onBackupImportSourcePicked(resultCode, data);
            return;
        }
        if (requestCode != AppFormView.REQUEST_OPEN_IMAGE) {
            return;
        }
        View form = fixedSurfaces.get(DesktopDestination.ADD_APP);
        if (form instanceof AppFormView) {
            ((AppFormView) form).onImagePicked(resultCode, data);
        }
    }

    // ---- Wiring ----

    private void wireLauncher() {
        desktopHome.setOnInstallListener(view -> startInstall());
        desktopHome.setOnEntryOpenListener(this::openEntry);
        desktopHome.setOnEntryEditListener(this::openEntryEditor);
        desktopHome.setStorageRequirement(
                catalogEntry.getCompressedBytes() + catalogEntry.getUncompressedBytes());
        desktopHome.setOnUpdateOpenListener(view -> onBannerInstall());
        desktopHome.setOnUpdateDismissListener(view -> dismissUpdateBanner());
    }

    private void wireAppSurfaceHost() {
        appSurfaceHost.setOnHomeListener(view -> showShell());
    }

    private void wireSystemScreen() {
        systemScreen.setRuntimeProfile(catalogEntry.getAppId(), catalogEntry.getVersion());
        systemScreen.setOnHowItWorksListener(view -> contractDialog.show());
        systemScreen.setOnWorkspaceActionListener(view -> onWorkspaceAction());
        systemScreen.setOnOpenAppSettingsListener(view -> openAppSettings());
        systemScreen.setOnBatteryActionListener(view -> onBatteryAction());
        systemScreen.setOnBootActionListener(view -> onBootAction());
        systemScreen.setBackupListener(new SystemBackupPageView.Listener() {
            @Override
            public void onExportRequested(BackupSelection selection) {
                onBackupExportRequested(selection);
            }

            @Override
            public void onImportRequested() {
                onBackupImportRequested();
            }
        });
    }

    private void restoreShellState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        // Restored before the destination branches: the export selection is
        // needed by the create-document result no matter which surface was
        // open, because the picker may have outlived this Activity.
        pendingBackupSelection = restoreBackupSelection(savedInstanceState);
        pendingBackupName = savedInstanceState.getString(STATE_BACKUP_NAME);
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
        if (activeDestination == DesktopDestination.SYSTEM) {
            systemScreen.restorePage(savedInstanceState.getString(STATE_SYSTEM_PAGE));
        }
    }

    /**
     * Rebuilds the export selection that was stashed before the SAF
     * create-document picker opened. The platform destroys and recreates this
     * Activity on some devices while the picker is in front, so the selection
     * has to come back from the instance state — without it the result handler
     * would answer "cancelled" after the user tapped Save and leave an empty
     * document behind.
     *
     * @return the rebuilt selection, or {@code null} when none was pending or
     *         the persisted value no longer validates
     */
    private static BackupSelection restoreBackupSelection(Bundle state) {
        BackupMode mode = BackupMode.fromWireValue(state.getString(STATE_BACKUP_MODE, ""));
        if (mode == null) {
            return null;
        }
        try {
            switch (mode) {
                case HOME:
                    return BackupSelection.home();
                case CUSTOM:
                    List<String> roots = state.getStringArrayList(STATE_BACKUP_ROOTS);
                    return BackupSelection.custom(
                            roots == null ? Collections.<String>emptyList() : roots);
                default:
                    return BackupSelection.full();
            }
        } catch (IllegalArgumentException stale) {
            return null;
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
        activeTerminalAppId = null;
        desktopHome.setVisibility(View.VISIBLE);
        appSurfaceHost.setVisibility(View.GONE);
        requestMissingFavicons();
    }

    /** Shows one fixed surface (terminal, system, add/edit form). */
    private void showSurface(DesktopDestination destination) {
        dismissLauncherInput();
        activeDestination = destination;
        activeWebAppId = null;
        activeTerminalAppId = null;
        desktopHome.setVisibility(View.GONE);
        appSurfaceHost.setVisibility(View.VISIBLE);
        View surface = fixedSurfaceFor(destination);
        for (View other : fixedSurfaces.values()) {
            other.setVisibility(other == surface ? View.VISIBLE : View.GONE);
        }
        for (WebAppSurfaceView other : webAppSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (TerminalAppView other : terminalAppSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        appSurfaceHost.setAppTitle(destination.getTitleRes());
        appSurfaceHost.setMenuActions(Collections.emptyList());
        if (destination == DesktopDestination.TERMINAL) {
            // Isolation (ADR-0054 amendment): the terminal owns the shell tabs
            // only, and showing it brings one of them back to the front — a
            // command app's tab stays in its own surface.
            TerminalTabsSnapshot snapshot = TerminalTabsBus.getInstance().current();
            TerminalTabSnapshot visible = TerminalSurfaceScope.shellTabs().visible(snapshot);
            TerminalTabsPort port = TerminalTabsRegistry.getInstance().port();
            if (port != null && visible != null) {
                port.select(visible.getId());
            }
            // The terminal's options menu is its tab set, re-rendered on every
            // tab change by the surface's own listener.
            renderTerminalMenu(snapshot);
        }
        if (destination == DesktopDestination.LOGS) {
            // Re-scan on every open: log files appear and rotate while the
            // surface is away, so a retained list would go stale.
            refreshLogList();
        }
    }

    /** Shows one user web app surface, probing its endpoint before it loads. */
    private void showWebApp(WebAppDefinition definition) {
        dismissLauncherInput();
        activeDestination = DesktopDestination.HOME;
        activeWebAppId = definition.getId().value();
        activeTerminalAppId = null;
        desktopHome.setVisibility(View.GONE);
        appSurfaceHost.setVisibility(View.VISIBLE);
        WebAppSurfaceView surface = webAppSurfaceFor(definition);
        for (View other : fixedSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (WebAppSurfaceView other : webAppSurfaces.values()) {
            other.setVisibility(other == surface ? View.VISIBLE : View.GONE);
        }
        for (TerminalAppView other : terminalAppSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        appSurfaceHost.setAppTitle(definition.getDisplayName());
        renderWebAppMenu(definition, surface);
    }

    /** Renders edit plus the live internal tabs for the active web app. */
    private void renderWebAppMenu(WebAppDefinition definition, WebAppSurfaceView surface) {
        List<AppSurfaceHostView.MenuAction> actions = new ArrayList<>();
        actions.add(new AppSurfaceHostView.MenuAction(
                R.string.webapp_menu_edit, () -> openAppForm(definition, null)));
        for (WebAppTabStack.Tab tab : surface.getTabs()) {
            String tabId = tab.getId();
            String tabTitle = tab.isRoot()
                    ? getString(R.string.webapp_tab_main)
                    : surface.getTabTitle(tabId);
            if (tabId.equals(surface.getSelectedTabId())) {
                tabTitle = getString(R.string.webapp_tab_current, tabTitle);
            }
            String finalTitle = tabTitle;
            actions.add(new AppSurfaceHostView.MenuAction(
                    getString(R.string.webapp_tab_open, finalTitle),
                    () -> surface.selectTab(tabId)));
            if (!tab.isRoot()) {
                actions.add(new AppSurfaceHostView.MenuAction(
                        getString(R.string.webapp_tab_close, finalTitle),
                        () -> surface.closeTab(tabId)));
            }
        }
        appSurfaceHost.setMenuActions(actions);
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
                openAppForm(null, null);
                return;
            case TERMINAL_APP:
                showTerminalApp(entry.getTerminalApp());
                return;
            case WEB_APP:
                WebAppDefinition definition = definitionFor(entry.getId());
                if (definition != null) {
                    showWebApp(definition);
                } else {
                    // The tile was rendered from a definition that is gone;
                    // reconcile instead of opening a stale app.
                    refreshUserApps();
                }
                return;
            default:
                DesktopApp app = DesktopApp.fromId(entry.getId());
                if (app != null) {
                    showSurface(app.getDestination());
                }
        }
    }

    /** Long-press on a user app tile opens that app's edit form. */
    private void openEntryEditor(LauncherEntry entry) {
        if (entry.isWebApp()) {
            openAppForm(entry.getWebApp(), null);
        } else if (entry.isTerminalApp()) {
            openAppForm(null, entry.getTerminalApp());
        }
    }

    /**
     * Opens a launcher terminal-command app in a surface of its own. The
     * desktop shell isolates apps (ADR-0054, isolation amendment): the
     * command's session is the window — titled after the app, with its own
     * options menu — never a tab inside the built-in terminal, so the terminal
     * can not present itself as a way to open other apps. When Linux is not
     * running yet the surface's own waiting state is the whole answer — no tab
     * is created, and nothing is queued to run later.
     */
    private void showTerminalApp(TerminalCommandApp app) {
        if (app == null) {
            refreshUserApps();
            return;
        }
        dismissLauncherInput();
        activeDestination = DesktopDestination.HOME;
        activeWebAppId = null;
        activeTerminalAppId = app.getId().value();
        desktopHome.setVisibility(View.GONE);
        appSurfaceHost.setVisibility(View.VISIBLE);
        TerminalAppView surface = terminalAppSurfaceFor(app);
        for (View other : fixedSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (WebAppSurfaceView other : webAppSurfaces.values()) {
            other.setVisibility(View.GONE);
        }
        for (TerminalAppView other : terminalAppSurfaces.values()) {
            other.setVisibility(other == surface ? View.VISIBLE : View.GONE);
        }
        appSurfaceHost.setAppTitle(app.getDisplayName());
        renderTerminalAppMenu(app.getId().value(), TerminalTabsBus.getInstance().current());

        TerminalTabsPort port = TerminalTabsRegistry.getInstance().port();
        if (port == null) {
            return; // the host service is gone; the surface states Linux is not running
        }
        try {
            port.openOrSelectCommand(app.getId().value(), app.getCommand().value());
        } catch (TerminalTabException failure) {
            renderTerminalTabFailure(failure);
        }
    }

    /** Creates or reuses the retained surface for one terminal-command app. */
    private TerminalAppView terminalAppSurfaceFor(TerminalCommandApp app) {
        String id = app.getId().value();
        TerminalAppView surface = terminalAppSurfaces.get(id);
        if (surface == null) {
            surface = new TerminalAppView(this);
            surface.setTabScope(TerminalSurfaceScope.commandApp(id));
            surface.setOnGoToDesktopListener(view -> showShell());
            surface.setOnTabsChangedListener(snapshot -> {
                if (id.equals(activeTerminalAppId)) {
                    renderTerminalAppMenu(id, snapshot);
                }
            });
            surface.setTerminalDependencies(
                    TerminalTabsRegistry.getInstance(), TerminalTabsBus.getInstance());
            terminalAppSurfaces.put(id, surface);
            appSurfaceHost.getSurfaceContainer().addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return surface;
    }

    /**
     * The options menu of one terminal-command app's surface. The surface owns
     * exactly one tab, so the whole choice is whether to close it; there is no
     * {@code New} — this window is the app, not a tab host — and no other
     * app's tab can ever appear here.
     */
    private void renderTerminalAppMenu(String appId, TerminalTabsSnapshot snapshot) {
        TerminalTabsPort port = TerminalTabsRegistry.getInstance().port();
        List<AppSurfaceHostView.MenuAction> actions = new ArrayList<>();
        if (port != null) {
            for (TerminalTabSnapshot tab : TerminalSurfaceScope.commandApp(appId)
                    .inScope(snapshot)) {
                if (tab.isClosable()) {
                    String tabId = tab.getId();
                    actions.add(new AppSurfaceHostView.MenuAction(
                            R.string.terminal_menu_close, () -> port.close(tabId)));
                }
            }
        }
        appSurfaceHost.setMenuActions(actions);
    }

    /** The user-visible half of a refused tab action; a cap gets its own line. */
    private void renderTerminalTabFailure(TerminalTabException failure) {
        if (failure.getReason() == TerminalTabException.Reason.NOT_RUNNING) {
            return; // the terminal surface already explains that Linux is not running
        }
        boolean atCap = failure.getReason() == TerminalTabException.Reason.TAB_LIMIT;
        Toast.makeText(this, getString(
                atCap ? R.string.terminal_tab_limit : R.string.terminal_app_open_failed,
                atCap ? TerminalTabsController.MAX_TABS
                        : String.valueOf(failure.getMessage())),
                Toast.LENGTH_LONG).show();
    }

    /**
     * Opens the add/edit form for either kind of user app. The form is transient
     * by design: a fresh instance per open, so no stale field value or error line
     * can survive into the next app the user edits. Exactly one of the two
     * arguments is non-null when editing.
     */
    private void openAppForm(WebAppDefinition webApp, TerminalCommandApp commandApp) {
        View previous = fixedSurfaces.remove(DesktopDestination.ADD_APP);
        if (previous != null) {
            appSurfaceHost.getSurfaceContainer().removeView(previous);
        }
        showSurface(DesktopDestination.ADD_APP);
        View surface = fixedSurfaces.get(DesktopDestination.ADD_APP);
        if (!(surface instanceof AppFormView)) {
            return;
        }
        AppFormView form = (AppFormView) surface;
        WebAppDefinition existingWebApp = webApp == null
                ? null : definitionFor(webApp.getId().value());
        TerminalCommandApp existingCommandApp = commandApp == null
                ? null : commandAppFor(commandApp.getId().value());
        if (existingCommandApp != null) {
            form.bindExisting(existingCommandApp);
            appSurfaceHost.setAppTitle(R.string.webapp_edit_title);
        } else if (existingWebApp != null) {
            form.bindExisting(existingWebApp);
            appSurfaceHost.setAppTitle(R.string.webapp_edit_title);
        } else if (webApp != null || commandApp != null) {
            // The app was deleted while the tile was still on screen; reconcile
            // instead of editing a definition that no longer exists.
            refreshUserApps();
            showShell();
        } else {
            form.bindNew();
        }
    }

    /**
     * Keeps the top-level terminal menu small: one action to create a tab, then
     * one name per open shell tab — a command app's tab belongs to its own
     * surface and must not be offered here (ADR-0054, isolation amendment).
     * Opening the tab row presents its own Open/Close choice instead of
     * duplicating close entries for every tab here.
     */
    private void renderTerminalMenu(TerminalTabsSnapshot snapshot) {
        TerminalTabsPort port = TerminalTabsRegistry.getInstance().port();
        if (port == null) {
            appSurfaceHost.setMenuActions(Collections.emptyList());
            return;
        }
        List<AppSurfaceHostView.MenuAction> actions = new ArrayList<>();
        if (!port.isFull()) {
            actions.add(new AppSurfaceHostView.MenuAction(
                    R.string.terminal_menu_new, this::openNewTerminal));
        }
        if (snapshot != null) {
            for (TerminalTabSnapshot tab : TerminalSurfaceScope.shellTabs().inScope(snapshot)) {
                String tabId = tab.getId();
                String label = terminalTabLabel(tab);
                actions.add(new AppSurfaceHostView.MenuAction(
                        label, () -> showTerminalTabActions(port, tabId, label)));
            }
        }
        appSurfaceHost.setMenuActions(actions);
    }

    /** A tab's stable menu name, independent of whether it runs a shell or command. */
    private String terminalTabLabel(TerminalTabSnapshot tab) {
        return getString(R.string.terminal_tab_numbered, tab.getDisplayOrdinal());
    }

    /** Shows the context menu for one tab, then revalidates it before acting. */
    private void showTerminalTabActions(
            TerminalTabsPort port, String tabId, String label) {
        TerminalTabSnapshot current = port.snapshot().tab(tabId);
        if (current == null) {
            return; // the tab exited or was closed while the parent menu was open
        }
        CharSequence[] actions = {
                getString(R.string.terminal_menu_open),
                getString(R.string.terminal_menu_close)
        };
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(actions, (dialog, which) -> {
                    TerminalTabSnapshot stillOpen = port.snapshot().tab(tabId);
                    if (stillOpen == null) {
                        return;
                    }
                    if (which == 0) {
                        port.select(tabId);
                    } else if (which == 1 && stillOpen.isClosable()) {
                        port.close(tabId);
                    }
                })
                .show();
    }

    /** Opens one more shell tab ("New") from the options menu. */
    private void openNewTerminal() {
        TerminalTabsPort port = TerminalTabsRegistry.getInstance().port();
        if (port == null) {
            return;
        }
        try {
            port.openShell();
        } catch (TerminalTabException failure) {
            renderTerminalTabFailure(failure);
        }
    }

    /** The registered terminal-command app for an id, or {@code null}. */
    private TerminalCommandApp commandAppFor(String commandAppId) {
        if (commandAppId == null) {
            return null;
        }
        for (TerminalCommandApp app : terminalCommandRegistry.list()) {
            if (app.getId().value().equals(commandAppId)) {
                return app;
            }
        }
        return null;
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
        if (destination == DesktopDestination.LOGS) {
            return createLogsSurface();
        }
        if (destination == DesktopDestination.ADD_APP) {
            return createAppForm();
        }
        return systemScreen;
    }

    /**
     * Builds the terminal surface. The tab sessions live in the host service
     * (ADR-0033, extended by ADR-0054): this surface consumes them through the
     * tabs registry and the tabs bus, and never opens or closes an SSH
     * connection of its own — there is no target to choose, exactly as the
     * local-only factory contract requires (ADR-0013).
     *
     * <p>The tabs listener keeps the options button's tab menu honest: a tab
     * opened from a launcher tile, or closed from the menu itself, re-renders
     * it while the terminal is the visible surface.</p>
     */
    private TerminalAppView createTerminalSurface() {
        TerminalAppView terminal = new TerminalAppView(this);
        terminal.setTabScope(TerminalSurfaceScope.shellTabs());
        terminal.setOnGoToDesktopListener(view -> showShell());
        terminal.setOnTabsChangedListener(snapshot -> {
            if (activeDestination == DesktopDestination.TERMINAL) {
                renderTerminalMenu(snapshot);
            }
        });
        terminal.setTerminalDependencies(
                TerminalTabsRegistry.getInstance(), TerminalTabsBus.getInstance());
        return terminal;
    }

    /**
     * Builds the Logs surface. The view only lists and renders; this host owns
     * the catalog scan and the {@link GuestLogTail} lifecycle, so a tail can
     * never outlive the surface that displays it.
     */
    private LogsScreenView createLogsSurface() {
        logsScreen = new LogsScreenView(this);
        logsScreen.setListener(new LogsScreenView.Listener() {
            @Override
            public void onLogItemSelected(GuestLog log) {
                startLogTail(log);
            }

            @Override
            public void onLogViewClosed() {
                stopLogTail();
            }
        });
        return logsScreen;
    }

    // ---- Logs surface ----

    /**
     * Scans the guest log catalog off the UI thread and pushes the items to
     * the surface. The rootfs is app-private storage, so a plain file listing
     * is all a "journal" needs here — no guest process is involved.
     */
    private void refreshLogList() {
        if (logsScreen == null) {
            return;
        }
        logsScreen.showReading();
        Path rootfs = ProotPaths.activeRootfsPath(
                getFilesDir().toPath(), catalogEntry.getAppId());
        probeExecutor.execute(() -> {
            List<GuestLog> items = GuestLogCatalog.list(rootfs);
            mainHandler.post(() -> {
                if (!isDestroyed() && logsScreen != null) {
                    logsScreen.renderLogs(items);
                }
            });
        });
    }

    /**
     * Starts following one guest log file into the viewer. A superseded
     * tail's late chunks are dropped through the generation token rather than
     * racing the new file's output.
     */
    private void startLogTail(GuestLog log) {
        stopLogTail();
        final int generation = ++logTailGeneration;
        logTail = GuestLogTail.follow(log.getHostPath(),
                text -> mainHandler.post(() -> {
                    if (!isDestroyed() && generation == logTailGeneration
                            && logsScreen != null) {
                        logsScreen.writeLogOutput(text);
                    }
                }),
                GuestLogTail.DEFAULT_INITIAL_BYTES, GuestLogTail.POLL_MS);
    }

    /** Stops the active tail, if any. Idempotent. */
    private void stopLogTail() {
        logTailGeneration++;
        GuestLogTail.TailHandle tail = logTail;
        logTail = null;
        if (tail != null) {
            tail.stop();
        }
    }

    private AppFormView createAppForm() {
        AppFormView form = new AppFormView(this, webAppRegistry, terminalCommandRegistry);
        form.setListener(new AppFormView.Listener() {
            @Override
            public void onWebAppSaved(WebAppDefinition definition) {
                // The app may now point at a different endpoint, so an image
                // fetched from the old one must not stay on the tile.
                forgetFavicon(definition.getId().value());
                refreshUserApps();
                showShell();
            }

            @Override
            public void onCommandAppSaved(TerminalCommandApp app) {
                refreshUserApps();
                showShell();
            }

            @Override
            public void onWebAppDeleted(String webAppId) {
                discardWebAppSurface(webAppId);
                forgetFavicon(webAppId);
                refreshUserApps();
                showShell();
            }

            @Override
            public void onCommandAppDeleted(String commandAppId) {
                // The app is gone, so its window and its session go with it:
                // an isolated surface exposes exactly one app, and a tab it
                // could no longer reach would be unclosable (ADR-0054
                // amendment).
                closeCommandAppTab(commandAppId);
                discardTerminalAppSurface(commandAppId);
                refreshUserApps();
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
        WebAppSurfaceView activeSurface = surface;
        activeSurface.setOnTabListener((tabs, selectedTabId) -> {
            if (id.equals(activeWebAppId) && activeSurface == webAppSurfaces.get(id)) {
                WebAppDefinition current = definitionFor(id);
                if (current != null) {
                    renderWebAppMenu(current, activeSurface);
                }
            }
        });
        activeSurface.bind(definition, probeExecutor);
        return activeSurface;
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

    /** Closes a deleted terminal app's tab, if its session is still live. */
    private void closeCommandAppTab(String commandAppId) {
        TerminalTabsPort port = TerminalTabsRegistry.getInstance().port();
        if (port == null) {
            return;
        }
        for (TerminalTabSnapshot tab : TerminalSurfaceScope.commandApp(commandAppId)
                .inScope(port.snapshot())) {
            port.close(tab.getId());
        }
    }

    /** Removes a deleted terminal app's retained surface and its WebViews. */
    private void discardTerminalAppSurface(String commandAppId) {
        TerminalAppView surface = terminalAppSurfaces.remove(commandAppId);
        if (surface == null) {
            return;
        }
        if (commandAppId.equals(activeTerminalAppId)) {
            activeTerminalAppId = null;
        }
        appSurfaceHost.getSurfaceContainer().removeView(surface);
        // Detaching releases the surface's bridges; the session itself is the
        // tab the caller closed above.
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

    /**
     * Re-renders the launcher's user apps. The grid lists web apps first, then
     * terminal-command apps, each in its own launcher order; the System screen
     * keeps counting only web apps, because that is what its row says.
     */
    private void refreshUserApps() {
        List<WebAppDefinition> definitions = new ArrayList<>(webAppRegistry.list());
        List<TerminalCommandApp> commandApps = terminalCommandRegistry.list();
        desktopHome.setApps(definitions, commandApps);
        systemScreen.setWebAppCount(definitions.size());
    }

    /**
     * The workspace card's single action (ADR-0023). Which step runs depends on
     * where the device is: without the platform grant the user is sent to the one
     * Settings page that can give it, and only with the grant does the folder
     * picker open. Nothing here widens storage access by itself.
     */
    private void onWorkspaceAction() {
        workspaceAccess.preparePickerRoot();
        WorkspaceFolder root = workspaceAccess.pickerRoot();
        if (root == null) {
            Toast.makeText(this, R.string.system_workspace_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (workspaceAccess.isSupportedPlatform()) {
            if (!workspaceAccess.hasAllFilesAccess()) {
                openAllFilesAccessSettings();
                refreshWorkspace();
                return;
            }
        } else if (!workspaceAccess.hasLegacyStorageAccess()) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_LEGACY_STORAGE_PERMISSIONS);
            return;
        }
        // Android 10 browses shared storage through the legacy model, Android
        // 11+ through the all-files grant; either way the chosen path is
        // validated and probed before it is stored.
        WorkspaceFolder stored = workspaceStore.load();
        WorkspaceFolderBrowserDialog.show(this, root.getHostPath(),
                stored == null ? null : stored.getHostPath(), path -> {
                    WorkspaceFolder picked = workspaceAccess.pickedPathWorkspace(path);
                    if (picked != null && workspaceAccess.isUsable(picked)) {
                        workspaceStore.save(picked);
                    } else {
                        Toast.makeText(this, R.string.system_workspace_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                    refreshWorkspace();
                });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LEGACY_STORAGE_PERMISSIONS) {
            return;
        }
        if (workspaceAccess.hasLegacyStorageAccess()) {
            onWorkspaceAction();
            return;
        }
        Toast.makeText(this, R.string.system_workspace_picker_permission,
                Toast.LENGTH_LONG).show();
    }

    /**
     * Opens the all-files access screen, falling back to the generic one on the
     * builds that do not publish the app-specific page. The intents are started
     * rather than probed first: a package-visibility check can report "missing"
     * for a page that would open, and the failure is cheap to handle here.
     */
    private void openAllFilesAccessSettings() {
        if (startSettings(workspaceAccess.allFilesAccessIntent())) {
            return;
        }
        if (!startSettings(workspaceAccess.genericAllFilesAccessIntent())) {
            Toast.makeText(this, R.string.system_workspace_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The battery card's single action: asks Android for the battery
     * optimization exemption directly and falls back to the optimization list
     * screen on the builds that refuse the direct dialog. The dialogs give no
     * result, so the card re-renders when the dialog closes (the
     * {@code onActivityResult} callback fires even when the underlying
     * Activity was only paused, not stopped) and again on the next foreground
     * event instead of trusting a return value.
     */
    private void onBatteryAction() {
        if (startSettingsForResult(batteryAccess.requestExemptionIntent(),
                BatteryOptimizationAccess.REQUEST_OPTIMIZATION)) {
            refreshBattery();
            return;
        }
        if (startSettingsForResult(batteryAccess.optimizationSettingsIntent(),
                BatteryOptimizationAccess.REQUEST_OPTIMIZATION)) {
            return;
        }
        Toast.makeText(this, R.string.system_battery_unavailable,
                Toast.LENGTH_LONG).show();
        refreshBattery();
    }

    /** Renders the battery-optimization card from the permission-free probe. */
    private void refreshBattery() {
        if (systemScreen == null || batteryAccess == null) {
            return;
        }
        systemScreen.renderBattery(batteryAccess.isExempt());
    }

    /**
     * The boot card's single action: flips the "Start Linux at boot" opt-in
     * (ADR-0037). The receiver stays inert until this store says on; the card
     * re-renders from the store so the row always mirrors the persisted truth.
     */
    private void onBootAction() {
        bootPreferences.setEnabled(!bootPreferences.enabled());
        refreshBoot();
    }

    /** Renders the boot-start card from the persisted opt-in. */
    private void refreshBoot() {
        if (systemScreen == null || bootPreferences == null) {
            return;
        }
        systemScreen.renderBoot(bootPreferences.enabled());
    }

    // ---- Update check (ADR-0038) ----

    /**
     * The one update-check path, fired from foreground events only: the first
     * foreground event of a fresh process always asks — a reboot, a force-stop
     * and a fresh launch are the moments a user expects the app to look —
     * later ones are gated by the prefs floor (15 minutes) plus an immediate
     * check whenever the installed version changed since the last attempt, and
     * a foreground poll keeps re-asking while the app stays open (ADR-0038,
     * cadence amended by ADR-0046). Silent on every failure. The banner always
     * renders what the last stored check proved; the network check runs on the
     * update executor and lands on the main thread.
     */
    private void checkForUpdate() {
        if (updatePrefs == null) {
            return;
        }
        renderStoredUpdateBanner();
        String installedVersion = installedVersionName();
        boolean freshProcess = !processStartCheckConsumed;
        processStartCheckConsumed = true;
        if (!updatePrefs.isDue(System.currentTimeMillis(), installedVersion, freshProcess)) {
            return;
        }
        updateExecutor.execute(() -> {
            GitHubReleaseChecker.UpdateCheckResult result =
                    new GitHubReleaseChecker().check(installedVersion);
            updatePrefs.recordCheck(System.currentTimeMillis(), result.getTag(), installedVersion);
            mainHandler.post(() -> applyUpdateResult(result));
        });
    }

    /**
     * Keeps the check moving while the app stays open: one tick every
     * {@link #UPDATE_POLL_TICK_MILLIS}, dropped in {@code onStop} so a
     * backgrounded app costs no wakeups. Reopening re-arms it, and a foreground
     * event after the floor checks on its own (ADR-0046 amendment).
     */
    private void scheduleUpdatePoll() {
        mainHandler.removeCallbacks(updatePoll);
        mainHandler.postDelayed(updatePoll, UPDATE_POLL_TICK_MILLIS);
    }

    /**
     * Renders the banner from the last stored check without a network round
     * trip. The stored tag counts as available only while it really is newer
     * than the installed version and the user has not dismissed it.
     */
    private void renderStoredUpdateBanner() {
        ReleaseVersion seen = ReleaseVersion.parseOrNull(updatePrefs.lastSeenTag());
        ReleaseVersion installed = ReleaseVersion.parseOrNull(installedVersionName());
        boolean show = seen != null && installed != null
                && seen.isNewerThan(installed)
                && !seen.getRaw().equals(updatePrefs.dismissedTag());
        pendingReleaseUrl = null;
        visibleUpdateTag = show ? seen.getRaw() : null;
        desktopHome.renderUpdateBanner(visibleUpdateTag);
    }

    /**
     * Applies one completed check on the main thread. Anything but a newer
     * release clears the banner; a release the user dismissed stays quiet for
     * both surfaces until a different tag arrives.
     */
    private void applyUpdateResult(GitHubReleaseChecker.UpdateCheckResult result) {
        if (result.getKind() != GitHubReleaseChecker.UpdateCheckResult.Kind.UPDATE_AVAILABLE) {
            desktopHome.renderUpdateBanner(null);
            return;
        }
        // Persist the asset the channel reported, so the banner's Install action
        // (and a retry after a restart) can stage the download without another
        // check. Regression: nothing ever called this, so Install always
        // answered "the channel did not report the APK over HTTPS" even though
        // the checker had parsed the asset (observed on the S7 Edge,
        // 2026-09-21).
        updatePrefs.recordAsset(
                result.getDownloadUrl(), result.getDigest(), result.getSizeBytes());
        if (result.getTag().equals(updatePrefs.dismissedTag())) {
            return;
        }
        pendingReleaseUrl = result.getReleaseUrl();
        visibleUpdateTag = result.getTag();
        desktopHome.renderUpdateBanner(result.getTag());
        updateNotifier.notifyIfAvailable(this,
                getString(R.string.update_notification_title),
                getString(R.string.update_notification_text, result.getTag()),
                result.getReleaseUrl());
    }

    /** Remembers the dismissed tag and silences both surfaces for that release. */
    private void dismissUpdateBanner() {
        if (visibleUpdateTag != null) {
            updatePrefs.setDismissed(visibleUpdateTag);
            updateNotifier.cancelUpdateNotice();
        }
        desktopHome.renderUpdateBanner(null);
    }

    /**
     * Opens a release page in the system browser: the exact page a live check
     * returned, or the channel's own "latest" page when the banner came from a
     * stored tag. The browser hand-off stays available alongside the assisted
     * install flow (ADR-0039) — it is the fallback whenever the channel did
     * not report a usable asset.
     */
    private void openReleasePage(String releasePageUrl) {
        String url = releasePageUrl == null || releasePageUrl.trim().isEmpty()
                ? "https://github.com/jahrulnr/NusaDesk/releases/latest"
                : releasePageUrl;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException noBrowser) {
            Toast.makeText(this, R.string.update_banner_open_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---- Assisted in-app update (ADR-0039) ----

    /**
     * The banner's Install action: opens the popup and runs the assisted
     * flow — probe the digest-validated cache, stream the asset when needed
     * with live progress, verify the checksum, and stage the platform
     * install session whose confirmation dialog the user taps themselves.
     */
    private void onBannerInstall() {
        if (installDialog != null && installDialog.isShowing()) {
            return;
        }
        String tag = updatePrefs.lastSeenTag();
        String url = updatePrefs.assetUrl();
        String digest = updatePrefs.assetDigest();
        long size = updatePrefs.assetSizeBytes();
        UpdateInstallDialog dialog = new UpdateInstallDialog(this);
        installDialog = dialog;
        dialog.showWithHost(new UpdateInstallDialog.Host() {
            @Override
            public void onInstallReady() {
                continueInstallFromCache();
            }
            @Override
            public void onAllowInstalls() {
                openUnknownSourcesSettings();
            }

            @Override
            public void onRetry() {
                continueInstallFlow(tag, url, digest, size);
            }

            @Override
            public void onReleasePage() {
                openReleasePage(pendingReleaseUrl);
            }

            @Override
            public void onPopupClosed() {
                unregisterInstallStatusReceiver();
            }
        });
        if (tag == null || url == null || ApkDigest.normalize(digest) == null) {
            // The channel did not report a usable HTTPS asset for this tag.
            dialog.renderUnavailable();
            return;
        }
        dialog.renderIdentity(tag, UpdateInstallDialog.formatBytes(size));
        continueInstallFlow(tag, url, digest, size);
    }

    /**
     * Continues the flow from wherever it stopped: the unknown-apps gate
     * first (returning from that Settings page is a resume, not a new
     * install), then the cache probe, then the streaming download.
     */
    private void continueInstallFlow(String tag, String url, String digest, long size) {
        if (getPackageManager().canRequestPackageInstalls()) {
            installDialog.resetProgress();
            installDialog.renderPreparing();
            updateExecutor.execute(() -> downloadAndStage(tag, url, digest, size));
            return;
        }
        installDialog.renderNeedsUnknownSources();
    }

    /**
     * The aborted-install path: re-verify the kept cache file and restage it
     * without touching the network. A cache file that no longer matches its
     * recorded digest (evicted, truncated) falls back to a typed failure so
     * the user can retry the full download.
     */
    private void continueInstallFromCache() {
        String digest = updatePrefs.assetDigest();
        updateExecutor.execute(() -> {
            File cached = cachedUpdateApk();
            try {
                if (cached.exists()
                        && ApkDigest.matches(digest, ApkDownloader.sha256Hex(cached))) {
                    mainHandler.post(() -> {
                        installDialog.renderReady();
                        stageInstall(cached);
                    });
                    return;
                }
                mainHandler.post(() -> installDialog.renderFailed(
                        "the cached copy is missing or stale"));
            } catch (IOException unreadable) {
                mainHandler.post(() -> installDialog.renderFailed(
                        "the cached copy cannot be read"));
            }
        });
    }

    /**
     * The platform installer is staged from a verified file: the session is
     * created and committed with a token-carrying status PendingIntent, and
     * the system's own confirmation dialog is where the user taps Install.
     */
    private void stageInstall(File apk) {
        installToken = UUID.randomUUID().toString();
        PendingIntent sender = PackageInstallerBridge.buildStatusPendingIntent(this,
                ACTION_INSTALL_STATUS, installToken, 0);
        registerInstallStatusReceiver();
        installDialog.renderInstalling();
        updateExecutor.execute(() -> {
            try {
                PackageInstallerBridge.stageApk(this, apk, sender.getIntentSender());
            } catch (IOException stagingFailed) {
                mainHandler.post(() -> installDialog.renderFailed(
                        stagingFailed.getMessage() == null ? "staging failed"
                                : stagingFailed.getMessage()));
            }
        });
    }

    /** Cache probe, streaming download, checksum verify — on the executor. */
    private void downloadAndStage(String tag, String url, String digest, long size) {
        File cached = cachedUpdateApk();
        try {
            if (cached.exists()
                    && ApkDigest.matches(digest, ApkDownloader.sha256Hex(cached))) {
                // A cancelled install left a verified file: skip the download.
                mainHandler.post(() -> {
                    installDialog.renderReady();
                    stageInstall(cached);
                });
                return;
            }
            mainHandler.post(() -> installDialog.resetProgress());
            String computed = ApkDownloader.download(new HttpAssetSource(url), cached,
                    APK_SIZE_CAP,
                    (read, total) -> mainHandler.post(() -> installDialog.onProgress(read, total)));
            mainHandler.post(installDialog::renderVerifying);
            if (!ApkDigest.matches(digest, computed)) {
                mainHandler.post(() -> installDialog.renderFailed("checksum mismatch"));
                return;
            }
            mainHandler.post(() -> {
                installDialog.renderReady();
                stageInstall(cached);
            });
        } catch (IOException failed) {
            mainHandler.post(() -> installDialog.renderFailed(
                    failed.getMessage() == null ? failed.getClass().getSimpleName()
                            : failed.getMessage()));
        }
    }

    /**
     * The fixed-name cache slot for one pending update. The digest recorded
     * with the tag ties the file to its release, so a stale file for another
     * version fails the probe and re-downloads.
     */
    private File cachedUpdateApk() {
        File dir = new File(getCacheDir(), "update");
        dir.mkdirs();
        return new File(dir, "NusaDesk-update.apk");
    }

    /**
     * Listens for the platform's install-session status: first the
     * pending-user-action broadcast (whose confirmation intent the app
     * launches — that is what shows the system dialog), then the terminal
     * result. Token-verified; a spoofed broadcast is dropped.
     *
     * <p>The below-33 branch cannot pass a receiver flag — the overload
     * arrived with API 33 — so the lint flag rule is suppressed here with the
     * token check as the actual gate, and API 33+ registers not-exported.</p>
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerInstallStatusReceiver() {
        if (installStatusReceiver != null) {
            return;
        }
        installStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (installToken == null || !installToken.equals(
                        intent.getStringExtra(PackageInstallerBridge.EXTRA_STATUS_TOKEN))) {
                    return;
                }
                PackageInstallerBridge.InstallStatus status =
                        PackageInstallerBridge.parseStatus(intent);
                if (status == null) {
                    return;
                }
                if (status.isPendingUserAction()) {
                    Intent confirmation = status.getConfirmationIntent();
                    if (confirmation != null) {
                        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(confirmation);
                        } catch (RuntimeException noScreen) {
                            mainHandler.post(() -> installDialog.renderFailed(
                                    "the installer did not open"));
                        }
                    }
                    return;
                }
                unregisterInstallStatusReceiver();
                if (status.isSuccess()) {
                    // The platform replaces the process for a real install.
                    cachedUpdateApk().delete();
                    mainHandler.post(installDialog::dismiss);
                } else if (status.isUserAborted()) {
                    mainHandler.post(installDialog::renderAborted);
                } else {
                    String message = status.getMessage();
                    mainHandler.post(() -> installDialog.renderFailed(
                            message == null ? "install failed" : message));
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installStatusReceiver, filter);
        }
    }

    private void unregisterInstallStatusReceiver() {
        if (installStatusReceiver != null) {
            try {
                unregisterReceiver(installStatusReceiver);
            } catch (IllegalArgumentException notRegistered) {
                // Nothing to clean up.
            }
            installStatusReceiver = null;
        }
    }

    /**
     * The one-time Android gate: allow installs staged by this app. Enabling
     * it does not change what installs silently — the system still asks for
     * confirmation on every update.
     */
    private void openUnknownSourcesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException noScreen) {
            if (installDialog != null) {
                installDialog.renderFailed("the Android settings screen is not available");
            }
        }
    }

    /** Reads the installed APK versionName; null when the platform has none. */
    private String installedVersionName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0L)).versionName;
            }
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }

    /**
     * The System card's app-permissions shortcut: opens this app's Android App
     * Info page, where the platform manages camera, microphone, location,
     * contacts, SMS and other permission switches. NusaDesk never requests these
     * itself, so the card can only point at the page that owns them.
     */
    private void openAppSettings() {
        if (!startSettings(appDetailsSettingsIntent(getPackageName()))) {
            Toast.makeText(this, R.string.system_permissions_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The exact intent the app-permissions shortcut starts. Kept small and
     * static so a focused test can pin the action and package URI without
     * driving the whole Activity.
     */
    static Intent appDetailsSettingsIntent(String packageName) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + packageName));
    }

    private boolean startSettings(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException noSuchScreen) {
            return false;
        }
    }

    /**
     * startActivityForResult with the same start-and-fall-back shape as
     * {@link #startSettings(Intent)}. The battery dialogs return no result,
     * but their close callback is the reliable re-render signal.
     */
    @SuppressWarnings("deprecation")
    private boolean startSettingsForResult(Intent intent, int requestCode) {
        if (intent == null) {
            return false;
        }
        try {
            startActivityForResult(intent, requestCode);
            return true;
        } catch (ActivityNotFoundException noSuchScreen) {
            return false;
        }
    }

    /**
     * Stores the picked folder when it is a local folder this app can really
     * write, and says so plainly when it is not. A rejected pick leaves the
     * previous choice untouched.
     */
    private void onWorkspacePicked(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            WorkspaceFolder picked = workspaceAccess.resolvePickedFolder(data);
            if (picked != null && workspaceAccess.isUsable(picked)) {
                workspaceStore.save(picked);
            } else {
                Toast.makeText(this, R.string.system_workspace_unavailable,
                        Toast.LENGTH_LONG).show();
            }
        }
        refreshWorkspace();
    }

    // ---- Guest backup & restore ----

    /**
     * The export action: the create-document picker gets the suggested file
     * name; the selection is stashed until the result arrives. A running
     * install is reported as {@code busy} instead of racing the installer's
     * staging trees.
     */
    private void onBackupExportRequested(BackupSelection selection) {
        if (selection == null) {
            Toast.makeText(this, R.string.system_backup_pick_folder,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (installInProgress.get()) {
            renderBackupFailure(BackupFailure.BUSY,
                    "a setup install is running — retry when it finishes");
            return;
        }
        String name = BackupDocumentAccess.suggestedFileName(
                selection.getMode(), System.currentTimeMillis());
        pendingBackupSelection = selection;
        pendingBackupName = name;
        try {
            startActivityForResult(backupDocumentAccess.createDocumentIntent(name),
                    BackupDocumentAccess.REQUEST_CREATE_BACKUP);
        } catch (ActivityNotFoundException noPicker) {
            pendingBackupSelection = null;
            pendingBackupName = null;
            renderBackupFailure(BackupFailure.IO_FAILURE,
                    getString(R.string.system_backup_picker_unavailable));
        }
    }

    /**
     * The import action: the open-document picker hands back the archive to
     * restore. The engine reads the manifest first, so no mode choice is
     * needed here — the file says what it is.
     */
    private void onBackupImportRequested() {
        if (installInProgress.get()) {
            renderBackupFailure(BackupFailure.BUSY,
                    "a setup install is running — retry when it finishes");
            return;
        }
        try {
            startActivityForResult(backupDocumentAccess.openDocumentIntent(),
                    BackupDocumentAccess.REQUEST_OPEN_BACKUP);
        } catch (ActivityNotFoundException noPicker) {
            renderBackupFailure(BackupFailure.IO_FAILURE,
                    getString(R.string.system_backup_picker_unavailable));
        }
    }

    /**
     * The create-document result: streams the archive straight into the picked
     * document's OutputStream on the backup executor — no temporary copy.
     */
    private void onBackupExportTargetPicked(int resultCode, Intent data) {
        BackupSelection selection = pendingBackupSelection;
        String suggestedName = pendingBackupName;
        pendingBackupSelection = null;
        pendingBackupName = null;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || uri == null || selection == null) {
            systemScreen.renderBackupCancelled();
            return;
        }
        String queried = backupDocumentAccess.displayName(uri);
        String recordName = queried == null ? suggestedName : queried;
        systemScreen.setBackupBusy(true);
        backupExecutor.execute(() -> {
            BackupResult result;
            try (OutputStream out = backupDocumentAccess.openForWrite(uri)) {
                result = out == null
                        ? BackupResult.failed(BackupFailure.IO_FAILURE,
                                "the picked document could not be opened")
                        : backupTransfer.exportBackup(selection, out,
                                progress -> mainHandler.post(
                                        () -> systemScreen.renderBackupProgress(progress)));
            } catch (IOException failed) {
                result = BackupResult.failed(BackupFailure.IO_FAILURE,
                        failed.getMessage() == null
                                ? "the storage write failed" : failed.getMessage());
            }
            BackupResult terminal = result;
            mainHandler.post(() -> onBackupExportFinished(terminal, selection, recordName));
        });
    }

    /**
     * The open-document result: streams the picked archive into the restore
     * pipeline on the backup executor.
     */
    private void onBackupImportSourcePicked(int resultCode, Intent data) {
        Uri uri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || uri == null) {
            systemScreen.renderBackupCancelled();
            return;
        }
        systemScreen.setBackupBusy(true);
        backupExecutor.execute(() -> {
            BackupResult result;
            try (InputStream in = backupDocumentAccess.openForRead(uri)) {
                result = in == null
                        ? BackupResult.failed(BackupFailure.IO_FAILURE,
                                "the picked document could not be opened")
                        : backupTransfer.importBackup(in,
                                progress -> mainHandler.post(
                                        () -> systemScreen.renderBackupProgress(progress)));
            } catch (IOException failed) {
                result = BackupResult.failed(BackupFailure.IO_FAILURE,
                        failed.getMessage() == null
                                ? "the storage read failed" : failed.getMessage());
            }
            BackupResult terminal = result;
            mainHandler.post(() -> onBackupImportFinished(terminal));
        });
    }

    /** Terminal export state: render it and persist the display records. */
    private void onBackupExportFinished(BackupResult result, BackupSelection selection,
            String displayName) {
        systemScreen.renderBackupResult(result);
        persistBackupRun(LastBackupRun.OPERATION_EXPORT, result);
        if (!result.isReady()) {
            return;
        }
        LastBackupRecord record = new LastBackupRecord(selection.getMode(),
                System.currentTimeMillis(), displayName,
                result.getEntries(), result.getBytes());
        try {
            lastBackupStore.save(record);
        } catch (RuntimeException persistFailed) {
            // The backup itself succeeded; only the display record is lost.
        }
        systemScreen.renderLastBackup(record);
    }

    /** Terminal import state: a restored runtime is the new install truth. */
    private void onBackupImportFinished(BackupResult result) {
        systemScreen.renderBackupResult(result);
        persistBackupRun(LastBackupRun.OPERATION_RESTORE, result);
        if (result.isReady()) {
            loadPersistedState();
            refreshGuestSshState();
        }
    }

    /**
     * The typed success/failure record, written only now that the operation
     * has reached its terminal state — a killed transfer leaves the previous
     * record standing rather than a speculative one.
     */
    private void persistBackupRun(String operation, BackupResult result) {
        LastBackupRun run = new LastBackupRun(operation, result.isReady(),
                result.getFailure() == null ? "" : result.getFailure().getCode(),
                System.currentTimeMillis());
        try {
            lastBackupStore.saveRun(run);
        } catch (RuntimeException persistFailed) {
            // The operation already reached its terminal state; only the
            // display record is lost.
        }
        systemScreen.renderLastRun(run);
    }

    private void renderBackupFailure(BackupFailure failure, String detail) {
        systemScreen.renderBackupResult(BackupResult.failed(failure, detail));
    }

    /**
     * Renders what the workspace card may offer here: below API 30 the limitation
     * itself, otherwise the grant step, the picker, or the folder in use.
     */
    private void refreshWorkspace() {
        if (systemScreen == null || workspaceAccess == null) {
            return;
        }
        if (!workspaceAccess.isSupportedPlatform()) {
            // Below API 30 no shared folder can be bound: a stored pick lives
            // inside the app's own media tree (ADR-0047), and without one the app
            // folder itself is the workspace. The card names whichever is in
            // effect.
            WorkspaceFolder stored = workspaceStore.load();
            if (stored != null && workspaceAccess.isUsable(stored)) {
                systemScreen.renderWorkspace(WorkspaceUiState.chosen(stored.getDisplayName()));
                return;
            }
            WorkspaceFolder appFolder = workspaceAccess.appFolderWorkspace();
            systemScreen.renderWorkspace(WorkspaceUiState.appFolder(
                    appFolder == null ? "" : appFolder.getHostPath()));
            return;
        }
        if (!workspaceAccess.hasAllFilesAccess()) {
            systemScreen.renderWorkspace(WorkspaceUiState.needsAllFilesAccess());
            return;
        }
        WorkspaceFolder current = workspaceStore.load();
        systemScreen.renderWorkspace(current == null
                ? WorkspaceUiState.notChosen()
                : WorkspaceUiState.chosen(current.getDisplayName()));
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
                if (!isServiceBridgeActiveOnDisk()) {
                    addonInstaller.install(serviceProfile, catalogEntry.getAppId(),
                            snapshot -> mainHandler.post(() -> onServiceAddonSnapshot(snapshot)));
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
     * Receives one guest service-bridge install snapshot from the single setup
     * pipeline. It shares the installer's phase surface and failure toast with
     * the SSH add-on but never feeds {@code guestSshState}: the SSH component
     * is what the terminal needs, the bridge is additive.
     */
    private void onServiceAddonSnapshot(RuntimeSnapshot snapshot) {
        serviceAddonSnapshot = snapshot;
        desktopHome.renderAddonPhase(InstallPhaseSnapshot.addon(snapshot));
        if (snapshot.getState() == RuntimeState.FAILED) {
            Toast.makeText(this, snapshot.getDetail(), Toast.LENGTH_LONG).show();
        }
        if (activityStarted) {
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

    /**
     * Whether the guest service-bridge overlay is active on disk, derived from
     * the same detection the workload uses so pipeline and session agree.
     */
    private boolean isServiceBridgeActiveOnDisk() {
        Path filesDir = getFilesDir().toPath();
        Path overlay = ProotPaths.activeAddonPath(filesDir, serviceProfile.getAddonId());
        return GuestServiceBridge.detect(overlay) != null;
    }

    /**
     * Whether the service bridge is settled for session start: either its
     * overlay is already active on disk, or its last install attempt failed
     * (in which case the session still starts — SSH works without it, and the
     * missing {@code systemctl} is honest). An unsettled bridge (installing,
     * or never attempted) holds the session start so the first session can
     * run its service manager.
     */
    private boolean serviceBridgeSettled() {
        return isServiceBridgeActiveOnDisk()
                || (serviceAddonSnapshot != null
                        && serviceAddonSnapshot.getState() == RuntimeState.FAILED);
    }

    private RuntimeSnapshot baseSnapshot(RuntimeState state) {
        return new RuntimeSnapshot(
                catalogEntry.getAppId(), state, "", 0, System.currentTimeMillis());
    }

    @SuppressLint("Deprecation")
    private void applySystemBars() {
        Window window = getWindow();
        window.setNavigationBarColor(getColor(R.color.surface));
        int nightMode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        boolean light = nightMode == Configuration.UI_MODE_NIGHT_NO;
        // getDecorView() installs the decor on demand, so it must run before any
        // insets-controller access. On API 30/31 Window.getInsetsController()
        // dereferences the decor directly and throws while it does not exist yet
        // — which is the whole window during onCreate — while the decor view's
        // own accessor simply reports "not attached" as null.
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller == null) {
                // The controller exists only once the decor view is attached.
                // View.post() queues the runnable until attach, so the request
                // is replayed on the same window instead of being dropped; once
                // attached there is nothing left to wait for, so no retry loop.
                if (!decor.isAttachedToWindow()) {
                    decor.post(this::applySystemBars);
                }
                return;
            }
            controller.hide(WindowInsets.Type.statusBars());
            controller.setSystemBarsBehavior(
                    android.view.WindowInsetsController
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.setSystemBarsAppearance(
                    light ? android.view.WindowInsetsController
                            .APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                    android.view.WindowInsetsController
                            .APPEARANCE_LIGHT_NAVIGATION_BARS);
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (light) {
            systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(systemUiVisibility);
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                // API 29 clears FULLSCREEN when the IME takes focus; reassert it
                // after that transition. A fullscreen window never receives an
                // IME bottom inset, so keeping the accessory keys above the
                // keyboard is the visible-frame listener's job in
                // applyWindowInsets(), not SOFT_INPUT_ADJUST_RESIZE's.
                decor.postDelayed(this::applySystemBars, 100L);
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Native selection ActionMode and the IME may temporarily reveal
            // system chrome. Restore the terminal's fullscreen contract when
            // focus returns without recreating its WebView/session.
            applySystemBars();
        }
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
        int[] legacyBarInsets = new int[4];
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
                // A fullscreen API 29 window does not receive an IME bottom
                // inset, so keep the bar insets here and let the visible-frame
                // listener below add the keyboard height.
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                legacyBarInsets[0] = left;
                legacyBarInsets[1] = top;
                legacyBarInsets[2] = right;
                legacyBarInsets[3] = bottom;
            }
            setShellPadding(view, left, top, right, bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android.graphics.Rect visibleFrame = new android.graphics.Rect();
            shell.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                shell.getWindowVisibleDisplayFrame(visibleFrame);
                int rootHeight = shell.getRootView().getHeight();
                int coveredHeight = Math.max(0, rootHeight - visibleFrame.bottom);
                int imeHeight = coveredHeight > rootHeight / 4 ? coveredHeight : 0;
                setShellPadding(shell,
                        legacyBarInsets[0], legacyBarInsets[1], legacyBarInsets[2],
                        Math.max(legacyBarInsets[3], imeHeight));
            });
        }
        shell.requestApplyInsets();
    }

    private static void setShellPadding(View view, int left, int top, int right, int bottom) {
        if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
            view.setPadding(left, top, right, bottom);
        }
    }
}
