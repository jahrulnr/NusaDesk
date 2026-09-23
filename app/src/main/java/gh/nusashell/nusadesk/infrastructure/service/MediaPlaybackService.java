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
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    /** Routing view of {@link #player}; null exactly when the player is. */
    private static AudioRoutingPlayer routing;
    /**
     * The guest's preferred output id while it has not been applied to a live
     * player (or the platform refused it). Process-local only — audio device
     * ids are ephemeral, so the choice is never persisted and is revalidated
     * against a fresh {@code AudioManager.getDevices} list every time a track
     * begins.
     */
    private static Integer pendingRouteId;
    /** Stream backing the loaded track; open while the track exists. */
    private static boolean hasTrack;
    private static String trackName;
    private static volatile boolean foregroundActive;
    private static volatile CountDownLatch foregroundLatch;
    private static volatile Context appContext;
    /** Test seams; null selects the platform implementations. */
    private static volatile AudioOutputProvider outputProviderOverride;
    private static volatile AudioRoutingFactory routingFactoryOverride;

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
        /**
         * A pending preferred output could not be revalidated or applied when
         * the track began — the play is refused instead of silently reporting
         * success on the default route.
         */
        ROUTE_FAILED,
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
        private final Integer preferredOutputId;
        private final Integer routedOutputId;

        PlaybackResult(Outcome outcome, boolean trackLoaded, boolean playing,
                       long positionMs, long durationMs, String track,
                       Integer preferredOutputId, Integer routedOutputId) {
            this.outcome = outcome;
            this.trackLoaded = trackLoaded;
            this.playing = playing;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.track = track;
            this.preferredOutputId = preferredOutputId;
            this.routedOutputId = routedOutputId;
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

        /**
         * Id of the preferred output the player was given, or the pending
         * choice when no player exists; {@code null} when neither applies.
         */
        public Integer getPreferredOutputId() {
            return preferredOutputId;
        }

        /**
         * Id of the device the player is actually routed to; {@code null}
         * whenever the platform reports none (e.g. not playing), never
         * guessed.
         */
        public Integer getRoutedOutputId() {
            return routedOutputId;
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
            if (pendingRouteId != null) {
                AudioOutput target = findOutputLocked(
                        freshOutputsLocked(), pendingRouteId);
                if (target == null || !target.isSink()) {
                    return result(Outcome.ROUTE_FAILED);
                }
            }
            // A pending preferred output is revalidated against a fresh
            // device list and applied after prepare() has created the media
            // output track, but before start() can emit audio. A stale or
            // refused route fails the play instead of silently falling back
            // to the default device while claiming the route was honored.
            // Play by path, not by file descriptor: the descriptor form
            // shares our stream's offset with the player and was measured on
            // the S10e (2026-09-22) to start and then stop the track within
            // ~35 ms (AudioService player state started -> stopped, and a
            // MediaPlayer error what=-38), while the same file plays by path.
            try {
                player.setDataSource(file.toAbsolutePath().toString());
                player.prepare();
                if (applyPendingRouteLocked() != null) {
                    player.reset();
                    return result(Outcome.ROUTE_FAILED);
                }
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
            // Output ids belong to this bridge/process view only. Drop the
            // pending choice with the owning capability session so a later
            // session cannot reuse a stale or recycled platform id.
            pendingRouteId = null;
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
        AudioRoutingFactory factory = routingFactoryOverride;
        routing = factory != null
                ? factory.attach(player) : new MediaPlayerRouting(player);
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
        routing = null;
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
        Integer preferred = null;
        Integer routed = null;
        AudioRoutingPlayer current = routing;
        if (current != null) {
            preferred = current.preferredOutputId();
            routed = current.routedOutputId();
        }
        if (preferred == null) {
            // No live preferred device: report the remembered choice so a
            // pending route is visible before any player exists.
            preferred = pendingRouteId;
        }
        return new PlaybackResult(outcome, hasTrack, playing,
                position, duration, trackName, preferred, routed);
    }

    // --- audio output routing ---------------------------------------------------
    //
    // Public-API routing for this app's own MediaPlayer only: the bridge may
    // enumerate the current output sinks, pin the player to one of them via
    // AudioRouting.setPreferredDevice, and clear that pin. It is not global
    // route control and it does not connect audio profiles. Device ids are
    // ephemeral — every decision is made from a fresh
    // AudioManager.getDevices(GET_DEVICES_OUTPUTS) list and the remembered
    // choice lives only for this process.

    /** Max device-name length kept on the wire and in the pending choice. */
    static final int OUTPUT_NAME_MAX_CHARS = 128;

    /** One current output device: wire data plus the platform handle. */
    public static final class AudioOutput {
        private final int id;
        private final String type;
        private final String name;
        private final boolean sink;
        /** The platform device used to apply the route; null under fakes. */
        private final AudioDeviceInfo device;

        AudioOutput(int id, String type, String name, boolean sink,
                    AudioDeviceInfo device) {
            this.id = id;
            this.type = type == null ? "unknown" : type;
            this.name = sanitizeOutputName(name);
            this.sink = sink;
            this.device = device;
        }

        /** Ephemeral platform device id; never persisted. */
        public int getId() {
            return id;
        }

        /** Stable type string such as {@code bluetooth_a2dp}. */
        public String getType() {
            return type;
        }

        /** Bounded product name, control characters stripped. */
        public String getName() {
            return name;
        }

        /** Whether the device can be an output sink. */
        public boolean isSink() {
            return sink;
        }

        AudioDeviceInfo device() {
            return device;
        }
    }

    /** Fresh snapshot of the current output devices. */
    interface AudioOutputProvider {
        List<AudioOutput> outputs();
    }

    /** The routing calls of the one player, isolated for tests. */
    interface AudioRoutingPlayer {
        /** Pin the preferred output ({@code null} clears); false when refused. */
        boolean setPreferredOutput(AudioOutput output);

        /** Id of the player's preferred output, or null when none is set. */
        Integer preferredOutputId();

        /** Id of the device actually routed to, or null when the platform reports none. */
        Integer routedOutputId();
    }

    /** Builds the routing view for a freshly constructed player. */
    interface AudioRoutingFactory {
        AudioRoutingPlayer attach(MediaPlayer player);
    }

    /** Wire status of one {@code mediaplayer.route} call. */
    public enum RouteStatus {
        /** Applied to the live player. */
        ROUTED,
        /** No player exists; remembered for the next one. */
        PENDING,
        /** The id is absent from the fresh output list. */
        DEVICE_UNKNOWN,
        /** The id exists but is not an output sink. */
        DEVICE_NOT_OUTPUT,
        /** The platform refused to apply the preferred device. */
        FAILED
    }

    /** Immutable result of {@code mediaplayer.route} for wire formatting. */
    public static final class RouteResult {
        private final RouteStatus status;
        private final int deviceId;
        private final String name;
        private final String type;
        private final boolean applied;

        private RouteResult(RouteStatus status, int deviceId,
                            String name, String type, boolean applied) {
            this.status = status;
            this.deviceId = deviceId;
            this.name = name;
            this.type = type;
            this.applied = applied;
        }

        static RouteResult routed(AudioOutput output) {
            return new RouteResult(RouteStatus.ROUTED, output.getId(),
                    output.getName(), output.getType(), true);
        }

        static RouteResult pending(AudioOutput output) {
            return new RouteResult(RouteStatus.PENDING, output.getId(),
                    output.getName(), output.getType(), false);
        }

        static RouteResult deviceUnknown(int deviceId) {
            return new RouteResult(RouteStatus.DEVICE_UNKNOWN, deviceId,
                    null, null, false);
        }

        static RouteResult notOutput(AudioOutput output) {
            return new RouteResult(RouteStatus.DEVICE_NOT_OUTPUT, output.getId(),
                    output.getName(), output.getType(), false);
        }

        static RouteResult failed(AudioOutput output) {
            return new RouteResult(RouteStatus.FAILED, output.getId(),
                    output.getName(), output.getType(), false);
        }

        public RouteStatus getStatus() {
            return status;
        }

        public int getDeviceId() {
            return deviceId;
        }

        /** Sanitized device name; null when the id was not found. */
        public String getName() {
            return name;
        }

        /** Stable device type string; null when the id was not found. */
        public String getType() {
            return type;
        }

        /** Whether the route was applied to a live player. */
        public boolean isApplied() {
            return applied;
        }
    }

    /** Immutable result of {@code mediaplayer.route.clear}. */
    public static final class RouteClearResult {
        private final boolean cleared;
        private final boolean applied;

        private RouteClearResult(boolean cleared, boolean applied) {
            this.cleared = cleared;
            this.applied = applied;
        }

        /** Whether any remembered or live preferred route was dropped. */
        public boolean isCleared() {
            return cleared;
        }

        /** Whether the clear reached a live player. */
        public boolean isApplied() {
            return applied;
        }
    }

    /** Fresh list of the current output sinks for {@code mediaplayer.outputs}. */
    public static List<AudioOutput> outputs(Context context) {
        appContext = context.getApplicationContext();
        synchronized (PLAYER_LOCK) {
            return freshOutputsLocked();
        }
    }

    /**
     * {@code mediaplayer.route}: pin the shared player to the output whose id
     * is {@code deviceId}, or remember the choice process-locally when no
     * player exists yet. The id must exist in a fresh output list and be a
     * sink; a refused live apply keeps the pending choice so the next play
     * still surfaces the failure instead of silently rerouting.
     */
    public static RouteResult routeTo(Context context, int deviceId) {
        appContext = context.getApplicationContext();
        synchronized (PLAYER_LOCK) {
            AudioOutput target = findOutputLocked(freshOutputsLocked(), deviceId);
            if (target == null) {
                return RouteResult.deviceUnknown(deviceId);
            }
            if (!target.isSink()) {
                return RouteResult.notOutput(target);
            }
            pendingRouteId = deviceId;
            if (routing == null || !hasTrack) {
                return RouteResult.pending(target);
            }
            return applyRouteLocked(target)
                    ? RouteResult.routed(target) : RouteResult.failed(target);
        }
    }

    /**
     * {@code mediaplayer.route.clear}: drop the remembered choice and clear
     * the preferred device on the live player. Idempotent — a second call
     * reports {@code cleared=false} and never errors.
     */
    public static RouteClearResult clearRoute() {
        synchronized (PLAYER_LOCK) {
            boolean cleared = pendingRouteId != null;
            pendingRouteId = null;
            boolean applied = false;
            if (routing != null) {
                cleared = cleared || routing.preferredOutputId() != null;
                applied = applyRouteLocked(null);
            }
            return new RouteClearResult(cleared, applied);
        }
    }

    /**
     * Revalidate the remembered output id against a fresh device list and
     * apply it to the (idle) player. Returns {@link Outcome#ROUTE_FAILED}
     * when a choice exists but is no longer a sink or the platform refuses
     * it; {@code null} when nothing is pending or the apply succeeded.
     */
    private static Outcome applyPendingRouteLocked() {
        Integer id = pendingRouteId;
        if (id == null) {
            return null;
        }
        AudioOutput target = findOutputLocked(freshOutputsLocked(), id);
        if (target == null || !target.isSink()) {
            return Outcome.ROUTE_FAILED;
        }
        return applyRouteLocked(target) ? null : Outcome.ROUTE_FAILED;
    }

    /** {@code setPreferredDevice} on the live player; false when refused. */
    private static boolean applyRouteLocked(AudioOutput target) {
        AudioRoutingPlayer current = routing;
        if (current == null) {
            return false;
        }
        try {
            return current.setPreferredOutput(target);
        } catch (RuntimeException e) {
            Log.w(TAG, "mediaplayer route apply failed", e);
            return false;
        }
    }

    private static AudioOutput findOutputLocked(List<AudioOutput> outputs, int id) {
        for (AudioOutput output : outputs) {
            if (output != null && output.getId() == id) {
                return output;
            }
        }
        return null;
    }

    private static List<AudioOutput> freshOutputsLocked() {
        AudioOutputProvider provider = outputProviderOverride;
        if (provider == null) {
            Context app = appContext;
            provider = app == null ? null : new PlatformAudioOutputProvider(app);
        }
        if (provider == null) {
            return List.of();
        }
        try {
            List<AudioOutput> outputs = provider.outputs();
            return outputs == null ? List.of() : outputs;
        } catch (RuntimeException e) {
            Log.w(TAG, "mediaplayer output list failed", e);
            return List.of();
        }
    }

    /**
     * Package-private test seam: substitutes the device list and the routing
     * view of every subsequently constructed player (and re-attaches to a
     * live one), so the route policy is testable without a platform
     * {@link AudioDeviceInfo}.
     */
    static void setAudioRoutingForTest(AudioOutputProvider provider,
                                       AudioRoutingFactory factory) {
        synchronized (PLAYER_LOCK) {
            outputProviderOverride = provider;
            routingFactoryOverride = factory;
            if (player != null) {
                routing = factory != null
                        ? factory.attach(player) : new MediaPlayerRouting(player);
            }
        }
    }

    /** Package-private test reset: release the player and drop every seam. */
    static void resetForTest(Context context) {
        releaseAll(context);
        synchronized (PLAYER_LOCK) {
            pendingRouteId = null;
            outputProviderOverride = null;
            routingFactoryOverride = null;
        }
    }

    /** Platform provider: {@code AudioManager.getDevices(GET_DEVICES_OUTPUTS)}. */
    private static final class PlatformAudioOutputProvider implements AudioOutputProvider {
        private final Context context;

        PlatformAudioOutputProvider(Context context) {
            this.context = context;
        }

        @Override
        public List<AudioOutput> outputs() {
            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager == null) {
                return List.of();
            }
            AudioDeviceInfo[] devices;
            try {
                devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            } catch (RuntimeException e) {
                return List.of();
            }
            if (devices == null) {
                return List.of();
            }
            List<AudioOutput> outputs = new ArrayList<>(devices.length);
            for (AudioDeviceInfo device : devices) {
                if (device == null) {
                    continue;
                }
                CharSequence product;
                try {
                    product = device.getProductName();
                } catch (RuntimeException e) {
                    product = null;
                }
                outputs.add(new AudioOutput(device.getId(),
                        deviceTypeName(device.getType()),
                        product == null ? null : product.toString(),
                        device.isSink(), device));
            }
            return outputs;
        }
    }

    /** Production routing view: delegates to the player's AudioRouting surface. */
    private static final class MediaPlayerRouting implements AudioRoutingPlayer {
        private final MediaPlayer player;

        MediaPlayerRouting(MediaPlayer player) {
            this.player = player;
        }

        @Override
        public boolean setPreferredOutput(AudioOutput output) {
            try {
                return player.setPreferredDevice(
                        output == null ? null : output.device());
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public Integer preferredOutputId() {
            try {
                AudioDeviceInfo device = player.getPreferredDevice();
                return device == null ? null : device.getId();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public Integer routedOutputId() {
            try {
                AudioDeviceInfo device = player.getRoutedDevice();
                return device == null ? null : device.getId();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * Stable device-type names for the wire. Unknown platform values degrade
     * to {@code unknown_<n>} so the field stays a string and still carries
     * the raw type code.
     */
    @SuppressWarnings("deprecation") // legacy TYPE_* constants still report real devices
    static String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "built_in_earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "built_in_speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "wired_headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "wired_headphones";
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                return "line_analog";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return "line_digital";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "bluetooth_sco";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "bluetooth_a2dp";
            case AudioDeviceInfo.TYPE_HDMI:
                return "hdmi";
            case AudioDeviceInfo.TYPE_HDMI_ARC:
                return "hdmi_arc";
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return "hdmi_earc";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "usb_device";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "usb_accessory";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "usb_headset";
            case AudioDeviceInfo.TYPE_DOCK:
                return "dock";
            case AudioDeviceInfo.TYPE_DOCK_ANALOG:
                return "dock_analog";
            case AudioDeviceInfo.TYPE_FM:
                return "fm";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "built_in_mic";
            case AudioDeviceInfo.TYPE_FM_TUNER:
                return "fm_tuner";
            case AudioDeviceInfo.TYPE_TV_TUNER:
                return "tv_tuner";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "telephony";
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return "aux_line";
            case AudioDeviceInfo.TYPE_IP:
                return "ip";
            case AudioDeviceInfo.TYPE_BUS:
                return "bus";
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return "hearing_aid";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:
                return "built_in_speaker_safe";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX:
                return "remote_submix";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "ble_headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "ble_speaker";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return "ble_broadcast";
            case AudioDeviceInfo.TYPE_BLE_HEARING_AID:
                return "ble_hearing_aid";
            case AudioDeviceInfo.TYPE_BLE_CENTRAL:
                return "ble_central";
            case AudioDeviceInfo.TYPE_BLE_CENTRAL_BROADCAST:
                return "ble_central_broadcast";
            case AudioDeviceInfo.TYPE_MULTICHANNEL_GROUP:
                return "multichannel_group";
            case AudioDeviceInfo.TYPE_UNKNOWN:
            default:
                return "unknown_" + type;
        }
    }

    /** Bounded display name: control characters stripped, length capped. */
    static String sanitizeOutputName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(
                Math.min(name.length(), OUTPUT_NAME_MAX_CHARS));
        for (int i = 0; i < name.length()
                && clean.length() < OUTPUT_NAME_MAX_CHARS; i++) {
            char c = name.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        return clean.toString().trim();
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
