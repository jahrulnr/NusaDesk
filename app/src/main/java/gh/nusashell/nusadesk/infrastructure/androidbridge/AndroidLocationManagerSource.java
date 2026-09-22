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
 * permission checker. A caller may force one provider ({@code gps},
 * {@code network}, or {@code passive}) or ask for the last-known fix only;
 * an automatic read picks the deliverable provider whose last-known fix is
 * freshest — indoors the network provider typically holds a fix long before
 * GPS delivers one — and falls back to the fixed preference order (GPS then
 * network under a fine grant) when none has one. A forced provider the held
 * grant does not cover, an absent or disabled provider, a missing or denied
 * grant, or a missing manager is an explicit typed state, never a
 * fabricated fix and never an automatic permission prompt.</p>
 *
 * <p>The read registers a single-update listener on a dedicated bounded
 * {@link HandlerThread} (created lazily, stopped by {@link #close()}) and
 * waits at most the configured read window for the first fix. The listener is
 * unregistered on success, timeout, interruption, registration failure, and
 * platform exceptions, so no read can outlive the bridge session or block a
 * bridge worker indefinitely. When the window expires without a fresh fix
 * (or no fresh fix can be registered), the read answers the freshest
 * last-known fix the grant covers, marked stale with its age in
 * milliseconds when the platform fix carries a time; a device with no
 * last-known fix at all still reports the typed timeout. This adapter never
 * requests background location, never claims a continuous or background
 * fix, and never opens a permission activity.</p>
 */
public final class AndroidLocationManagerSource implements LocationSource, AutoCloseable {
    private static final String THREAD_NAME = "android-capability-location";
    /**
     * Bounded window of one read. Thirty seconds because a cold GPS first
     * fix indoors can take tens of seconds — on the measured device the old
     * 5 s window always expired before GPS delivered while the network
     * provider already held a fix, so every read surfaced as a typed
     * timeout. The guest {@code termux-location} command pairs this with a
     * 35 s socket timeout, and a miss inside the window still answers the
     * freshest last-known fix marked stale rather than failing bare.
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

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
        return read(null, false);
    }

    @Override
    public LocationSnapshot read(String forcedProvider, boolean lastOnly) {
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
        String[] staleCandidates = lastKnownCandidates(grant, forcedProvider);
        if (staleCandidates == null) {
            // A forced provider the grant does not cover fails typed, never
            // silently substituted with another provider.
            return LocationSnapshot.unavailable();
        }
        if (lastOnly) {
            // A cached-fix read never registers a listener: answer the
            // freshest last-known fix the candidates hold, or the typed
            // timeout when none has one.
            LocationSnapshot stale = lastKnownReading(manager, staleCandidates);
            return stale != null ? stale : LocationSnapshot.timeout();
        }
        String provider = chooseProvider(manager, grant, forcedProvider);
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
                settle(outcome, latch, mapLocation(location, provider, false), manager, this);
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
            // No fresh fix can be registered: answer the freshest last-known
            // fix when the grant covers one before failing typed.
            LocationSnapshot stale = lastKnownReading(manager, staleCandidates);
            return stale != null ? stale : LocationSnapshot.error();
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
        LocationSnapshot result = outcome.get();
        if (result != null && result.getState() == LocationSnapshot.State.TIMEOUT) {
            // No fresh fix inside the window: answer the freshest last-known
            // fix marked stale when the grant covers one; a device with none
            // keeps the typed timeout.
            LocationSnapshot stale = lastKnownReading(manager, staleCandidates);
            if (stale != null) {
                return stale;
            }
        }
        return result;
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
     * Pick the bounded provider for one fresh-fix read. A caller-forced
     * provider is honored only when it can deliver under the held grant (the
     * grant coverage check ran earlier, so only usability is decided here);
     * automatic selection picks the deliverable provider whose last-known
     * fix is freshest — indoors the network provider typically holds a fix
     * long before GPS delivers one — and falls back to the fixed preference
     * order (GPS then network) when none has one. A provider is usable only
     * when it exists and is enabled, so a device with location switched off
     * fails typed and fast instead of timing out.
     */
    private static String chooseProvider(LocationManager manager, LocationGrant grant,
                                         String forcedProvider) {
        if (forcedProvider != null) {
            return providerUsable(manager, forcedProvider) ? forcedProvider : null;
        }
        String[] candidates = freshFixProviders(grant);
        String freshest = freshestLastKnownProvider(manager, candidates);
        if (freshest != null) {
            return freshest;
        }
        for (String candidate : candidates) {
            if (providerUsable(manager, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Providers able to deliver a fresh fix under this grant, in preference
     * order: GPS then network under a fine grant, network only under a
     * coarse-only grant (a fine fix is never requested without the fine
     * grant). The passive provider is deliberately absent: it relays other
     * apps' fixes and never produces one on demand.
     */
    private static String[] freshFixProviders(LocationGrant grant) {
        return grant == LocationGrant.FINE
                ? new String[]{LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER};
    }

    /**
     * Providers whose last-known cache this grant may read: the fresh-fix
     * providers plus the passive provider under a fine grant, whose cache
     * mirrors the freshest fix delivered to any app on the device.
     */
    private static String[] lastKnownProviders(LocationGrant grant) {
        return grant == LocationGrant.FINE
                ? new String[]{LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER};
    }

    /**
     * The providers a last-known lookup may read: every covered provider for
     * an automatic read, or the forced provider alone — {@code null} when a
     * forced provider is outside the held grant.
     */
    private static String[] lastKnownCandidates(LocationGrant grant, String forcedProvider) {
        if (forcedProvider == null) {
            return lastKnownProviders(grant);
        }
        return grantCovers(grant, forcedProvider)
                ? new String[]{forcedProvider} : null;
    }

    /**
     * Whether the grant covers a provider's data: the network provider under
     * any held grant, GPS and passive only under a fine grant.
     */
    private static boolean grantCovers(LocationGrant grant, String provider) {
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return grant == LocationGrant.FINE || grant == LocationGrant.COARSE_ONLY;
        }
        return grant == LocationGrant.FINE;
    }

    /**
     * The usable candidate holding the freshest last-known fix, or
     * {@code null} when no usable candidate has one. A fix without a
     * platform time sorts oldest; ties keep the earlier preference order.
     */
    private static String freshestLastKnownProvider(LocationManager manager,
                                                    String[] candidates) {
        String best = null;
        long bestTime = Long.MIN_VALUE;
        for (String provider : candidates) {
            if (!providerUsable(manager, provider)) {
                continue;
            }
            Location fix = lastKnown(manager, provider);
            if (fix == null) {
                continue;
            }
            if (best == null || fix.getTime() > bestTime) {
                best = provider;
                bestTime = fix.getTime();
            }
        }
        return best;
    }

    /**
     * Freshest last-known fix across the given providers, mapped as a stale
     * reading with its age when the fix carries a platform time. Returns
     * {@code null} when no provider has a fix or the fix fails validation,
     * so the caller keeps its typed result; coordinates are never
     * fabricated.
     */
    private static LocationSnapshot lastKnownReading(LocationManager manager,
                                                     String[] providers) {
        Location freshest = null;
        String freshestProvider = null;
        for (String provider : providers) {
            Location fix = lastKnown(manager, provider);
            if (fix != null && (freshest == null || fix.getTime() > freshest.getTime())) {
                freshest = fix;
                freshestProvider = provider;
            }
        }
        if (freshest == null) {
            return null;
        }
        LocationSnapshot mapped = mapLocation(freshest, freshestProvider, true);
        return mapped.getState() == LocationSnapshot.State.READING ? mapped : null;
    }

    /**
     * One provider's cached fix, or {@code null} when it has none. A lookup
     * that throws — a grant revoked mid-read or a platform failure — counts
     * as no fix for that provider.
     */
    private static Location lastKnown(LocationManager manager, String provider) {
        try {
            return manager.getLastKnownLocation(provider);
        } catch (SecurityException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
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
     * provider when the fix did not name one. A {@code stale} fix is a
     * last-known value: it is marked stale and carries its age in
     * milliseconds when the platform fix time can produce one.
     */
    private static LocationSnapshot mapLocation(Location location, String requestedProvider,
                                                boolean stale) {
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
        Long ageMillis = null;
        if (stale && location.getTime() > 0L) {
            // Age of the cached fix, clamped so a skewed platform clock
            // never reports a negative age.
            ageMillis = Math.max(0L, System.currentTimeMillis() - location.getTime());
        }
        Double altitude = location.hasAltitude() && Double.isFinite(location.getAltitude())
                ? location.getAltitude() : null;
        Double speed = location.hasSpeed() && Float.isFinite(location.getSpeed())
                && location.getSpeed() >= 0f ? (double) location.getSpeed() : null;
        Double bearing = location.hasBearing() && Float.isFinite(location.getBearing())
                && location.getBearing() >= 0f && location.getBearing() <= 360f
                ? (double) location.getBearing() : null;
        try {
            return stale
                    ? LocationSnapshot.staleReading(latitude, longitude, accuracy, provider,
                            timestamp, altitude, speed, bearing, ageMillis)
                    : LocationSnapshot.reading(latitude, longitude, accuracy, provider,
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
