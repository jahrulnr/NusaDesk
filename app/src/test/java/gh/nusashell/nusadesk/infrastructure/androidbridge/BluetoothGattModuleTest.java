package gh.nusashell.nusadesk.infrastructure.androidbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
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
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothDevice;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.shadows.ShadowContextWrapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * State-machine and typed-error coverage of {@link BluetoothGattModule} on
 * the JVM through Robolectric's bluetooth shadows.
 *
 * <p>The shadows drive the real {@link BluetoothGattCallback} /
 * {@link BluetoothGattServerCallback} surface: {@code connectGatt} produces a
 * {@link ShadowBluetoothGatt}-backed object, {@code simulateGattConnectionChange}
 * fires connection transitions, {@code addDiscoverableService} +
 * {@code discoverServices} populate discovery, {@code readIncomingCharacteristic}
 * answers a pending read, and a peer CCCD write arrives as
 * {@code onDescriptorWriteRequest}. What the shadows cannot drive (a real
 * radio, an {@code addService} completion callback, a write failure status on
 * a writable characteristic) stays flagged in the per-test comments.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class BluetoothGattModuleTest {

    private static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
    private static final UUID SERVICE_UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final long CONNECT_MS = 400L;
    private static final long IO_MS = 300L;

    /** Robolectric starts with the radio off; the module gates on it. */
    @Before
    public void enableAdapter() {
        Shadow.<ShadowBluetoothAdapter>extract(adapter()).setEnabled(true);
    }

    @Test
    public void moduleDeclaresTheElevenGattMethods() {
        BluetoothGattModule module = module();

        assertEquals(java.util.List.of(
                "bt.gatt.connect", "bt.gatt.services", "bt.gatt.read",
                "bt.gatt.write", "bt.gatt.notify.start", "bt.gatt.notify.poll",
                "bt.gatt.notify.stop", "bt.gatt.disconnect",
                "bt.gatt.server.start", "bt.gatt.server.stop",
                "bt.gatt.server.notify"), module.methods());
        assertEquals(java.util.Set.of(
                "bt.gatt.connect", "bt.gatt.read", "bt.gatt.write",
                "bt.gatt.notify.start", "bt.gatt.notify.stop",
                "bt.gatt.server.start", "bt.gatt.server.notify"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.gatt.nope"));

        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void connectWithoutGrantIsPermissionRequired() {
        denyBluetooth();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.gatt.connect", "{\"address\":\"" + ADDRESS + "\"}"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-required"));
    }

    @Test
    public void connectWithRecordedDenialIsPermissionDenied() {
        denyBluetooth();
        denyAppOp();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.gatt.connect", "{\"address\":\"" + ADDRESS + "\"}"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-denied"));
    }

    @Test
    public void connectValidatesAddressParam() {
        BluetoothGattModule module = module();
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.connect")));
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.connect",
                        "{\"address\":\"" + ADDRESS + "\",\"extra\":1}")));
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.connect",
                        "{\"address\":\"AA:BB:CC:DD:EE:FF:00:11\"}")));
    }

    @Test
    public void connectRejectsMalformedAddressAsDeviceUnknown() {
        grantBluetooth();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.gatt.connect", "{\"address\":\"ZZZ\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-device-unknown:ZZZ", response.getError());
    }

    @Test
    public void connectSilenceIsGattTimeout() {
        grantBluetooth();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.gatt.connect", "{\"address\":\"" + ADDRESS + "\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-timeout", response.getError());
    }

    @Test
    public void connectThenBusyThenDisconnectIdempotent() {
        grantBluetooth();
        BluetoothGattModule module = module();

        AndroidCapabilityProtocol.Response connected = connectAndWait(module, ADDRESS);
        assertTrue(connected.isOk());
        assertEquals(Boolean.TRUE, connected.getFields().get("connected"));
        assertEquals(ADDRESS, connected.getFields().get("address"));

        AndroidCapabilityProtocol.Response second = module.handle(
                request("2", "bt.gatt.connect", "{\"address\":\"" + ADDRESS + "\"}"));
        assertFalse(second.isOk());
        assertEquals("bt-gatt-busy", second.getError());

        for (int i = 0; i < 2; i++) {
            AndroidCapabilityProtocol.Response off =
                    module.handle(request("d" + i, "bt.gatt.disconnect"));
            assertTrue(off.isOk());
            assertEquals(Boolean.FALSE, off.getFields().get("connected"));
        }
    }

    @Test
    public void servicesRequiresConnection() {
        grantBluetooth();
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "bt.gatt.services"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-not-connected", response.getError());
    }

    @Test
    public void servicesReturnsDiscoveredShape() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGatt gatt = gattOf(ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gatt)
                .addDiscoverableService(service(readCharacteristic()));

        AndroidCapabilityProtocol.Response response =
                module.handle(request("1", "bt.gatt.services"));

        assertTrue(response.isOk());
        String json = (String) response.getFields().get("services_json");
        assertEquals(1L, response.getFields().get("count"));
        assertTrue(json.contains("\"uuid\":\"" + SERVICE_UUID + "\""));
        assertTrue(json.contains("\"type\":\"primary\""));
        assertTrue(json.contains("\"uuid\":\"" + CHAR_UUID + "\""));
        assertTrue(json.contains("\"properties\":2"));
    }

    @Test
    public void readRoundTripsPrintableValueAsUtf8() throws Exception {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic characteristic = readCharacteristic();
        characteristic.setValue("hello".getBytes(StandardCharsets.UTF_8));
        BluetoothGatt gatt = gattOf(ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gatt)
                .addDiscoverableService(service(characteristic));

        AtomicReference<AndroidCapabilityProtocol.Response> result =
                new AtomicReference<>();
        Thread worker = handleAsync(module,
                request("1", "bt.gatt.read", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"), result);
        driveUntilDone(worker, () ->
                Shadow.<ShadowBluetoothGatt>extract(gatt)
                        .readIncomingCharacteristic(characteristic));

        assertTrue(result.get().isOk());
        assertEquals("hello", result.get().getFields().get("value_utf8"));
        assertEquals(5L, result.get().getFields().get("length"));
    }

    @Test
    public void readRoundTripsBinaryAsHex() throws Exception {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic characteristic = readCharacteristic();
        characteristic.setValue(new byte[]{0x01, 0x02, (byte) 0xff});
        BluetoothGatt gatt = gattOf(ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gatt)
                .addDiscoverableService(service(characteristic));

        AtomicReference<AndroidCapabilityProtocol.Response> result =
                new AtomicReference<>();
        Thread worker = handleAsync(module,
                request("1", "bt.gatt.read", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"), result);
        driveUntilDone(worker, () ->
                Shadow.<ShadowBluetoothGatt>extract(gatt)
                        .readIncomingCharacteristic(characteristic));

        assertTrue(result.get().isOk());
        assertEquals("0102ff", result.get().getFields().get("value_hex"));
        assertEquals(3L, result.get().getFields().get("length"));
    }

    @Test
    public void readUnknownCharacteristicIsTyped() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gattOf(ADDRESS))
                .addDiscoverableService(service(readCharacteristic()));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.read", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"00002a38-0000-1000-8000-00805f9b34fb\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-unknown-characteristic", response.getError());
    }

    @Test
    public void readOnWriteOnlyCharacteristicIsNotReadable() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic writeOnly = new BluetoothGattCharacteristic(
                CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        Shadow.<ShadowBluetoothGatt>extract(gattOf(ADDRESS))
                .addDiscoverableService(service(writeOnly));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.read", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-not-readable", response.getError());
    }

    @Test
    public void writeRequiresExactlyOneValueParam() {
        BluetoothGattModule module = module();
        String uuids = "\"service_uuid\":\"" + SERVICE_UUID
                + "\",\"char_uuid\":\"" + CHAR_UUID + "\"";
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.write", "{" + uuids + "}")));
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.write", "{" + uuids
                        + ",\"value_hex\":\"00\",\"value_utf8\":\"a\"}")));
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.write", "{" + uuids
                        + ",\"value_hex\":\"0\"}")));
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.write", "{" + uuids
                        + ",\"value_hex\":\"zz\"}")));
    }

    @Test
    public void writeDeliversBytesAndReportsLength() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGatt gatt = gattOf(ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gatt)
                .addDiscoverableService(service(writeCharacteristic()));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.write", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"" + CHAR_UUID
                        + "\",\"value_hex\":\"aabb\"}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("written"));
        assertEquals(2L, response.getFields().get("length"));
        byte[] written = Shadow.<ShadowBluetoothGatt>extract(gatt)
                .getLatestWrittenBytes();
        assertNotNull(written);
        assertEquals(2, written.length);
        assertEquals((byte) 0xaa, written[0]);
        assertEquals((byte) 0xbb, written[1]);
    }

    @Test
    @Config(sdk = 33)
    public void writeOnApi33UsesModernSignature() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGatt gatt = gattOf(ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gatt)
                .addDiscoverableService(service(writeCharacteristic()));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.write", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"" + CHAR_UUID
                        + "\",\"value_utf8\":\"hi\"}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("written"));
        assertEquals(2L, response.getFields().get("length"));
    }

    @Test
    public void writeOnReadOnlyCharacteristicIsNotWritable() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gattOf(ADDRESS))
                .addDiscoverableService(service(readCharacteristic()));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.write", "{\"service_uuid\":\"" + SERVICE_UUID
                        + "\",\"char_uuid\":\"" + CHAR_UUID
                        + "\",\"value_hex\":\"00\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-not-writable", response.getError());
    }

    @Test
    public void notifyStartSubscribesThroughCccd() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic characteristic = notifyCharacteristic();
        BluetoothGatt gatt = gattOf(ADDRESS);
        ShadowBluetoothGatt shadow = Shadow.extract(gatt);
        shadow.addDiscoverableService(service(characteristic));
        shadow.allowCharacteristicNotification(characteristic);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.notify.start", "{\"service_uuid\":\""
                        + SERVICE_UUID + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("subscribed"));
        byte[] cccdWrite = shadow.getLatestWrittenBytes();
        assertNotNull(cccdWrite);
        assertEquals(2, cccdWrite.length);
        assertEquals(1, cccdWrite[0]);
    }

    @Test
    @Config(sdk = 33)
    public void notifyStartOnApi33UsesModernDescriptorWrite() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic characteristic = notifyCharacteristic();
        BluetoothGatt gatt = gattOf(ADDRESS);
        ShadowBluetoothGatt shadow = Shadow.extract(gatt);
        shadow.addDiscoverableService(service(characteristic));
        shadow.allowCharacteristicNotification(characteristic);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.notify.start", "{\"service_uuid\":\""
                        + SERVICE_UUID + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("subscribed"));
    }

    @Test
    public void notifyStartOnNonNotifiableIsTyped() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        Shadow.<ShadowBluetoothGatt>extract(gattOf(ADDRESS))
                .addDiscoverableService(service(readCharacteristic()));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.gatt.notify.start", "{\"service_uuid\":\""
                        + SERVICE_UUID + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-not-notifiable", response.getError());
    }

    @Test
    public void notifyPollDrainsBufferedNotifications() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic characteristic = notifyCharacteristic();
        BluetoothGatt gatt = gattOf(ADDRESS);
        ShadowBluetoothGatt shadow = Shadow.extract(gatt);
        shadow.addDiscoverableService(service(characteristic));
        shadow.allowCharacteristicNotification(characteristic);
        assertTrue(module.handle(request("s", "bt.gatt.notify.start",
                "{\"service_uuid\":\"" + SERVICE_UUID + "\",\"char_uuid\":\""
                        + CHAR_UUID + "\"}")).isOk());

        for (int i = 0; i < 3; i++) {
            characteristic.setValue(new byte[]{(byte) i});
            module.clientCallback.onCharacteristicChanged(gatt, characteristic);
        }
        AndroidCapabilityProtocol.Response poll =
                module.handle(request("1", "bt.gatt.notify.poll"));

        assertTrue(poll.isOk());
        assertEquals(3L, poll.getFields().get("count"));
        String json = (String) poll.getFields().get("notifications_json");
        assertTrue(json.contains("\"char_uuid\":\"" + CHAR_UUID + "\""));
        assertTrue(json.contains("\"value_hex\":\"02\""));
        assertTrue(json.contains("\"at_ms\":"));

        AndroidCapabilityProtocol.Response empty =
                module.handle(request("2", "bt.gatt.notify.poll"));
        assertEquals(0L, empty.getFields().get("count"));
        assertEquals("[]", empty.getFields().get("notifications_json"));
    }

    @Test
    public void notifyPollCapsAtFifty() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGatt gatt = gattOf(ADDRESS);
        BluetoothGattCharacteristic characteristic = notifyCharacteristic();

        for (int i = 0; i < 60; i++) {
            characteristic.setValue(new byte[]{(byte) i});
            module.clientCallback.onCharacteristicChanged(gatt, characteristic);
        }

        AndroidCapabilityProtocol.Response poll =
                module.handle(request("1", "bt.gatt.notify.poll"));
        assertEquals(50L, poll.getFields().get("count"));
    }

    @Test
    public void notifyStopIsIdempotent() {
        grantBluetooth();
        BluetoothGattModule module = module();
        connectAndWait(module, ADDRESS);
        BluetoothGattCharacteristic characteristic = notifyCharacteristic();
        BluetoothGatt gatt = gattOf(ADDRESS);
        ShadowBluetoothGatt shadow = Shadow.extract(gatt);
        shadow.addDiscoverableService(service(characteristic));
        shadow.allowCharacteristicNotification(characteristic);
        module.handle(request("s", "bt.gatt.notify.start",
                "{\"service_uuid\":\"" + SERVICE_UUID + "\",\"char_uuid\":\""
                        + CHAR_UUID + "\"}"));

        for (int i = 0; i < 3; i++) {
            AndroidCapabilityProtocol.Response stop = module.handle(
                    request("x" + i, "bt.gatt.notify.stop",
                            "{\"service_uuid\":\"" + SERVICE_UUID
                                    + "\",\"char_uuid\":\"" + CHAR_UUID + "\"}"));
            assertTrue(stop.isOk());
            assertEquals(Boolean.FALSE, stop.getFields().get("subscribed"));
        }
    }

    @Test
    public void serverStartReportsFixedServiceAndIsIdempotent() {
        grantBluetooth();
        BluetoothGattModule module = module();

        for (int i = 0; i < 2; i++) {
            AndroidCapabilityProtocol.Response started = module.handle(
                    request("s" + i, "bt.gatt.server.start"));
            assertTrue(started.isOk());
            assertEquals(Boolean.TRUE, started.getFields().get("started"));
            assertEquals(BluetoothGattModule.SERVICE_UUID.toString(),
                    started.getFields().get("service_uuid"));
        }
    }

    @Test
    public void serverStartValidatesNameBound() {
        BluetoothGattModule module = module();
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            longName.append('x');
        }
        assertThrows(CapabilityParams.Invalid.class,
                () -> module.handle(request("1", "bt.gatt.server.start",
                        "{\"name\":\"" + longName + "\"}")));
    }

    @Test
    public void serverNotifyWithoutSubscribersIsTyped() {
        grantBluetooth();
        BluetoothGattModule module = module();
        module.handle(request("1", "bt.gatt.server.start"));

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.gatt.server.notify", "{\"value_hex\":\"00\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-gatt-server-no-subscribers", response.getError());
    }

    @Test
    public void serverNotifyReportsSubscribersAndDelivered() {
        grantBluetooth();
        BluetoothGattModule module = module();
        module.handle(request("1", "bt.gatt.server.start"));

        BluetoothDevice peer = adapter().getRemoteDevice("11:22:33:44:55:66");
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                BluetoothGattModule.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE);
        module.serverCallback.onDescriptorWriteRequest(peer, 7, cccd,
                false, true, 0, new byte[]{0x01, 0x00});

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.gatt.server.notify", "{\"value_utf8\":\"ping\"}"));

        assertTrue(response.isOk());
        assertEquals(1L, response.getFields().get("subscribers"));
        assertNotNull(response.getFields().get("delivered"));

        module.serverCallback.onDescriptorWriteRequest(peer, 8, cccd,
                false, true, 0, new byte[]{0x00, 0x00});
        AndroidCapabilityProtocol.Response drained = module.handle(
                request("3", "bt.gatt.server.notify", "{\"value_utf8\":\"x\"}"));
        assertFalse(drained.isOk());
        assertEquals("bt-gatt-server-no-subscribers", drained.getError());
    }

    @Test
    public void serverStopIsIdempotentAndResets() {
        grantBluetooth();
        BluetoothGattModule module = module();

        for (int i = 0; i < 2; i++) {
            AndroidCapabilityProtocol.Response stopped =
                    module.handle(request("s" + i, "bt.gatt.server.stop"));
            assertTrue(stopped.isOk());
            assertEquals(Boolean.TRUE, stopped.getFields().get("stopped"));
        }

        AndroidCapabilityProtocol.Response notify = module.handle(
                request("n", "bt.gatt.server.notify", "{\"value_hex\":\"00\"}"));
        assertFalse(notify.isOk());
        assertEquals("bt-gatt-server-not-started", notify.getError());
    }

    @Test
    public void adoptedGattSeamDrivesTheSameStateMachine() {
        grantBluetooth();
        Context context = RuntimeEnvironment.getApplication();
        BluetoothGatt adopted = ShadowBluetoothGatt.newInstance(
                adapter().getRemoteDevice(ADDRESS));
        BluetoothGattModule module = new BluetoothGattModule(context,
                context.getSystemService(BluetoothManager.class),
                adopted, null, CONNECT_MS, IO_MS);
        ShadowBluetoothGatt shadow = Shadow.extract(adopted);
        shadow.setGattCallback(module.clientCallback);
        shadow.notifyConnection(ADDRESS);

        Shadow.<ShadowBluetoothGatt>extract(adopted)
                .addDiscoverableService(service(readCharacteristic()));
        AndroidCapabilityProtocol.Response services =
                module.handle(request("1", "bt.gatt.services"));

        assertTrue(services.isOk());
        assertEquals(1L, services.getFields().get("count"));

        // An adopted session counts toward the one-session rule.
        AndroidCapabilityProtocol.Response second = module.handle(
                request("2", "bt.gatt.connect", "{\"address\":\"" + ADDRESS + "\"}"));
        assertFalse(second.isOk());
        assertEquals("bt-gatt-busy", second.getError());
    }

    // ----- helpers -----

    private static BluetoothGattModule module() {
        Context context = RuntimeEnvironment.getApplication();
        return new BluetoothGattModule(context,
                context.getSystemService(BluetoothManager.class),
                null, null, CONNECT_MS, IO_MS);
    }

    private static BluetoothAdapter adapter() {
        return RuntimeEnvironment.getApplication()
                .getSystemService(BluetoothManager.class).getAdapter();
    }

    /** The module's shadow-backed gatt object for an address, post-connect. */
    private static BluetoothGatt gattOf(String address) {
        ShadowBluetoothDevice device =
                Shadow.extract(adapter().getRemoteDevice(address));
        assertFalse("expected a connectGatt-created session",
                device.getBluetoothGatts().isEmpty());
        return device.getBluetoothGatts().get(0);
    }

    /**
     * Runs {@code bt.gatt.connect} on a worker thread and completes it by
     * firing the shadow's connection-state change once the gatt exists. The
     * shadow's {@code connectGatt} adds the gatt to the device's list before
     * it stores the callback that {@code simulateGattConnectionChange}
     * dereferences, so the wait must cover the registration, not just the
     * list entry.
     */
    private static AndroidCapabilityProtocol.Response connectAndWait(
            BluetoothGattModule module, String address) {
        AtomicReference<AndroidCapabilityProtocol.Response> result =
                new AtomicReference<>();
        Thread worker = handleAsync(module,
                request("c", "bt.gatt.connect", "{\"address\":\"" + address + "\"}"),
                result);
        ShadowBluetoothDevice device =
                Shadow.extract(adapter().getRemoteDevice(address));
        long deadline = System.currentTimeMillis() + 3000;
        while (!gattCallbacksRegistered(device)
                && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertTrue("connectGatt never registered its callback",
                gattCallbacksRegistered(device));
        device.simulateGattConnectionChange(BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED);
        join(worker);
        return result.get();
    }

    /**
     * True once every gatt on the shadow device carries the callback
     * {@code simulateGattConnectionChange} invokes.
     */
    private static boolean gattCallbacksRegistered(ShadowBluetoothDevice device) {
        List<BluetoothGatt> gatts = device.getBluetoothGatts();
        if (gatts.isEmpty()) {
            return false;
        }
        for (BluetoothGatt gatt : gatts) {
            if (Shadow.<ShadowBluetoothGatt>extract(gatt).getGattCallback() == null) {
                return false;
            }
        }
        return true;
    }

    private static Thread handleAsync(BluetoothGattModule module,
                                      AndroidCapabilityProtocol.Request request,
                                      AtomicReference<AndroidCapabilityProtocol.Response> sink) {
        Thread worker = new Thread(() -> sink.set(module.handle(request)),
                "bt-gatt-test-handle");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    /**
     * Repeatedly fires the driver until the worker finishes: the module arms
     * its pending operation before initiating, so a drive that lands early is
     * safely retried.
     */
    private static void driveUntilDone(Thread worker, Runnable driver) {
        long deadline = System.currentTimeMillis() + 3000;
        while (worker.isAlive() && System.currentTimeMillis() < deadline) {
            driver.run();
            Thread.yield();
        }
        join(worker);
        assertFalse("worker never produced a response", worker.isAlive());
    }

    private static void join(Thread worker) {
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static BluetoothGattService service(
            BluetoothGattCharacteristic characteristic) {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(characteristic);
        return service;
    }

    private static BluetoothGattCharacteristic readCharacteristic() {
        return new BluetoothGattCharacteristic(CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
    }

    private static BluetoothGattCharacteristic writeCharacteristic() {
        return new BluetoothGattCharacteristic(CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
    }

    private static BluetoothGattCharacteristic notifyCharacteristic() {
        BluetoothGattCharacteristic characteristic =
                new BluetoothGattCharacteristic(CHAR_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ
                                | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ);
        characteristic.addDescriptor(new BluetoothGattDescriptor(
                BluetoothGattModule.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE));
        return characteristic;
    }

    private static void grantBluetooth() {
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private static void denyBluetooth() {
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.BLUETOOTH_CONNECT);
    }

    /** Records the app-op refusal that turns REQUIRED into DENIED. */
    private static void denyAppOp() {
        Context context = RuntimeEnvironment.getApplication();
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                AppOpsManager.permissionToOp(Manifest.permission.BLUETOOTH_CONNECT),
                Process.myUid(), context.getPackageName(),
                AppOpsManager.MODE_IGNORED);
    }

    private static AndroidCapabilityProtocol.Request request(String id,
                                                             String method) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\","
                        + "\"method\":\"" + method + "\"}");
    }

    private static AndroidCapabilityProtocol.Request request(String id,
                                                             String method,
                                                             String paramsJson) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\","
                        + "\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
    }
}
