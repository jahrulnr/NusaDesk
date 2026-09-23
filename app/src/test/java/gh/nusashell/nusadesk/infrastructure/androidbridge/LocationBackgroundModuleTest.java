package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLocationManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import gh.nusashell.nusadesk.infrastructure.service.LocationBackgroundService;

/**
 * {@link LocationBackgroundModule} on the JVM. The platform manager comes
 * from the seam constructor and is driven through Robolectric's location
 * shadow; the real {@link LocationBackgroundService} is driven through a
 * {@link ServiceController} fed by the very intent the module dispatched, so
 * the claim, listener registration, buffered fixes, and teardown are all
 * observable without a device. Fix cadence, provider accuracy, and the
 * platform's actual background-start/FGS behavior remain device-verification
 * items.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LocationBackgroundModuleTest {

    private Context context;
    private LocationManager manager;
    private ShadowLocationManager shadowManager;
    private ServiceController<LocationBackgroundService> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        shadowManager = Shadow.extract(manager);
        shadowManager.setLocationEnabled(true);
        shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        shadowManager.setProviderEnabled(LocationManager.PASSIVE_PROVIDER, true);
        resetServiceState();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        resetServiceState();
    }

    @Test
    public void declaresTheBackgroundLocationMethods() {
        LocationBackgroundModule module = module();
        assertEquals(List.of("location.background.start", "location.background.poll",
                "location.background.stop"), module.methods());
        assertEquals(Set.of("location.background.start"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "location.background.nope"));
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void startWithoutBackgroundGrantIsTypedPermissionRequired() {
        // Robolectric defaults every grant to denied-without-recorded-refusal,
        // which the checker reports as REQUIRED; both map to the same error.
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "location.background.start"));

        assertFalse(response.isOk());
        assertTrue(response.getError()
                .startsWith("location-background-permission-required"));
        assertTrue(response.getError().contains("Allow all the time"));
        assertTrue(response.getError().contains("permission.request mode=settings"));
    }

    @Test
    public void startRejectsBadParams() {
        grantLocation();
        LocationBackgroundModule module = module();
        assertThrowsInvalid(module, "1", "location.background.start",
                "{\"provider\":\"cell\"}");
        assertThrowsInvalid(module, "2", "location.background.start",
                "{\"interval_ms\":999}");
        assertThrowsInvalid(module, "3", "location.background.start",
                "{\"interval_ms\":60001}");
        assertThrowsInvalid(module, "4", "location.background.start",
                "{\"distance_m\":-1}");
        assertThrowsInvalid(module, "5", "location.background.start",
                "{\"distance_m\":1001}");
        assertThrowsInvalid(module, "6", "location.background.start",
                "{\"bogus\":1}");
    }

    @Test
    public void startWithUnavailableProviderIsTyped() {
        grantLocation();
        shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "location.background.start", "{\"provider\":\"gps\"}"));

        assertFalse(response.isOk());
        assertEquals("location-background-provider-unavailable:gps",
                response.getError());

        // The failed start released its claim: a usable provider can start.
        AndroidCapabilityProtocol.Response next = module().handle(
                request("2", "location.background.start"));
        assertTrue(next.isOk());
        module().handle(request("3", "location.background.stop"));
    }

    @Test
    public void startDispatchesServiceIntentWithParamsAndDefaults() {
        grantLocation();
        LocationBackgroundModule module = module();

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "location.background.start",
                        "{\"provider\":\"gps\",\"interval_ms\":2000,\"distance_m\":5}"));

        assertTrue(response.isOk());
        Map<String, Object> fields = response.getFields();
        assertEquals(true, fields.get("started"));
        assertEquals("gps", fields.get("provider"));
        assertEquals(2_000L, fields.get("interval_ms"));
        assertEquals(5L, fields.get("distance_m"));

        Intent dispatched = lastStartedService();
        assertNotNull("start must dispatch the foreground service", dispatched);
        assertEquals(LocationBackgroundService.class.getName(),
                dispatched.getComponent().getClassName());
        assertEquals(LocationBackgroundService.ACTION_START, dispatched.getAction());
        assertEquals("gps", dispatched.getStringExtra(
                LocationBackgroundService.EXTRA_PROVIDER));
        assertEquals(2_000L, dispatched.getLongExtra(
                LocationBackgroundService.EXTRA_INTERVAL_MS, -1));
        assertEquals(5f, dispatched.getFloatExtra(
                LocationBackgroundService.EXTRA_DISTANCE_M, -1f), 0.001f);
        module.handle(request("2", "location.background.stop"));

        // Defaults mirror the upstream 5 s / 1 m update shape.
        module.handle(request("3", "location.background.start"));
        Intent defaulted = lastStartedService();
        assertEquals("network", defaulted.getStringExtra(
                LocationBackgroundService.EXTRA_PROVIDER));
        assertEquals(5_000L, defaulted.getLongExtra(
                LocationBackgroundService.EXTRA_INTERVAL_MS, -1));
        assertEquals(1f, defaulted.getFloatExtra(
                LocationBackgroundService.EXTRA_DISTANCE_M, -1f), 0.001f);
        module.handle(request("4", "location.background.stop"));
    }

    @Test
    public void startWhileClaimedIsAlreadyRunning() {
        grantLocation();
        LocationBackgroundModule module = module();
        assertTrue(module.handle(request("1", "location.background.start")).isOk());

        AndroidCapabilityProtocol.Response second =
                module.handle(request("2", "location.background.start"));

        assertFalse(second.isOk());
        assertEquals("location-background-already-running", second.getError());
        module.handle(request("3", "location.background.stop"));
    }

    @Test
    public void stopIsIdempotentAndReleasesTheClaim() {
        grantLocation();
        LocationBackgroundModule module = module();

        AndroidCapabilityProtocol.Response idle =
                module.handle(request("1", "location.background.stop"));
        assertTrue(idle.isOk());
        assertEquals(true, idle.getFields().get("stopped"));
        assertEquals(false, idle.getFields().get("was_running"));

        module.handle(request("2", "location.background.start"));
        AndroidCapabilityProtocol.Response stop =
                module.handle(request("3", "location.background.stop"));
        assertTrue(stop.isOk());
        assertEquals(true, stop.getFields().get("stopped"));
        assertEquals(true, stop.getFields().get("was_running"));

        AndroidCapabilityProtocol.Response again =
                module.handle(request("4", "location.background.stop"));
        assertTrue(again.isOk());
        assertEquals(false, again.getFields().get("was_running"));

        // The claim is fully released: a new start dispatches again.
        assertTrue(module.handle(request("5", "location.background.start")).isOk());
        module.handle(request("6", "location.background.stop"));
    }

    @Test
    public void pollWithoutSessionIsEmptyNotError() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "location.background.poll"));
        assertTrue(response.isOk());
        assertEquals("[]", response.getFields().get("fixes_json"));
        assertEquals(0L, response.getFields().get("count"));
        assertEquals(false, response.getFields().get("running"));
    }

    @Test
    public void pollDrainsBufferedFixesAsUpstreamArray() {
        LocationBackgroundService.offerFix(new LocationBackgroundService.Fix(
                -6.9175, 107.6191, 12.5, "gps", 1_700_000_000_000L));
        LocationBackgroundService.offerFix(new LocationBackgroundService.Fix(
                -6.9180, 107.6200, 9.0, "gps", 1_700_000_005_000L));
        LocationBackgroundModule module = module();

        AndroidCapabilityProtocol.Response response =
                module.handle(request("1", "location.background.poll"));

        assertTrue(response.isOk());
        assertEquals(2L, response.getFields().get("count"));
        String json = (String) response.getFields().get("fixes_json");
        assertTrue(json.startsWith("["));
        assertTrue(json.contains("{\"latitude\":-6.9175,\"longitude\":107.6191,"
                + "\"accuracy\":12.5,\"provider\":\"gps\","
                + "\"at_ms\":1700000000000}"));

        // Drained: the next poll is empty.
        AndroidCapabilityProtocol.Response empty =
                module.handle(request("2", "location.background.poll"));
        assertEquals(0L, empty.getFields().get("count"));
        assertEquals("[]", empty.getFields().get("fixes_json"));
    }

    @Test
    public void bufferDropsOldestAtCapacityAndPollCapsAtHundred() {
        for (int i = 0; i < 260; i++) {
            LocationBackgroundService.offerFix(new LocationBackgroundService.Fix(
                    i, 107.0, 1.0, "network", 1_000L + i));
        }
        LocationBackgroundModule module = module();

        AndroidCapabilityProtocol.Response first =
                module.handle(request("1", "location.background.poll"));
        assertEquals(100L, first.getFields().get("count"));
        // The buffer kept the newest 256 of 260: fix 4 is now the oldest.
        String json = (String) first.getFields().get("fixes_json");
        assertTrue(json.contains("\"latitude\":4.0"));
        assertFalse(json.contains("\"latitude\":3.0"));

        AndroidCapabilityProtocol.Response second =
                module.handle(request("2", "location.background.poll"));
        assertEquals(100L, second.getFields().get("count"));
        AndroidCapabilityProtocol.Response third =
                module.handle(request("3", "location.background.poll"));
        assertEquals(56L, third.getFields().get("count"));
        AndroidCapabilityProtocol.Response empty =
                module.handle(request("4", "location.background.poll"));
        assertEquals(0L, empty.getFields().get("count"));
    }

    @Test
    public void closeReleasesAClaimedSession() {
        grantLocation();
        LocationBackgroundModule module = module();
        module.handle(request("1", "location.background.start"));

        module.close();

        assertFalse(LocationBackgroundService.isRunning());
        assertTrue(module.handle(request("2", "location.background.start")).isOk());
        module.handle(request("3", "location.background.stop"));
    }

    @Test
    public void dispatchedStartRunsTheRealServiceAndPollDrainsItsFix() {
        grantLocation();
        LocationBackgroundModule module = module();
        assertTrue(module.handle(request("1", "location.background.start",
                "{\"provider\":\"gps\",\"interval_ms\":1000}")).isOk());

        controller = Robolectric
                .buildService(LocationBackgroundService.class, lastStartedService())
                .create();
        controller.startCommand(0, 1);

        assertTrue(LocationBackgroundService.isRunning());
        assertEquals(1, shadowManager.getLocationUpdateListeners(
                LocationManager.GPS_PROVIDER).size());
        assertTrue(shadowManager.getLocationUpdateListeners(
                LocationManager.NETWORK_PROVIDER).isEmpty());
        assertEquals(1_000L, shadowManager.getLegacyLocationRequests(
                LocationManager.GPS_PROVIDER).get(0).getIntervalMillis());

        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(-6.9175);
        fix.setLongitude(107.6191);
        fix.setAccuracy(8.0f);
        fix.setTime(1_700_000_000_000L);
        shadowManager.simulateLocation(fix);
        ShadowLooper.idleMainLooper();

        AndroidCapabilityProtocol.Response poll =
                module.handle(request("2", "location.background.poll"));
        assertTrue(poll.isOk());
        assertEquals(true, poll.getFields().get("running"));
        assertEquals(1L, poll.getFields().get("count"));
        String json = (String) poll.getFields().get("fixes_json");
        assertTrue(json.contains("\"latitude\":-6.9175"));
        assertTrue(json.contains("\"provider\":\"gps\""));

        // The notification Stop action targets this service's stop action.
        controller.withIntent(
                LocationBackgroundService.stopIntent(context)).startCommand(0, 2);
        assertFalse(LocationBackgroundService.isRunning());
        assertTrue(shadowManager.getLocationUpdateListeners().isEmpty());
        assertTrue(Shadow.<ShadowService>extract(controller.get()).isStoppedBySelf());
        controller.destroy();
        controller = null;
    }

    @Test
    public void providerDisableDuringSessionStopsTheService() {
        grantLocation();
        LocationBackgroundModule module = module();
        assertTrue(module.handle(request("1", "location.background.start",
                "{\"provider\":\"gps\"}")).isOk());
        controller = Robolectric
                .buildService(LocationBackgroundService.class, lastStartedService())
                .create();
        controller.startCommand(0, 1);
        assertTrue(LocationBackgroundService.isRunning());

        shadowManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false);
        List<android.location.LocationListener> listeners =
                shadowManager.getLocationUpdateListeners();
        if (!listeners.isEmpty()) {
            listeners.get(0).onProviderDisabled(LocationManager.GPS_PROVIDER);
        }
        ShadowLooper.idleMainLooper();

        assertFalse(LocationBackgroundService.isRunning());
        controller.destroy();
        controller = null;
    }

    // --- helpers ---------------------------------------------------------

    private LocationBackgroundModule module() {
        return new LocationBackgroundModule(context, manager);
    }

    private void grantLocation() {
        Shadow.<ShadowContextWrapper>extract(context).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private static Intent lastStartedService() {
        List<Intent> started = Shadow.<ShadowContextWrapper>extract(
                RuntimeEnvironment.getApplication()).getAllStartedServices();
        return started.isEmpty() ? null : started.get(started.size() - 1);
    }

    private static void resetServiceState() {
        LocationBackgroundService.releaseStartClaim();
        LocationBackgroundService.drainFixes(256);
        ShadowContextWrapper shadow = Shadow.extract(
                RuntimeEnvironment.getApplication());
        shadow.clearStartedServices();
        while (shadow.getNextStoppedService() != null) {
            // Drain stale stop intents between tests.
        }
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                             String paramsJson) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\""
                + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        AndroidCapabilityProtocol.Request request =
                AndroidCapabilityProtocol.decodeRequest(frame);
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
