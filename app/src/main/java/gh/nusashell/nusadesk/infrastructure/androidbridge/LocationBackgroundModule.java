package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.location.LocationManager;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.service.LocationBackgroundService;

/**
 * Background-location capability: {@code location.background.start|poll|stop}.
 *
 * <p>This mirrors the upstream {@code termux-location -r updates} contract on
 * Termux:API master (commits {@code 255bc405f5}/{@code ee29d4314c},
 * 2026-09-16, not yet released): a continuous stream that only runs once the
 * user chose "Allow all the time" for location. On API 30+ every request is
 * rejected until then — there is no runtime dialog for the background grant —
 * so a missing grant answers
 * {@code location-background-permission-required:set "Allow all the time" for
 * location in app settings (permission.request mode=settings)}, pointing the
 * guest at the Settings path instead of prompting.</p>
 *
 * <p>{@code location.background.start} takes {@code provider}
 * ({@code gps|network|passive}, default {@code network}), {@code interval_ms}
 * (1000..60000, default 5000), and {@code distance_m} (0..1000, default 1) —
 * the upstream 5 s / 1 m update shape — and dispatches them to
 * {@link LocationBackgroundService}, a {@code location} foreground service
 * that owns the user-visible notification and the
 * {@link LocationManager#requestLocationUpdates} registration. One session at
 * a time: a second start answers {@code location-background-already-running},
 * an absent/disabled provider answers
 * {@code location-background-provider-unavailable:<provider>}, and a refused
 * service dispatch answers {@code location-background-failed:<reason>}.</p>
 *
 * <p>{@code location.background.poll} drains the service's bounded buffer
 * (256 entries, drop-oldest) with at most {@value #MAX_POLL_FIXES} fixes per
 * call and answers {@code fixes_json} — the upstream array-of-fixes shape,
 * carried as a string field because the bridge frame is flat — plus
 * {@code count} and {@code running}. {@code location.background.stop} is
 * idempotent: it always stops the service and reports {@code stopped=true}
 * with {@code was_running} reflecting whether a session existed.</p>
 *
 * <p>Only the start method declares parameters; the module never throws
 * except {@link CapabilityParams.Invalid} (mapped to
 * {@code invalid-argument} by the framework).</p>
 */
public final class LocationBackgroundModule implements CapabilityModule {
    private static final String TAG = "LocationBackgroundModule";

    private static final String METHOD_START = "location.background.start";
    private static final String METHOD_POLL = "location.background.poll";
    private static final String METHOD_STOP = "location.background.stop";

    private static final Set<String> PROVIDERS = Set.of("gps", "network", "passive");
    private static final int PROVIDER_MAX_CHARS = 16;
    private static final long INTERVAL_MIN_MS = 1_000L;
    private static final long INTERVAL_MAX_MS = 60_000L;
    private static final long INTERVAL_DEFAULT_MS = 5_000L;
    private static final long DISTANCE_MIN_M = 0L;
    private static final long DISTANCE_MAX_M = 1_000L;
    private static final long DISTANCE_DEFAULT_M = 1L;
    private static final int MAX_POLL_FIXES = 100;

    private static final String PERMISSION_REQUIRED =
            "location-background-permission-required:set \"Allow all the time\" "
            + "for location in app settings (permission.request mode=settings)";

    private final Context context;
    private final LocationManager locationManager;
    private final AndroidPermissionChecker permissions;

    public LocationBackgroundModule(Context context) {
        this(context, locationManagerOf(context));
    }

