package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.util.Log;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesSessionStateStore;
import gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridgeTransportFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSecurityInitializer;

import java.util.UUID;

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
 * <p>The service also owns the <em>terminal</em> SSH client session
 * (ADR-0033): a {@link TerminalSessionController} follows the runtime session
 * and the same notification carries the terminal state with a Reconnect
 * action when the shell dropped or failed while Linux stayed up. The terminal
 * surface in the app is a consumer of that session, so Activity recreation
 * never closes the shell.</p>
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
     * Intent action to explicitly re-attach the terminal SSH session after its
     * shell dropped or failed while the runtime stayed up (ADR-0033). A no-op
     * unless a runtime session is running.
     */
    public static final String ACTION_TERMINAL_RECONNECT =
            "gh.nusashell.nusadesk.action.TERMINAL_RECONNECT";

    /** Intent extra: app id of the runtime to start. */
    public static final String EXTRA_APP_ID = "appId";
    /** Intent extra: app version of the runtime to start. */
    public static final String EXTRA_APP_VERSION = "appVersion";
    /** Intent extra: session id to assign to the new session. */
    public static final String EXTRA_SESSION_ID = "sessionId";

    private NotificationManager notificationManager;
    private RuntimeHostController controller;
    private TerminalSessionController terminalController;
    private SharedPreferencesSessionStateStore sessionStateStore;
    /** Last runtime status; the terminal-driven notification refresh re-renders it. */
    private HostRuntimeStatus lastStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Stored once: a capturing method reference creates a fresh object per
    // evaluation, so register/unregister must share one instance.
    private final TerminalSessionBus.Listener terminalListener = this::onTerminalStatus;

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
        terminalController = new TerminalSessionController(
                new SshClientBridgeTransportFactory(
                        new KeystoreVaultCredentialProvider(this),
                        new SharedPreferencesHostKeyTrustStore(this),
                        SshReconnectPolicy.DEFAULT,
                        System::currentTimeMillis),
                TerminalSessionBus.getInstance()::publish,
                mainHandler::post);
        TerminalSessionRegistry.getInstance().register(terminalController);
        TerminalSessionBus.getInstance().register(terminalListener);
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
            controller.stop(this::onStatus);
        } else if (ACTION_TERMINAL_RECONNECT.equals(action)) {
            // Explicit re-attach from the notification after the shell dropped
            // or failed while Linux stayed up. The controller ignores it when
            // no runtime session is running (ADR-0033); a stale action from a
            // restarted process (runtime lost, reconciled FAILED) must not
            // leave the service foregrounded with nothing to do.
            terminalController.reconnect();
            if (lastStatus == null || !lastStatus.isRuntimeRunning()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        } else if (ACTION_START.equals(action)) {
            String appId = intent.getStringExtra(EXTRA_APP_ID);
            String appVersion = intent.getStringExtra(EXTRA_APP_VERSION);
            String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            if (isBlank(appId) || isBlank(appVersion) || isBlank(sessionId)) {
                // Malformed start intent: nothing valid to do, release the slot.
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else {
                controller.start(appId, appVersion, sessionId, this::onStatus);
            }
        } else if (ACTION_ENSURE_RUNNING.equals(action)) {
            // App-visible autostart: the app is in the foreground, so the local
            // Linux runtime must be up. The identity comes from the curated
            // catalog, not from the caller, and a live session is left alone.
            RuntimeCatalogEntry entry = CuratedRuntimeCatalog.ubuntuBaseArm64();
            controller.ensureRunning(entry.getAppId(), entry.getVersion(),
                    UUID.randomUUID().toString(), this::onStatus);
        } else {
            // Unknown or null action (including a redelivered start): nothing to do.
            stopSelf();
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
        // The terminal session follows the runtime session: it opens once the
        // runtime is running and closes the moment it is not (ADR-0033). Feed
        // it before rendering so the notification reflects the new terminal
        // state together with the runtime state.
        terminalController.onRuntimeStatus(status);
        updateNotification(status);
        RuntimeStatusBus.getInstance().publish(status);
        persistSnapshot(status);
        Log.i(TAG, "runtime session state=" + status.getState()
                + " endpoint=" + endpointText(status)
                + failureText(status));
    }

    /**
     * Terminal session status deliveries (always on the main thread, via the
     * bus): re-render the notification with the fresh terminal line and
     * Reconnect action. No runtime status is touched.
     */
    private void onTerminalStatus(TerminalSessionStatus status) {
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
        if (RuntimeNotificationPolicy.requiresForeground(status)) {
            startForeground(NOTIFICATION_ID, buildNotification(status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private Notification buildNotification(HostRuntimeStatus status) {
        TerminalSessionStatus terminal = TerminalSessionBus.getInstance().current();
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
        if (controller.requiresLiveWorkload()) {
            Log.i(TAG, "service destroyed while the runtime was live; stopping it");
            controller.stop(this::publishDestroyedStatus);
        }
        // The terminal session belongs to this service: release it so no view
        // can keep a session the host no longer supervises, and drop the
        // registry handle so a freshly created surface resolves a new session
        // instead of a stale one.
        TerminalSessionBus.getInstance().unregister(terminalListener);
        terminalController.close();
        TerminalSessionRegistry.getInstance().clear();
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
     * <p>Callers must only use this from a user-visible launch. There is
     * deliberately no boot receiver, job, alarm, or sticky-restart path that
     * could start Linux without the user opening the app (ADR-0013).</p>
     */
    public static void ensureRunning(Context context) {
        Intent intent = new Intent(context, RuntimeHostService.class)
                .setAction(ACTION_ENSURE_RUNNING);
        context.startForegroundService(intent);
    }

    /** Helper for callers to request a runtime stop. */
    public static void requestStop(Context context) {
        Intent intent = new Intent(context, RuntimeHostService.class).setAction(ACTION_STOP);
        context.startForegroundService(intent);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
