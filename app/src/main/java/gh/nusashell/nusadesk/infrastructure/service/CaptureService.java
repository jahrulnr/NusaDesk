package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import gh.nusashell.nusadesk.infrastructure.androidbridge.CameraPhotoSource;
import gh.nusashell.nusadesk.infrastructure.androidbridge.MicrophoneRecorderSource;

/**
 * Foreground service that owns the capture capability's device work: one
 * bounded camera photo and one stateful microphone recording.
 *
 * <p>The service exists because Android requires a {@code camera} /
 * {@code microphone} foreground service while the app holds those devices
 * (see {@link LiveMediaService} for the live-media sibling). The bridge
 * never starts it from the background: {@code CaptureForegroundOperation}
 * launches the transparent host activity first and calls
 * {@code startForegroundService} once it is resumed, so the service start
 * and the device access both run in a while-in-use state. A platform
 * refusal still possible there surfaces as the typed
 * {@code camera-foreground-required} / {@code microphone-foreground-required}
 * outcome through {@link CaptureRegistry}.</p>
 *
 * <p>The recording survives the guest command that started it: the guest
 * calls {@code microphone.record.start} once and {@code .stop} later, while
 * this service holds the {@link MicrophoneRecorderSource} across both
 * calls.</p>
 */
public final class CaptureService extends Service {

    private static final String TAG = "CaptureService";

    private static final String ACTION_PREFIX = "gh.nusashell.nusadesk.action.";
    private static final String EXTRA_PREFIX = "gh.nusashell.nusadesk.extra.";

    public static final String ACTION_PHOTO = ACTION_PREFIX + "CAPTURE_PHOTO";
    public static final String ACTION_PHOTO_CANCEL =
            ACTION_PREFIX + "CAPTURE_PHOTO_CANCEL";
    public static final String ACTION_RECORD_START =
            ACTION_PREFIX + "CAPTURE_RECORD_START";
    public static final String ACTION_RECORD_STOP =
            ACTION_PREFIX + "CAPTURE_RECORD_STOP";

    public static final String EXTRA_HOST_PATH = EXTRA_PREFIX + "CAPTURE_HOST_PATH";
    public static final String EXTRA_CAMERA_ID = EXTRA_PREFIX + "CAPTURE_CAMERA_ID";
    public static final String EXTRA_LIMIT_MS = EXTRA_PREFIX + "CAPTURE_LIMIT_MS";
    public static final String EXTRA_ENCODER = EXTRA_PREFIX + "CAPTURE_ENCODER";
    public static final String EXTRA_BITRATE_BPS =
            EXTRA_PREFIX + "CAPTURE_BITRATE_BPS";
    public static final String EXTRA_SAMPLE_RATE =
            EXTRA_PREFIX + "CAPTURE_SAMPLE_RATE";
    public static final String EXTRA_CHANNELS = EXTRA_PREFIX + "CAPTURE_CHANNELS";
    public static final String EXTRA_SEQ = EXTRA_PREFIX + "CAPTURE_SEQ";

    private static final String CHANNEL_ID = "capture";
    private static final int NOTIFICATION_ID = 0x4350; // "CP"

    private static final int TYPE_NONE = 0;
    private static final int TYPE_CAMERA = 1;
    private static final int TYPE_MICROPHONE = 2;

    private CaptureRegistry registry;
    private ExecutorService worker;
    private NotificationManager notifications;
    private final AtomicInteger foregroundTypes = new AtomicInteger(TYPE_NONE);

    private volatile CameraPhotoSource photoTask;
    private volatile long photoTaskSeq = -1;
    private volatile MicrophoneRecorderSource recorder;
    private volatile long recorderSeq = -1;