    /** Package-private seam: the platform manager is injectable for tests. */
    LocationBackgroundModule(Context context, LocationManager locationManager) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.locationManager = locationManager;
        this.permissions = new AndroidPermissionChecker(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_START, METHOD_POLL, METHOD_STOP);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_START);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_START:
                return start(request);
            case METHOD_POLL:
                return poll(request);
            case METHOD_STOP:
                return stop(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    public void close() {
        // The guest session owns the stream; a closing bridge must not leave
        // a location foreground service running without its owner.
        if (LocationBackgroundService.isRunning()) {
            LocationBackgroundService.releaseStartClaim();
            try {
                context.stopService(LocationBackgroundService.stopIntent(context));
            } catch (RuntimeException ignored) {
                // The service may already be gone.
            }
        }
    }

    /**
     * {@code location.background.start} — grant check, single-session claim,
     * provider check, then dispatch the foreground-service start.
     */
    private AndroidCapabilityProtocol.Response start(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("provider", "interval_ms", "distance_m"));
        String provider =
                params.optionalString("provider", PROVIDER_MAX_CHARS, "network");
        if (!PROVIDERS.contains(provider)) {
            throw new CapabilityParams.Invalid("unsupported parameter value: provider");
        }
        long intervalMs = params.optionalLong("interval_ms",
                INTERVAL_MIN_MS, INTERVAL_MAX_MS, INTERVAL_DEFAULT_MS);
        long distanceM = params.optionalLong("distance_m",
                DISTANCE_MIN_M, DISTANCE_MAX_M, DISTANCE_DEFAULT_M);
        if (permissions.check(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != CapabilityPermission.GRANTED) {
            return AndroidCapabilityProtocol.Response.error(id, PERMISSION_REQUIRED);
        }
        if (locationManager == null || !providerUsable(provider)) {
            return AndroidCapabilityProtocol.Response.error(
                    id, "location-background-provider-unavailable:" + provider);
        }
        if (!LocationBackgroundService.claimStart()) {
            return AndroidCapabilityProtocol.Response.error(
                    id, "location-background-already-running");
        }
        try {
            context.startForegroundService(LocationBackgroundService.startIntent(
                    context, provider, intervalMs, distanceM));
        } catch (IllegalStateException e) {
            // API 30+ background-start refusal despite the held grant.
            LocationBackgroundService.releaseStartClaim();
            Log.w(TAG, "background location start refused", e);
            return AndroidCapabilityProtocol.Response.error(
                    id, "location-background-failed:foreground-start-refused");
        } catch (RuntimeException e) {
            LocationBackgroundService.releaseStartClaim();
            Log.w(TAG, "background location start failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    id, "location-background-failed:service-start-failed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("started", true);
        fields.put("provider", provider);
        fields.put("interval_ms", intervalMs);
        fields.put("distance_m", distanceM);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /** {@code location.background.poll} — drain the buffered fixes. */
    private AndroidCapabilityProtocol.Response poll(
            AndroidCapabilityProtocol.Request request) {
        List<LocationBackgroundService.Fix> fixes =
                LocationBackgroundService.drainFixes(MAX_POLL_FIXES);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("fixes_json", fixesJson(fixes));
        fields.put("count", (long) fixes.size());
        fields.put("running", LocationBackgroundService.isRunning());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code location.background.stop} — idempotent service stop. */
    private AndroidCapabilityProtocol.Response stop(
            AndroidCapabilityProtocol.Request request) {
        boolean wasRunning = LocationBackgroundService.isRunning();
        LocationBackgroundService.releaseStartClaim();
        try {
            context.stopService(LocationBackgroundService.stopIntent(context));
        } catch (RuntimeException ignored) {
            // A never-started service stops silently anyway.
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stopped", true);
        fields.put("was_running", wasRunning);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private boolean providerUsable(String provider) {
        try {
            return locationManager.getProvider(provider) != null
                    && locationManager.isProviderEnabled(provider);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The upstream {@code termux-location} update array: one object per fix
     * with {@code latitude}, {@code longitude}, {@code accuracy},
     * {@code provider}, and {@code at_ms}.
     */
    private static String fixesJson(List<LocationBackgroundService.Fix> fixes) {
        StringBuilder json = new StringBuilder(fixes.size() * 96 + 2);
        json.append('[');
        boolean first = true;
        for (LocationBackgroundService.Fix fix : fixes) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"latitude\":").append(fix.latitude)
                    .append(",\"longitude\":").append(fix.longitude)
                    .append(",\"accuracy\":").append(fix.accuracy)
                    .append(",\"provider\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(fix.provider))
                    .append(",\"at_ms\":").append(fix.atMs)
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static LocationManager locationManagerOf(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
