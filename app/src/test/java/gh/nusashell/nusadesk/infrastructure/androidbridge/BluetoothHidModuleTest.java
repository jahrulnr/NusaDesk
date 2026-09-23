package gh.nusashell.nusadesk.infrastructure.androidbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit coverage of {@link BluetoothHidModule}: the fixed composite
 * descriptor, the registration/connect lifecycle behind callback truth, the
 * bounded permission/foreground gates, the ASCII key map, the bounded mouse
 * reports, and honest degradation on auto-unregister — all through the
 * {@link BluetoothHidModule.HidBackend} seam, so no Bluetooth radio or
 * Android framework is needed.
 */
public class BluetoothHidModuleTest {

    private static final String HOST = "11:22:33:44:55:66";
    private static final String OTHER = "AA:BB:CC:DD:EE:FF";

    // ------------------------------------------------------------------
    // Shape, descriptor, and validation
    // ------------------------------------------------------------------

    @Test
    public void declaresTheNineHidMethods() {
        BluetoothHidModule module = module(new FakeBackend());
        assertEquals(List.of("bt.hid.start", "bt.hid.status", "bt.hid.connect",
                "bt.hid.disconnect", "bt.hid.stop", "bt.hid.type", "bt.hid.key",
                "bt.hid.mouse.move", "bt.hid.mouse.click"), module.methods());
        assertEquals(Set.of("bt.hid.start", "bt.hid.connect", "bt.hid.disconnect",
                "bt.hid.type", "bt.hid.key", "bt.hid.mouse.move",
                "bt.hid.mouse.click"), module.parameterMethods());
    }

    @Test
    public void startRegistersTheFixedCompositeDescriptor() {
        FakeBackend backend = new FakeBackend();
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response response =
                module.handle(request("1", "bt.hid.start"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("registered"));
        assertEquals("NusaDesk HID", response.getFields().get("name"));
        assertEquals("NusaDesk HID", backend.registeredName);
        assertEquals(10_000L, backend.registerTimeoutMs);

        // The pinned descriptor: keyboard application collection keyed on
        // report ID 1 and a mouse application collection on report ID 2.
        byte[] d = backend.descriptor;
        assertEquals(101, d.length);
        assertEquals(0x05, d[0] & 0xff);           // Usage Page
        assertEquals(0x01, d[1] & 0xff);           // Generic Desktop
        assertEquals(0x09, d[2] & 0xff);           // Usage
        assertEquals(0x06, d[3] & 0xff);           // Keyboard
        assertTrue(containsPair(d, 0x85, 0x01));   // Report ID 1
        assertTrue(containsPair(d, 0x85, 0x02));   // Report ID 2
        assertTrue(containsPair(d, 0x09, 0x02));   // Usage (Mouse)
        assertTrue(containsPair(d, 0x09, 0x30));   // Usage (X)
        assertTrue(containsPair(d, 0x09, 0x38));   // Usage (Wheel)
        assertEquals((byte) 0xC0, d[d.length - 1]);
        assertEquals((byte) 0xC0, d[d.length - 2]);
    }

    @Test
    public void startHonoursTheNameBound() {
        FakeBackend backend = new FakeBackend();
        BluetoothHidModule module = module(backend);

        assertTrue(module.handle(request("1", "bt.hid.start",
                "{\"name\":\"Desk HID\"}")).isOk());
        assertEquals("Desk HID", backend.registeredName);

        BluetoothHidModule fresh = module(new FakeBackend());
        String tooLong = new String(new char[33]).replace('\0', 'x');
        assertThrows(CapabilityParams.Invalid.class, () -> fresh.handle(
                request("2", "bt.hid.start", "{\"name\":\"" + tooLong + "\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> fresh.handle(
                request("3", "bt.hid.start", "{\"name\":\"\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> fresh.handle(
                request("4", "bt.hid.start", "{\"name\":7}")));
        assertThrows(CapabilityParams.Invalid.class, () -> fresh.handle(
                request("5", "bt.hid.start", "{\"bogus\":1}")));
    }

    @Test
    public void methodsRejectUndeclaredParams() {
        BluetoothHidModule module = module(new FakeBackend());
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("1", "bt.hid.connect", "{\"address\":\"" + HOST
                        + "\",\"timeout_ms\":1000}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.hid.type", "{\"text\":\"a\",\"speed\":1}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.hid.key", "{\"key\":\"ENTER\",\"code\":40}")));
    }

    // ------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------

    @Test
    public void gatesReportThePermissionState() {
        FakeBackend backend = new FakeBackend();
        BluetoothHidModule module =
                module(backend, CapabilityPermission.REQUIRED);

        AndroidCapabilityProtocol.Response required =
                module.handle(request("1", "bt.hid.start"));
        assertFalse(required.isOk());
        assertTrue(required.getError().startsWith("bt-permission-required"));
        assertNull(backend.descriptor);

        BluetoothHidModule deniedModule =
                module(new FakeBackend(), CapabilityPermission.DENIED);
        AndroidCapabilityProtocol.Response denied =
                deniedModule.handle(request("2", "bt.hid.start"));
        assertFalse(denied.isOk());
        assertEquals("bt-permission-denied", denied.getError());
    }

    @Test
    public void stopAndDisconnectCheckPermissionBeforePlatformCalls() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule denied = module(backend, CapabilityPermission.DENIED);

        AndroidCapabilityProtocol.Response stopped = denied.handle(
                request("1", "bt.hid.stop"));
        assertFalse(stopped.isOk());
        assertEquals("bt-permission-denied", stopped.getError());
        assertEquals(0, backend.unregisterCalls);

        AndroidCapabilityProtocol.Response disconnected = denied.handle(
                request("2", "bt.hid.disconnect"));
        assertFalse(disconnected.isOk());
        assertEquals("bt-permission-denied", disconnected.getError());
        assertNull(backend.disconnected);
    }

