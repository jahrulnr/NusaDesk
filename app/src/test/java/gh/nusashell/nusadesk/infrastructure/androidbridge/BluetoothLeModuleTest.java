package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.AdvertiseData;
import android.content.Context;
import android.location.LocationManager;
import android.os.Looper;
import android.os.Process;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothLeScanner;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLocationManager;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

/**
 * {@link BluetoothLeModule} under Robolectric: the adapter, LE scanner, and
 * advertiser are the shadow-backed platform objects, so the module's real
 * dispatch path — filters, settings, dedup table, typed errors — is exercised
 * without a device. Sights are driven through the recorded
 * {@link ScanCallback} exactly as the stack would deliver them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class BluetoothLeModuleTest {

    /** Advertisement: flags + complete 16-bit service uuid 0x180D + name "NusaBLE-1". */
    private static final byte[] ADV_NUSA_180D = new byte[] {
            0x02, 0x01, 0x06,
            0x03, 0x03, 0x0D, 0x18,
            0x0A, 0x09, 'N', 'u', 's', 'a', 'B', 'L', 'E', '-', '1'};
    /** Advertisement: flags + complete local name "Other". */
    private static final byte[] ADV_OTHER = new byte[] {
            0x02, 0x01, 0x06,
            0x06, 0x09, 'O', 't', 'h', 'e', 'r'};
    private static final String UUID_180D =
            "0000180d-0000-1000-8000-00805f9b34fb";

    private Context app;
    private BluetoothAdapter adapter;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        adapter = BluetoothAdapter.getDefaultAdapter();
        assertNotNull("Robolectric must supply an adapter", adapter);
        Shadow.<ShadowBluetoothAdapter>extract(adapter).setEnabled(true);
    }

    @After
    public void tearDown() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(true);
    }

    @Test
    public void declaresItsFiveMethodsAndTwoParameterMethods() {
        BluetoothLeModule module = module();
        assertEquals(List.of("bt.le.scan.start", "bt.le.scan.poll", "bt.le.scan.stop",
                "bt.le.advertise.start", "bt.le.advertise.stop"), module.methods());
        assertEquals(Set.of("bt.le.scan.start", "bt.le.advertise.start"),
                module.parameterMethods());
        assertEquals("unsupported-method",
                module.handle(request("x", "bt.le.unknown")).getError());
    }

    @Test
    public void scanStartWithoutGrantIsPermissionRequired() {
        denyAll();
        assertError(module(), "bt.le.scan.start", "bt-permission-required");
    }

    @Test
    public void scanStartDeniedGrantIsPermissionDenied() {
        denyAll();
        ignoreOp(Manifest.permission.BLUETOOTH_SCAN);
        assertError(module(), "bt.le.scan.start", "bt-permission-denied");
    }

    @Test
    public void scanStartDisabledAdapterIsUnavailable() {
        grantAll();
        Shadow.<ShadowBluetoothAdapter>extract(adapter).setEnabled(false);
        assertError(module(), "bt.le.scan.start", "bt-unavailable:bluetooth is off");
    }

    @Test
    public void scanStartWithoutAdapterIsUnavailable() {
        grantAll();
        BluetoothLeModule module = new BluetoothLeModule(app, () -> null);
        assertError(module, "bt.le.scan.start", "bt-unavailable:no bluetooth adapter");
    }

    @Test
    public void scanStartRegistersLowLatencyScanAndPollReportsRows() {
        grantAll();
        BluetoothLeModule module = module();
        AndroidCapabilityProtocol.Response started = module.handle(
                request("1", "bt.le.scan.start"));
        assertTrue(started.isOk());
        assertEquals(true, started.getFields().get("started"));

        ShadowBluetoothLeScanner shadow = scanShadow();
        assertEquals(1, shadow.getActiveScans().size());
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY,
                shadow.getActiveScans().get(0).scanSettings().getScanMode());

        ScanCallback callback = shadow.getScanCallbacks().iterator().next();
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                sighting("AA:BB:CC:00:00:01", ADV_NUSA_180D, -60));
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                sighting("AA:BB:CC:00:00:01", ADV_NUSA_180D, -55));

        AndroidCapabilityProtocol.Response poll = module.handle(
                request("2", "bt.le.scan.poll"));
        assertTrue(poll.isOk());
        assertEquals(true, poll.getFields().get("running"));
        assertEquals(1L, poll.getFields().get("count"));
        assertEquals(false, poll.getFields().get("truncated"));
        String json = String.valueOf(poll.getFields().get("devices_json"));
        assertTrue(json, json.contains("\"name\":\"NusaBLE-1\""));
        assertTrue(json, json.contains("\"address\":\"AA:BB:CC:00:00:01\""));
        assertTrue(json, json.contains("\"rssi\":-55"));
        assertTrue(json, json.contains("\"0000180d-0000-1000-8000-00805f9b34fb\""));
        assertTrue(json, json.contains("\"last_seen_ms\":"));
    }

    @Test
    public void scanStartServiceUuidFilterReachesThePlatformScan() {
        grantAll();
        BluetoothLeModule module = module();
        AndroidCapabilityProtocol.Response started = module.handle(
                request("1", "bt.le.scan.start", "{\"service_uuid\":\"" + UUID_180D + "\"}"));
        assertTrue(started.isOk());
        assertEquals(UUID_180D, started.getFields().get("service_uuid"));

        // The shadow replays pre-registered results through the real
        // ScanFilter: the 0x180D record matches, the name-only one does not.
        ShadowBluetoothLeScanner shadow = scanShadow();
        shadow.addScanResult(sighting("AA:BB:CC:00:00:02", ADV_NUSA_180D, -70));
        shadow.addScanResult(sighting("AA:BB:CC:00:00:03", ADV_OTHER, -70));

        module.handle(request("2", "bt.le.scan.stop"));
        module.handle(request("3", "bt.le.scan.start",
                "{\"service_uuid\":\"" + UUID_180D + "\"}"));
        AndroidCapabilityProtocol.Response poll = module.handle(
                request("4", "bt.le.scan.poll"));
        String json = String.valueOf(poll.getFields().get("devices_json"));
        assertTrue(json, json.contains("AA:BB:CC:00:00:02"));
        assertFalse(json, json.contains("AA:BB:CC:00:00:03"));
    }

    @Test
    public void scanStartTwiceIsAlreadyRunning() {
        grantAll();
        BluetoothLeModule module = module();
        assertTrue(module.handle(request("1", "bt.le.scan.start")).isOk());
        assertError(module, "bt.le.scan.start", "bt-scan-already-running");
    }

    @Test
    public void scanNamePrefixFiltersAtIngest() {
        grantAll();
        BluetoothLeModule module = module();
        assertTrue(module.handle(request("1", "bt.le.scan.start",
                "{\"name_prefix\":\"Nusa\"}")).isOk());

        ScanCallback callback = scanShadow().getScanCallbacks().iterator().next();
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                sighting("AA:BB:CC:00:00:04", ADV_OTHER, -60));
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                sighting("AA:BB:CC:00:00:05", ADV_NUSA_180D, -60));
        callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                new ScanResult(adapter.getRemoteDevice("AA:BB:CC:00:00:06"),
                        null, -60, 0L));

        String json = String.valueOf(module.handle(request("2", "bt.le.scan.poll"))
                .getFields().get("devices_json"));
        assertFalse(json, json.contains("AA:BB:CC:00:00:04"));
        assertTrue(json, json.contains("AA:BB:CC:00:00:05"));
        assertFalse(json, json.contains("AA:BB:CC:00:00:06"));
    }

    @Test
    public void scanPollCapsRowsAndReportsTruncated() {
        grantAll();
        BluetoothLeModule module = module();
        assertTrue(module.handle(request("1", "bt.le.scan.start")).isOk());
        ScanCallback callback = scanShadow().getScanCallbacks().iterator().next();
        for (int i = 0; i < 55; i++) {
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                    sighting(String.format("AA:BB:CC:DD:00:%02X", i), ADV_OTHER, -40 - i));
        }
        AndroidCapabilityProtocol.Response poll = module.handle(
                request("2", "bt.le.scan.poll"));
        assertEquals(50L, poll.getFields().get("count"));
        assertEquals(true, poll.getFields().get("truncated"));
    }

    @Test
    public void scanFailureMarksPollNotRunningWithErrorCode() {
        grantAll();
        BluetoothLeModule module = module();
        assertTrue(module.handle(request("1", "bt.le.scan.start")).isOk());
        scanShadow().getScanCallbacks().iterator().next()
                .onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
        AndroidCapabilityProtocol.Response poll = module.handle(
                request("2", "bt.le.scan.poll"));
        assertEquals(false, poll.getFields().get("running"));
        assertEquals((long) ScanCallback.SCAN_FAILED_INTERNAL_ERROR,
                poll.getFields().get("scan_error"));
    }

    @Test
    public void scanStopIsIdempotentAndKeepsTheFinalTable() {
        grantAll();
        BluetoothLeModule module = module();
        assertTrue(module.handle(request("1", "bt.le.scan.start")).isOk());
        scanShadow().getScanCallbacks().iterator().next().onScanResult(
                ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                sighting("AA:BB:CC:00:00:07", ADV_NUSA_180D, -61));

        AndroidCapabilityProtocol.Response stop = module.handle(
                request("2", "bt.le.scan.stop"));
        assertTrue(stop.isOk());
        assertEquals(true, stop.getFields().get("stopped"));
        assertEquals(true, stop.getFields().get("was_running"));
        assertTrue(String.valueOf(stop.getFields().get("devices_json"))
                .contains("AA:BB:CC:00:00:07"));
        assertTrue(scanShadow().getScanCallbacks().isEmpty());

        AndroidCapabilityProtocol.Response again = module.handle(
                request("3", "bt.le.scan.stop"));
        assertTrue(again.isOk());
        assertEquals(true, again.getFields().get("stopped"));
        assertEquals(false, again.getFields().get("was_running"));
        assertTrue(String.valueOf(again.getFields().get("devices_json"))
                .contains("AA:BB:CC:00:00:07"));

        AndroidCapabilityProtocol.Response poll = module.handle(
                request("4", "bt.le.scan.poll"));
        assertEquals(false, poll.getFields().get("running"));
    }

    @Test
    public void scanPollNeverStartedReportsEmpty() {
        AndroidCapabilityProtocol.Response poll = module().handle(
                request("1", "bt.le.scan.poll"));
        assertTrue(poll.isOk());
        assertEquals("[]", poll.getFields().get("devices_json"));
        assertEquals(0L, poll.getFields().get("count"));
        assertEquals(false, poll.getFields().get("running"));
    }

    @Test
    public void advertiseStartWithoutGrantIsPermissionRequired() {
        denyAll();
        assertError(module(), "bt.le.advertise.start", "bt-permission-required");
    }

    @Test
    public void advertiseStartReportsUnsupportedDevices() {
        grantAll();
        Shadow.<ShadowBluetoothAdapter>extract(adapter)
                .setIsMultipleAdvertisementSupported(false);
        assertError(module(), "bt.le.advertise.start",
                "bt-advertise-unsupported:this device does not support BLE advertising");
    }

    @Test
    public void advertiseStartDisabledAdapterIsUnavailable() {
        grantAll();
        Shadow.<ShadowBluetoothAdapter>extract(adapter).setEnabled(false);
        assertError(module(), "bt.le.advertise.start",
                "bt-unavailable:bluetooth is off");
    }

    @Test
    public void advertiseStartStartsAndStopIsIdempotent() {
        grantAll();
        BluetoothLeModule module = module();
        AndroidCapabilityProtocol.Response started = handleOnWorker(
                module, "bt.le.advertise.start",
                "{\"service_uuid\":\"" + UUID_180D + "\",\"name\":\"NusaDesk\"}");
        assertTrue(started.isOk());
        assertEquals(true, started.getFields().get("started"));
        assertEquals("NusaDesk", adapter.getName());
        AdvertiseData data = advertiseShadow().getLastAdvertisingData();
        assertNotNull(data);
        assertTrue(data.getServiceUuids().stream()
                .anyMatch(u -> UUID_180D.equals(u.toString())));
        // A 128-bit uuid nearly fills the 31-byte payload, so the name is
        // dropped from the data even though the adapter was renamed.
        assertFalse(data.getIncludeDeviceName());

        assertError(module, "bt.le.advertise.start", "bt-advertise-already-running");

        AndroidCapabilityProtocol.Response stop = module.handle(
                request("2", "bt.le.advertise.stop"));
        assertTrue(stop.isOk());
        assertEquals(true, stop.getFields().get("stopped"));
        assertEquals(true, stop.getFields().get("was_running"));
        assertNotEquals("NusaDesk", adapter.getName());

        AndroidCapabilityProtocol.Response again = module.handle(
                request("3", "bt.le.advertise.stop"));
        assertEquals(true, again.getFields().get("stopped"));
        assertEquals(false, again.getFields().get("was_running"));
    }

    @Test
    public void advertiseStartWithUuidDropsNameFromPayload() {
        grantAll();
        BluetoothLeModule module = module();
        AndroidCapabilityProtocol.Response started = handleOnWorker(
                module, "bt.le.advertise.start",
                "{\"service_uuid\":\"" + UUID_180D + "\",\"name\":\"NusaDesk\"}");
        assertTrue(started.isOk());
        AdvertiseData data = advertiseShadow().getLastAdvertisingData();
        assertNotNull(data);
        assertFalse("a 128-bit uuid leaves no room for the name in 31 bytes",
                data.getIncludeDeviceName());
        assertTrue(data.getServiceUuids().stream()
                .anyMatch(u -> UUID_180D.equals(u.toString())));
        // The adapter rename still applies; only the payload drops the name.
        assertEquals("NusaDesk", adapter.getName());
        module.handle(request("2", "bt.le.advertise.stop"));
    }

    @Test
    public void advertiseStartWithoutUuidKeepsNameInPayload() {
        grantAll();
        BluetoothLeModule module = module();
        AndroidCapabilityProtocol.Response started = handleOnWorker(
                module, "bt.le.advertise.start", "{\"name\":\"NusaDesk\"}");
        assertTrue(started.isOk());
        AdvertiseData data = advertiseShadow().getLastAdvertisingData();
        assertNotNull(data);
        assertTrue(data.getIncludeDeviceName());
        assertEquals("NusaDesk", adapter.getName());
        module.handle(request("2", "bt.le.advertise.stop"));
    }

    @Test
    public void advertiseStartMapsPlatformFailureCodes() throws Exception {
        grantAll();
        // The shadow's bluetooth proxy always answers success, so the test
        // drives the registered AdvertiseCallback directly — the same object
        // the module's bounded wait is parked on.
        Set<AdvertiseCallback> seen = new HashSet<>();
        assertAdvertiseStartError(seen, AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE,
                "bt-advertise-data-too-large:drop --name or the service uuid");
        assertAdvertiseStartError(seen, AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
                "bt-advertise-too-many-advertisers");
        assertAdvertiseStartError(seen, AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED,
                "bt-advertise-already-running");
        assertAdvertiseStartError(seen, AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR,
                "bt-advertise-internal-error");
        assertAdvertiseStartError(seen, AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED,
                "bt-advertise-unsupported:this device does not support BLE advertising");
    }

    @Test
    public void advertiseStartWithoutNameNeedsNoConnectGrant() {
        Shadow.<ShadowContextWrapper>extract(app)
                .grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE);
        AndroidCapabilityProtocol.Response started = handleOnWorker(
                module(), "bt.le.advertise.start", null);
        assertTrue(started.isOk());
    }

    @Test
    public void advertiseStartWithNameRequiresConnectGrant() {
        denyAll();
        Shadow.<ShadowContextWrapper>extract(app)
                .grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE);
        assertError(module(), "bt.le.advertise.start",
                "{\"name\":\"NusaDesk\"}", "bt-permission-required");
    }

    @Test
    public void invalidParamsFailClosed() {
        grantAll();
        BluetoothLeModule module = module();
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("1", "bt.le.scan.start", "{\"service_uuid\":\"not-a-uuid\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.le.scan.start", "{\"bogus\":1}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.le.scan.start",
                        "{\"name_prefix\":\"" + "x".repeat(65) + "\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("4", "bt.le.advertise.start",
                        "{\"name\":\"" + "n".repeat(33) + "\"}")));
    }

    @Test
    public void closeStopsLiveScanAndAdvertisement() {
        grantAll();
        BluetoothLeModule module = module();
        assertTrue(module.handle(request("1", "bt.le.scan.start")).isOk());
        assertTrue(handleOnWorker(module, "bt.le.advertise.start", null).isOk());
        module.close();
        assertTrue(scanShadow().getScanCallbacks().isEmpty());
        assertEquals(0, advertiseShadow().getAdvertisementRequestCount());
        AndroidCapabilityProtocol.Response poll = module.handle(
                request("3", "bt.le.scan.poll"));
        assertEquals(false, poll.getFields().get("running"));
    }

    @Test
    @Config(sdk = 29)
    public void scanStartBelow31GatesOnFineLocation() {
        // API 29: BLUETOOTH_SCAN does not exist; the runtime gate is the
        // location grant the legacy pair does not cover.
        Shadow.<ShadowContextWrapper>extract(app)
                .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Shadow.<ShadowLocationManager>extract(
                (LocationManager) app.getSystemService(Context.LOCATION_SERVICE))
                .setLocationEnabled(true);
        assertTrue(module().handle(request("1", "bt.le.scan.start")).isOk());
    }

    @Test
    @Config(sdk = 29)
    public void scanStartBelow31WithoutLocationGrantIsRequired() {
        assertError(module(), "bt.le.scan.start", "bt-permission-required");
    }

    @Test
    @Config(sdk = 29)
    public void scanStartBelow31WithLocationOffIsUnavailable() {
        Shadow.<ShadowContextWrapper>extract(app)
                .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Shadow.<ShadowLocationManager>extract(
                (LocationManager) app.getSystemService(Context.LOCATION_SERVICE))
                .setLocationEnabled(false);
        assertError(module(), "bt.le.scan.start",
                "bt-unavailable:enable device location");
    }

    @Test
    @Config(sdk = 29)
    public void advertiseStartBelow31NeedsNoRuntimeGrant() {
        denyAll();
        assertTrue(handleOnWorker(module(), "bt.le.advertise.start", null).isOk());
    }

    private BluetoothLeModule module() {
        return new BluetoothLeModule(app, () -> adapter);
    }

    private ShadowBluetoothLeScanner scanShadow() {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        assertNotNull("shadow adapter must expose a scanner while enabled", scanner);
        return Shadow.extract(scanner);
    }

    private org.robolectric.shadows.ShadowBluetoothLeAdvertiser advertiseShadow() {
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        assertNotNull("shadow adapter must expose an advertiser while enabled", advertiser);
        return Shadow.extract(advertiser);
    }

    private void grantAll() {
        Shadow.<ShadowContextWrapper>extract(app).grantPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT);
    }

    private void denyAll() {
        Shadow.<ShadowContextWrapper>extract(app).denyPermissions(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT);
    }

    private static void ignoreOp(String permission) {
        String op = AppOpsManager.permissionToOp(permission);
        assertNotNull("platform must map " + permission + " to an app-op", op);
        AppOpsManager appOps = (AppOpsManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.APP_OPS_SERVICE);
        Shadow.<ShadowAppOpsManager>extract(appOps).setMode(op, Process.myUid(),
                RuntimeEnvironment.getApplication().getPackageName(),
                AppOpsManager.MODE_IGNORED);
    }

    /**
     * Drive one failing {@code bt.le.advertise.start}: the module's
     * {@link AdvertiseCallback} is pulled out of the platform advertiser's
     * legacy-callback table and failed directly while the success answer the
     * shadow proxy queued stays unpumped on the main looper.
     */
    private void assertAdvertiseStartError(Set<AdvertiseCallback> seen, int code,
                                           String expectedError) throws Exception {
        BluetoothLeModule module = module();
        AtomicReference<AndroidCapabilityProtocol.Response> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(
                module.handle(request("f", "bt.le.advertise.start", null))),
                "bt-advertise-test");
        worker.setDaemon(true);
        worker.start();
        try {
            AdvertiseCallback callback = awaitAdvertiseCallback(seen);
            callback.onStartFailure(code);
            worker.join(5_000L);
            assertNotNull("advertise.start must settle", result.get());
            assertFalse(result.get().isOk());
            assertEquals(expectedError, result.get().getError());
        } finally {
            module.close();
        }
    }

    /**
     * The callback the module registered with the platform advertiser, found
     * through the advertiser's {@code mLegacyAdvertisers} table (the platform
     * object is final and the shadow exposes no callback accessor). Entries
     * already reported in {@code seen} are skipped — a failed start keeps its
     * stale registration.
     */
    private AdvertiseCallback awaitAdvertiseCallback(Set<AdvertiseCallback> seen)
            throws Exception {
        Field field = BluetoothLeAdvertiser.class.getDeclaredField("mLegacyAdvertisers");
        field.setAccessible(true);
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        assertNotNull("shadow adapter must expose an advertiser", advertiser);
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            for (Object key : ((Map<?, ?>) field.get(advertiser)).keySet()) {
                if (!seen.contains(key)) {
                    seen.add((AdvertiseCallback) key);
                    return (AdvertiseCallback) key;
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("no advertise callback registered with the platform");
    }

    /**
     * Runs one module call on a worker thread while the test pumps the main
     * looper: the platform dispatches the advertise-start callback through a
     * main-thread handler, exactly as it does on device.
     */
    private static AndroidCapabilityProtocol.Response handleOnWorker(
            BluetoothLeModule module, String method, String params) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AndroidCapabilityProtocol.Response> future = executor.submit(
                    () -> module.handle(request("w", method, params)));
            long deadline = System.currentTimeMillis() + 10_000L;
            while (!future.isDone() && System.currentTimeMillis() < deadline) {
                Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
                Thread.yield();
            }
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("worker handle failed", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private ScanResult sighting(String address, byte[] advertisement, int rssi) {
        return new ScanResult(adapter.getRemoteDevice(address),
                advertisement == null ? null : scanRecord(advertisement), rssi, 0L);
    }

    /**
     * {@code ScanRecord.parseFromBytes} is hidden from the SDK stub but real
     * in the instrumented sandbox class, so the record is built reflectively.
     */
    private static ScanRecord scanRecord(byte[] advertisement) {
        try {
            java.lang.reflect.Method parse = ScanRecord.class
                    .getDeclaredMethod("parseFromBytes", byte[].class);
            parse.setAccessible(true);
            return (ScanRecord) parse.invoke(null, advertisement);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("instrumented ScanRecord must parse", e);
        }
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                           String params) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        AndroidCapabilityProtocol.Request request =
                AndroidCapabilityProtocol.decodeRequest(frame);
        assertNotNull("test request must decode", request);
        return request;
    }

    private static void assertError(BluetoothLeModule module, String method,
                                    String errorPrefix) {
        assertError(module, method, null, errorPrefix);
    }

    private static void assertError(BluetoothLeModule module, String method,
                                    String params, String errorPrefix) {
        AndroidCapabilityProtocol.Response response =
                module.handle(request("e", method, params));
        assertFalse(response.isOk());
        assertTrue("expected " + errorPrefix + " but got " + response.getError(),
                response.getError().startsWith(errorPrefix));
    }
}
