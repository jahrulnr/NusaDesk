package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot, permission-aware {@link LocationManager} adapter.
 *
 * <p>Each read first resolves the current foreground grant from the
 * permission checker and picks the provider from that grant: a fine grant
 * prefers GPS and falls back to the network provider when GPS is absent or
 * disabled, while a coarse-only grant uses the network provider. A missing or
 * denied grant, an absent/disabled provider, or a missing manager is an
 * explicit typed state, never a fabricated fix and never an automatic
 * permission prompt.</p>
 *
 * <p>The read registers a single-update listener on a dedicated bounded
 * {@link HandlerThread} (created lazily, stopped by {@link #close()}) and
 * waits at most the configured read window for the first fix. The listener is
 * unregistered on success, timeout, interruption, registration failure, and
 * platform exceptions, so no read can outlive the bridge session or block a
 * bridge worker indefinitely. This adapter never requests background
 * location, never claims a continuous or background fix, and never opens a
 * permission activity.</p>
 */
public final class AndroidLocationManagerSource implements LocationSource, AutoCloseable {
    private static final String THREAD_NAME = "android-capability-location";
    private static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;

    private final Context context;
    private final long timeoutMillis;
    private final LocationPermissionChecker permissionChecker;
    private HandlerThread locationThread;
    private volatile boolean closed;

    public AndroidLocationManagerSource(Context context) {
        this(context, DEFAULT_TIMEOUT_MILLIS, new AndroidLocationPermissionChecker(context));
    }

    AndroidLocationManagerSource(Context context, long timeoutMillis,
                                 LocationPermissionChecker permissionChecker) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (permissionChecker == null) {
            throw new IllegalArgumentException("permissionChecker must not be null");
        }
        this.context = context.getApplicationContext();
        this.timeoutMillis = timeoutMillis;
        this.permissionChecker = permissionChecker;
    }

    @Override
    public LocationSnapshot read() {
        if (closed) {
            // Fast path; locationHandler() re-checks under the close monitor so
            // a read racing teardown never recreates the delivery thread.
            return LocationSnapshot.error();
        }
        LocationManager manager = locationManager();
        if (manager == null) {
            return LocationSnapshot.unavailable();
        }
        LocationGrant grant = permissionChecker.check();
        if (grant == LocationGrant.REQUIRED) {
            return LocationSnapshot.permissionRequired();
        }
        if (grant == LocationGrant.DENIED) {
            return LocationSnapshot.permissionDenied();
        }
        String provider = chooseProvider(manager, grant);
        if (provider == null) {
            return LocationSnapshot.unavailable();
        }
        Handler handler = locationHandler();
        if (handler == null) {
            // Teardown won the race between the closed check and thread
            // creation; never recreate the delivery thread.
            return LocationSnapshot.error();
        }
        AtomicReference<LocationSnapshot> outcome = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                settle(outcome, latch, mapLocation(location, provider), manager, this);
            }

            @Override
            public void onStatusChanged(String providerName, int status, Bundle extras) {
                // One-shot read: status changes do not settle the outcome.
            }

            @Override
            public void onProviderEnabled(String providerName) {
                // One-shot read: not a fix.
            }

            @Override
            public void onProviderDisabled(String providerName) {
                // One-shot read: not a fix.
            }
        };
        try {
            manager.requestSingleUpdate(provider, listener, handler.getLooper());
        } catch (SecurityException e) {
            // The platform refused the request because no suitable permission
            // is present (for example a grant revoked between the check and
            // the request). Typed as a denial, never a fabricated fix.
            return LocationSnapshot.permissionDenied();
        } catch (RuntimeException e) {
            return LocationSnapshot.error();
        }
        // The timeout runs on the same looper as the fix, scheduled at the
        // read deadline, so a fix delivered inside the window always wins:
        // whichever settles the outcome first wins.
        handler.postDelayed(() -> {
            if (outcome.compareAndSet(null, LocationSnapshot.timeout())) {
                safeUnregister(manager, listener);
                latch.countDown();
            }
        }, timeoutMillis);
        boolean delivered;
        try {
            delivered = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeUnregister(manager, listener);
            return LocationSnapshot.error();
        }
        if (!delivered && outcome.compareAndSet(null, LocationSnapshot.timeout())) {
            // The await deadline expired just before the queued timeout ran;
            // settle once so the caller always sees a typed result.
            safeUnregister(manager, listener);
        }
        return outcome.get();
    }

    /** Stop the location delivery thread; idempotent and safe after close. */
    @Override
    public synchronized void close() {
        closed = true;
        HandlerThread thread = locationThread;
        locationThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /**
     * Return a handler on the shared delivery thread, or {@code null} when the
     * source is closed. Synchronized with {@link #close()} so a read racing
     * teardown can never create a thread after close has run.
     */
    private synchronized Handler locationHandler() {
        if (closed) {
            return null;
        }
        HandlerThread thread = locationThread;
        if (thread == null || !thread.isAlive()) {
            thread = new HandlerThread(THREAD_NAME);
            thread.start();
            locationThread = thread;
        }
        return new Handler(thread.getLooper());
    }

    private LocationManager locationManager() {
        try {
            return (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Pick the bounded provider for the granted level: fine prefers GPS and
     * falls back to the network provider; coarse-only uses the network
     * provider (GPS is not valid under a coarse-only grant). A provider is
     * usable only when it exists and is enabled, so a device with location
     * switched off fails typed and fast instead of timing out.
     */
    private static String chooseProvider(LocationManager manager, LocationGrant grant) {
        if (grant == LocationGrant.FINE) {
            if (providerUsable(manager, LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (providerUsable(manager, LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
            return null;
        }
        return providerUsable(manager, LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER : null;
    }

    private static boolean providerUsable(LocationManager manager, String provider) {
        try {
            return manager.getProvider(provider) != null && manager.isProviderEnabled(provider);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Translate one platform fix into the bounded contract: required values
     * must be finite and complete (a fix without accuracy is rejected rather
     * than guessed), and optional values are kept only when the fix carried a
     * valid one. The wall-clock timestamp prefers the platform fix time and
     * falls back to the read time; the provider falls back to the requested
     * provider when the fix did not name one.
     */
    private static LocationSnapshot mapLocation(Location location, String requestedProvider) {
        if (location == null) {
            return LocationSnapshot.error();
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        float accuracy = location.getAccuracy();
        if (!location.hasAccuracy() || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || !Float.isFinite(accuracy) || accuracy < 0f) {
            return LocationSnapshot.error();
        }
        String provider = location.getProvider();
        if (provider == null || provider.trim().isEmpty()) {
            provider = requestedProvider;
        }
        long timestamp = location.getTime() > 0L
                ? location.getTime() : System.currentTimeMillis();
        Double altitude = location.hasAltitude() && Double.isFinite(location.getAltitude())
                ? location.getAltitude() : null;
        Double speed = location.hasSpeed() && Float.isFinite(location.getSpeed())
                && location.getSpeed() >= 0f ? (double) location.getSpeed() : null;
        Double bearing = location.hasBearing() && Float.isFinite(location.getBearing())
                && location.getBearing() >= 0f && location.getBearing() <= 360f
                ? (double) location.getBearing() : null;
        try {
            return LocationSnapshot.reading(latitude, longitude, accuracy, provider,
                    timestamp, altitude, speed, bearing);
        } catch (IllegalArgumentException invalid) {
            // Non-finite or otherwise invalid platform values become an
            // explicit error, never a fabricated fix.
            return LocationSnapshot.error();
        }
    }

    private static void settle(AtomicReference<LocationSnapshot> outcome, CountDownLatch latch,
                               LocationSnapshot snapshot, LocationManager manager,
                               LocationListener listener) {
        if (outcome.compareAndSet(null, snapshot)) {
            safeUnregister(manager, listener);
            latch.countDown();
        }
    }

    private static void safeUnregister(LocationManager manager, LocationListener listener) {
        try {
            manager.removeUpdates(listener);
        } catch (RuntimeException ignored) {
            // Unregistration is best-effort; the read outcome is already settled.
        }
    }
}
