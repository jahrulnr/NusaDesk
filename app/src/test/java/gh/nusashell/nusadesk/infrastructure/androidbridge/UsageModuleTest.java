package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowUsageStatsManager;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link UsageModule} on the JVM. The platform surface is Robolectric's
 * {@link ShadowUsageStatsManager}: {@code addUsageStats} feeds
 * {@code queryUsageStats} (filtered by interval and window overlap),
 * {@code addEvent} feeds {@code queryEvents} (a {@code [begin, end)}
 * window), and {@code setCurrentAppStandbyBucket} feeds the public
 * no-arg {@code getAppStandbyBucket}. The usage-access grant is exercised
 * through the app-op state the real {@link AndroidPermissionChecker} reads;
 * the shadow defaults an unset op to {@code MODE_ALLOWED}, so each test pins
 * the mode it needs. {@code isAppInactive} is not implemented by the shadow,
 * so {@code inactive} is only ever {@code false} here — the {@code true}
 * path needs a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class UsageModuleTest {
    private Context context;
    private UsageStatsManager usageStats;
    private ShadowUsageStatsManager usageShadow;
    private AppOpsManager appOps;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        usageStats = context.getSystemService(UsageStatsManager.class);
        usageShadow = Shadow.extract(usageStats);
        appOps = context.getSystemService(AppOpsManager.class);
        // Real devices ship the op in MODE_DEFAULT; pinning MODE_IGNORED here
        // gives the module a deterministic "no grant" baseline.
        setUsageAccess(AppOpsManager.MODE_IGNORED);
    }

    @Test
    public void declaresTheUsageMethodsAndParamSet() {
        UsageModule module = module();
        assertEquals(3, module.methods().size());
        assertTrue(module.methods().contains("usage.query"));
        assertTrue(module.methods().contains("usage.events"));
        assertTrue(module.methods().contains("usage.standby"));
        assertEquals(Set.of("usage.query", "usage.events", "usage.standby"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "usage.nope"));
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void queryWithoutUsageAccessIsPermissionError() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "usage.query"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("usage-permission-required"));
        assertTrue(response.getError().contains("permission.request"));
    }

    @Test
    public void queryAggregatesDailyRowsPerPackage() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        long now = System.currentTimeMillis();
        // INTERVAL_DAILY reports one row per package per day; two days of the
        // same package fold into one aggregated row.
        usageShadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY,
                stats("com.a", now - 3_600_000L, now, 100L, now - 2_000L));
        usageShadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY,
                stats("com.a", now - 3_000_000L, now, 40L, now - 500L));
        usageShadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY,
                stats("com.b", now - 3_600_000L, now, 200L, now - 1_000L));
        // A different interval must not leak into the daily query.
        usageShadow.addUsageStats(UsageStatsManager.INTERVAL_WEEKLY,
                stats("com.c", now - 3_600_000L, now, 999L, now));

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "usage.query", "{\"days\":3}"));
        assertTrue(response.isOk());
        assertEquals(2L, response.getFields().get("count"));
        assertEquals(3L, response.getFields().get("window_days"));
        assertFalse(response.getFields().containsKey("truncated"));
        String expected = "[{\"package\":\"com.b\",\"total_time_ms\":200,"
                + "\"last_time_used_ms\":" + (now - 1_000L) + "},"
                + "{\"package\":\"com.a\",\"total_time_ms\":140,"
                + "\"last_time_used_ms\":" + (now - 500L) + "}]";
        assertEquals(expected, response.getFields().get("apps_json"));
    }

    @Test
    public void queryHonoursLimitAndTruncates() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            usageShadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    stats("com.p" + i, now - 3_600_000L, now, (i + 1) * 10L, now - i));
        }
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "usage.query", "{\"limit\":2}"));
        assertTrue(response.isOk());
        assertEquals(2L, response.getFields().get("count"));
        assertEquals(true, response.getFields().get("truncated"));
        // The two heaviest packages win, in descending order.
        assertEquals("[{\"package\":\"com.p4\",\"total_time_ms\":50,"
                        + "\"last_time_used_ms\":" + (now - 4) + "},"
                        + "{\"package\":\"com.p3\",\"total_time_ms\":40,"
                        + "\"last_time_used_ms\":" + (now - 3) + "}]",
                response.getFields().get("apps_json"));
    }

    @Test
    public void queryEmptyWindowIsAnEmptyList() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "usage.query"));
        assertTrue(response.isOk());
        assertEquals("[]", response.getFields().get("apps_json"));
        assertEquals(0L, response.getFields().get("count"));
        assertEquals(1L, response.getFields().get("window_days"));
    }

    @Test
    public void queryRejectsBadParams() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        UsageModule module = module();
        assertThrowsInvalid(module, "1", "usage.query", "{\"days\":0}");
        assertThrowsInvalid(module, "2", "usage.query", "{\"days\":8}");
        assertThrowsInvalid(module, "3", "usage.query", "{\"limit\":0}");
        assertThrowsInvalid(module, "4", "usage.query", "{\"limit\":51}");
        assertThrowsInvalid(module, "5", "usage.query", "{\"days\":\"3\"}");
        assertThrowsInvalid(module, "6", "usage.query", "{\"bogus\":1}");
    }

    @Test
    public void eventsWithoutUsageAccessIsPermissionError() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "usage.events"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("usage-permission-required"));
    }

    @Test
    public void eventsReportsRowsInChronologicalOrder() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        long now = System.currentTimeMillis();
        usageShadow.addEvent("com.a", now - 3_000L, UsageEvents.Event.ACTIVITY_RESUMED);
        usageShadow.addEvent("com.a", now - 2_000L, UsageEvents.Event.ACTIVITY_PAUSED);
        // A device event carries no package and serializes as null.
        usageShadow.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setTimeStamp(now - 1_000L)
                .setEventType(UsageEvents.Event.SCREEN_INTERACTIVE)
                .build());

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "usage.events"));
        assertTrue(response.isOk());
        assertEquals(3L, response.getFields().get("count"));
        assertFalse(response.getFields().containsKey("truncated"));
        assertEquals("[{\"package\":\"com.a\",\"type\":\"ACTIVITY_RESUMED\","
                        + "\"at_ms\":" + (now - 3_000L) + "},"
                        + "{\"package\":\"com.a\",\"type\":\"ACTIVITY_PAUSED\","
                        + "\"at_ms\":" + (now - 2_000L) + "},"
                        + "{\"package\":null,\"type\":\"SCREEN_INTERACTIVE\","
                        + "\"at_ms\":" + (now - 1_000L) + "}]",
                response.getFields().get("events_json"));
    }

    @Test
    public void eventsTruncatesAtLimit() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            usageShadow.addEvent("com.a", now - 3_000L + i,
                    UsageEvents.Event.ACTIVITY_RESUMED);
        }
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "usage.events", "{\"limit\":2}"));
        assertTrue(response.isOk());
        assertEquals(2L, response.getFields().get("count"));
        assertEquals(true, response.getFields().get("truncated"));
    }

    @Test
    public void eventsRejectsBadParams() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        UsageModule module = module();
        assertThrowsInvalid(module, "1", "usage.events", "{\"hours\":0}");
        assertThrowsInvalid(module, "2", "usage.events", "{\"hours\":25}");
        assertThrowsInvalid(module, "3", "usage.events", "{\"limit\":0}");
        assertThrowsInvalid(module, "4", "usage.events", "{\"limit\":101}");
        assertThrowsInvalid(module, "5", "usage.events", "{\"bogus\":1}");
    }

    @Test
    public void standbyOwnPackageNeedsNoGrant() {
        // MODE_IGNORED baseline: the own-package path must still answer.
        usageShadow.setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET);
        AndroidCapabilityProtocol.Response response = module().handle(request("1",
                "usage.standby", "{\"package\":\"" + context.getPackageName() + "\"}"));
        assertTrue(response.isOk());
        assertEquals(context.getPackageName(), response.getFields().get("package"));
        assertEquals("working_set", response.getFields().get("bucket"));
        assertEquals(false, response.getFields().get("inactive"));
    }

    @Test
    public void standbyForeignPackageWithoutGrantIsPermissionError() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "usage.standby", "{\"package\":\"com.other\"}"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("usage-permission-required"));
    }

    @Test
    public void standbyForeignPackageDerivesBucketFromEvents() {
        setUsageAccess(AppOpsManager.MODE_ALLOWED);
        long now = System.currentTimeMillis();
        usageShadow.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage("com.other")
                .setTimeStamp(now - 10_000L)
                .setEventType(UsageEvents.Event.STANDBY_BUCKET_CHANGED)
                .setAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
                .build());
        // A newer bucket event wins over an older one.
        usageShadow.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage("com.other")
                .setTimeStamp(now - 1_000L)
                .setEventType(UsageEvents.Event.STANDBY_BUCKET_CHANGED)
                .setAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT)
                .build());

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "usage.standby", "{\"package\":\"com.other\"}"));
        assertTrue(response.isOk());
        assertEquals("frequent", response.getFields().get("bucket"));
        assertEquals(false, response.getFields().get("inactive"));

        // No bucket event inside the lookback window reports "unknown".
        AndroidCapabilityProtocol.Response unseen = module().handle(
                request("2", "usage.standby", "{\"package\":\"com.unseen\"}"));
        assertTrue(unseen.isOk());
        assertEquals("unknown", unseen.getFields().get("bucket"));
    }

    @Test
    public void standbyBucketNamesAreStable() {
        UsageModule module = module();
        String self = context.getPackageName();
        assertBucket(module, self, UsageStatsManager.STANDBY_BUCKET_ACTIVE, "active");
        assertBucket(module, self, UsageStatsManager.STANDBY_BUCKET_WORKING_SET, "working_set");
        assertBucket(module, self, UsageStatsManager.STANDBY_BUCKET_FREQUENT, "frequent");
        assertBucket(module, self, UsageStatsManager.STANDBY_BUCKET_RARE, "rare");
        assertBucket(module, self, UsageStatsManager.STANDBY_BUCKET_RESTRICTED, "restricted");
        // STANDBY_BUCKET_NEVER is a hidden constant (50); an app that never
        // ran reports it through bucket events.
        assertBucket(module, self, 50, "never");
        assertBucket(module, self, 999, "unknown");
    }

    @Test
    public void standbyRejectsBadParams() {
        UsageModule module = module();
        assertThrowsInvalid(module, "1", "usage.standby", "{}");
        assertThrowsInvalid(module, "2", "usage.standby", "{\"package\":\"\"}");
        StringBuilder tooLong = new StringBuilder("{\"package\":\"");
        for (int i = 0; i < 129; i++) {
            tooLong.append('a');
        }
        tooLong.append("\"}");
        assertThrowsInvalid(module, "3", "usage.standby", tooLong.toString());
        assertThrowsInvalid(module, "4", "usage.standby", "{\"package\":5}");
        assertThrowsInvalid(module, "5", "usage.standby",
                "{\"package\":\"com.a\",\"bogus\":1}");
    }

    // --- helpers ---------------------------------------------------------

    private UsageModule module() {
        return new UsageModule(context);
    }

    private void setUsageAccess(int mode) {
        Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                context.getPackageName(), mode);
    }

    private void assertBucket(UsageModule module, String packageName,
                              int bucket, String expected) {
        usageShadow.setCurrentAppStandbyBucket(bucket);
        AndroidCapabilityProtocol.Response response = module.handle(request(
                "b-" + bucket, "usage.standby",
                "{\"package\":\"" + packageName + "\"}"));
        assertTrue(response.isOk());
        assertEquals(expected, response.getFields().get("bucket"));
    }

    private static UsageStats stats(String packageName, long first, long last,
                                    long foregroundMs, long lastUsedMs) {
        return ShadowUsageStatsManager.UsageStatsBuilder.newBuilder()
                .setPackageName(packageName)
                .setFirstTimeStamp(first)
                .setLastTimeStamp(last)
                .setTotalTimeInForeground(foregroundMs)
                .setLastTimeUsed(lastUsedMs)
                .build();
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                           String paramsJson) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        AndroidCapabilityProtocol.Request request = AndroidCapabilityProtocol.decodeRequest(frame);
        assertNotNull("test frame must decode", request);
        return request;
    }

    private static void assertThrowsInvalid(CapabilityModule module, String id,
                                            String method, String paramsJson) {
        try {
            module.handle(request(id, method, paramsJson));
        } catch (CapabilityParams.Invalid e) {
            return;
        }
        throw new AssertionError(method + " must reject params " + paramsJson);
    }
}