    public static Intent photoIntent(Context context, long seq,
                                     String cameraId, String hostPath) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.setAction(ACTION_PHOTO);
        intent.putExtra(EXTRA_SEQ, seq);
        intent.putExtra(EXTRA_CAMERA_ID, cameraId);
        intent.putExtra(EXTRA_HOST_PATH, hostPath);
        return intent;
    }

    public static Intent photoCancelIntent(Context context, long seq) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.setAction(ACTION_PHOTO_CANCEL);
        intent.putExtra(EXTRA_SEQ, seq);
        return intent;
    }

    public static Intent recordStartIntent(Context context, long seq,
                                           String hostPath, long limitMs,
                                           String encoder, long bitrateBps,
                                           long sampleRate, long channels) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.setAction(ACTION_RECORD_START);
        intent.putExtra(EXTRA_SEQ, seq);
        intent.putExtra(EXTRA_HOST_PATH, hostPath);
        intent.putExtra(EXTRA_LIMIT_MS, limitMs);
        intent.putExtra(EXTRA_ENCODER, encoder);
        intent.putExtra(EXTRA_BITRATE_BPS, bitrateBps);
        intent.putExtra(EXTRA_SAMPLE_RATE, sampleRate);
        intent.putExtra(EXTRA_CHANNELS, channels);
        return intent;
    }

    public static Intent recordStopIntent(Context context, long seq) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.setAction(ACTION_RECORD_STOP);
        intent.putExtra(EXTRA_SEQ, seq);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registry = CaptureRegistry.getInstance();
        worker = Executors.newSingleThreadExecutor();
        notifications = getSystemService(NotificationManager.class);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Capture", NotificationManager.IMPORTANCE_LOW);
            notifications.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PHOTO.equals(action)) {
            onPhotoIntent(intent);
        } else if (ACTION_PHOTO_CANCEL.equals(action)) {
            onPhotoCancelIntent(intent);
        } else if (ACTION_RECORD_START.equals(action)) {
            onRecordStartIntent(intent);
        } else if (ACTION_RECORD_STOP.equals(action)) {
            onRecordStopIntent(intent);
        } else {
            stopSelfIfIdle();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------
    // photo
    // ------------------------------------------------------------------

    private void onPhotoIntent(Intent intent) {
        long seq = intent.getLongExtra(EXTRA_SEQ, -1);
        String cameraId = intent.getStringExtra(EXTRA_CAMERA_ID);
        String hostPath = intent.getStringExtra(EXTRA_HOST_PATH);
        if (seq < 0 || hostPath == null || hostPath.isEmpty()) {
            registry.failPhoto(seq, "camera-unavailable");
            stopSelfIfIdle();
            return;
        }
        if (photoTask != null) {
            registry.failPhoto(seq, "camera-busy");
            stopSelfIfIdle();
            return;
        }
        if (!promote(TYPE_CAMERA | activeTypes())) {
            registry.failPhoto(seq,
                    "camera-foreground-required:open the NusaDesk app and retry");
            stopSelfIfIdle();
            return;
        }
        String requestedCamera = cameraId == null ? "0" : cameraId;
        runOnWorker(() -> {
            CameraPhotoSource task = new CameraPhotoSource(getApplicationContext(),
                    requestedCamera, Paths.get(hostPath));
            photoTask = task;
            photoTaskSeq = seq;
            try {
                long bytes = task.capture();
                registry.publishPhoto(seq, bytes);
            } catch (CameraPhotoSource.CaptureException e) {
                registry.failPhoto(seq, e.code());
            } catch (RuntimeException e) {
                Log.w(TAG, "photo task failed", e);
                registry.failPhoto(seq, "camera-unavailable");
            } finally {
                photoTask = null;
                photoTaskSeq = -1;
                refreshForeground();
            }
        });
    }

    private void onPhotoCancelIntent(Intent intent) {
        long seq = intent.getLongExtra(EXTRA_SEQ, -1);
        CameraPhotoSource task = photoTask;
        if (task != null && seq == photoTaskSeq) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------
    // recording
    // ------------------------------------------------------------------

    private void onRecordStartIntent(Intent intent) {
        long seq = intent.getLongExtra(EXTRA_SEQ, -1);
        String hostPath = intent.getStringExtra(EXTRA_HOST_PATH);
        if (seq < 0 || hostPath == null || hostPath.isEmpty()) {
            registry.failRecording(seq, "microphone-unavailable");
            stopSelfIfIdle();
            return;
        }
        if (recorder != null) {
            registry.failRecording(seq, "microphone-busy");
            stopSelfIfIdle();
            return;
        }
        if (!promote(TYPE_MICROPHONE | activeTypes())) {
            registry.failRecording(seq,
                    "microphone-foreground-required:open the NusaDesk app and retry");
            stopSelfIfIdle();
            return;
        }
        long limitMs = intent.getLongExtra(EXTRA_LIMIT_MS, 0);
        String encoder = intent.getStringExtra(EXTRA_ENCODER);
        long bitrateBps = intent.getLongExtra(EXTRA_BITRATE_BPS, 0);
        long sampleRate = intent.getLongExtra(EXTRA_SAMPLE_RATE, 0);
        long channels = intent.getLongExtra(EXTRA_CHANNELS, 0);
        runOnWorker(() -> {
            MicrophoneRecorderSource next = new MicrophoneRecorderSource(
                    getApplicationContext(), Paths.get(hostPath), limitMs,
                    encoder, bitrateBps, sampleRate, channels);
            next.setListener(new MicrophoneRecorderSource.Listener() {
                @Override public void onLimitReached() {
                    stopRecorder();
                }

                @Override public void onError() {
                    stopRecorder();
                }
            });
            try {
                next.start();
            } catch (MicrophoneRecorderSource.RecorderException e) {
                next.release();
                registry.failRecording(seq, e.code());
                refreshForeground();
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "recorder start failed", e);
                next.release();
                registry.failRecording(seq, "microphone-unavailable");
                refreshForeground();
                return;
            }
            recorder = next;
            recorderSeq = seq;
            if (!registry.publishRecording(seq)) {
                // The caller abandoned this start (a bounded wait expired);
                // stop the orphan so it never holds the microphone.
                recorder = null;
                recorderSeq = -1;
                next.stopQuietly();
                refreshForeground();
            }
        });
    }

    private void onRecordStopIntent(Intent intent) {
        runOnWorker(this::stopRecorderInternal);
    }

    /** Stop the active recorder; safe to call from any thread. */
    private void stopRecorder() {
        runOnWorker(this::stopRecorderInternal);
    }

    /**
     * Stop whichever recorder this service owns. The registry publish uses
     * the service's own recording sequence, so a stop works even when the
     * requesting call (module stop, limit callback, error, teardown) does
     * not carry a matching seq.
     */
    private void stopRecorderInternal() {
        MicrophoneRecorderSource current = recorder;
        long seq = recorderSeq;
        recorder = null;
        recorderSeq = -1;
        if (current == null) {
            refreshForeground();
            return;
        }
        long bytes = current.stopQuietly();
        if (bytes >= 0) {
            registry.publishStopped(seq, bytes);
        } else {
            registry.failStopped(seq, "microphone-unavailable");
        }
        refreshForeground();
    }

    // ------------------------------------------------------------------
    // foreground promotion / teardown
    // ------------------------------------------------------------------

    /** The device types owned by tasks the service is currently running. */
    private int activeTypes() {
        return (photoTask != null ? TYPE_CAMERA : TYPE_NONE)
                | (recorder != null ? TYPE_MICROPHONE : TYPE_NONE);
    }

    /**
     * Promote the service with exactly {@code types}, so a recording keeps
     * its microphone type while a photo runs alongside it, and a finished
     * photo drops the camera type again.
     */
    private boolean promote(int types) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(types),
                    platformTypes(types));
            foregroundTypes.set(types);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground promotion refused", e);
            return false;
        }
    }

    /**
     * Recompute the promoted types from live tasks; stop the service when
     * nothing remains. Narrowing the types after a photo finishes drops the
     * camera indicator while the recording keeps its microphone type.
     */
    private void refreshForeground() {
        int types = activeTypes();
        if (types == TYPE_NONE) {
            foregroundTypes.set(TYPE_NONE);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        if (types != foregroundTypes.get()) {
            promote(types);
        }
    }

    private void stopSelfIfIdle() {
        if (photoTask == null && recorder == null) {
            stopSelf();
        }
    }

    private void runOnWorker(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "capture task rejected; service is stopping");
        }
    }

    private int platformTypes(int types) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
        }
        int platform = 0;
        if ((types & TYPE_CAMERA) != 0) {
            platform |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        if ((types & TYPE_MICROPHONE) != 0) {
            platform |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return platform;
    }

    private Notification buildNotification(int types) {
        String text;
        if (types == (TYPE_CAMERA | TYPE_MICROPHONE)) {
            text = "Capturing a photo and recording audio for the Linux guest.";
        } else if (types == TYPE_MICROPHONE) {
            text = "Recording audio for the Linux guest.";
        } else {
            text = "Capturing a photo for the Linux guest.";
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NusaDesk capture")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        CameraPhotoSource photo = photoTask;
        if (photo != null) {
            photo.cancel();
        }
        MicrophoneRecorderSource current = recorder;
        recorder = null;
        if (current != null && current.isRecording()) {
            long bytes = current.stopQuietly();
            if (bytes >= 0) {
                registry.publishStopped(recorderSeq, bytes);
            }
        }
        registry.onServiceLost();
        if (worker != null) {
            worker.shutdownNow();
        }
        foregroundTypes.set(TYPE_NONE);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
