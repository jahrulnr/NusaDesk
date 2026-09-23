package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Usage-stats capability domain behind three bridge methods. There is no
 * upstream Termux:API counterpart; the {@code usage.*} surface is
 * NusaDesk-native.
 *
 * <p>The usage-access special grant ({@code PACKAGE_USAGE_STATS}) is checked
 * through {@link AndroidPermissionChecker} before any platform read — without
 * it the platform returns empty results instead of throwing, so the module
 * gate is what distinguishes "no data" from "no grant". Special access has
 * no recorded refusal, so a missing grant always answers
 * {@code usage-permission-required:grant usage access in Settings
 * (permission.request mode=settings)}; {@code permission.request} with
 * {@code mode=settings} opens {@code ACTION_USAGE_ACCESS_SETTINGS}.</p>
 *
 * <p>{@code usage.query} (params {@code days} optional 1..7 default 1,
 * {@code limit} optional 1..50 default 20) runs
 * {@link UsageStatsManager#queryUsageStats} with {@code INTERVAL_DAILY} over
 * the last {@code days} days and aggregates the platform's per-day rows into
 * one row per package: {@code apps_json} entries carry {@code package},
 * {@code total_time_ms} (summed foreground time), and
 * {@code last_time_used_ms} (latest use), sorted by {@code total_time_ms}
 * descending, capped at {@code limit} rows. Fields: {@code apps_json},
 * {@code count}, {@code window_days}, and {@code truncated} when the
 * aggregated set exceeds {@code limit}.</p>
 *
 * <p>{@code usage.events} (params {@code hours} optional 1..24 default 1,
 * {@code limit} optional 1..100 default 50) runs
 * {@link UsageStatsManager#queryEvents} over the last {@code hours} hours and
 * reports {@code events_json} rows of {@code package} (null for device
 * events), {@code type} (a stable name such as {@code ACTIVITY_RESUMED},
 * {@code ACTIVITY_PAUSED}, {@code ACTIVITY_STOPPED}, {@code DEVICE_SHUTDOWN};
 * unknown platform codes are {@code UNKNOWN_<n>}), and {@code at_ms}, in the
 * platform's chronological order, capped at {@code limit} rows with
 * {@code count} and {@code truncated}.</p>
 *
 * <p>{@code usage.standby} (param {@code package} required &le;128 chars)
 * reports {@code package}, {@code bucket} (a stable name: {@code active},
 * {@code working_set}, {@code frequent}, {@code rare}, {@code restricted},
 * {@code never}, or {@code unknown}), and {@code inactive}. For this app's
 * own package the public {@link UsageStatsManager#getAppStandbyBucket()} and
 * {@link UsageStatsManager#isAppInactive} answer without any special access.
 * For a foreign package the platform offers no public bucket query —
 * {@code getAppStandbyBucket(String)} is a {@code @SystemApi} — and
 * {@code isAppInactive} is documented to silently answer {@code false}
 * without the usage grant, so a foreign package is gated on usage access and
 * the bucket is derived from the newest {@code STANDBY_BUCKET_CHANGED} event
 * inside a bounded {@value #STANDBY_LOOKBACK_DAYS}-day lookback
 * ({@code "unknown"} when none is in window) instead of fabricating a
 * value.</p>
 *
 * <p>No method needs an SDK gate: every platform call used here exists at or
 * below API 28 ({@code isAppInactive} API 23, {@code getAppStandbyBucket} API
 * 28, the queries API 21), all under the app's minSdk 29, so no
 * {@code usage-*-unsupported} variant exists. Query windows are hard-bounded
 * at {@value #MAX_WINDOW_DAYS} days.</p>
 */
public final class UsageModule implements CapabilityModule {
    private static final String TAG = "UsageModule";

    private static final String METHOD_QUERY = "usage.query";
    private static final String METHOD_EVENTS = "usage.events";
    private static final String METHOD_STANDBY = "usage.standby";

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final long MAX_WINDOW_DAYS = 7;
    private static final long MAX_QUERY_ROWS = 50;
    private static final long DEFAULT_QUERY_ROWS = 20;
    private static final long MAX_EVENT_HOURS = 24;
    private static final long MAX_EVENT_ROWS = 100;
    private static final long DEFAULT_EVENT_ROWS = 50;
    private static final long STANDBY_LOOKBACK_DAYS = 7;
    private static final int MAX_PACKAGE_CHARS = 128;

    /**
     * Hidden platform bucket for an app that has never been used
     * ({@code STANDBY_BUCKET_NEVER}); the constant is not in the public SDK.
     */
    private static final int BUCKET_NEVER = 50;

    private final Context context;
    private final AndroidPermissionChecker permissions;

    public UsageModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissions = new AndroidPermissionChecker(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_QUERY, METHOD_EVENTS, METHOD_STANDBY);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_QUERY, METHOD_EVENTS, METHOD_STANDBY);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_QUERY:
                return query(request);
            case METHOD_EVENTS:
                return events(request);
            case METHOD_STANDBY:
                return standby(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code usage.query}. The grant gate runs before the platform call so a
     * missing usage-access grant is its typed error rather than a silent
     * empty list, which is what {@code queryUsageStats} returns without it.
     */
    @SuppressLint({"MissingPermission", "Deprecation"})
    private AndroidCapabilityProtocol.Response query(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("days", "limit"));
        long days = params.optionalLong("days", 1, MAX_WINDOW_DAYS, 1);
        long limit = params.optionalLong("limit", 1, MAX_QUERY_ROWS, DEFAULT_QUERY_ROWS);
        UsageStatsManager usage = usageStatsManager();
        if (usage == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }
        String grantError = usageGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usage.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - days * DAY_MS, now);
        } catch (SecurityException e) {
            Log.w(TAG, "usage.query refused", e);
            String refreshed = usageGrantError();
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), refreshed != null ? refreshed : "usage-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "usage.query failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }
        if (stats == null) {
            stats = List.of();
        }

        // INTERVAL_DAILY yields one row per package per day; fold them into a
        // single row per package: summed foreground time, latest use.
        Map<String, long[]> totals = new HashMap<>();
        for (UsageStats stat : stats) {
            String packageName = stat.getPackageName();
            if (packageName == null || packageName.isEmpty()) {
                continue;
            }
            long[] acc = totals.computeIfAbsent(packageName, k -> new long[2]);
            // getTotalTimeInForeground is deprecated in API 29 for
            // getTotalTimeVisible but remains populated and is the field the
            // contract reports.
            acc[0] += stat.getTotalTimeInForeground();
            acc[1] = Math.max(acc[1], stat.getLastTimeUsed());
        }
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(totals.entrySet());
        rows.sort((a, b) -> {
            int cmp = Long.compare(b.getValue()[0], a.getValue()[0]);
            return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
        });
        int count = (int) Math.min(rows.size(), limit);
        StringBuilder json = new StringBuilder(count * 96);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            Map.Entry<String, long[]> row = rows.get(i);
            json.append("{\"package\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(row.getKey()))
                    .append(",\"total_time_ms\":").append(row.getValue()[0])
                    .append(",\"last_time_used_ms\":").append(row.getValue()[1])
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("apps_json", json.toString());
        fields.put("count", (long) count);
        fields.put("window_days", days);
        if (rows.size() > count) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code usage.events}; same grant gate as {@code usage.query}. */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response events(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("hours", "limit"));
        long hours = params.optionalLong("hours", 1, MAX_EVENT_HOURS, 1);
        long limit = params.optionalLong("limit", 1, MAX_EVENT_ROWS, DEFAULT_EVENT_ROWS);
        UsageStatsManager usage = usageStatsManager();
        if (usage == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }
        String grantError = usageGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usage.queryEvents(now - hours * HOUR_MS, now);
        } catch (SecurityException e) {
            Log.w(TAG, "usage.events refused", e);
            String refreshed = usageGrantError();
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), refreshed != null ? refreshed : "usage-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "usage.events failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }
        if (events == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }

        StringBuilder json = new StringBuilder((int) limit * 96);
        json.append('[');
        UsageEvents.Event event = new UsageEvents.Event();
        long count = 0;
        while (count < limit && events.hasNextEvent()) {
            events.getNextEvent(event);
            if (count > 0) {
                json.append(',');
            }
            String packageName = event.getPackageName();
            json.append("{\"package\":")
                    .append(packageName == null
                            ? "null"
                            : AndroidCapabilityProtocol.encodeStringValue(packageName))
                    .append(",\"type\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(
                            eventTypeName(event.getEventType())))
                    .append(",\"at_ms\":").append(event.getTimeStamp())
                    .append('}');
            count++;
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("events_json", json.toString());
        fields.put("count", count);
        if (events.hasNextEvent()) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code usage.standby}. The own-package path needs no grant: the public
     * no-arg {@code getAppStandbyBucket} and self {@code isAppInactive} are
     * exempt. A foreign package needs usage access — the platform answers
     * {@code isAppInactive(foreign)} with a silent {@code false} without it —
     * and the bucket comes from the newest {@code STANDBY_BUCKET_CHANGED}
     * event in the bounded lookback since the public SDK has no foreign-app
     * bucket query.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response standby(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("package"));
        String packageName = params.requireString("package", MAX_PACKAGE_CHARS);
        if (packageName.isEmpty()) {
            throw new CapabilityParams.Invalid("missing string parameter: package");
        }
        UsageStatsManager usage = usageStatsManager();
        if (usage == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }
        boolean self = packageName.equals(context.getPackageName());
        if (!self) {
            String grantError = usageGrantError();
            if (grantError != null) {
                return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
            }
        }
        int bucket;
        boolean inactive;
        try {
            inactive = usage.isAppInactive(packageName);
            bucket = self
                    ? usage.getAppStandbyBucket()
                    : foreignStandbyBucket(usage, packageName);
        } catch (SecurityException e) {
            Log.w(TAG, "usage.standby refused", e);
            String refreshed = usageGrantError();
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), refreshed != null ? refreshed : "usage-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "usage.standby failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "usage-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("package", packageName);
        fields.put("bucket", bucketName(bucket));
        fields.put("inactive", inactive);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * The newest {@code STANDBY_BUCKET_CHANGED} bucket recorded for a foreign
     * package inside the lookback window, or {@code -1} when the platform has
     * none in window — the only public signal for another app's bucket.
     */
    @SuppressLint("MissingPermission")
    private static int foreignStandbyBucket(UsageStatsManager usage, String packageName) {
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usage.queryEvents(now - STANDBY_LOOKBACK_DAYS * DAY_MS, now);
        } catch (RuntimeException e) {
            return -1;
        }
        if (events == null) {
            return -1;
        }
        int bucket = -1;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.STANDBY_BUCKET_CHANGED
                    && packageName.equals(event.getPackageName())) {
                bucket = event.getAppStandbyBucket();
            }
        }
        return bucket;
    }

    /**
     * {@code null} when usage access is granted, else the typed error. The
     * grant is special access (app-op, not runtime permission): the platform
     * only reports GRANTED or REQUIRED, never a recorded denial.
     */
    private String usageGrantError() {
        return permissions.check(Manifest.permission.PACKAGE_USAGE_STATS)
                == CapabilityPermission.GRANTED
                ? null
                : "usage-permission-required:grant usage access in Settings"
                        + " (permission.request mode=settings)";
    }

    private UsageStatsManager usageStatsManager() {
        try {
            return context.getSystemService(UsageStatsManager.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Stable wire name for a {@link UsageEvents.Event} type code. */
    private static String eventTypeName(int type) {
        switch (type) {
            case UsageEvents.Event.NONE: return "NONE";
            case UsageEvents.Event.ACTIVITY_RESUMED: return "ACTIVITY_RESUMED";
            case UsageEvents.Event.ACTIVITY_PAUSED: return "ACTIVITY_PAUSED";
            case UsageEvents.Event.CONFIGURATION_CHANGE: return "CONFIGURATION_CHANGE";
            case UsageEvents.Event.USER_INTERACTION: return "USER_INTERACTION";
            case UsageEvents.Event.SHORTCUT_INVOCATION: return "SHORTCUT_INVOCATION";
            case UsageEvents.Event.STANDBY_BUCKET_CHANGED: return "STANDBY_BUCKET_CHANGED";
            case UsageEvents.Event.SCREEN_INTERACTIVE: return "SCREEN_INTERACTIVE";
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE: return "SCREEN_NON_INTERACTIVE";
            case UsageEvents.Event.KEYGUARD_SHOWN: return "KEYGUARD_SHOWN";
            case UsageEvents.Event.KEYGUARD_HIDDEN: return "KEYGUARD_HIDDEN";
            case UsageEvents.Event.FOREGROUND_SERVICE_START: return "FOREGROUND_SERVICE_START";
            case UsageEvents.Event.FOREGROUND_SERVICE_STOP: return "FOREGROUND_SERVICE_STOP";
            case UsageEvents.Event.ACTIVITY_STOPPED: return "ACTIVITY_STOPPED";
            case UsageEvents.Event.DEVICE_SHUTDOWN: return "DEVICE_SHUTDOWN";
            case UsageEvents.Event.DEVICE_STARTUP: return "DEVICE_STARTUP";
            default: return "UNKNOWN_" + type;
        }
    }

    /** Stable wire name for a standby bucket value; {@code -1} maps to {@code unknown}. */
    private static String bucketName(int bucket) {
        switch (bucket) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE: return "active";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET: return "working_set";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT: return "frequent";
            case UsageStatsManager.STANDBY_BUCKET_RARE: return "rare";
            case UsageStatsManager.STANDBY_BUCKET_RESTRICTED: return "restricted";
            case BUCKET_NEVER: return "never";
            default: return "unknown";
        }
    }
}
