package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.os.Process;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothDevice;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BluetoothModule} on the JVM. The platform adapter is injected (the
 * class is final) and driven through Robolectric's bluetooth shadow; the
 * bond/discovery receivers are the real ones, fed by real broadcasts that
 * drain through the paused main looper while the bridge call blocks on a
 * worker thread — mirroring how the bridge's connection thread behaves.
 * The consent dialogs are seamed out behind {@code ForegroundRunner} because
 * no activity exists on the JVM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class BluetoothModuleTest {
    private static final long PAIR_WAIT_MS = 30_000L;
    private static final String ADDR = "00:11:22:33:AA:BB";
    private static final String ADDR2 = "00:11:22:33:AA:CC";

    private Context context;
    private BluetoothAdapter adapter;
    private ShadowBluetoothAdapter shadowAdapter;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        adapter = BluetoothAdapter.getDefaultAdapter();
        shadowAdapter = Shadow.extract(adapter);
        shadowAdapter.setState(BluetoothAdapter.STATE_ON);
        adapter.setName("nusadesk");
        shadowAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        shadowAdapter.setBondedDevices(new HashSet<>());
    }

    @Test
    public void declaresTheBtMethodsAndParamSet() {
        BluetoothModule module = module();
        assertEquals(9, module.methods().size());
        assertTrue(module.methods().contains("bt.status"));
        assertTrue(module.methods().contains("bt.devices"));
        assertTrue(module.methods().contains("bt.discover.start"));
        assertTrue(module.methods().contains("bt.discover.poll"));
        assertTrue(module.methods().contains("bt.discover.stop"));
        assertTrue(module.methods().contains("bt.pair"));
        assertTrue(module.methods().contains("bt.unpair"));
        assertTrue(module.methods().contains("bt.enable.request"));
        assertTrue(module.methods().contains("bt.discoverable.request"));
        assertEquals(Set.of("bt.pair", "bt.unpair", "bt.discoverable.request"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.nope"));
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void statusReportsAdapterFields() {
        shadowAdapter.setBondedDevices(Set.of(device(ADDR), device(ADDR2)));

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.status"));

        assertTrue(response.isOk());
        Map<String, Object> fields = response.getFields();
        assertEquals("on", fields.get("state"));
        assertEquals("nusadesk", fields.get("name"));
        assertEquals("connectable_discoverable", fields.get("scan_mode"));
        assertEquals(2L, fields.get("bonded_count"));
    }

    @Test
    public void statusMapsTurningStates() {
        shadowAdapter.setState(BluetoothAdapter.STATE_TURNING_ON);
        assertEquals("turning_on",
                module().handle(request("1", "bt.status")).getFields().get("state"));
        shadowAdapter.setState(BluetoothAdapter.STATE_TURNING_OFF);
        assertEquals("turning_off",
                module().handle(request("2", "bt.status")).getFields().get("state"));
        shadowAdapter.setState(BluetoothAdapter.STATE_OFF);
        assertEquals("off",
                module().handle(request("3", "bt.status")).getFields().get("state"));
    }

    @Test
    public void statusWithoutAdapterIsTypedUnsupported() {
        BluetoothModule module = new BluetoothModule(context, null, null, PAIR_WAIT_MS);
        AndroidCapabilityProtocol.Response response = module.handle(request("1", "bt.status"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-unsupported"));
    }

    @Test
    public void devicesEncodesBondedRows() {
        BluetoothDevice dual = device(ADDR);
        ShadowBluetoothDevice shadow = Shadow.extract(dual);
        shadow.setName("mouse");
        shadow.setType(BluetoothDevice.DEVICE_TYPE_DUAL);
        shadow.setBondState(BluetoothDevice.BOND_BONDED);
        shadow.setUuids(new ParcelUuid[]{
                ParcelUuid.fromString("00001124-0000-1000-8000-00805f9b34fb")});
        BluetoothDevice le = device(ADDR2);
        ShadowBluetoothDevice leShadow = Shadow.extract(le);
        leShadow.setType(BluetoothDevice.DEVICE_TYPE_LE);
        leShadow.setBondState(BluetoothDevice.BOND_NONE);
        shadowAdapter.setBondedDevices(Set.of(dual, le));

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.devices"));

        assertTrue(response.isOk());
        Map<String, Object> fields = response.getFields();
        assertEquals(2L, fields.get("count"));
        assertNull(fields.get("truncated"));
        String json = (String) fields.get("devices_json");
        assertTrue(json.contains("{\"name\":\"mouse\",\"address\":\"00:11:22:33:AA:BB\","
                + "\"type\":\"dual\",\"bond_state\":\"bonded\","
                + "\"uuids\":[\"00001124-0000-1000-8000-00805f9b34fb\"]}"));
        assertTrue(json.contains("\"address\":\"00:11:22:33:AA:CC\",\"type\":\"le\","
                + "\"bond_state\":\"none\",\"uuids\":[]"));
    }

    @Test
    public void devicesCapsAtFiftyWithTruncatedFlag() {
        Set<BluetoothDevice> bonded = new HashSet<>();
        for (int i = 0; i < 51; i++) {
            bonded.add(device(String.format("00:11:22:33:AA:%02X", i)));
        }
        shadowAdapter.setBondedDevices(bonded);

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.devices"));

        assertTrue(response.isOk());
        assertEquals(50L, response.getFields().get("count"));
        assertEquals(true, response.getFields().get("truncated"));
    }

    @Test
    public void discoverStartPollStopCollectsBroadcastDevices() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
        BluetoothModule module = module();

        AndroidCapabilityProtocol.Response start =
                module.handle(request("1", "bt.discover.start"));
        assertTrue(start.isOk());
        assertEquals(true, start.getFields().get("started"));

        Intent found = new Intent(BluetoothDevice.ACTION_FOUND)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device(ADDR));
        context.sendBroadcast(found);
        ShadowLooper.idleMainLooper();

        AndroidCapabilityProtocol.Response poll =
                module.handle(request("2", "bt.discover.poll"));
        assertTrue(poll.isOk());
        assertEquals(true, poll.getFields().get("running"));
        assertTrue(((String) poll.getFields().get("devices_json")).contains(ADDR));
        assertTrue((Long) poll.getFields().get("elapsed_ms") >= 0L);

        context.sendBroadcast(new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
        ShadowLooper.idleMainLooper();

        AndroidCapabilityProtocol.Response stopped =
                module.handle(request("3", "bt.discover.stop"));
        assertTrue(stopped.isOk());
        // The platform window already ended, so this call stopped nothing.
        assertEquals(false, stopped.getFields().get("stopped"));
        assertTrue(((String) stopped.getFields().get("devices_json")).contains(ADDR));

        AndroidCapabilityProtocol.Response afterStop =
                module.handle(request("4", "bt.discover.poll"));
        assertEquals(false, afterStop.getFields().get("running"));
        assertTrue(((String) afterStop.getFields().get("devices_json")).contains(ADDR));
    }

    @Test
    public void discoverStopWhileRunningCancelsAndIsIdempotent() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
        BluetoothModule module = module();
        assertTrue(module.handle(request("1", "bt.discover.start")).isOk());
        assertTrue(adapter.isDiscovering());

        AndroidCapabilityProtocol.Response stop =
                module.handle(request("2", "bt.discover.stop"));
        assertTrue(stop.isOk());
        assertEquals(true, stop.getFields().get("stopped"));
        assertFalse(adapter.isDiscovering());

        AndroidCapabilityProtocol.Response again =
                module.handle(request("3", "bt.discover.stop"));
        assertTrue(again.isOk());
        assertEquals(false, again.getFields().get("stopped"));
    }

    @Test
    public void discoverStartWhileRunningIsTypedError() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
        BluetoothModule module = module();
        assertTrue(module.handle(request("1", "bt.discover.start")).isOk());

        AndroidCapabilityProtocol.Response second =
                module.handle(request("2", "bt.discover.start"));
        assertFalse(second.isOk());
        assertEquals("bt-discover-already-running", second.getError());
    }

    @Test
    public void discoverStartWhenOffIsUnavailable() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION);
        shadowAdapter.setEnabled(false);

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.discover.start"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-unavailable"));
    }

    @Test
    public void discoverStartWithoutLocationGrantIsPermissionErrorOnLegacySdk() {
        // Robolectric defaults every grant to denied-without-recorded-refusal,
        // which the checker reports as REQUIRED.
        AndroidCapabilityProtocol.Response required =
                module().handle(request("1", "bt.discover.start"));
        assertFalse(required.isOk());
        assertTrue(required.getError().startsWith("bt-permission-required"));
        assertTrue(required.getError().contains("ACCESS_FINE_LOCATION"));

        deny(Manifest.permission.ACCESS_FINE_LOCATION);
        deny(Manifest.permission.ACCESS_COARSE_LOCATION);
        AndroidCapabilityProtocol.Response denied =
                module().handle(request("2", "bt.discover.start"));
        assertFalse(denied.isOk());
        assertTrue(denied.getError().startsWith("bt-permission-denied"));
    }

    @Test
    public void discoverPollWithoutSessionIsEmptyNotError() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.discover.poll"));
        assertTrue(response.isOk());
        assertEquals("[]", response.getFields().get("devices_json"));
        assertEquals(false, response.getFields().get("running"));
        assertEquals(0L, response.getFields().get("elapsed_ms"));
    }

    @Test
    public void pairAlreadyBondedReturnsBonded() {
        Shadow.<ShadowBluetoothDevice>extract(device(ADDR))
                .setBondState(BluetoothDevice.BOND_BONDED);

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.pair", "{\"address\":\"" + ADDR + "\"}"));

        assertTrue(response.isOk());
        assertEquals("bonded", response.getFields().get("state"));
        assertEquals(ADDR, response.getFields().get("address"));
    }

    @Test
    public void pairAlreadyBondingReportsInProgress() {
        Shadow.<ShadowBluetoothDevice>extract(device(ADDR))
                .setBondState(BluetoothDevice.BOND_BONDING);

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.pair", "{\"address\":\"" + ADDR + "\"}"));

        assertTrue(response.isOk());
        assertEquals("bonding", response.getFields().get("state"));
    }

    @Test
    public void pairMalformedAddressIsUnknownDevice() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.pair", "{\"address\":\"nope\"}"));
        assertFalse(response.isOk());
        assertEquals("bt-device-unknown:NOPE", response.getError());
    }

    @Test
    public void pairStackRefusalIsUnknownDevice() {
        BluetoothDevice device = device(ADDR);
        Shadow.<ShadowBluetoothDevice>extract(device).setBondState(BluetoothDevice.BOND_NONE);
        Shadow.<ShadowBluetoothDevice>extract(device).setCreatedBond(false);

        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.pair", "{\"address\":\"" + ADDR + "\"}"));
        assertFalse(response.isOk());
        assertEquals("bt-device-unknown:" + ADDR, response.getError());
    }

    @Test
    public void pairBroadcastBondedResolvesBonded() throws Exception {
        AtomicReference<AndroidCapabilityProtocol.Response> result = pairOnWorker(ADDR);
        awaitPairReceiver(ADDR);
        sendBondBroadcast(ADDR, BluetoothDevice.BOND_BONDED);
        assertTrue("pair must finish", awaitResult(result));
        assertTrue(result.get().isOk());
        assertEquals("bonded", result.get().getFields().get("state"));
        assertEquals(ADDR, result.get().getFields().get("address"));
    }

    @Test
    public void pairBroadcastNoneResolvesFailed() throws Exception {
        AtomicReference<AndroidCapabilityProtocol.Response> result = pairOnWorker(ADDR);
        awaitPairReceiver(ADDR);
        sendBondBroadcast(ADDR, BluetoothDevice.BOND_NONE);
        assertTrue(awaitResult(result));
        assertTrue(result.get().isOk());
        assertEquals("failed", result.get().getFields().get("state"));
    }

    @Test
    public void pairTimesOutWithTypedError() {
        BluetoothDevice device = device(ADDR);
        Shadow.<ShadowBluetoothDevice>extract(device).setBondState(BluetoothDevice.BOND_NONE);
        Shadow.<ShadowBluetoothDevice>extract(device).setCreatedBond(true);

        BluetoothModule module = new BluetoothModule(context, adapter, null, 200L);
        AndroidCapabilityProtocol.Response response =
                module.handle(request("1", "bt.pair", "{\"address\":\"" + ADDR + "\"}"));
        assertFalse(response.isOk());
        assertEquals("bt-pair-timeout", response.getError());
    }

    @Test
    public void pairTimeoutRereadsBondStateAndReportsBonded() throws Exception {
        // Some stacks land the bond but never deliver the terminal broadcast;
        // the bounded wait must trust the state read over the missing
        // broadcast instead of reporting bt-pair-timeout.
        BluetoothDevice device = device(ADDR);
        ShadowBluetoothDevice shadow = Shadow.extract(device);
        shadow.setBondState(BluetoothDevice.BOND_NONE);
        shadow.setCreatedBond(true);
        AtomicReference<AndroidCapabilityProtocol.Response> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(
                new BluetoothModule(context, adapter, null, 300L).handle(
                        request("1", "bt.pair", "{\"address\":\"" + ADDR + "\"}"))),
                "bt-pair-test");
        worker.setDaemon(true);
        worker.start();
        awaitPairReceiver(ADDR);
        // The bond completes underneath the wait with no broadcast at all.
        shadow.setBondState(BluetoothDevice.BOND_BONDED);

        assertTrue("pair must finish", awaitResult(result));
        assertTrue(result.get().isOk());
        assertEquals("bonded", result.get().getFields().get("state"));
        assertEquals(ADDR, result.get().getFields().get("address"));
    }

    @Test
    public void unpairIsTypedAbsent() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.unpair", "{\"address\":\"" + ADDR + "\"}"));
        assertFalse(response.isOk());
        assertEquals("bt-unpair-unsupported:platform hides removeBond; "
                + "use the system Bluetooth settings", response.getError());
    }

    @Test
    public void enableRequestRunsTheConsentOperation() {
        Map<String, Object> captured = new LinkedHashMap<>();
        String[] kinds = new String[1];
        BluetoothModule module = new BluetoothModule(context, adapter,
                (kind, params, timeoutMillis) -> {
                    kinds[0] = kind;
                    captured.putAll(params);
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("state", "on");
                    return AndroidCapabilityProtocol.Response.success("", fields);
                }, PAIR_WAIT_MS);

        AndroidCapabilityProtocol.Response response =
                module.handle(request("1", "bt.enable.request"));

        assertTrue(response.isOk());
        assertEquals("bluetooth", kinds[0]);
        assertEquals("enable", captured.get("op"));
        assertEquals("on", response.getFields().get("state"));
        assertEquals(true, response.getFields().get("user_action"));
    }

    @Test
    public void enableRequestMapsForegroundFailures() {
        BluetoothModule module = new BluetoothModule(context, adapter,
                (kind, params, timeoutMillis) ->
                        AndroidCapabilityProtocol.Response.error("", "foreground-busy"),
                PAIR_WAIT_MS);
        AndroidCapabilityProtocol.Response busy =
                module.handle(request("1", "bt.enable.request"));
        assertFalse(busy.isOk());
        assertEquals("bt-busy", busy.getError());

        BluetoothModule timedOut = new BluetoothModule(context, adapter,
                (kind, params, timeoutMillis) ->
                        AndroidCapabilityProtocol.Response.error("", "foreground-timeout"),
                PAIR_WAIT_MS);
        AndroidCapabilityProtocol.Response timeout =
                timedOut.handle(request("2", "bt.enable.request"));
        assertFalse(timeout.isOk());
        assertEquals("bt-consent-timeout", timeout.getError());
    }

    @Test
    public void discoverableRequestPassesSecondsToTheConsentOperation() {
        Map<String, Object> captured = new LinkedHashMap<>();
        BluetoothModule module = new BluetoothModule(context, adapter,
                (kind, params, timeoutMillis) -> {
                    captured.putAll(params);
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("duration_seconds", 60L);
                    fields.put("scan_mode", "connectable_discoverable");
                    return AndroidCapabilityProtocol.Response.success("", fields);
                }, PAIR_WAIT_MS);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.discoverable.request", "{\"seconds\":60}"));

        assertTrue(response.isOk());
        assertEquals("discoverable", captured.get("op"));
        assertEquals(60L, captured.get("seconds"));
        assertEquals(60L, response.getFields().get("duration_seconds"));
        assertEquals("connectable_discoverable", response.getFields().get("scan_mode"));

        module.handle(request("2", "bt.discoverable.request"));
        assertEquals(120L, captured.get("seconds"));
    }

    @Test
    public void discoverableRequestBoundsSeconds() {
        BluetoothModule module = module();
        assertThrowsInvalid(module, "1", "bt.discoverable.request", "{\"seconds\":0}");
        assertThrowsInvalid(module, "2", "bt.discoverable.request", "{\"seconds\":301}");
        assertThrowsInvalid(module, "3", "bt.discoverable.request", "{\"seconds\":\"x\"}");
    }

    @Test
    @Config(sdk = 31)
    public void api31StatusRequiresConnectGrant() {
        AndroidCapabilityProtocol.Response required =
                module().handle(request("1", "bt.status"));
        assertFalse(required.isOk());
        assertTrue(required.getError().startsWith("bt-permission-required"));
        assertTrue(required.getError().contains("BLUETOOTH_CONNECT"));

        grant(Manifest.permission.BLUETOOTH_CONNECT);
        assertTrue(module().handle(request("2", "bt.status")).isOk());
    }

    @Test
    @Config(sdk = 31)
    public void api31DeniedGrantReportsDenied() {
        deny(Manifest.permission.BLUETOOTH_CONNECT);
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.status"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-denied"));
    }

    @Test
    @Config(sdk = 31)
    public void api31DiscoverRequiresScanThenConnectGrant() {
        grant(Manifest.permission.BLUETOOTH_CONNECT);
        AndroidCapabilityProtocol.Response noScan =
                module().handle(request("1", "bt.discover.start"));
        assertFalse(noScan.isOk());
        assertTrue(noScan.getError().startsWith("bt-permission-required"));
        assertTrue(noScan.getError().contains("BLUETOOTH_SCAN"));

        grant(Manifest.permission.BLUETOOTH_SCAN);
        assertTrue(module().handle(request("2", "bt.discover.start")).isOk());
        module().handle(request("3", "bt.discover.stop"));
    }

    @Test
    @Config(sdk = 31)
    public void api31PairRequiresConnectGrant() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.pair", "{\"address\":\"" + ADDR + "\"}"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-required"));
    }

    @Test
    @Config(sdk = 31)
    public void api31DiscoverableRequiresAdvertiseGrant() {
        grant(Manifest.permission.BLUETOOTH_CONNECT);
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.discoverable.request"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-required"));
        assertTrue(response.getError().contains("BLUETOOTH_ADVERTISE"));
    }

    @Test
    @Config(sdk = 31)
    public void api31EnableRequiresConnectGrant() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.enable.request"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-required"));
        assertTrue(response.getError().contains("BLUETOOTH_CONNECT"));
    }

    // --- helpers ---------------------------------------------------------

    private BluetoothModule module() {
        return new BluetoothModule(context, adapter, null, PAIR_WAIT_MS);
    }

    private BluetoothDevice device(String address) {
        return adapter.getRemoteDevice(address);
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

    /**
     * Run {@code bt.pair} on a worker thread (the call blocks on the bond
     * broadcast) and return the slot its response lands in.
     */
    private AtomicReference<AndroidCapabilityProtocol.Response> pairOnWorker(String address) {
        BluetoothDevice device = device(address);
        Shadow.<ShadowBluetoothDevice>extract(device).setBondState(BluetoothDevice.BOND_NONE);
        Shadow.<ShadowBluetoothDevice>extract(device).setCreatedBond(true);
        AtomicReference<AndroidCapabilityProtocol.Response> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(module().handle(
                request("1", "bt.pair", "{\"address\":\"" + address + "\"}"))),
                "bt-pair-test");
        worker.setDaemon(true);
        worker.start();
        return result;
    }

    /** Wait until the module's bond receiver is registered, then return. */
    private void awaitPairReceiver(String address) throws InterruptedException {
        Intent probe = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Shadow.<ShadowApplication>extract(context).hasReceiverForIntent(probe)) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("pair receiver never registered for " + address);
    }

    /** Deliver a terminal bond-state broadcast through the real broadcast path. */
    private void sendBondBroadcast(String address, int bondState) {
        Intent intent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device(address))
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, bondState)
                .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_BONDING);
        context.sendBroadcast(intent);
        ShadowLooper.idleMainLooper();
    }

    /** Join the pair worker's slot without hanging the test on a failure. */
    private static boolean awaitResult(
            AtomicReference<AndroidCapabilityProtocol.Response> result)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (result.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        return result.get() != null;
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