    @Test
    public void startRefusesWhenTheAppIsNotForeground() {
        FakeBackend backend = new FakeBackend();
        backend.foregrounded = false;
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response response =
                module.handle(request("1", "bt.hid.start"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-hid-foreground-required"));
        assertNull(backend.descriptor);
    }

    @Test
    public void radioUnavailabilityIsPropagated() {
        FakeBackend backend = new FakeBackend();
        backend.unavailable = "bt-unsupported:no bluetooth adapter";
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response start =
                module.handle(request("1", "bt.hid.start"));
        assertFalse(start.isOk());
        assertEquals("bt-unsupported:no bluetooth adapter", start.getError());

        backend.unavailable = "bt-unavailable:bluetooth is off";
        AndroidCapabilityProtocol.Response status =
                module.handle(request("2", "bt.hid.status"));
        assertFalse(status.isOk());
        assertEquals("bt-unavailable:bluetooth is off", status.getError());
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    @Test
    public void registerTimeoutAndRefusalAreTyped() {
        FakeBackend timeout = new FakeBackend();
        timeout.registerResult = BluetoothHidModule.HidResult.TIMEOUT;
        AndroidCapabilityProtocol.Response timedOut =
                module(timeout).handle(request("1", "bt.hid.start"));
        assertFalse(timedOut.isOk());
        assertEquals("bt-hid-register-timeout", timedOut.getError());

        FakeBackend failed = new FakeBackend();
        failed.registerResult = BluetoothHidModule.HidResult.FAILED;
        AndroidCapabilityProtocol.Response refused =
                module(failed).handle(request("2", "bt.hid.start"));
        assertFalse(refused.isOk());
        assertEquals("bt-hid-register-failed", refused.getError());
    }

    @Test
    public void aSecondStartIsAlreadyRegistered() {
        FakeBackend backend = new FakeBackend();
        BluetoothHidModule module = module(backend);
        assertTrue(module.handle(request("1", "bt.hid.start")).isOk());

        AndroidCapabilityProtocol.Response again =
                module.handle(request("2", "bt.hid.start"));

        assertFalse(again.isOk());
        assertEquals("bt-hid-already-registered", again.getError());
    }

    @Test
    public void statusReflectsRegistrationAndHostTruth() {
        FakeBackend backend = new FakeBackend();
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response idle =
                module.handle(request("1", "bt.hid.status"));
        assertTrue(idle.isOk());
        assertEquals(Boolean.FALSE, idle.getFields().get("registered"));
        assertEquals(Boolean.FALSE, idle.getFields().get("connected"));

        backend.paired.add(HOST);
        assertTrue(module.handle(request("2", "bt.hid.start")).isOk());
        assertTrue(module.handle(request("3", "bt.hid.connect",
                "{\"address\":\"" + HOST + "\"}")).isOk());
        backend.connectedName = "ThinkPad";

        AndroidCapabilityProtocol.Response live =
                module.handle(request("4", "bt.hid.status"));
        assertTrue(live.isOk());
        assertEquals(Boolean.TRUE, live.getFields().get("registered"));
        assertEquals(Boolean.TRUE, live.getFields().get("connected"));
        assertEquals(HOST, live.getFields().get("host_address"));
        assertEquals("ThinkPad", live.getFields().get("host_name"));
    }

    @Test
    public void autoUnregisterDegradesHonestly() {
        FakeBackend backend = new FakeBackend();
        backend.paired.add(HOST);
        BluetoothHidModule module = module(backend);
        assertTrue(module.handle(request("1", "bt.hid.start")).isOk());
        assertTrue(module.handle(request("2", "bt.hid.connect",
                "{\"address\":\"" + HOST + "\"}")).isOk());

        // The platform drops the registration when the app backgrounds.
        backend.registered = false;
        backend.connected = null;

        AndroidCapabilityProtocol.Response status =
                module.handle(request("3", "bt.hid.status"));
        assertTrue(status.isOk());
        assertEquals(Boolean.FALSE, status.getFields().get("registered"));
        assertEquals(Boolean.FALSE, status.getFields().get("connected"));

        AndroidCapabilityProtocol.Response typed = module.handle(
                request("4", "bt.hid.type", "{\"text\":\"x\"}"));
        assertFalse(typed.isOk());
        assertEquals("bt-hid-not-registered", typed.getError());
    }

    // ------------------------------------------------------------------
    // connect / disconnect
    // ------------------------------------------------------------------

    @Test
    public void connectRequiresRegistrationAndPairedHostsOnly() {
        FakeBackend backend = new FakeBackend();
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response unregistered = module.handle(
                request("1", "bt.hid.connect", "{\"address\":\"" + HOST + "\"}"));
        assertFalse(unregistered.isOk());
        assertEquals("bt-device-unknown:" + HOST, unregistered.getError());

        backend.paired.add(HOST);
        AndroidCapabilityProtocol.Response notRegistered = module.handle(
                request("2", "bt.hid.connect", "{\"address\":\"" + HOST + "\"}"));
        assertFalse(notRegistered.isOk());
        assertEquals("bt-hid-not-registered", notRegistered.getError());

        assertTrue(module.handle(request("3", "bt.hid.start")).isOk());
        AndroidCapabilityProtocol.Response unpaired = module.handle(
                request("4", "bt.hid.connect", "{\"address\":\"" + OTHER + "\"}"));
        assertFalse(unpaired.isOk());
        assertEquals("bt-device-unknown:" + OTHER, unpaired.getError());

        AndroidCapabilityProtocol.Response malformed = module.handle(
                request("5", "bt.hid.connect", "{\"address\":\"not-a-mac\"}"));
        assertFalse(malformed.isOk());
        assertEquals("bt-device-unknown:not-a-mac", malformed.getError());
    }

    @Test
    public void connectReportsTheCallbackOutcome() {
        FakeBackend backend = new FakeBackend();
        backend.paired.add(HOST);
        BluetoothHidModule module = module(backend);
        assertTrue(module.handle(request("1", "bt.hid.start")).isOk());

        AndroidCapabilityProtocol.Response connected = module.handle(
                request("2", "bt.hid.connect", "{\"address\":\"" + HOST + "\"}"));
        assertTrue(connected.isOk());
        assertEquals(Boolean.TRUE, connected.getFields().get("connected"));
        assertEquals(HOST, connected.getFields().get("address"));
        assertEquals(15_000L, backend.connectTimeoutMs);

        // A repeat connect to the live host is idempotent.
        assertTrue(module.handle(request("3", "bt.hid.connect",
                "{\"address\":\"" + HOST + "\"}")).isOk());

        backend.paired.add(OTHER);
        AndroidCapabilityProtocol.Response second = module.handle(
                request("4", "bt.hid.connect", "{\"address\":\"" + OTHER + "\"}"));
        assertFalse(second.isOk());
        assertEquals("bt-hid-already-connected:" + HOST, second.getError());
    }

    @Test
    public void connectNormalizesLowercaseMacBeforeCallbackComparison() {
        FakeBackend backend = new FakeBackend();
        backend.paired.add(OTHER);
        BluetoothHidModule module = module(backend);
        assertTrue(module.handle(request("1", "bt.hid.start")).isOk());

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.hid.connect",
                        "{\"address\":\"aa:bb:cc:dd:ee:ff\"}"));

        assertTrue(response.isOk());
        assertEquals(OTHER, response.getFields().get("address"));
    }

    @Test
    public void connectTimeoutAndFailureAreTyped() {
        FakeBackend timeout = new FakeBackend();
        timeout.paired.add(HOST);
        timeout.connectResult = BluetoothHidModule.HidResult.TIMEOUT;
        BluetoothHidModule timeoutModule = module(timeout);
        assertTrue(timeoutModule.handle(request("1", "bt.hid.start")).isOk());
        AndroidCapabilityProtocol.Response timedOut = timeoutModule.handle(
                request("2", "bt.hid.connect", "{\"address\":\"" + HOST + "\"}"));
        assertFalse(timedOut.isOk());
        assertEquals("bt-hid-connect-timeout", timedOut.getError());

        FakeBackend failed = new FakeBackend();
        failed.paired.add(HOST);
        failed.connectResult = BluetoothHidModule.HidResult.FAILED;
        BluetoothHidModule failedModule = module(failed);
        assertTrue(failedModule.handle(request("3", "bt.hid.start")).isOk());
        AndroidCapabilityProtocol.Response refused = failedModule.handle(
                request("4", "bt.hid.connect", "{\"address\":\"" + HOST + "\"}"));
        assertFalse(refused.isOk());
        assertEquals("bt-hid-connect-failed", refused.getError());
    }

    @Test
    public void disconnectIsIdempotentAndHonoursAddress() {
        FakeBackend backend = new FakeBackend();
        backend.paired.add(HOST);
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response idle =
                module.handle(request("1", "bt.hid.disconnect"));
        assertTrue(idle.isOk());
        assertEquals(Boolean.FALSE, idle.getFields().get("disconnected"));

        assertTrue(module.handle(request("2", "bt.hid.start")).isOk());
        assertTrue(module.handle(request("3", "bt.hid.connect",
                "{\"address\":\"" + HOST + "\"}")).isOk());

        AndroidCapabilityProtocol.Response wrongHost = module.handle(
                request("4", "bt.hid.disconnect", "{\"address\":\"" + OTHER + "\"}"));
        assertTrue(wrongHost.isOk());
        assertEquals(Boolean.FALSE, wrongHost.getFields().get("disconnected"));

        AndroidCapabilityProtocol.Response dropped = module.handle(
                request("5", "bt.hid.disconnect", "{\"address\":\"" + HOST + "\"}"));
        assertTrue(dropped.isOk());
        assertEquals(Boolean.TRUE, dropped.getFields().get("disconnected"));
        assertEquals(HOST, dropped.getFields().get("address"));
        assertEquals(HOST, backend.disconnected);
        assertNull(backend.connectedAddress());

        // A refusal is typed, not swallowed.
        backend.connected = HOST;
        backend.disconnectOk = false;
        AndroidCapabilityProtocol.Response refused =
                module.handle(request("6", "bt.hid.disconnect"));
        assertFalse(refused.isOk());
        assertEquals("bt-hid-disconnect-failed", refused.getError());
    }

    // ------------------------------------------------------------------
    // bt.hid.type
    // ------------------------------------------------------------------

    @Test
    public void typeMapsAsciiThroughTheUsLayout() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.hid.type", "{\"text\":\"aA!z0 ?\"}"));

