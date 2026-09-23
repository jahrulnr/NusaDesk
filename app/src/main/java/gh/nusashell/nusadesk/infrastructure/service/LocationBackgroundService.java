package gh.nusashell.nusadesk.infrastructure.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service behind the {@code location.background.*} bridge methods
 * (the upstream {@code termux-location -r updates} contract on Termux:API
 * master, not yet released upstream).
 *
 * <p>The bridge module starts this service only after the app holds
 * {@code ACCESS_BACKGROUND_LOCATION} ("Allow all the time") — the same grant
 * that lets a {@code location} foreground service start while the app is not
 * visible. On start the service promotes itself with the {@code location}
 * foreground type, posts an ongoing user-visible notification with a Stop
 * action on channel {@link #CHANNEL_ID}, and registers
 * {@link LocationManager#requestLocationUpdates} for the requested
 * provider/interval/distance. Delivered fixes land in a bounded process-wide
 * buffer ({@value #BUFFER_CAPACITY} entries, drop-oldest) that
 * {@code location.background.poll} drains through {@link #drainFixes(int)}.</p>
 *
 * <p>Lifecycle is deliberately simple and honest: {@code START_NOT_STICKY},
 * no restart after kill, the listener is released and foreground dropped on
 * stop or destroy, and a provider-disable ends the session instead of
 * sitting subscribed to a dead provider. {@code startClaimed}/{@code running}
 * are the module's view of the session: {@link #claimStart()} serializes the
 * single session, and every terminal path clears both flags so a failed
 * start never wedges the capability.</p>
 */
public final class LocationBackgroundService extends Service {
    private static final String TAG = "LocationBgService";

    /** Intent action: promote and register the requested provider stream. */
    public static final String ACTION_START =
            "gh.nusashell.nusadesk.action.LOCATION_BACKGROUND_START";
    /** Intent action: release updates and tear the service down. */
    public static final String ACTION_STOP =
            "gh.nusashell.nusadesk.action.LOCATION_BACKGROUND_STOP";

    public static final String EXTRA_PROVIDER =
            "gh.nusashell.nusadesk.extra.LOCATION_PROVIDER";
    public static final String EXTRA_INTERVAL_MS =
            "gh.nusashell.nusadesk.extra.LOCATION_INTERVAL_MS";
    public static final String EXTRA_DISTANCE_M =
            "gh.nusashell.nusadesk.extra.LOCATION_DISTANCE_M";

    /** Notification channel id for the ongoing background-location notice. */
    public static final String CHANNEL_ID = "nusadesk-location-background";

    private static final int NOTIFICATION_ID = 0x4C42; // "LB"
    private static final int STOP_REQUEST_CODE = 29;
    private static final int BUFFER_CAPACITY = 256;
    private static final long DEFAULT_INTERVAL_MS = 5_000L;
    private static final float DEFAULT_DISTANCE_M = 1.0f;

    private static final Object STATE_LOCK = new Object();
    private static final ArrayDeque<Fix> FIXES = new ArrayDeque<>();
    /** A start was claimed and not yet torn down; guarded by STATE_LOCK. */
    private static boolean startClaimed;
    /** The listener is registered and the service foreground; guarded by STATE_LOCK. */
    private static boolean running;

    private LocationManager locationManager;
    private LocationListener listener;

    /** One buffered fix drained by {@code location.background.poll}. */
    public static final class Fix {
        public final double latitude;
        public final double longitude;
        public final double accuracy;
        public final String provider;
        public final long atMs;

        public Fix(double latitude, double longitude, double accuracy,
                   String provider, long atMs) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || !Double.isFinite(accuracy) || accuracy < 0) {
                throw new IllegalArgumentException("invalid fix");
            }
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.provider = provider == null ? "" : provider;
            this.atMs = atMs;
        }
    }

    /** Start intent carrying the requested stream parameters. */
    public static Intent startIntent(Context context, String provider,
                                     long intervalMs, float distanceM) {
        return new Intent(context, LocationBackgroundService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROVIDER, provider)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
                .putExtra(EXTRA_DISTANCE_M, distanceM);
    }

    /** Stop intent; also the notification Stop action target. */
    public static Intent stopIntent(Context context) {
        return new Intent(context, LocationBackgroundService.class)
                .setAction(ACTION_STOP);
    }

    /**
     * Claim the single background session before dispatching a start.
     * Returns {@code false} when a session is already claimed or running.
     */
    public static boolean claimStart() {
        synchronized (STATE_LOCK) {
            if (startClaimed || running) {
                return false;
            }
            startClaimed = true;
            return true;
        }
    }

    /**
     * Release a start claim that never became a running session (dispatch or
     * start-up failure), or mark the session as stopping from the module.
     */
    public static void releaseStartClaim() {
        synchronized (STATE_LOCK) {
            startClaimed = false;
        }
    }

    /** Whether a background session is claimed or live. */
    public static boolean isRunning() {
        synchronized (STATE_LOCK) {
            return startClaimed || running;
        }
    }

    /**
     * Append one fix to the bounded buffer, dropping the oldest entry at
     * capacity. This is the location listener's entry point and the module
     * test's injection seam.
     */
    public static void offerFix(Fix fix) {
        if (fix == null) {
            throw new IllegalArgumentException("fix must not be null");
        }
        synchronized (STATE_LOCK) {
            if (FIXES.size() == BUFFER_CAPACITY) {
                FIXES.removeFirst();
            }
            FIXES.addLast(fix);
        }
    }

    /** Remove and return up to {@code max} oldest buffered fixes. */
    public static List<Fix> drainFixes(int max) {
        synchronized (STATE_LOCK) {
            List<Fix> drained = new ArrayList<>(Math.min(max, FIXES.size()));
            while (drained.size() < max && !FIXES.isEmpty()) {
                drained.add(FIXES.removeFirst());
            }
            return drained;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Background location", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Location fixes for the Linux guest session.");
            notifications.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            // Unknown or null action on a non-sticky service: nothing to do.
            if (listener == null) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        String provider = intent.getStringExtra(EXTRA_PROVIDER);
        long intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        float distanceM = intent.getFloatExtra(EXTRA_DISTANCE_M, DEFAULT_DISTANCE_M);
        if (provider == null || provider.isEmpty() || locationManager == null) {
            failStart();
            return START_NOT_STICKY;
        }
        if (!promoteForeground(provider)) {
            failStart();
            return START_NOT_STICKY;
        }
        registerUpdates(provider, intervalMs, distanceM);
        return START_NOT_STICKY;
    }

    /**
     * Swap in a listener for the requested stream. A restart while a session
     * runs replaces the parameters; a platform refusal ends the session.
     */
    @SuppressLint("MissingPermission")
    private void registerUpdates(String provider, long intervalMs, float distanceM) {
        LocationListener previous = listener;
        if (previous != null) {
            removeUpdatesQuietly(previous);
            listener = null;
        }
        FixListener next = new FixListener(provider);
        try {
            locationManager.requestLocationUpdates(provider, intervalMs, distanceM,
                    next, getMainLooper());
        } catch (RuntimeException e) {
            // Includes SecurityException: a grant revoked between the
            // module's check and this registration lands here.
            Log.w(TAG, "location updates refused", e);
            failStart();
            return;
        }
        listener = next;
        synchronized (STATE_LOCK) {
            running = true;
        }
    }

    /** Terminal failure of a start: drop claim, foreground, and the service. */
    private void failStart() {
        synchronized (STATE_LOCK) {
            startClaimed = false;
            running = false;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Release updates and drop foreground; idempotent. Never self-stops. */
    private void stopSession() {
        LocationListener current = listener;
        listener = null;
        if (current != null) {
            removeUpdatesQuietly(current);
        }
        synchronized (STATE_LOCK) {
            running = false;
            startClaimed = false;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void removeUpdatesQuietly(LocationListener target) {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(target);
        } catch (RuntimeException ignored) {
            // Unregistration is best-effort on teardown.
        }
    }

    private boolean promoteForeground(String provider) {
        int type = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                // API 29 predates runtime foreground types; the manifest
                // declaration is the only source and MANIFEST selects it.
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        try {
            startForeground(NOTIFICATION_ID, buildNotification(provider), type);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground promotion refused", e);
            return false;
        }
    }

    private Notification buildNotification(String provider) {
        PendingIntent stopAction = PendingIntent.getService(this, STOP_REQUEST_CODE,
                stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("NusaDesk background location")
                .setContentText("Tracking " + provider + " for the Linux guest.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopAction).build())
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    /**
     * Mirror of the foreground stream's mapping ({@code
     * AndroidLocationStreamSession#mapLocation}): required values must be
     * finite and complete, and a corrupt fix is dropped, never buffered.
     */
    private static Fix mapFix(Location location, String fallbackProvider) {
        if (location == null) {
            return null;
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        float accuracy = location.getAccuracy();
        if (!location.hasAccuracy() || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || !Float.isFinite(accuracy)
                || accuracy < 0f) {
            return null;
        }
        String provider = location.getProvider();
        if (provider == null || provider.trim().isEmpty()) {
            provider = fallbackProvider;
        }
        long atMs = location.getTime() > 0L
                ? location.getTime() : System.currentTimeMillis();
        try {
            return new Fix(latitude, longitude, accuracy, provider, atMs);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Single provider listener feeding the shared buffer. */
    private final class FixListener implements LocationListener {
        private final String provider;

        FixListener(String provider) {
            this.provider = provider;
        }

        @Override
        public void onLocationChanged(Location location) {
            Fix fix = mapFix(location, provider);
            if (fix != null) {
                offerFix(fix);
            }
        }

        @Override
        public void onProviderEnabled(String name) {
            // Nothing to do; fixes resume on their own.
        }

        @Override
        public void onProviderDisabled(String name) {
            if (provider.equals(name)) {
                // The requested provider is gone; a dead subscription is a
                // dishonest "running", so the session ends itself.
                stopSession();
                stopSelf();
            }
        }
    }
}
