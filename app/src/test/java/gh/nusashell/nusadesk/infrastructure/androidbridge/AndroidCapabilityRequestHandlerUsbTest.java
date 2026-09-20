package gh.nusashell.nusadesk.infrastructure.androidbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * Focused coverage of the USB pass-through slice ({@code usb.list} /
 * {@code usb.open}) on the request handler: the response shape the guest
 * parses, the typed error mapping, and the parameter validation that keeps
 * the declared-params allowlist honest.
 */
public class AndroidCapabilityRequestHandlerUsbTest {

    private static final BatteryStatus BATTERY = new BatteryStatus(
            true, 73, "charging", "good", "usb", true,
            275, 4_200_000L, 450_000L, 1_234_000L, 5_000_000_000L);

    @Test
    public void usbListCarriesOneJsonDeviceArrayAndCount() {
        FakeUsb usb = new FakeUsb();
        usb.devices = List.of(
                new UsbPassThroughSource.UsbDeviceEntry(0x04e8, 0x6860,
                        "/dev/bus/usb/001/002", "samsung", "SAMSUNG_Android",
                        "ff4201,060101"));
        AndroidCapabilityRequestHandler handler = usbHandler(usb);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "usb.list"));

        assertTrue(response.isOk());
        assertEquals("[{\"vendorId\":1256,\"productId\":26720,"
                        + "\"name\":\"/dev/bus/usb/001/002\","
                        + "\"manufacturer\":\"samsung\",\"product\":\"SAMSUNG_Android\","
                        + "\"interfaces\":\"ff4201,060101\"}]",
                response.getFields().get("devices"));
        assertEquals(Integer.valueOf(1), response.getFields().get("count"));
    }

    @Test
    public void usbListKeepsAbsentDescriptorStringsAsJsonNull() {
        FakeUsb usb = new FakeUsb();
        usb.devices = List.of(new UsbPassThroughSource.UsbDeviceEntry(
                0x05e3, 0x0751, "/dev/bus/usb/001/003", null, null));
        AndroidCapabilityRequestHandler handler = usbHandler(usb);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "usb.list"));

        assertTrue(response.isOk());
        assertEquals("[{\"vendorId\":1507,\"productId\":1873,"
                        + "\"name\":\"/dev/bus/usb/001/003\","
                        + "\"manufacturer\":null,\"product\":null,"
                        + "\"interfaces\":null}]",
                response.getFields().get("devices"));
    }

    @Test
    public void usbListWithNoDevicesIsAnEmptyArray() {
        AndroidCapabilityRequestHandler handler = usbHandler(new FakeUsb());

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "usb.list"));

        assertTrue(response.isOk());
        assertEquals("[]", response.getFields().get("devices"));
        assertEquals(Integer.valueOf(0), response.getFields().get("count"));
    }

    @Test
    public void usbListPlatformFailureStaysABoundedError() {
        FakeUsb usb = new FakeUsb();
        usb.failure = new IllegalStateException("no usb service");
        AndroidCapabilityRequestHandler handler = usbHandler(usb);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "usb.list"));

        assertFalse(response.isOk());
        assertEquals("capability-unavailable", response.getError());
    }

    @Test
    public void usbOpenDeliversIdsAndSocketAndReportsOpened() {
        FakeUsb usb = new FakeUsb();
        usb.result = UsbPassThroughSource.OpenResult.of(
                UsbPassThroughSource.OpenState.OPENED, 0x04e8, 0x6860);
        AndroidCapabilityRequestHandler handler = usbHandler(usb);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "usb.open",
                        "{\"vendorId\":1256,\"productId\":26720,"
                                + "\"socket\":\"nu-usb-abcdef123456\"}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("opened"));
        assertEquals(Integer.valueOf(1256), response.getFields().get("vendorId"));
        assertEquals(Integer.valueOf(26720), response.getFields().get("productId"));
        assertEquals(1256, usb.lastVendorId);
        assertEquals(26720, usb.lastProductId);
        assertEquals("nu-usb-abcdef123456", usb.lastSocket);
    }

    @Test
    public void usbOpenMapsEveryTypedStateToItsErrorCode() {
        Object[][] cases = {
                {UsbPassThroughSource.OpenState.DEVICE_NOT_FOUND, "usb-device-not-found"},
                {UsbPassThroughSource.OpenState.PERMISSION_DENIED, "usb-permission-denied"},
                {UsbPassThroughSource.OpenState.PERMISSION_TIMEOUT, "usb-permission-timeout"},
                {UsbPassThroughSource.OpenState.OPEN_FAILED, "usb-open-failed"},
                {UsbPassThroughSource.OpenState.SOCKET_FAILED, "usb-socket-failed"},
        };
        for (Object[] item : cases) {
            FakeUsb usb = new FakeUsb();
            usb.result = UsbPassThroughSource.OpenResult.of(
                    (UsbPassThroughSource.OpenState) item[0], 0x04e8, 0x6860);
            AndroidCapabilityProtocol.Response response = usbHandler(usb).handle(
                    request("1", "usb.open",
                            "{\"vendorId\":1256,\"productId\":26720,"
                                    + "\"socket\":\"nu-usb-abcdef123456\"}"));

            assertFalse(String.valueOf(item[0]), response.isOk());
            assertEquals(String.valueOf(item[0]), item[1], response.getError());
        }
    }

    @Test
    public void usbOpenRejectsMalformedParametersAsInvalidArgument() {
        String[] badParams = {
                "{\"vendorId\":1256,\"productId\":26720}",                       // no socket
                "{\"productId\":26720,\"socket\":\"nu-usb-abcdef123456\"}",      // no vendorId
                "{\"vendorId\":70000,\"productId\":26720,\"socket\":\"nu-usb-1\"}", // id overflow
                "{\"vendorId\":-1,\"productId\":26720,\"socket\":\"nu-usb-1\"}",  // negative id
                "{\"vendorId\":\"04e8\",\"productId\":26720,\"socket\":\"nu-usb-1\"}", // string id
                "{\"vendorId\":1256,\"productId\":26720,\"socket\":\"nu/usb/1\"}", // unsafe socket
                "{\"vendorId\":1256,\"productId\":26720,\"socket\":\"\"}",        // empty socket
        };
        for (String params : badParams) {
            FakeUsb usb = new FakeUsb();
            AndroidCapabilityProtocol.Response response = usbHandler(usb).handle(
                    request("1", "usb.open", params));

            assertFalse(params, response.isOk());
            assertEquals(params, "invalid-argument", response.getError());
            assertEquals(params, -1, usb.lastVendorId);
        }
    }

    @Test
    public void usbOpenPlatformFailureAndNullResultAreBoundedErrors() {
        FakeUsb failing = new FakeUsb();
        failing.failure = new IllegalStateException("usb service gone");
        AndroidCapabilityProtocol.Response thrown = usbHandler(failing).handle(
                request("1", "usb.open",
                        "{\"vendorId\":1256,\"productId\":26720,"
                                + "\"socket\":\"nu-usb-abcdef123456\"}"));
        assertFalse(thrown.isOk());
        assertEquals("usb-open-failed", thrown.getError());

        FakeUsb nullResult = new FakeUsb();
        nullResult.result = null;
        AndroidCapabilityProtocol.Response absent = usbHandler(nullResult).handle(
                request("2", "usb.open",
                        "{\"vendorId\":1256,\"productId\":26720,"
                                + "\"socket\":\"nu-usb-abcdef123456\"}"));
        assertFalse(absent.isOk());
        assertEquals("usb-open-failed", absent.getError());
    }

    @Test
    public void usbListRejectsUndeclaredParamsButUsbOpenDeclaresThem() {
        AndroidCapabilityRequestHandler handler = usbHandler(new FakeUsb());

        AndroidCapabilityProtocol.Response rejected = handler.handle(
                request("1", "usb.list", "{\"vendorId\":1256}"));

        assertFalse(rejected.isOk());
        assertEquals("unsupported-parameter", rejected.getError());
    }

    @Test
    public void bridgeInfoAdvertisesTheUsbMethods() {
        AndroidCapabilityProtocol.Response info = usbHandler(new FakeUsb()).handle(
                request("1", "bridge.info"));

        assertTrue(info.isOk());
        String capabilities = (String) info.getFields().get("capabilities");
        assertTrue(capabilities.endsWith("usb.list,usb.open"));
    }

    /** USB source fake: scriptable devices/result with recorded open arguments. */
    private static final class FakeUsb implements UsbPassThroughSource {
        List<UsbDeviceEntry> devices = Collections.emptyList();
        OpenResult result = OpenResult.of(OpenState.OPENED, 0, 0);
        RuntimeException failure;
        int lastVendorId = -1;
        int lastProductId = -1;
        String lastSocket;

        @Override
        public List<UsbDeviceEntry> list() {
            if (failure != null) {
                throw failure;
            }
            return devices;
        }

        @Override
        public OpenResult open(int vendorId, int productId, String socketName) {
            lastVendorId = vendorId;
            lastProductId = productId;
            lastSocket = socketName;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** Off location stream: never used by the USB slice. */
    private static final class OffStream implements LocationStreamSession {
        @Override
        public void start(LocationStreamRequest request) {
            throw new IllegalStateException("not used");
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }

        @Override
        public State state() {
            return State.IDLE;
        }

        @Override
        public LocationStreamEvent poll(long timeoutMillis) {
            return null;
        }

        @Override
        public LocationSnapshot latestReading() {
            return null;
        }
    }

    /** Off live media controller: never used by the USB slice. */
    private static final class OffMedia implements LiveMediaController {
        @Override
        public LiveMediaStatus start(LiveMediaMode mode) {
            return LiveMediaStatus.stopped();
        }

        @Override
        public LiveMediaStatus status() {
            return LiveMediaStatus.stopped();
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }
    }

    private static AndroidCapabilityRequestHandler usbHandler(UsbPassThroughSource usb) {
        return new AndroidCapabilityRequestHandler("right-token", () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable(),
                query -> ContactsSnapshot.unavailable(),
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                () -> TelephonyDeviceInfo.unavailable(),
                () -> TelephonyCellInfo.unavailable(),
                new OffStream(), new OffMedia(),
                query -> CalendarEventSnapshot.reading(Collections.emptyList(), false),
                new CalendarWriter() {
                    @Override
                    public CalendarWriteResult insert(CalendarWriteRequest request) {
                        return CalendarWriteResult.ok(0L);
                    }

                    @Override
                    public CalendarWriteResult update(CalendarWriteRequest request) {
                        return CalendarWriteResult.ok(0L);
                    }

                    @Override
                    public CalendarWriteResult delete(CalendarWriteRequest request) {
                        return CalendarWriteResult.ok(0L);
                    }
                },
                usb);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"right-token\","
                        + "\"method\":\"" + method + "\"}");
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                             String paramsJson) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"right-token\","
                        + "\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
    }
}
