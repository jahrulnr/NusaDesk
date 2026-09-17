package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaError;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaMode;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaState;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;

/**
 * Foreground service for the unified live camera + microphone stream.
 *
 * <p>Started only by {@code media.start} through the capability bridge (a
 * user-invoked guest command while the app is foreground-eligible), it
 * promotes itself with the {@code camera|microphone} foreground-service type
 * and a user-visible notification with a Stop action, runs the
 * {@link AndroidLiveMediaPipeline}, and publishes the typed start result to
 * {@link LiveMediaRegistry}. The service never writes capture artifacts:
 * no JPEG/M4A/MP4 file exists, so stop is a pure teardown.</p>
 *
 * <p>The pipeline is the {@link LiveMediaPipeline} seam so JVM tests can
 * substitute a deterministic fake; the real pipeline is device-only.</p>
 */
public final class LiveMediaService extends Service {
    private static final String TAG = "LiveMediaService";

    /** Intent action to start (or restart) the live media pipeline. */
    public static final String ACTION_START =
            "gh.nusashell.nusadesk.action.LIVE_MEDIA_START";
    /** Intent action to stop the live media pipeline. */
    public static final String ACTION_STOP =
            "gh.nusashell.nusadesk.action.LIVE_MEDIA_STOP";
    /** Extra carrying the requested {@link LiveMediaMode} name on ACTION_START. */
    public static final String EXTRA_MODE =
            "gh.nusashell.nusadesk.extra.LIVE_MEDIA_MODE";
    /** Notification channel id for the live media status. */
    public static final String CHANNEL_ID = "live_media";

    private static final int NOTIFICATION_ID = 0x4D44; // "MD"
    private static final int STOP_REQUEST_CODE = 7;

    /**
     * Test seam: when set, {@link #handleStart()} runs this pipeline instead
     * of the real capture pipeline, so JVM tests exercise the service
     * lifecycle without a camera or hardware encoders. Public because the
     * bridge-side controller tests drive the real service through Robolectric
     * from another package; never set in production code.
     */
    public static volatile LiveMediaPipeline pipelineOverride;

    private LiveMediaRegistry registry;
    private ExecutorService worker;
    private volatile LiveMediaPipeline pipeline;
    private volatile boolean starting;
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        registry = LiveMediaRegistry.getInstance();
        createNotificationChannel();
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "live-media-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            handleStop();
            return START_NOT_STICKY;
        }
        LiveMediaMode mode = requestedMode(intent);
        // Promote to foreground immediately to satisfy the
        // startForegroundService contract regardless of the action, with the
        // foreground-service types this mode actually uses.
        if (ACTION_START.equals(action)) {
            if (promoteForeground(mode)) {
                handleStart(mode);
            }
        } else {
            // Unknown or null action (including a redelivered start): nothing
            // valid to do, release the slot.
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Requested mode from the start intent; unknown values default to both. */
    private static LiveMediaMode requestedMode(Intent intent) {
        String name = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
        if (name == null) {
            return LiveMediaMode.BOTH;
        }
        try {
            return LiveMediaMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return LiveMediaMode.BOTH;
        }
    }

    private void handleStart(LiveMediaMode mode) {
        if (stopping) {
            stopSelf();
            return;
        }
        if (starting) {
            return; // an in-flight start owns the pipeline
        }
        if (pipeline != null && registry.currentStatus().getState()
                == LiveMediaState.RUNNING
                && registry.currentStatus().getMode() == mode) {
            // Idempotent start: the live session is already the answer.
            registry.publishResult(registry.currentStatus());
            return;
        }
        starting = true;
        registry.publishStarting(mode);
        worker.execute(() -> {
            try {
                if (stopping) {
                    registry.publishStopped();
                    stopSelf();
                    return;
                }
                LiveMediaPipeline built = pipelineOverride != null
                        ? pipelineOverride
                        : new AndroidLiveMediaPipeline(getApplicationContext());
                pipeline = built;
                LiveMediaStatus result = built.start(mode);
                if (stopping) {
                    built.stop();
                    pipeline = null;
                    registry.publishStopped();
                    stopSelf();
                    return;
                }
                registry.publishResult(result);
                if (result.getState() == LiveMediaState.FAILED) {
                    // A failed pipeline has nothing user-visible to keep alive.
                    pipeline = null;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            } finally {
                starting = false;
            }
        });
    }

    private void handleStop() {
        stopping = true;
        worker.execute(() -> {
            LiveMediaPipeline current = pipeline;
            if (current != null) {
                current.stop();
            }
            pipeline = null;
            registry.publishStopped();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    /**
     * Promote to foreground with exactly the types the mode uses; a failure is
     * reported as a typed PERMISSION_DENIED result instead of crashing the
     * service (the platform refuses a camera/microphone type without its
     * grant on API 30+).
     *
     * @return true when the service is foreground and the start may continue
     */
    private boolean promoteForeground(LiveMediaMode mode) {
        int type = foregroundTypeFor(mode);
        try {
            startForeground(NOTIFICATION_ID, buildNotification(mode), type);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not promote live media service for mode "
                    + mode.wireName(), e);
            registry.publishResult(
                    LiveMediaStatus.failed(mode, LiveMediaError.PERMISSION_DENIED));
            stopSelf();
            return false;
        }
    }

    private static int foregroundTypeFor(LiveMediaMode mode) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            // API 29 predates the runtime types: the manifest declaration is
            // the only type source, and the platform performs no type check.
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
        }
        // API 30+ enforces the declared type at runtime, so a camera-only or
        // microphone-only session claims only the type it actually uses.
        int type = 0;
        if (mode.hasVideo()) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        if (mode.hasAudio()) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return type == 0 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST : type;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        worker.shutdownNow();
        LiveMediaPipeline current = pipeline;
        if (current != null) {
            current.stop();
        }
        pipeline = null;
        registry.publishStopped();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Live media", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "Camera and microphone streaming to the local Linux guest.");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(LiveMediaMode mode) {
        Intent stopIntent = new Intent(this, LiveMediaService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, STOP_REQUEST_CODE,
                stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NusaDesk live media")
                .setContentText(notificationText(mode))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stopAction).build())
                .build();
    }

    /** Honest per-mode notification text: never claims a track that is off. */
    private static String notificationText(LiveMediaMode mode) {
        String tracks;
        if (mode.hasVideo() && mode.hasAudio()) {
            tracks = "Camera and microphone";
        } else if (mode.hasVideo()) {
            tracks = "Camera";
        } else {
            tracks = "Microphone";
        }
        return tracks + " streaming to the Linux guest on the loopback address.";
    }
}
