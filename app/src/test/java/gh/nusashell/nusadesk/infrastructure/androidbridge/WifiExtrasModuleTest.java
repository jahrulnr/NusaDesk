package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Process;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowContextWrapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link WifiExtrasModule} on the JVM. The platform surface is injected as a
 * {@link WifiExtrasModule.WifiBackend} fake because {@link WifiManager} cannot
 * be subclassed (package-private constructor) and its hotspot reservation and
 * wifi-lock types are unconstructible on the JVM; the fake also drives the
 * asynchronous hotspot verdicts synchronously. Permission gates are exercised
 * through Robolectric's real grant/app-ops state.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WifiExtrasModuleTest {
    private static final long HOTSPOT_TIMEOUT_MS = 250L;

    private Context context;
    private FakeBackend backend;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        backend = new FakeBackend();
    }

    @Test
    public void declaresTheWifiExtrasMethodsAndParamSet() {
        WifiExtrasModule module = module();
        assertEquals(8, module.methods().size());
        assertTrue(module.methods().contains("wifi.hotspot.start"));
        assertTrue(module.methods().contains("wifi.hotspot.stop"));
        assertTrue(module.methods().contains("wifi.hotspot.status"));
        assertTrue(module.methods().contains("wifi.suggest.add"));
        assertTrue(module.methods().contains("wifi.suggest.remove"));
        assertTrue(module.methods().contains("wifi.suggest.list"));
        assertTrue(module.methods().contains("wifi.lock.acquire"));
        assertTrue(module.methods().contains("wifi.lock.release"));
        assertEquals(Set.of("wifi.suggest.add", "wifi.suggest.remove", "wifi.lock.acquire"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.nope"));
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void hotspotStartWithoutChangeWifiStateIsPermissionError() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.hotspot.start"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("wifi-permission-required"));
        assertTrue(response.getError().contains("CHANGE_WIFI_STATE"));
        assertEquals(0, backend.startCalls);
    }

    @Test
    public void hotspotStartWithoutLocationGrantIsPermissionError() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        AndroidCapabilityProtocol.Response required =
                module().handle(request("1", "wifi.hotspot.start"));
        assertFalse(required.isOk());
        assertTrue(required.getError().startsWith("wifi-permission-required"));
        assertTrue(required.getError().contains("ACCESS_FINE_LOCATION"));

        deny(Manifest.permission.ACCESS_FINE_LOCATION);
        AndroidCapabilityProtocol.Response denied =
                module().handle(request("2", "wifi.hotspot.start"));
        assertFalse(denied.isOk());
        assertTrue(denied.getError().startsWith("wifi-permission-denied"));
    }

    @Test
    public void hotspotStartReportsCredentials() {
        grantHotspotPermissions();
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.hotspot.start"));
        assertTrue(response.isOk());
        assertEquals("AndroidShare_ab12", response.getFields().get("ssid"));
        assertEquals("p@ssw0rd!", response.getFields().get("passphrase"));
        assertEquals("wpa2_psk", response.getFields().get("security_type"));
        assertEquals(true, response.getFields().get("running"));
    }

    @Test
    public void hotspotStartWhileHeldIsInUse() {
        grantHotspotPermissions();
        WifiExtrasModule module = module();
        assertTrue(module.handle(request("1", "wifi.hotspot.start")).isOk());

        AndroidCapabilityProtocol.Response second =
                module.handle(request("2", "wifi.hotspot.start"));
        assertFalse(second.isOk());
        assertEquals("wifi-hotspot-in-use", second.getError());
        assertEquals(1, backend.startCalls);
    }

    @Test
    public void hotspotStartIncompatibleModeIsInUse() {
        grantHotspotPermissions();
        backend.failReason = WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.hotspot.start"));
        assertFalse(response.isOk());
        assertEquals("wifi-hotspot-in-use", response.getError());
    }

    @Test
    public void hotspotStartTetheringDisallowedIsUnsupported() {
        grantHotspotPermissions();
        backend.failReason = WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.hotspot.start"));
        assertFalse(response.isOk());
        assertEquals("wifi-hotspot-unsupported:this device does not support a local-only hotspot",
                response.getError());
    }

    @Test
    public void hotspotStartPlatformFailuresAreTyped() {
        grantHotspotPermissions();
        backend.failReason = WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
        assertEquals("wifi-hotspot-failed:no channel available",
                module().handle(request("1", "wifi.hotspot.start")).getError());
        backend.failReason = WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
        assertEquals("wifi-hotspot-failed:generic error",
                module().handle(request("2", "wifi.hotspot.start")).getError());
    }

    @Test
    public void hotspotStartSynchronousThrowIsTypedFailure() {
        grantHotspotPermissions();
        backend.runtimeOnStart = true;
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.hotspot.start"));
        assertFalse(response.isOk());
        assertEquals("wifi-hotspot-failed:start request rejected", response.getError());
    }

    @Test
    public void hotspotStartTimeoutIsTypedFailureAndLateReservationIsClosed() {
        grantHotspotPermissions();
        backend.deferStart = true;
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.hotspot.start"));
        assertFalse(response.isOk());
        assertEquals("wifi-hotspot-failed:start timed out", response.getError());

        FakeReservation late = new FakeReservation();
        backend.listener.onStarted(late);
        assertTrue(late.closed);
    }

    @Test
    public void hotspotStopIsIdempotent() {
        grantHotspotPermissions();
        WifiExtrasModule module = module();
        assertTrue(module.handle(request("1", "wifi.hotspot.start")).isOk());
        FakeReservation reservation = backend.lastReservation;
        assertFalse(reservation.closed);

        AndroidCapabilityProtocol.Response stop =
                module.handle(request("2", "wifi.hotspot.stop"));
        assertTrue(stop.isOk());
        assertEquals(true, stop.getFields().get("stopped"));
        assertEquals(true, stop.getFields().get("was_running"));
        assertTrue(reservation.closed);

        AndroidCapabilityProtocol.Response again =
                module.handle(request("3", "wifi.hotspot.stop"));
        assertTrue(again.isOk());
        assertEquals(true, again.getFields().get("stopped"));
        assertEquals(false, again.getFields().get("was_running"));
    }

    @Test
    public void hotspotStatusReflectsRunningAndStoppedStates() {
        grantHotspotPermissions();
        WifiExtrasModule module = module();
        AndroidCapabilityProtocol.Response idle =
                module.handle(request("1", "wifi.hotspot.status"));
        assertTrue(idle.isOk());
        assertEquals(false, idle.getFields().get("running"));
        assertEquals("", idle.getFields().get("ssid"));
        assertEquals("", idle.getFields().get("passphrase"));

        assertTrue(module.handle(request("2", "wifi.hotspot.start")).isOk());
        AndroidCapabilityProtocol.Response live =
                module.handle(request("3", "wifi.hotspot.status"));
        assertEquals(true, live.getFields().get("running"));
        assertEquals("AndroidShare_ab12", live.getFields().get("ssid"));
        assertEquals("p@ssw0rd!", live.getFields().get("passphrase"));

        // The platform can end the reservation on its own (wifi off, channel lost).
        backend.listener.onStopped();
        AndroidCapabilityProtocol.Response stopped =
                module.handle(request("4", "wifi.hotspot.status"));
        assertEquals(false, stopped.getFields().get("running"));
        assertEquals("", stopped.getFields().get("ssid"));

        AndroidCapabilityProtocol.Response stop =
                module.handle(request("5", "wifi.hotspot.stop"));
        assertEquals(false, stop.getFields().get("was_running"));
    }

    @Test
    public void suggestAddBelowApi30IsUnsupported() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "wifi.suggest.add", "{\"ssid\":\"cafe\"}"));
        assertFalse(response.isOk());
        assertEquals("wifi-suggest-unsupported:requires Android 11", response.getError());
    }

    @Test
    @Config(sdk = 30)
    public void suggestAddBuildsTheSuggestion() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        AndroidCapabilityProtocol.Response response = module().handle(request("1",
                "wifi.suggest.add",
                "{\"ssid\":\"cafe\",\"passphrase\":\"hunter22\",\"priority\":10,"
                        + "\"is_hidden\":true}"));
        assertTrue(response.isOk());
        assertEquals(true, response.getFields().get("added"));
        assertEquals("cafe", response.getFields().get("ssid"));
        assertEquals(1, backend.added.size());
        WifiNetworkSuggestion suggestion = backend.added.get(0);
        assertEquals("cafe", suggestion.getSsid());
        assertEquals("hunter22", suggestion.getPassphrase());
        assertEquals(10, suggestion.getPriority());
        assertTrue(suggestion.isHiddenSsid());
    }

    @Test
    @Config(sdk = 30)
    public void suggestAddWithoutChangeWifiStateIsPermissionError() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "wifi.suggest.add", "{\"ssid\":\"cafe\"}"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("wifi-permission-required"));
        assertTrue(response.getError().contains("CHANGE_WIFI_STATE"));
        assertTrue(backend.added.isEmpty());
    }

    @Test
    @Config(sdk = 30)
    public void suggestAddPlatformStatusIsTypedFailure() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        backend.addResult = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE;
        assertEquals("wifi-suggest-failed:duplicate suggestion", module().handle(
                request("1", "wifi.suggest.add", "{\"ssid\":\"cafe\"}")).getError());
        backend.addResult = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP;
        assertEquals("wifi-suggest-failed:suggestion limit reached", module().handle(
                request("2", "wifi.suggest.add", "{\"ssid\":\"cafe\"}")).getError());
        backend.addResult = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED;
        assertEquals("wifi-suggest-failed:suggestions disallowed for this app",
                module().handle(request("3", "wifi.suggest.add", "{\"ssid\":\"cafe\"}"))
                        .getError());
    }

    @Test
    @Config(sdk = 30)
    public void suggestAddRejectsBadParams() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        assertThrowsInvalid(module, "1", "wifi.suggest.add", "{}");
        assertThrowsInvalid(module, "2", "wifi.suggest.add", "{\"ssid\":\"\"}");
        assertThrowsInvalid(module, "3", "wifi.suggest.add",
                "{\"ssid\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}");
        assertThrowsInvalid(module, "4", "wifi.suggest.add",
                "{\"ssid\":\"cafe\",\"priority\":1001}");
        assertThrowsInvalid(module, "5", "wifi.suggest.add",
                "{\"ssid\":\"cafe\",\"priority\":-1}");
        assertThrowsInvalid(module, "6", "wifi.suggest.add",
                "{\"ssid\":\"cafe\",\"bogus\":1}");
    }

    @Test
    @Config(sdk = 30)
    public void suggestRemoveDeletesBySsid() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        assertTrue(module.handle(
                request("1", "wifi.suggest.add", "{\"ssid\":\"cafe\"}")).isOk());

        AndroidCapabilityProtocol.Response removed = module.handle(
                request("2", "wifi.suggest.remove", "{\"ssid\":\"cafe\"}"));
        assertTrue(removed.isOk());
        assertEquals(true, removed.getFields().get("removed"));
        assertEquals("cafe", removed.getFields().get("ssid"));
        assertTrue(backend.suggestions().isEmpty());
    }

    @Test
    @Config(sdk = 30)
    public void suggestRemoveSubmitsTheStoredSuggestionObject() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        // Device regression: the platform matches the whole stored
        // suggestion object, so a suggestion carrying a passphrase and
        // priority cannot be removed by a freshly built same-SSID object —
        // removal must submit the exact entry that suggestions() reported.
        assertTrue(module.handle(request("1", "wifi.suggest.add",
                "{\"ssid\":\"NusaDeskQA-net\",\"passphrase\":\"qaqaqaqa1\","
                        + "\"priority\":100}")).isOk());

        AndroidCapabilityProtocol.Response removed = module.handle(
                request("2", "wifi.suggest.remove", "{\"ssid\":\"NusaDeskQA-net\"}"));
        assertTrue(removed.isOk());
        assertEquals(true, removed.getFields().get("removed"));
        assertEquals("NusaDeskQA-net", removed.getFields().get("ssid"));
        assertTrue(backend.suggestions().isEmpty());
    }

    @Test
    @Config(sdk = 30)
    public void suggestRemoveMatchesAQuotedStoredSsid() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        backend.added.add(new WifiNetworkSuggestion.Builder()
                .setSsid("\"cafe\"").build());

        AndroidCapabilityProtocol.Response removed = module.handle(
                request("1", "wifi.suggest.remove", "{\"ssid\":\"cafe\"}"));
        assertTrue(removed.isOk());
        assertTrue(backend.suggestions().isEmpty());
    }

    @Test
    @Config(sdk = 30)
    public void suggestRemoveUnknownSsidIsNothingToRemove() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        assertTrue(module.handle(
                request("1", "wifi.suggest.add", "{\"ssid\":\"cafe\"}")).isOk());

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "wifi.suggest.remove", "{\"ssid\":\"other\"}"));
        assertFalse(response.isOk());
        assertEquals("wifi-suggest-failed:nothing to remove", response.getError());
        assertEquals(1, backend.suggestions().size());
    }

    @Test
    @Config(sdk = 30)
    public void suggestRemoveInvalidIsTypedFailure() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        assertTrue(module.handle(
                request("1", "wifi.suggest.add", "{\"ssid\":\"cafe\"}")).isOk());
        backend.removeResult = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "wifi.suggest.remove", "{\"ssid\":\"cafe\"}"));
        assertFalse(response.isOk());
        assertEquals("wifi-suggest-failed:nothing to remove", response.getError());
    }

    @Test
    @Config(sdk = 30)
    public void suggestListReportsRowsAndBoundsThem() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        WifiExtrasModule module = module();
        AndroidCapabilityProtocol.Response empty =
                module.handle(request("1", "wifi.suggest.list"));
        assertTrue(empty.isOk());
        assertEquals("[]", empty.getFields().get("suggestions_json"));
        assertEquals(0L, empty.getFields().get("count"));

        backend.added.add(new WifiNetworkSuggestion.Builder()
                .setSsid("cafe").setPriority(10).setIsAppInteractionRequired(true).build());
        AndroidCapabilityProtocol.Response listed =
                module.handle(request("2", "wifi.suggest.list"));
        assertTrue(listed.isOk());
        assertEquals(1L, listed.getFields().get("count"));
        assertEquals("[{\"ssid\":\"cafe\",\"priority\":10,\"is_app_interactive\":true}]",
                listed.getFields().get("suggestions_json"));

        for (int i = 0; i < 21; i++) {
            backend.added.add(new WifiNetworkSuggestion.Builder()
                    .setSsid("net" + i).build());
        }
        AndroidCapabilityProtocol.Response capped =
                module.handle(request("3", "wifi.suggest.list"));
        assertTrue(capped.isOk());
        assertEquals(20L, capped.getFields().get("count"));
        assertEquals(true, capped.getFields().get("truncated"));
    }

    @Test
    public void suggestListBelowApi30IsUnsupported() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.suggest.list"));
        assertFalse(response.isOk());
        assertEquals("wifi-suggest-unsupported:requires Android 11", response.getError());
    }

    @Test
    public void lockAcquireHoldsAndRejectsSecondAcquire() {
        grant(Manifest.permission.WAKE_LOCK);
        WifiExtrasModule module = module();
        AndroidCapabilityProtocol.Response acquired =
                module.handle(request("1", "wifi.lock.acquire", "{\"tag\":\"xfer\"}"));
        assertTrue(acquired.isOk());
        assertEquals(true, acquired.getFields().get("held"));
        assertEquals("xfer", acquired.getFields().get("tag"));
        assertEquals(1, backend.locks.size());
        assertTrue(backend.locks.get(0).held);

        AndroidCapabilityProtocol.Response second =
                module.handle(request("2", "wifi.lock.acquire"));
        assertFalse(second.isOk());
        assertEquals("wifi-lock-already-held", second.getError());
    }

    @Test
    public void lockAcquireWithoutWakeLockIsPermissionError() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "wifi.lock.acquire"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("wifi-permission-required"));
        assertTrue(response.getError().contains("WAKE_LOCK"));
        assertTrue(backend.locks.isEmpty());
    }

    @Test
    public void lockReleaseIsIdempotent() {
        grant(Manifest.permission.WAKE_LOCK);
        WifiExtrasModule module = module();
        AndroidCapabilityProtocol.Response idle =
                module.handle(request("1", "wifi.lock.release"));
        assertTrue(idle.isOk());
        assertEquals(false, idle.getFields().get("held"));
        assertEquals(false, idle.getFields().get("was_held"));

        assertTrue(module.handle(request("2", "wifi.lock.acquire")).isOk());
        AndroidCapabilityProtocol.Response released =
                module.handle(request("3", "wifi.lock.release"));
        assertTrue(released.isOk());
        assertEquals(false, released.getFields().get("held"));
        assertEquals(true, released.getFields().get("was_held"));
        assertFalse(backend.locks.get(0).held);

        AndroidCapabilityProtocol.Response again =
                module.handle(request("4", "wifi.lock.release"));
        assertEquals(false, again.getFields().get("was_held"));
    }

    @Test
    public void closeReleasesHotspotAndLock() {
        grantHotspotPermissions();
        grant(Manifest.permission.WAKE_LOCK);
        WifiExtrasModule module = module();
        assertTrue(module.handle(request("1", "wifi.hotspot.start")).isOk());
        assertTrue(module.handle(request("2", "wifi.lock.acquire")).isOk());
        FakeReservation reservation = backend.lastReservation;
        FakeLock lock = backend.locks.get(0);

        module.close();
        assertTrue(reservation.closed);
        assertFalse(lock.held);

        AndroidCapabilityProtocol.Response after =
                module.handle(request("3", "wifi.hotspot.start"));
        assertFalse(after.isOk());
        assertEquals("wifi-unavailable", after.getError());
    }

    // --- helpers ---------------------------------------------------------

    private WifiExtrasModule module() {
        return new WifiExtrasModule(context, backend, HOTSPOT_TIMEOUT_MS);
    }

    private void grantHotspotPermissions() {
        grant(Manifest.permission.CHANGE_WIFI_STATE);
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
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

    private void grant(String permission) {
        Shadow.<ShadowContextWrapper>extract(context).grantPermissions(permission);
    }

    private void deny(String permission) {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(permission);
        String op = AppOpsManager.permissionToOp(permission);
        if (op != null) {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                    op, Process.myUid(), context.getPackageName(),
                    AppOpsManager.MODE_IGNORED);
        }
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

    /** A recorded local-only-hotspot reservation. */
    private static final class FakeReservation implements WifiExtrasModule.HotspotReservation {
        boolean closed;

        @Override
        public String ssid() {
            return "AndroidShare_ab12";
        }

        @Override
        public String passphrase() {
            return "p@ssw0rd!";
        }

        @Override
        public String securityType() {
            return "wpa2_psk";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A recorded wifi lock. */
    private static final class FakeLock implements WifiExtrasModule.WifiLockHandle {
        boolean held;

        @Override
        public void acquire() {
            held = true;
        }

        @Override
        public void release() {
            held = false;
        }

        @Override
        public boolean isHeld() {
            return held;
        }
    }

    /**
     * The {@link WifiExtrasModule.WifiBackend} fake: hotspot verdicts are
     * delivered synchronously unless {@code deferStart} parks them for the
     * test to drive, and the suggestion list is a real in-memory store.
     * Removal mimics the platform's whole-object matching: only the exact
     * instances that {@link #suggestions()} handed out can be removed, so
     * a freshly built same-SSID object answers
     * {@code STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID} just like the
     * real {@code WifiManager} does.
     */
    private static final class FakeBackend implements WifiExtrasModule.WifiBackend {
        WifiExtrasModule.HotspotListener listener;
        FakeReservation lastReservation;
        boolean deferStart;
        Integer failReason;
        boolean runtimeOnStart;
        boolean securityOnStart;
        int startCalls;
        int addResult = WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        int removeResult = WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        final List<WifiNetworkSuggestion> added = new ArrayList<>();
        final List<FakeLock> locks = new ArrayList<>();

        @Override
        public void startHotspot(WifiExtrasModule.HotspotListener listener) {
            this.listener = listener;
            startCalls++;
            if (securityOnStart) {
                throw new SecurityException("permission refused");
            }
            if (runtimeOnStart) {
                throw new IllegalStateException("wifi service dead");
            }
            if (deferStart) {
                return;
            }
            if (failReason != null) {
                listener.onFailed(failReason);
                return;
            }
            lastReservation = new FakeReservation();
            listener.onStarted(lastReservation);
        }

        @Override
        public int addSuggestions(List<WifiNetworkSuggestion> suggestions) {
            if (addResult == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                added.addAll(suggestions);
            }
            return addResult;
        }

        @Override
        public int removeSuggestions(List<WifiNetworkSuggestion> suggestions) {
            if (removeResult != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                return removeResult;
            }
            boolean removed = false;
            for (WifiNetworkSuggestion suggestion : suggestions) {
                for (Iterator<WifiNetworkSuggestion> it = added.iterator(); it.hasNext();) {
                    if (it.next() == suggestion) {
                        it.remove();
                        removed = true;
                        break;
                    }
                }
            }
            return removed ? WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
                    : WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }

        @Override
        public List<WifiNetworkSuggestion> suggestions() {
            return new ArrayList<>(added);
        }

        @Override
        public WifiExtrasModule.WifiLockHandle createWifiLock(String tag) {
            FakeLock lock = new FakeLock();
            locks.add(lock);
            return lock;
        }
    }
}
