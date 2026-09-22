package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Media-playback foreground service behind the {@code mediaplayer.*} bridge
 * methods ({@code termux-media-player}).
 *
 * <p>One process-wide {@link MediaPlayer} holds at most one loaded track —
 * the same shape as upstream's {@code MediaPlayerService}. The bridge module
 * drives playback through the synchronized static operations below; the
 * service itself only owns the user-visible foreground contract: a
 * {@code mediaPlayback} foreground slot with a low-importance notification
 * and a Stop action, so audio keeps playing while the app is backgrounded
 * and always stays user-stoppable (the product rule for foreground work).</p>
 *
 * <p>The file is opened by the app and handed to the player as a descriptor
 * ({@link MediaPlayer#setDataSource(java.io.FileDescriptor)}), so a staged
 * rootfs path under app-private storage always reads on the app side instead
 * of depending on mediaserver path access. The manifest entry
 * ({@code foregroundServiceType="mediaPlayback"}, not exported) is wired by
 * the coordinator; {@code FOREGROUND_SERVICE_MEDIA_PLAYBACK} is already
 * declared.</p>
 */
public final class MediaPlaybackService extends Service {
    private static final String TAG = "MediaPlaybackService";

    /** Intent action: promote to the mediaPlayback foreground type. */
    public static final String ACTION_START =
            "gh.nusashell.nusadesk.action.MEDIA_PLAYBACK_START";
    /** Intent action: stop playback and tear the service down. */
    public static final String ACTION_STOP =
            "gh.nusashell.nusadesk.action.MEDIA_PLAYBACK_STOP";
    /** Notification channel id for the playback status. */
    public static final String CHANNEL_ID = "media_playback";

    private static final int NOTIFICATION_ID = 0x4D50; // "MP"
    private static final int STOP_REQUEST_CODE = 13;
    private static final long FOREGROUND_WAIT_MILLIS = 5_000L;

    private static final Object PLAYER_LOCK = new Object();
    private static MediaPlayer player;
    /** Stream backing the loaded track; open while the track exists. */
    private static boolean hasTrack;
    private static String trackName;
    private static volatile boolean foregroundActive;
    private static volatile CountDownLatch foregroundLatch;
    private static volatile Context appContext;

    /** Outcome of one static playback operation, mapped by the module. */
    public enum Outcome {
        /** A new track loaded and started. */
        PLAYED,
        /** A paused track resumed. */
        RESUMED,
        /** Resume was requested on a track already playing. */
        ALREADY_PLAYING,
        PAUSED,
        ALREADY_PAUSED,
        STOPPED,
        /** No track is loaded (pause/stop/resume/info with nothing to act on). */
        NO_TRACK,
        /** The platform refused the load/start. */
        FAILED,
        /** Snapshot answer for {@code mediaplayer.info}. */
        INFO
    }

    /** Immutable result handed to the bridge module for wire formatting. */
    public static final class PlaybackResult {
        private final Outcome outcome;
        private final boolean trackLoaded;
        private final boolean playing;
        private final long positionMs;
        private final long durationMs;
        private final String track;

        PlaybackResult(Outcome outcome, boolean trackLoaded, boolean playing,
                       long positionMs, long durationMs, String track) {
            this.outcome = outcome;
            this.trackLoaded = trackLoaded;
            this.playing = playing;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.track = track;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public boolean isTrackLoaded() {
            return trackLoaded;
        }

        public boolean isPlaying() {
            return playing;
        }

        /** Milliseconds into the track; {@code -1} when unknown. */
        public long getPositionMs() {
            return positionMs;
        }

        /** Track length in milliseconds; {@code -1} when unknown. */
        public long getDurationMs() {
            return durationMs;
        }

        /** Display name of the loaded track; {@code null} when none. */
        public String getTrack() {
            return track;
        }
    }

    /**
     * Promote the service to the {@code mediaPlayback} foreground type,
     * starting it when needed, and wait bounded for the promotion. Returns
     * {@code null} on success or a typed error code for the bridge module.
     */
    public static String ensureForeground(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        CountDownLatch latch;
        boolean starter;
        synchronized (PLAYER_LOCK) {
            if (foregroundActive) {
                return null;
            }
            // A counted-down latch means a previous attempt already settled;
            // a fresh start needs a fresh one so a refused promotion cannot
            // poison every later call.
            if (foregroundLatch == null || foregroundLatch.getCount() == 0) {
                foregroundLatch = new CountDownLatch(1);
                starter = true;
            } else {
                starter = false;
            }
            latch = foregroundLatch;
        }
        if (starter) {
            try {
                app.startForegroundService(new Intent(app, MediaPlaybackService.class)
                        .setAction(ACTION_START));
            } catch (IllegalStateException e) {
                // API 26+ background-start refusal (a
                // ForegroundServiceStartNotAllowedException on API 31+).
                synchronized (PLAYER_LOCK) {
                    foregroundLatch = null;
                }
                return "foreground-required:open the app or grant "
                        + "draw-over-apps via permission.request mode=settings "
                        + "permission=SYSTEM_ALERT_WINDOW";
            } catch (RuntimeException e) {
                Log.w(TAG, "media playback service start failed", e);
                synchronized (PLAYER_LOCK) {
                    foregroundLatch = null;
                }
                return "mediaplayer-unavailable";
            }
        }
        boolean promoted;
        try {
            promoted = latch.await(FOREGROUND_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                    && foregroundActive;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            promoted = false;
        }
        if (!promoted) {
            return "mediaplayer-unavailable";
        }
        return null;
    }

    /** Load {@code file} as the track (replacing any loaded one) and start. */
    public static PlaybackResult play(Path file, String displayName) {
        synchronized (PLAYER_LOCK) {
            ensurePlayerLocked();
            clearTrackLocked();
            // Play by path, not by file descriptor: the descriptor form
            // shares our stream's offset with the player and was measured on
            // the S10e (2026-09-22) to start and then stop the track within
            // ~35 ms (AudioService player state started -> stopped, and a
            // MediaPlayer error what=-38), while the same file plays by path.
            try {
                player.setDataSource(file.toAbsolutePath().toString());
                player.prepare();
                player.start();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "mediaplayer load/start failed", e);
                try {
                    player.reset();
                } catch (RuntimeException ignored) {
                    // Reset on a broken player is a no-op.
                }
                return result(Outcome.FAILED);
            }
            hasTrack = true;
            trackName = displayName;
            refreshNotificationLocked();
            return result(Outcome.PLAYED);
        }
    }

    /** Resume the loaded track, or report why that is not possible. */
    public static PlaybackResult resume() {
        synchronized (PLAYER_LOCK) {
            if (!hasTrack || player == null) {
                return result(Outcome.NO_TRACK);
            }
            if (isPlayingLocked()) {
                return result(Outcome.ALREADY_PLAYING);
            }
            try {
                player.start();
            } catch (RuntimeException e) {
                Log.w(TAG, "mediaplayer resume failed", e);
                return result(Outcome.FAILED);
            }
            refreshNotificationLocked();
            return result(Outcome.RESUMED);
        }
    }

    /** Pause the loaded track, or report why that is not possible. */
    public static PlaybackResult pause() {
        synchronized (PLAYER_LOCK) {
            if (!hasTrack || player == null) {
                return result(Outcome.NO_TRACK);
            }
            if (!isPlayingLocked()) {
                return result(Outcome.ALREADY_PAUSED);
            }
            try {
                player.pause();
            } catch (RuntimeException e) {
                Log.w(TAG, "mediaplayer pause failed", e);
                return result(Outcome.FAILED);
            }
            refreshNotificationLocked();
            return result(Outcome.PAUSED);
        }
    }

    /** Stop the loaded track and clear it. The module stops the service too. */
    public static PlaybackResult stopTrack() {
        synchronized (PLAYER_LOCK) {
            if (!hasTrack || player == null) {
                return result(Outcome.NO_TRACK);
            }
            clearTrackLocked();
            return result(Outcome.STOPPED);
        }
    }

    /** Snapshot the loaded track for {@code mediaplayer.info}. */
    public static PlaybackResult info() {
        synchronized (PLAYER_LOCK) {
            if (!hasTrack || player == null) {
                return result(Outcome.NO_TRACK);
            }
            return result(Outcome.INFO);
        }
    }

    /** Release the player and stop the service; called on bridge close. */
    public static void releaseAll(Context context) {
        Context app = context.getApplicationContext();
        synchronized (PLAYER_LOCK) {
            releasePlayerLocked();
        }
        try {
            app.stopService(new Intent(app, MediaPlaybackService.class));
        } catch (RuntimeException ignored) {
            // A never-started service stops silently anyway.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        appContext = getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            synchronized (PLAYER_LOCK) {
                releasePlayerLocked();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Any other start (ACTION_START, null, or unknown) still has to meet
        // the startForegroundService contract: promote first, then signal the
        // parked ensureForeground waiter.
        boolean promoted = promoteForeground();
        CountDownLatch latch = foregroundLatch;
        if (latch != null) {
            latch.countDown();
        }
        if (!promoted) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private boolean promoteForeground() {
        int type = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                // API 29 predates runtime types: the manifest declaration is
                // the only type source and the platform performs no check.
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        try {
            startForeground(NOTIFICATION_ID,
                    buildNotification(notificationText()), type);
        } catch (RuntimeException e) {
            Log.w(TAG, "media playback promotion refused", e);
            return false;
        }
        foregroundActive = true;
        return true;
    }

    @Override
    public void onDestroy() {
        synchronized (PLAYER_LOCK) {
            foregroundActive = false;
            foregroundLatch = null;
            releasePlayerLocked();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- shared player state -------------------------------------------------

    private static void ensurePlayerLocked() {
        if (player != null) {
            return;
        }
        player = new MediaPlayer();
        if (appContext != null) {
            player.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
        }
        player.setVolume(1.0f, 1.0f);
        // Explicit media attributes: the default (usage/content UNKNOWN) left
        // the stream unrouted in the audio policy on the S10e, and the track
        // drained instantly instead of playing.
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnCompletionListener(media -> {
            Context app = appContext;
            synchronized (PLAYER_LOCK) {
                clearTrackLocked();
            }
            // Track finished: nothing left to keep a foreground slot for.
            if (app != null) {
                try {
                    app.stopService(new Intent(app, MediaPlaybackService.class));
                } catch (RuntimeException ignored) {
                    // The service may already be going away.
                }
            }
        });
        // Unhandled errors flow into the completion callback, like upstream.
        player.setOnErrorListener((media, what, extra) -> {
            Log.w(TAG, "mediaplayer error what=" + what + " extra=" + extra);
            return false;
        });
    }

    private static void clearTrackLocked() {
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
                // stop() on an idle player throws; the reset below is enough.
            }
            player.reset();
        }
        hasTrack = false;
        trackName = null;
    }

    private static void releasePlayerLocked() {
        clearTrackLocked();
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
                // Releasing a broken player is best effort.
            }
            player = null;
        }
    }

    private static boolean isPlayingLocked() {
        try {
            return player != null && player.isPlaying();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static PlaybackResult result(Outcome outcome) {
        long position = -1;
        long duration = -1;
        boolean playing = false;
        if (hasTrack && player != null) {
            try {
                position = player.getCurrentPosition();
                duration = player.getDuration();
                playing = player.isPlaying();
            } catch (RuntimeException e) {
                position = -1;
                duration = -1;
            }
        }
        return new PlaybackResult(outcome, hasTrack, playing,
                position, duration, trackName);
    }

    // --- notification ---------------------------------------------------------

    private static String notificationText() {
        if (!hasTrack || trackName == null) {
            return "Playing audio for the Linux guest session.";
        }
        return (isPlayingLocked() ? "Playing: " : "Paused: ") + trackName;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Audio playback for the Linux guest session.");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static Notification buildNotification(Context context, String text) {
        Intent stopIntent = new Intent(context, MediaPlaybackService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(context, STOP_REQUEST_CODE,
                stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NusaDesk media playback")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stopAction).build())
                .build();
    }

    private Notification buildNotification(String text) {
        return buildNotification(this, text);
    }

    /** Refresh the visible text while foreground; a no-op otherwise. */
    private static void refreshNotificationLocked() {
        Context context = appContext;
        if (context == null || !foregroundActive) {
            return;
        }
        try {
            NotificationManager manager =
                    context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID,
                        buildNotification(context, notificationText()));
            }
        } catch (RuntimeException ignored) {
            // A stale notification is cosmetic only; playback is unaffected.
        }
    }
}
