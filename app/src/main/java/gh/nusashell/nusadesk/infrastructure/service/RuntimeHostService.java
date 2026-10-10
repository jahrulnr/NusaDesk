package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.util.Log;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesSessionStateStore;
import gh.nusashell.nusadesk.infrastructure.ssh.GuestLivenessProbe;
import gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridgeTransportFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSecurityInitializer;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service host shell for the local Linux runtime.
 *
 * <p>This is a lifecycle host only: it owns the foreground notification, the
 * start/stop intent actions, and the {@link RuntimeHostController}. It does
 * <em>not</em> run PRoot, spawn processes, or start arbitrary code. It delegates
 * start/stop to the single workload registered in
 * {@link RuntimeWorkloadRegistry}; while that registry is empty the service
 * refuses to start a runtime and surfaces a typed failure.
 *
 * <p>The product has no session start/stop control: Linux belongs to the app
 * and starts from a user-visible launch through
 * {@link #ensureRunning(Context)} (ADR-0013). The service therefore stays in
 * the foreground for as long as the runtime is up — backgrounding the app does
 * not stop Linux — while the notification and its Stop action remain, because
 * Android requires ongoing foreground work to be user-visible and stoppable.
 *
 * <p>The service never reports the runtime as running merely because it exists.
 * The {@code RUNNING} notification appears only after the controller accepts a
 * readiness frame and obtains a concrete loopback endpoint.
 *
 * <p>The service also owns the <em>terminal</em> SSH client sessions
 * (ADR-0033, extended to tabs by ADR-0054): a {@link TerminalTabsController}
 * follows the runtime session, opening one initial shell tab on the first
 * {@code RUNNING} status and closing every open tab when the runtime leaves
 * {@code RUNNING}. The user may close any tab without stopping Linux; a clean
 * remote channel exit removes only that tab. The notification carries the
 * selected tab's terminal state with a Reconnect action when its connection
 * dropped or failed while Linux stayed up. The terminal surface consumes the
 * sessions, so Activity recreation never closes them.</p>
 *
 * <p>The foreground service type is {@code specialUse} with a documented
 * runtime-host subtype. The subtype rationale and any distribution-channel
 * policy review (for example Google Play's special-use review) still need to be
 * completed before a public release; see ADR-0007.
 */
public final class RuntimeHostService extends Service {

    /** Notification channel id for the runtime host status. */
    public static final String CHANNEL_ID = "runtime_host";

    private static final String TAG = "RuntimeHostService";
    private static final int NOTIFICATION_ID = 0x4C57; // "LW"
    private static final int STOP_REQUEST_CODE = 1;
    private static final int CONTENT_REQUEST_CODE = 2;
    private static final int TERMINAL_RECONNECT_REQUEST_CODE = 3;
    private static final int RESTART_REQUEST_CODE = 4;

    /**
     * Liveness canary cadence and per-probe bound (ADR-0062): one probe every
     * 15 s with an 8 s hard timeout, so three consecutive misses declare the
     * session unresponsive after about 45 s while a single busy-guest stall
     * never reaches the user.
     */
    private static final long LIVENESS_PROBE_INTERVAL_MILLIS = 15_000L;
    private static final long LIVENESS_PROBE_TIMEOUT_MILLIS = 8_000L;

    /** Intent action to start the runtime session. */
    public static final String ACTION_START =
            "gh.nusashell.nusadesk.action.RUNTIME_START";
    /** Intent action to stop the runtime session. */
    public static final String ACTION_STOP =
            "gh.nusashell.nusadesk.action.RUNTIME_STOP";
    /**
     * Intent action to idempotently ensure the runtime is running. This is the
     * autostart boundary an Activity foreground event uses (ADR-0013); it never
     * starts a second session while one is live.
     */
    public static final String ACTION_ENSURE_RUNNING =
            "gh.nusashell.nusadesk.action.RUNTIME_ENSURE_RUNNING";
    /**
     * Intent action to explicitly re-attach the selected terminal tab's SSH
     * session after its shell or command dropped or failed while the runtime
     * stayed up (ADR-0033, ADR-0054). A no-op unless a runtime session is
     * running and a tab is selected.
     */
    public static final String ACTION_TERMINAL_RECONNECT =
            "gh.nusashell.nusadesk.action.TERMINAL_RECONNECT";
    /**
     * Intent action to restart the Linux session: stop through the existing
     * workload stop path, then start again through the same
     * {@code ensureRunning} boundary an app launch uses (ADR-0062). Offered to
     * the user while the session is {@code UNRESPONSIVE}.
     */
    public static final String ACTION_RESTART =
            "gh.nusashell.nusadesk.action.RUNTIME_RESTART";

    /** Intent extra: app id of the runtime to start. */
    public static final String EXTRA_APP_ID = "appId";
    /** Intent extra: app version of the runtime to start. */
    public static final String EXTRA_APP_VERSION = "appVersion";
    /** Intent extra: session id to assign to the new session. */
    public static final String EXTRA_SESSION_ID = "sessionId";

    private NotificationManager notificationManager;
    private RuntimeHostController controller;
    private TerminalTabsController terminalTabs;
    private SharedPreferencesSessionStateStore sessionStateStore;
    /** The canary itself; bound in {@link #onCreate} to the same SSH wiring the terminal uses. */
    private GuestLivenessProbe livenessProbe;
    /** Single background thread for blocking probes; the main thread only schedules and consumes. */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "guest-liveness-probe");
        t.setDaemon(true);
        return t;
    });
    /** Guards against overlapping probes while a slow one is still inside its bound. */
    private boolean probeInFlight;
    /** A user-requested restart waiting for the stop path to reach a startable state. */
    private boolean restartPending;
    /** Set in onDestroy: no queued callback may re-foreground or restart after it. */
    private boolean destroyed;
    /** Last runtime status; the terminal-driven notification refresh re-renders it. */
    private HostRuntimeStatus lastStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Stored once: a capturing method reference creates a fresh object per
    // evaluation, so removeCallbacks and register/unregister must share one
    // instance each.
    private final Runnable livenessTick = this::onLivenessTick;
    private final TerminalTabsBus.Listener terminalTabsListener = this::onTerminalTabs;

    @Override
    public void onCreate() {
        super.onCreate();
        // MINA SSHD needs one-time Android init before any SSH client session.
        // The service may be started by a notification action (Reconnect) in a
        // process where the Activity never ran, so this must happen here too;
        // the call is idempotent.
        SshSecurityInitializer.initialize(this);
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        sessionStateStore = new SharedPreferencesSessionStateStore(this);
        controller = new RuntimeHostController(
                RuntimeWorkloadRegistry.getInstance(), System::currentTimeMillis);
        terminalTabs = new TerminalTabsController(
                new SshClientBridgeTransportFactory(
                        new KeystoreVaultCredentialProvider(this),
                        new SharedPreferencesHostKeyTrustStore(this),
                        SshReconnectPolicy.DEFAULT,
                        System::currentTimeMillis),
                TerminalTabsBus.getInstance()::publish,
                mainHandler::post);
        TerminalTabsRegistry.getInstance().register(terminalTabs);
        TerminalTabsBus.getInstance().register(terminalTabsListener);
        // The canary reuses the terminal's SSH wiring: the same vault
        // credential and pinned host-key store, on the fixed loopback
        // endpoint. No new network surface (ADR-0062).
        livenessProbe = new GuestLivenessProbe(
                new KeystoreVaultCredentialProvider(this),
                new SharedPreferencesHostKeyTrustStore(this),
                System::currentTimeMillis);
        // A snapshot persisted before the process died is reconciled honestly:
        // states that require a live workload become FAILED so no view can show
        // a false RUNNING for a runtime that no longer exists. This bookkeeping
        // publish is kept off the notification/service lifecycle on purpose —
        // see publishReconciled.
        controller.reconcileStored(sessionStateStore.loadCurrent(), this::publishReconciled);
    }

    /**
     * Publish a reconciled (bookkeeping) status to views and the store only.
     *
     * <p>The service is created by an intent, and a reconciled terminal status
     * arriving afterwards would otherwise release the foreground slot and
     * {@code stopSelf()} the very instance that intent just started: on-device
     * that stopped the service right after a new session reached
     * {@code RUNNING}, which left the live guest daemon unmanaged and made the
     * next intent reconcile a running session as FAILED. Only real transitions
     * drive {@link #publishStatus}.</p>
     */
    private void publishReconciled(HostRuntimeStatus status) {
        RuntimeStatusBus.getInstance().publish(status);
        persistSnapshot(status);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Linux runtime", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Status of the local Linux runtime host.");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action + " startId=" + startId);
        // Promote to foreground immediately to satisfy the startForegroundService
        // contract regardless of the requested action.
        promoteForeground();

        if (ACTION_STOP.equals(action)) {
            // An explicit Stop is the last word: it cancels a queued restart.
            restartPending = false;
            controller.stop(this::onStatus);
        } else if (ACTION_TERMINAL_RECONNECT.equals(action)) {
            // Explicit re-attach from the notification after the selected
            // tab's session dropped or failed while Linux stayed up. The
            // per-tab controller ignores it when no runtime session is
            // running (ADR-0033); a stale action from a restarted process
            // (runtime lost, reconciled FAILED) must not leave the service
            // foregrounded with nothing to do.
            TerminalTabSnapshot selected = TerminalTabsBus.getInstance().current().selected();
            TerminalSessionPort selectedTab =
                    selected == null ? null : terminalTabs.tab(selected.getId());
            if (selectedTab != null) {
                selectedTab.reconnect();
            }
            // A stale action while a session is stopping or restarting must
            // not destroy the host mid-flight (ADR-0062).
            releaseIfIdle();
        } else if (ACTION_START.equals(action)) {
            String appId = intent.getStringExtra(EXTRA_APP_ID);
            String appVersion = intent.getStringExtra(EXTRA_APP_VERSION);
            String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            if (isBlank(appId) || isBlank(appVersion) || isBlank(sessionId)) {
                // Malformed start intent: nothing valid to do, release the
                // slot — unless live work or a restart is still in flight.
                releaseIfIdle();
            } else {
                controller.start(appId, appVersion, sessionId, this::onStatus);
            }
        } else if (ACTION_RESTART.equals(action)) {
            // Restart Linux (ADR-0062): take the existing stop path, and let
            // publishStatus re-enter through ensureRunning once the session
            // reaches a startable state. This is not a second lifecycle.
            restartPending = true;
            controller.stop(this::onStatus);
        } else if (ACTION_ENSURE_RUNNING.equals(action)) {
            // App-visible autostart: the app is in the foreground, so the local
            // Linux runtime must be up. The identity comes from the curated
            // catalog, not from the caller, and a live session is left alone.
            RuntimeCatalogEntry entry = CuratedRuntimeCatalog.ubuntuBaseArm64();
            controller.ensureRunning(entry.getAppId(), entry.getVersion(),
                    UUID.randomUUID().toString(), this::onStatus);
        } else {
            // Unknown or null action (including a redelivered start): nothing
            // to do — but never release a live workload or a pending restart.
            releaseIfIdle();
        }
        return START_NOT_STICKY;
    }

    private void promoteForeground() {
        startForeground(NOTIFICATION_ID, buildNotification(controller.status()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
    }

    private void onStatus(HostRuntimeStatus status) {
        // Workload callbacks may arrive on worker threads; update the
        // notification on the main thread for ordering and safety.
        mainHandler.post(() -> publishStatus(status));
    }

    /**
     * Single sink for every status transition on the main thread: updates the
     * user-visible notification, publishes to {@link RuntimeStatusBus} so views
     * can render honest state, and persists the snapshot so process death never
     * resurrects a false RUNNING. Carries no secret material.
     */
    private void publishStatus(HostRuntimeStatus status) {
        lastStatus = status;
        // The terminal tabs follow the runtime session: the shell tab opens
        // once the runtime is running and every tab closes the moment it is
        // not (ADR-0033). Feed it before rendering so the notification
        // reflects the new terminal state together with the runtime state.
        terminalTabs.onRuntimeStatus(status);
        RuntimeStatusBus.getInstance().publish(status);
        persistSnapshot(status);
        Log.i(TAG, "runtime session state=" + status.getState()
                + " endpoint=" + endpointText(status)
                + failureText(status));
        if (destroyed) {
            // A workload callback queued before onDestroy must not re-arm the
            // canary, re-foreground the service, or resurrect a session.
            return;
        }
        updateLivenessSchedule(status);
        updateNotification(status);
        if (isTerminal(status.getState())) {
            // The service dies only here, when a publish proves nothing is
            // live and no restart is coming. A user-requested restart answers
            // at the first terminal publish. A real STOPPED means the
            // workload is gone and the new session can bind the fixed port
            // through the same ensureRunning boundary an app launch uses
            // (ADR-0062). A FAILED stop that carries the workload's typed
            // survivor report escalates instead (ADR-0063): the app arms a
            // revival job and kills its own process tree, because a workload
            // that survived termination would otherwise keep holding
            // 127.0.0.1:22022 orphaned — an ordinary self-kill leaves exactly
            // that behind. Any other terminal outcome is not retried: the
            // honest failure is what the user must see.
            SelfRestartEscalation.Action action =
                    SelfRestartEscalation.decide(restartPending, status);
            restartPending = false;
            switch (action) {
                case RESTART_SESSION: {
                    RuntimeCatalogEntry entry = CuratedRuntimeCatalog.ubuntuBaseArm64();
                    controller.ensureRunning(entry.getAppId(), entry.getVersion(),
                            UUID.randomUUID().toString(), this::onStatus);
                    break;
                }
                case RESTART_PROCESS_TREE: {
                    Log.w(TAG, "workload survived the forced stop; restarting "
                            + "the app process tree to recover");
                    boolean armed = SelfRestartEscalation.run(
                            new JobSchedulerSelfRestart(this));
                    // When armed, run() kills this process: reaching the next
                    // line means the kill could not complete or no trigger
                    // could be armed — either way the FAILED snapshot is the
                    // honest record, so release the slot normally.
                    if (!armed) {
                        Log.e(TAG, "no revival trigger could be armed; leaving "
                                + "the failed session to the user");
                    } else {
                        Log.e(TAG, "the process-tree kill returned; releasing "
                                + "the slot with FAILED persisted");
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    break;
                }
                default:
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
            }
        }
    }

    /**
     * Release the foreground slot for an intent that carries no live work —
     * a stale re-attach, a malformed start, an unknown action. A live
     * workload or a pending restart is never released by an intent.
     */
    private void releaseIfIdle() {
        if (!restartPending && !controller.requiresLiveWorkload()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /**
     * Arm or cancel the canary for the freshly published state (ADR-0062).
     * Only a live session — {@code RUNNING} or {@code UNRESPONSIVE} — is
     * probed; reaching {@code RUNNING} probes immediately instead of waiting
     * a full interval, so a wedge at start is caught within one bound.
     */
    private void updateLivenessSchedule(HostRuntimeStatus status) {
        mainHandler.removeCallbacks(livenessTick);
        if (status != null && isProbedState(status.getState())) {
            mainHandler.post(livenessTick);
        }
    }

    private static boolean isProbedState(SessionState state) {
        return state == SessionState.RUNNING || state == SessionState.UNRESPONSIVE;
    }

    /** States in which a stop request has fully answered. */
    private static boolean isTerminal(SessionState state) {
        return state == SessionState.STOPPED
                || state == SessionState.FAILED
                || state == SessionState.CANCELLED
                || state == SessionState.NOT_STARTED;
    }

    /**
     * One cadence tick on the main thread: hand a bounded probe to the
     * background executor and re-arm. Probe results are marshalled back to the
     * main thread before they touch the controller, which is deliberately not
     * thread-safe. A miss while the session is still healthy changes nothing
     * the user can see; the threshold lives in the domain policy.
     */
    private void onLivenessTick() {
        HostRuntimeStatus status = lastStatus;
        // A tick already queued when onDestroy ran must not touch the
        // shut-down probe executor.
        if (destroyed || status == null || !isProbedState(status.getState())) {
            return;
        }
        // Re-arm before deciding: a probe that outlives the interval skips
        // this tick but must never let the chain die.
        mainHandler.postDelayed(livenessTick, LIVENESS_PROBE_INTERVAL_MILLIS);
        if (probeInFlight) {
            return;
        }
        probeInFlight = true;
        probeExecutor.execute(() -> {
            boolean alive;
            try {
                alive = livenessProbe.probeOnce(LIVENESS_PROBE_TIMEOUT_MILLIS);
            } catch (RuntimeException e) {
                // A probe that cannot even run (e.g. credential still absent)
                // is a miss, never a crash of the supervision thread.
                alive = false;
            }
            final boolean result = alive;
            mainHandler.post(() -> {
                probeInFlight = false;
                controller.onLivenessProbe(result, this::onStatus);
            });
        });
    }

    /**
     * Terminal tabs snapshot deliveries (always on the main thread, via the
     * bus): re-render the notification with the selected tab's fresh terminal
     * line and Reconnect action. No runtime status is touched.
     */
    private void onTerminalTabs(TerminalTabsSnapshot snapshot) {
        HostRuntimeStatus runtime = lastStatus;
        if (runtime != null) {
            updateNotification(runtime);
        }
    }

    /** Persist the session snapshot so process death never resurrects a false RUNNING. */
    private void persistSnapshot(HostRuntimeStatus status) {
        SessionSnapshot snapshot = status.getSnapshot();
        if (snapshot == null) {
            return;
        }
        try {
            sessionStateStore.save(snapshot);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not persist session state", e);
        }
    }

    private static String failureText(HostRuntimeStatus status) {
        String reason = status.getFailureReason();
        return reason == null || reason.isEmpty() ? "" : " reason=" + reason;
    }

    private static String endpointText(HostRuntimeStatus status) {
        return status.getEndpoint() == null ? "none"
                : status.getEndpoint().getHost() + ":" + status.getEndpoint().getPort();
    }

    private void updateNotification(HostRuntimeStatus status) {
        // Foreground service notifications are updated by re-calling startForeground
        // with the same id (the documented FGS update pattern). This avoids
        // NotificationManager.notify(), which would require the POST_NOTIFICATIONS
        // runtime permission on Android 13+; FGS notifications are exempt.
        // This method only renders: it never calls stopSelf. A queued
        // re-render (e.g. a terminal-tabs bus delivery arriving between a
        // restart's STOPPED publish and its STARTING publish) must not be
        // able to schedule the destroy that would kill the in-flight restart
        // (ADR-0062) — the slot is released only by publishStatus's terminal
        // branch and onStartCommand's idle release.
        if (RuntimeNotificationPolicy.requiresForeground(status)) {
            startForeground(NOTIFICATION_ID, buildNotification(status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else if (!restartPending) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        // With a restart pending a terminal render is transient: the
        // notification stays up through the stop-to-start gap instead of
        // flickering out for one main-queue turn.
    }

    private Notification buildNotification(HostRuntimeStatus status) {
        // The notification line and Reconnect action describe the selected
        // tab; with no tabs (runtime down) that degrades to NOT_STARTED, which
        // the policy renders as "no terminal line, no action".
        TerminalTabSnapshot selected = TerminalTabsBus.getInstance().current().selected();
        TerminalSessionStatus terminal = selected == null
                ? TerminalSessionStatus.notStarted() : selected.getStatus();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(RuntimeNotificationPolicy.title(status))
                .setContentText(contentText(status, terminal))
                .setOngoing(RuntimeNotificationPolicy.isOngoing(status))
                .setOnlyAlertOnce(true)
                .setLocalOnly(true);
        PendingIntent content = launchAppIntent();
        if (content != null) {
            // The product has no start/stop control, so tapping the status
            // notification is the natural way back into the app — which is also
            // where the runtime autostarts. Resolved from the package manager
            // so this layer never names a presentation class.
            builder.setContentIntent(content);
        }
        if (RuntimeNotificationPolicy.showsStopAction(status)) {
            builder.addAction(new Notification.Action.Builder(
                    null, "Stop", stopPendingIntent()).build());
        }
        if (RuntimeNotificationPolicy.showsRestartAction(status)) {
            builder.addAction(new Notification.Action.Builder(
                    null, "Restart Linux", restartPendingIntent()).build());
        }
        if (TerminalNotificationPolicy.showsReconnectAction(terminal)) {
            builder.addAction(new Notification.Action.Builder(
                    null, "Reconnect", terminalReconnectPendingIntent()).build());
        }
        return builder.build();
    }

    /**
     * Runtime text plus the terminal line while the runtime is running, so the
     * notification states both halves of the session honestly: Linux up but
     * its shell dropped is visible at a glance, with the Reconnect action
     * beneath it.
     */
    private static String contentText(HostRuntimeStatus status, TerminalSessionStatus terminal) {
        String runtimeText = RuntimeNotificationPolicy.text(status);
        if (!status.isRuntimeRunning()) {
            return runtimeText;
        }
        String terminalLine = TerminalNotificationPolicy.line(terminal);
        return terminalLine.isEmpty() ? runtimeText : runtimeText + "\n" + terminalLine;
    }

    /** Launcher intent for this package, or {@code null} when unresolvable. */
    private PendingIntent launchAppIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            return null;
        }
        return PendingIntent.getActivity(this, CONTENT_REQUEST_CODE, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent stopPendingIntent() {
        Intent intent = new Intent(this, RuntimeHostService.class).setAction(ACTION_STOP);
        return PendingIntent.getService(this, STOP_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent terminalReconnectPendingIntent() {
        Intent intent = new Intent(this, RuntimeHostService.class)
                .setAction(ACTION_TERMINAL_RECONNECT);
        return PendingIntent.getService(this, TERMINAL_RECONNECT_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent restartPendingIntent() {
        Intent intent = new Intent(this, RuntimeHostService.class)
                .setAction(ACTION_RESTART);
        return PendingIntent.getService(this, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        // The host owns the runtime. If the service is destroyed while a
        // session still needs a live workload (a system-initiated stop, or any
        // caller using stopService instead of ACTION_STOP), the guest daemon
        // must not be left running unsupervised with its loopback port held.
        // Terminal states are a no-op: the normal stop path has already torn
        // the runtime down.
        // A destroy is final: no queued publish may re-foreground the service
        // or resurrect a session through a stale restart flag (ADR-0062).
        destroyed = true;
        restartPending = false;
        // The canary dies first: no probe may still be running — or get
        // scheduled — while the workload underneath it is being torn down.
        mainHandler.removeCallbacks(livenessTick);
        probeExecutor.shutdownNow();
        if (controller.requiresLiveWorkload()) {
            Log.i(TAG, "service destroyed while the runtime was live; stopping it");
            controller.stop(this::publishDestroyedStatus);
        }
        // The terminal sessions belong to this service: release them so no
        // view can keep a session the host no longer supervises, and drop the
        // registry handle so a freshly created surface resolves a new
        // controller instead of a stale one.
        TerminalTabsBus.getInstance().unregister(terminalTabsListener);
        terminalTabs.close();
        TerminalTabsRegistry.getInstance().clear();
        super.onDestroy();
    }

    /**
     * Status sink used only from {@link #onDestroy()}: publishes a truthful
     * state to views and persists it, but never touches the notification API —
     * the service is already being destroyed, so re-promoting it would be
     * invalid.
     */
    private void publishDestroyedStatus(HostRuntimeStatus status) {
        RuntimeStatusBus.getInstance().publish(status);
        persistSnapshot(status);
    }

    /**
     * Helper for callers to request a runtime start as a foreground service.
     */
    public static void requestStart(
            Context context, String appId, String appVersion, String sessionId) {
        Intent intent = new Intent(context, RuntimeHostService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_APP_ID, appId)
                .putExtra(EXTRA_APP_VERSION, appVersion)
                .putExtra(EXTRA_SESSION_ID, sessionId);
        context.startForegroundService(intent);
    }

    /**
     * Idempotent autostart boundary for an Activity foreground event
     * ({@code onStart}/{@code onResume}/{@code onCreate}).
     *
     * <p>This is the only start path the product needs: Linux belongs to the
     * app, so bringing the app to the foreground ensures the runtime is up, and
     * a runtime that is already starting or running is left untouched. The app
     * being backgrounded afterwards does <em>not</em> stop it — the foreground
     * service keeps the guest running with its user-visible notification and
     * Stop action.</p>
     *
     * <p>Callers must only use this from a user-visible launch or the user's
     * own opt-in "Start Linux at boot" trigger (ADR-0037): that receiver is
     * unexported, defaults OFF, and re-checks the persisted install state
     * before calling in. The one further caller is the self-restart revival
     * job (ADR-0063), delivered by the platform only after the user tapped
     * Restart — the same boundary, re-entered from a fresh process.</p>
     *
     * @return the platform's started-service result so a background caller
     *         can tell a refused start from an accepted one
     */
    public static ComponentName ensureRunning(Context context) {
        Intent intent = new Intent(context, RuntimeHostService.class)
                .setAction(ACTION_ENSURE_RUNNING);
        return context.startForegroundService(intent);
    }

    /** Helper for callers to request a runtime stop. */
    public static void requestStop(Context context) {
        Intent intent = new Intent(context, RuntimeHostService.class).setAction(ACTION_STOP);
        context.startForegroundService(intent);
    }

    /**
     * Helper for callers to request a runtime restart — the {@code Restart
     * Linux} action offered while the session is {@code UNRESPONSIVE}
     * (ADR-0062). Stops through the existing workload path, then starts again
     * through {@link #ensureRunning(Context)}'s boundary.
     */
    public static void requestRestart(Context context) {
        Intent intent = new Intent(context, RuntimeHostService.class)
                .setAction(ACTION_RESTART);
        context.startForegroundService(intent);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
