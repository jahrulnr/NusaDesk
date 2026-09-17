package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Foreground-only continuous {@link LocationManager} stream adapter.
 *
 * <p>{@link #start(LocationStreamRequest)} resolves the current foreground
 * grant and picks the provider from the request accuracy: a FINE request
 * under a fine grant prefers GPS and falls back to the network provider, a
 * FINE request under a coarse-only grant degrades to the network provider
 * (GPS is never requested without the fine grant), and a COARSE request
 * always uses the network provider. A missing or denied grant, an absent or
 * disabled provider, or a missing manager ends the session typed — never a
 * fabricated fix and never an automatic permission prompt.</p>
 *
 * <p>Fixes are delivered on a dedicated {@link HandlerThread} created lazily
 * and stopped by {@link #close()}; every terminal path — stop, close,
 * provider loss, platform rejection — calls {@code removeLocationUpdates} and
 * no listener outlives the session. A fix-silence window bounded by
 * {@link LocationStreamIntervalPolicy#fixSilenceMillis(long)} is evaluated
 * when the consumer polls: a poll that finds no event while no fix arrived
 * within the window returns a non-terminal
 * {@link LocationStreamEvent.State#TIMEOUT} event (at most one per window)
 * and the stream keeps listening. The evaluation is pull-based and bounded —
 * no background timer, no wake-up — and the stream itself never stops or
 * restarts on a timeout. This adapter never requests background location,
 * never starts a foreground service, and never opens a permission activity;
 * it is safe to use only while the host session is explicitly active.</p>
 */
public final class AndroidLocationStreamSession implements LocationStreamSession {
    private static final String THREAD_NAME = "android-capability-location-stream";

    private final Context context;
    private final Long fixSilenceOverrideMillis;
    private final LocationPermissionChecker permissionChecker;
    private final LocationStreamBuffer buffer;
    private final Object lifecycleLock = new Object();

    private HandlerThread locationThread;
    private volatile boolean closed;
    private volatile State state = State.IDLE;
    /** Fix-silence window of the active session; guarded by {@link #lifecycleLock}. */
    private long activeSilenceMillis;
    /** Time of the last accepted fix; guarded by {@link #lifecycleLock}. */
    private long lastFixMillis;
    /** Time of the last emitted timeout; guarded by {@link #lifecycleLock}. */
    private long lastTimeoutAtMillis;
    private LocationListener listener;

    public AndroidLocationStreamSession(Context context) {
        this(context, new AndroidLocationPermissionChecker(context));
    }

    AndroidLocationStreamSession(Context context, LocationPermissionChecker permissionChecker) {
        this(context, (Long) null, permissionChecker,
                LocationStreamIntervalPolicy.DEFAULT_BUFFER_CAPACITY, false);
    }

    AndroidLocationStreamSession(Context context, long fixSilenceMillis,
                                 LocationPermissionChecker permissionChecker,
                                 int bufferCapacity) {
        this(context, fixSilenceMillis, permissionChecker, bufferCapacity, true);
    }

    private AndroidLocationStreamSession(Context context, Long fixSilenceOverrideMillis,
                                         LocationPermissionChecker permissionChecker,
                                         int bufferCapacity, boolean requireSilenceOverride) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (permissionChecker == null) {
            throw new IllegalArgumentException("permissionChecker must not be null");
        }
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("bufferCapacity must be positive");
        }
        if (requireSilenceOverride && (fixSilenceOverrideMillis == null
                || fixSilenceOverrideMillis <= 0)) {
            throw new IllegalArgumentException("fixSilenceMillis must be positive");
        }
        this.context = context.getApplicationContext();
        this.fixSilenceOverrideMillis = fixSilenceOverrideMillis;
        this.permissionChecker = permissionChecker;
        this.buffer = new LocationStreamBuffer(bufferCapacity);
    }

    @Override
    public void start(LocationStreamRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final long silenceMillis = fixSilenceOverrideMillis != null
                ? fixSilenceOverrideMillis
                : LocationStreamIntervalPolicy.fixSilenceMillis(request.getIntervalMillis());
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("session is closed");
            }
            if (state == State.STREAMING) {
                throw new IllegalStateException("session is already streaming");
            }
            buffer.clear();
            LocationManager manager = locationManager();
            if (manager == null) {
                failLocked(LocationStreamEvent.unavailable());
                return;
            }
            LocationGrant grant = permissionChecker.check();
            if (grant == LocationGrant.REQUIRED) {
                failLocked(LocationStreamEvent.permissionRequired());
                return;
            }
            if (grant == LocationGrant.DENIED) {
                failLocked(LocationStreamEvent.permissionDenied());
                return;
            }
            String provider = chooseProvider(manager, grant, request.getAccuracy());
            if (provider == null) {
                failLocked(LocationStreamEvent.unavailable());
                return;
            }
            Handler handler = locationHandlerLocked();
            if (handler == null) {
                // Teardown won the race between the preflight checks and thread creation.
                throw new IllegalStateException("session closed during start");
            }
            StreamListener newListener = new StreamListener(provider);
            try {
                manager.requestLocationUpdates(provider, request.getIntervalMillis(), 0f,
                        newListener, handler.getLooper());
            } catch (SecurityException e) {
                // A grant revoked between the check and the request: typed as a
                // denial, never a fabricated fix. The platform refused before
                // registering, so there is nothing to unregister.
                failLocked(LocationStreamEvent.permissionDenied());
                return;
            } catch (RuntimeException e) {
                failLocked(LocationStreamEvent.error());
                return;
            }
            listener = newListener;
            activeSilenceMillis = silenceMillis;
            lastFixMillis = System.currentTimeMillis();
            lastTimeoutAtMillis = 0L;
            state = State.STREAMING;
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (state != State.STREAMING) {
                return;
            }
            state = State.STOPPED;
            safeRemoveUpdatesLocked();
            buffer.offer(LocationStreamEvent.stopped());
        }
    }

    @Override
    public void close() {
        HandlerThread thread;
        synchronized (lifecycleLock) {
            closed = true;
            if (state == State.STREAMING) {
                safeRemoveUpdatesLocked();
                buffer.offer(LocationStreamEvent.stopped());
            }
            state = State.CLOSED;
            listener = null;
            thread = locationThread;
            locationThread = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public LocationStreamEvent poll(long timeoutMillis) {
        LocationStreamEvent event = buffer.poll(timeoutMillis);
        if (event != null) {
            return event;
        }
        // Pull-based fix-silence evaluation: a poll that found no event while
        // no fix arrived within the bounded window returns a non-terminal
        // TIMEOUT, at most one per window, while the stream keeps listening.
        // No background timer is involved.
        synchronized (lifecycleLock) {
            if (state == State.STREAMING) {
                long now = System.currentTimeMillis();
                if (now - lastFixMillis >= activeSilenceMillis
                        && now - lastTimeoutAtMillis >= activeSilenceMillis) {
                    lastTimeoutAtMillis = now;
                    return LocationStreamEvent.timeout();
                }
            }
        }
        return null;
    }

    @Override
    public LocationSnapshot latestReading() {
        return buffer.latestReading();
    }

    /**
     * Fail the session into {@link State#STOPPED} with a typed event. Caller
     * must hold {@link #lifecycleLock}.
     */
    private void failLocked(LocationStreamEvent event) {
        state = State.STOPPED;
        buffer.offer(event);
    }

    /**
     * Return a handler on the shared delivery thread, or {@code null} when
     * the session is closed. Caller must hold {@link #lifecycleLock} so a
     * start racing close can never create a thread after close has run.
     */
    private Handler locationHandlerLocked() {
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
     * Pick the bounded provider for the requested accuracy under the observed
     * grant: fine requires a fine grant (GPS preferred, network fallback);
     * everything else — coarse request, or fine request under a coarse-only
     * grant — uses the network provider. A provider is usable only when it
     * exists and is enabled, so a device with location switched off fails
     * typed and fast instead of timing out.
     */
    private static String chooseProvider(LocationManager manager, LocationGrant grant,
                                         LocationStreamRequest.Accuracy accuracy) {
        boolean fine = accuracy == LocationStreamRequest.Accuracy.FINE
                && grant == LocationGrant.FINE;
        if (fine) {
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
     * Mirror of the one-shot adapter's mapping ({@link
     * AndroidLocationManagerSource#mapLocation}): required values must be
     * finite and complete, optional values are kept only when valid, and a
     * corrupted fix becomes an explicit error, never a fabricated reading.
     * Kept local to this slice so the two adapters stay independently
     * reviewable; a shared mapper may be extracted during integration.
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
            return LocationSnapshot.error();
        }
    }

    /** Unregister the current listener; caller must hold {@link #lifecycleLock}. */
    private void safeRemoveUpdatesLocked() {
        LocationManager manager = locationManager();
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (RuntimeException ignored) {
                // Unregistration is best-effort; the session state is already terminal.
            }
        }
    }

    /**
     * Single provider listener. All event production happens under
     * {@link #lifecycleLock} and only while the session is streaming, so a
     * fix racing a stop or close can never produce a reading after the
     * terminal event.
     */
    private final class StreamListener implements LocationListener {
        private final String provider;

        StreamListener(String provider) {
            this.provider = provider;
        }

        @Override
        public void onLocationChanged(Location location) {
            LocationSnapshot snapshot = mapLocation(location, provider);
            boolean reading = snapshot.getState() == LocationSnapshot.State.READING;
            synchronized (lifecycleLock) {
                if (state != State.STREAMING) {
                    return;
                }
                if (reading) {
                    lastFixMillis = System.currentTimeMillis();
                    buffer.offer(LocationStreamEvent.reading(snapshot));
                } else {
                    // A corrupt fix is an explicit stream event, not a silent
                    // drop; the session keeps listening for the next valid fix.
                    buffer.offer(LocationStreamEvent.error());
                }
            }
        }

        @Override
        public void onStatusChanged(String providerName, int status, Bundle extras) {
            // Stream semantics: status changes are not session events; the
            // fix stream itself carries the data.
        }

        @Override
        public void onProviderEnabled(String providerName) {
            // Stream semantics: not a session event.
        }

        @Override
        public void onProviderDisabled(String providerName) {
            synchronized (lifecycleLock) {
                if (state != State.STREAMING || !provider.equals(providerName)) {
                    return;
                }
                state = State.STOPPED;
                safeRemoveUpdatesLocked();
                buffer.offer(LocationStreamEvent.unavailable());
            }
        }
    }
}