        assertTrue(response.isOk());
        assertEquals(7L, response.getFields().get("typed"));
        assertEquals(14, backend.reports.size());
        for (int id : backend.reportIds) {
            assertEquals(BluetoothHidModule.REPORT_ID_KEYBOARD, id);
        }
        assertArrayEquals(new byte[] {0, 0, 0x04, 0, 0, 0, 0, 0},
                backend.reports.get(0));                    // 'a'
        assertArrayEquals(new byte[8], backend.reports.get(1));
        assertArrayEquals(new byte[] {0x02, 0, 0x04, 0, 0, 0, 0, 0},
                backend.reports.get(2));                    // 'A' = shift+a
        assertArrayEquals(new byte[] {0x02, 0, 0x1E, 0, 0, 0, 0, 0},
                backend.reports.get(4));                    // '!' = shift+1
        assertArrayEquals(new byte[] {0, 0, 0x1D, 0, 0, 0, 0, 0},
                backend.reports.get(6));                    // 'z'
        assertArrayEquals(new byte[] {0, 0, 0x27, 0, 0, 0, 0, 0},
                backend.reports.get(8));                    // '0'
        assertArrayEquals(new byte[] {0, 0, 0x2C, 0, 0, 0, 0, 0},
                backend.reports.get(10));                   // ' '
        assertArrayEquals(new byte[] {0x02, 0, 0x38, 0, 0, 0, 0, 0},
                backend.reports.get(12));                   // '?' = shift+'/'
    }

    @Test
    public void typeCoversTheShiftedSymbolMap() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        assertTrue(module.handle(request("1", "bt.hid.type",
                "{\"text\":\"@_+|~<>\"}")).isOk());

        // '@'=shift+2, '_'=shift+'-', '+'=shift+'=', '|'=shift+'\\',
        // '~'=shift+'`', '<'=shift+',', '>'=shift+'.'
        int[] usages = {0x1F, 0x2D, 0x2E, 0x31, 0x35, 0x36, 0x37};
        for (int i = 0; i < usages.length; i++) {
            assertArrayEquals(new byte[] {0x02, 0, (byte) usages[i], 0, 0, 0, 0, 0},
                    backend.reports.get(i * 2));
        }
    }

    @Test
    public void typeRejectsBadTextBeforeSending() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("1", "bt.hid.type", "{\"text\":\"\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.hid.type", "{\"text\":\"héllo\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.hid.type", "{\"text\":\"a\u20AC\"}")));
        String tooLong = new String(new char[129]).replace('\0', 'x');
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("4", "bt.hid.type", "{\"text\":\"" + tooLong + "\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("5", "bt.hid.type", "{\"text\":42}")));
        assertTrue(backend.reports.isEmpty());
    }

    @Test
    public void typeAcceptsExactly128PrintableAsciiCharacters() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);
        String text = new String(new char[128]).replace('\0', 'x');

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.hid.type", "{\"text\":\"" + text + "\"}"));

        assertTrue(response.isOk());
        assertEquals(128L, response.getFields().get("typed"));
        assertEquals(256, backend.reports.size());
    }

    @Test
    public void reportsNeedRegistrationAndAHost() {
        FakeBackend backend = new FakeBackend();
        backend.paired.add(HOST);
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response unregistered = module.handle(
                request("1", "bt.hid.type", "{\"text\":\"x\"}"));
        assertFalse(unregistered.isOk());
        assertEquals("bt-hid-not-registered", unregistered.getError());

        assertTrue(module.handle(request("2", "bt.hid.start")).isOk());
        AndroidCapabilityProtocol.Response noHost = module.handle(
                request("3", "bt.hid.type", "{\"text\":\"x\"}"));
        assertFalse(noHost.isOk());
        assertEquals("bt-hid-not-connected", noHost.getError());
    }

    @Test
    public void aRefusedReportIsTyped() {
        FakeBackend backend = connectedBackend();
        backend.sendOk = false;
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.hid.type", "{\"text\":\"abc\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-hid-send-failed", response.getError());
        // The release report is attempted even after a failed key-down send.
        assertEquals(2, backend.reports.size());
    }

    // ------------------------------------------------------------------
    // bt.hid.key
    // ------------------------------------------------------------------

    @Test
    public void keySendsTheAllowlistedUsages() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        String[] names = {"ENTER", "TAB", "BACKSPACE", "ESCAPE", "SPACE",
                "UP", "DOWN", "LEFT", "RIGHT"};
        int[] usages = {0x28, 0x2B, 0x2A, 0x29, 0x2C, 0x52, 0x51, 0x50, 0x4F};
        for (int i = 0; i < names.length; i++) {
            AndroidCapabilityProtocol.Response response = module.handle(
                    request("k" + i, "bt.hid.key", "{\"key\":\"" + names[i] + "\"}"));
            assertTrue(names[i], response.isOk());
            assertEquals(names[i], response.getFields().get("key"));
            assertArrayEquals(new byte[] {0, 0, (byte) usages[i], 0, 0, 0, 0, 0},
                    backend.reports.get(i * 2));
            assertArrayEquals(new byte[8], backend.reports.get(i * 2 + 1));
        }
    }

    @Test
    public void keyHonoursShiftAndRejectsUnknownKeys() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        assertTrue(module.handle(request("1", "bt.hid.key",
                "{\"key\":\"enter\",\"shift\":true}")).isOk());
        assertArrayEquals(new byte[] {0x02, 0, 0x28, 0, 0, 0, 0, 0},
                backend.reports.get(0));

        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.hid.key", "{\"key\":\"F1\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.hid.key", "{\"shift\":true}")));
    }

    // ------------------------------------------------------------------
    // bt.hid.mouse
    // ------------------------------------------------------------------

    @Test
    public void mouseMoveSendsBoundedRelativeBytes() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.hid.mouse.move",
                        "{\"dx\":10,\"dy\":-20,\"wheel\":5}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("moved"));
        assertEquals(1, backend.reports.size());
        assertEquals(BluetoothHidModule.REPORT_ID_MOUSE,
                (int) backend.reportIds.get(0));
        assertArrayEquals(new byte[] {0, 10, -20, 5}, backend.reports.get(0));

        assertTrue(module.handle(request("2", "bt.hid.mouse.move",
                "{\"dx\":-127,\"dy\":127}")).isOk());
        assertArrayEquals(new byte[] {0, -127, 127, 0}, backend.reports.get(1));
    }

    @Test
    public void mouseMoveRejectsOutOfRangeAndMissingAxes() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("1", "bt.hid.mouse.move", "{\"dx\":128,\"dy\":0}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.hid.mouse.move", "{\"dx\":0,\"dy\":-128}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.hid.mouse.move",
                        "{\"dx\":0,\"dy\":0,\"wheel\":128}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("4", "bt.hid.mouse.move", "{\"dx\":0}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("5", "bt.hid.mouse.move", "{\"dx\":\"x\",\"dy\":0}")));
        assertTrue(backend.reports.isEmpty());
    }

    @Test
    public void mouseClickPressesAndReleasesOneButton() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        String[] names = {"LEFT", "RIGHT", "MIDDLE"};
        int[] bits = {0x01, 0x02, 0x04};
        for (int i = 0; i < names.length; i++) {
            AndroidCapabilityProtocol.Response response = module.handle(
                    request("c" + i, "bt.hid.mouse.click",
                            "{\"button\":\"" + names[i] + "\"}"));
            assertTrue(names[i], response.isOk());
            assertEquals(names[i], response.getFields().get("button"));
            assertArrayEquals(new byte[] {(byte) bits[i], 0, 0, 0},
                    backend.reports.get(i * 2));
            assertArrayEquals(new byte[4], backend.reports.get(i * 2 + 1));
            assertEquals(BluetoothHidModule.REPORT_ID_MOUSE,
                    (int) backend.reportIds.get(i * 2));
        }

        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("9", "bt.hid.mouse.click", "{\"button\":\"SCROLL\"}")));
    }

    @Test
    public void mouseReportsNeedAHost() {
        BluetoothHidModule module = module(new FakeBackend());
        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.hid.mouse.click", "{\"button\":\"LEFT\"}"));
        assertFalse(response.isOk());
        assertEquals("bt-hid-not-registered", response.getError());
    }

    // ------------------------------------------------------------------
    // stop / close
    // ------------------------------------------------------------------

    @Test
    public void stopIsIdempotentAndUnregisters() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        AndroidCapabilityProtocol.Response stopped =
                module.handle(request("1", "bt.hid.stop"));
        assertTrue(stopped.isOk());
        assertEquals(Boolean.TRUE, stopped.getFields().get("stopped"));
        assertEquals(1, backend.unregisterCalls);
        assertFalse(backend.registered());

        AndroidCapabilityProtocol.Response again =
                module.handle(request("2", "bt.hid.stop"));
        assertTrue(again.isOk());
        assertEquals(Boolean.FALSE, again.getFields().get("stopped"));
        assertEquals(2, backend.unregisterCalls);
    }

    @Test
    public void closeCleansUpIdempotently() {
        FakeBackend backend = connectedBackend();
        BluetoothHidModule module = module(backend);

        module.close();
        module.close();

        assertEquals(2, backend.closeCalls);
        assertFalse(backend.registered());
        assertNull(backend.connectedAddress());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static FakeBackend connectedBackend() {
        FakeBackend backend = new FakeBackend();
        backend.paired.add(HOST);
        BluetoothHidModule module = module(backend);
        assertTrue(module.handle(request("s", "bt.hid.start")).isOk());
        assertTrue(module.handle(request("c", "bt.hid.connect",
                "{\"address\":\"" + HOST + "\"}")).isOk());
        return backend;
    }

    private static BluetoothHidModule module(FakeBackend backend) {
        return module(backend, CapabilityPermission.GRANTED);
    }

    private static BluetoothHidModule module(FakeBackend backend,
                                             CapabilityPermission grant) {
        return new BluetoothHidModule(backend, () -> grant);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(
            String id, String method, String params) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        AndroidCapabilityProtocol.Request decoded =
                AndroidCapabilityProtocol.decodeRequest(frame);
        if (decoded == null) {
            throw new IllegalArgumentException("bad test frame: " + frame);
        }
        return decoded;
    }

    private static boolean containsPair(byte[] data, int first, int second) {
        for (int i = 0; i + 1 < data.length; i++) {
            if ((data[i] & 0xff) == first && (data[i + 1] & 0xff) == second) {
                return true;
            }
        }
        return false;
    }

    /**
     * In-memory {@link BluetoothHidModule.HidBackend}: callback truth lives in
     * plain fields, so tests drive registration loss and connection drops by
     * flipping them directly.
     */
    private static final class FakeBackend implements BluetoothHidModule.HidBackend {
        String unavailable;
        boolean foregrounded = true;
        final Set<String> paired = new HashSet<>();
        BluetoothHidModule.HidResult registerResult = BluetoothHidModule.HidResult.OK;
        BluetoothHidModule.HidResult connectResult = BluetoothHidModule.HidResult.OK;
        boolean registered;
        String connected;
        String connectedName = "FakeHost";
        boolean sendOk = true;
        boolean disconnectOk = true;
        String registeredName;
        byte[] descriptor;
        long registerTimeoutMs = -1;
        long connectTimeoutMs = -1;
        String disconnected;
        int unregisterCalls;
        int closeCalls;
        final List<Integer> reportIds = new ArrayList<>();
        final List<byte[]> reports = new ArrayList<>();

        @Override
        public String unavailableError() {
            return unavailable;
        }

        @Override
        public boolean foregrounded() {
            return foregrounded;
        }

        @Override
        public boolean paired(String address) {
            return paired.contains(address);
        }

        @Override
        public BluetoothHidModule.HidResult register(
                String name, byte[] descriptor, long timeoutMillis) {
            registeredName = name;
            this.descriptor = descriptor;
            registerTimeoutMs = timeoutMillis;
            registered = registerResult == BluetoothHidModule.HidResult.OK;
            return registerResult;
        }

        @Override
        public BluetoothHidModule.HidResult connect(String address, long timeoutMillis) {
            connectTimeoutMs = timeoutMillis;
            if (connectResult == BluetoothHidModule.HidResult.OK) {
                connected = address;
            }
            return connectResult;
        }

        @Override
        public boolean disconnect(String address) {
            disconnected = address;
            if (disconnectOk) {
                connected = null;
            }
            return disconnectOk;
        }

        @Override
        public boolean registered() {
            return registered;
        }

        @Override
        public String connectedAddress() {
            return connected;
        }

        @Override
        public String connectedName() {
            return connected == null ? null : connectedName;
        }

        @Override
        public boolean sendReport(int reportId, byte[] data) {
            reportIds.add(reportId);
            reports.add(Arrays.copyOf(data, data.length));
            return sendOk;
        }

        @Override
        public void unregister() {
            unregisterCalls++;
            registered = false;
            connected = null;
        }

        @Override
        public void close() {
            closeCalls++;
            unregister();
        }
    }
}
