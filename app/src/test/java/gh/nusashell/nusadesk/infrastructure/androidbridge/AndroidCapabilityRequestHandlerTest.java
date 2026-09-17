package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AndroidCapabilityRequestHandlerTest {

    private static final BatteryStatus BATTERY = new BatteryStatus(
            true, 73, "charging", "good", "usb", true,
            275, 4_200_000L, 450_000L, 1_234_000L, 5_000_000_000L);

    @Test
    public void rejectsMissingMalformedAndWrongTokenBeforeDispatch() {
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> {
            throw new AssertionError("battery source must not be called");
        }, kind -> {
            throw new AssertionError("sensor source must not be called");
        }, () -> {
            throw new AssertionError("location source must not be called");
        });

        AndroidCapabilityProtocol.Response missing = handler.handle(null);
        assertFalse(missing.isOk());
        assertEquals("malformed-request", missing.getError());

        AndroidCapabilityProtocol.Request wrong = AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"1\",\"token\":\"wrong\","
                        + "\"method\":\"battery.status\"}");
        AndroidCapabilityProtocol.Response unauthorized = handler.handle(wrong);
        assertFalse(unauthorized.isOk());
        assertEquals("unauthorized", unauthorized.getError());
    }

    @Test
    public void exposesInfoWithEveryWiredCapability() {
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable());

        AndroidCapabilityProtocol.Response info = handler.handle(request("1", "bridge.info"));
        assertTrue(info.isOk());
        assertEquals("tcp-loopback", info.getFields().get("transport"));
        assertEquals("battery.status,sensor.accelerometer,sensor.gyroscope,location.get,"
                        + "contacts.list,calllog.list,sms.inbox,"
                        + "telephony.info,telephony.cellinfo,location.stream.start,"
                        + "location.stream.poll,location.stream.stop,"
                        + "media.start,media.camera.start,media.microphone.start,"
                        + "media.status,media.stop,"
                        + "calendar.list,calendar.insert,calendar.update,calendar.delete",
                info.getFields().get("capabilities"));

        AndroidCapabilityProtocol.Response battery = handler.handle(
                request("2", "battery.status"));
        assertTrue(battery.isOk());
        Map<String, Object> fields = battery.getFields();
        assertEquals(73L, fields.get("capacity_percent"));
        assertEquals("charging", fields.get("status"));
        assertEquals(4_200_000L, fields.get("voltage_microvolts"));
        assertEquals(true, fields.get("present"));

        AndroidCapabilityProtocol.Response unsupported = handler.handle(
                request("3", "shell.exec"));
        assertFalse(unsupported.isOk());
        assertEquals("unsupported-method", unsupported.getError());
    }

    @Test
    public void dispatchesAuthorizedSensorMethodsToTheirKinds() {
        SensorReadingSource source = kind -> {
            switch (kind) {
                case ACCELEROMETER:
                    return SensorReading.reading("accelerometer", 0.5, -9.81, 0.25,
                            3, 1_700_000_000_123_456L);
                case GYROSCOPE:
                    return SensorReading.reading("gyroscope", -0.01, 0.02, 0.001,
                            1, 1_700_000_000_999_999L);
                default:
                    throw new AssertionError("unexpected kind: " + kind);
            }
        };
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> BATTERY,
                source, () -> LocationSnapshot.unavailable());

        AndroidCapabilityProtocol.Response accelerometer = handler.handle(
                request("1", "sensor.accelerometer"));
        assertTrue(accelerometer.isOk());
        Map<String, Object> accelFields = accelerometer.getFields();
        assertEquals("accelerometer", accelFields.get("sensor"));
        assertEquals(true, accelFields.get("available"));
        assertEquals(0.5, (Double) accelFields.get("x"), 0.0);
        assertEquals(-9.81, (Double) accelFields.get("y"), 0.0);
        assertEquals(0.25, (Double) accelFields.get("z"), 0.0);
        assertEquals("high", accelFields.get("accuracy"));
        assertEquals(1_700_000_000_123_456L, accelFields.get("timestamp"));

        AndroidCapabilityProtocol.Response gyroscope = handler.handle(
                request("2", "sensor.gyroscope"));
        assertTrue(gyroscope.isOk());
        Map<String, Object> gyroFields = gyroscope.getFields();
        assertEquals("gyroscope", gyroFields.get("sensor"));
        assertEquals(-0.01, (Double) gyroFields.get("x"), 0.0);
        assertEquals(0.02, (Double) gyroFields.get("y"), 0.0);
        assertEquals(0.001, (Double) gyroFields.get("z"), 0.0);
        assertEquals("low", gyroFields.get("accuracy"));
    }

    @Test
    public void reportsSensorUnavailableTimeoutAndFailureAsTypedErrors() {
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> BATTERY, kind -> {
            switch (kind) {
                case ACCELEROMETER:
                    return SensorReading.unavailable("accelerometer");
                case GYROSCOPE:
                    return SensorReading.timeout("gyroscope");
                default:
                    throw new AssertionError("unexpected kind: " + kind);
            }
        }, () -> LocationSnapshot.unavailable());

        AndroidCapabilityProtocol.Response unavailable = handler.handle(
                request("1", "sensor.accelerometer"));
        assertFalse(unavailable.isOk());
        assertEquals("sensor-unavailable", unavailable.getError());

        AndroidCapabilityProtocol.Response timeout = handler.handle(
                request("2", "sensor.gyroscope"));
        assertFalse(timeout.isOk());
        assertEquals("sensor-timeout", timeout.getError());
    }

    @Test
    public void convertsCapabilityFailuresToBoundedErrors() {
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> {
            throw new IllegalStateException("platform detail must not cross boundary");
        }, kind -> {
            throw new IllegalStateException("platform detail must not cross boundary");
        }, () -> {
            throw new IllegalStateException("platform detail must not cross boundary");
        });
        AndroidCapabilityProtocol.Response batteryResponse = handler.handle(
                request("1", "battery.status"));
        assertFalse(batteryResponse.isOk());
        assertEquals("capability-unavailable", batteryResponse.getError());
        AndroidCapabilityProtocol.Response sensorResponse = handler.handle(
                request("2", "sensor.accelerometer"));
        assertFalse(sensorResponse.isOk());
        assertEquals("capability-unavailable", sensorResponse.getError());
    }

    @Test
    public void dispatchesAuthorizedLocationGetToTheLocationSource() {
        LocationSnapshot fix = LocationSnapshot.reading(
                -6.9175, 107.6191, 12.5, "gps", 1_700_000_000_000L,
                768.25, 1.75, 42.0);
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()), () -> fix);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "location.get"));
        assertTrue(response.isOk());
        Map<String, Object> fields = response.getFields();
        assertEquals(true, fields.get("available"));
        assertEquals("gps", fields.get("provider"));
        assertEquals(-6.9175, (Double) fields.get("latitude"), 0.0);
        assertEquals(107.6191, (Double) fields.get("longitude"), 0.0);
        assertEquals(12.5, (Double) fields.get("accuracy_meters"), 0.0);
        assertEquals(1_700_000_000_000L, fields.get("timestamp_utc_ms"));
        assertEquals(768.25, (Double) fields.get("altitude_meters"), 0.0);
        assertEquals(1.75, (Double) fields.get("speed_meters_per_second"), 0.0);
        assertEquals(42.0, (Double) fields.get("bearing_degrees"), 0.0);
    }

    @Test
    public void reportsLocationPermissionUnavailableTimeoutAndErrorAsTypedErrors() {
        AndroidCapabilityRequestHandler states = handler("right-token", () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()), stateSource());

        assertError(states, "1", "location.get", "location-permission-required");
        assertError(states, "2", "location.get", "location-permission-denied");
        assertError(states, "3", "location.get", "location-unavailable");
        assertError(states, "4", "location.get", "location-timeout");
        assertError(states, "5", "location.get", "capability-unavailable");
    }

    @Test
    public void neverDispatchesWithoutAuth() {
        AndroidCapabilityRequestHandler handler = handler("right-token", () -> {
            throw new AssertionError("battery source must not be called");
        }, kind -> {
            throw new AssertionError("sensor source must not be called");
        }, () -> {
            throw new AssertionError("location source must not be called");
        });
        AndroidCapabilityProtocol.Request request = AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"1\",\"token\":\"wrong\","
                        + "\"method\":\"location.get\"}");
        AndroidCapabilityProtocol.Response response = handler.handle(request);
        assertFalse(response.isOk());
        assertEquals("unauthorized", response.getError());
    }

    @Test
    public void messagingReadsEncodeBoundedRowsWithCountAndTruncation() {
        ContactsSource contacts = query -> ContactsSnapshot.reading(Arrays.asList(
                new ContactsSnapshot.ContactEntry("Alice", Arrays.asList("08123", "08124")),
                new ContactsSnapshot.ContactEntry("Bob", Collections.singletonList("08125"))),
                false);
        CallLogSource callLog = query -> CallLogSnapshot.reading(Arrays.asList(
                new CallLogSnapshot.CallLogEntry("08123", "Alice",
                        CallLogSnapshot.CallType.INCOMING, 1_700_000_000_000L, 12L)),
                false);
        SmsSource sms = query -> SmsSnapshot.reading(Arrays.asList(
                new SmsSnapshot.SmsEntry("08123", 1_700_000_000_000L, true,
                        "hello {world} \"quoted\" \\ backslash")), false);
        TelephonyInfoSource telephonyInfo = () -> TelephonyDeviceInfo.reading(
                "gsm", "ready", "lte", "lte");
        TelephonyCellSource cellInfo = () -> TelephonyCellInfo.reading(Arrays.asList(
                new TelephonyCellInfo.CellEntry("lte", -95, 2)), false);

        AndroidCapabilityRequestHandler handler = handler("right-token", contacts,
                callLog, sms, telephonyInfo, cellInfo);

        AndroidCapabilityProtocol.Response contactsResponse = handler.handle(
                request("1", "contacts.list"));
        assertTrue(contactsResponse.isOk());
        Map<String, Object> contactsFields = contactsResponse.getFields();
        assertEquals(2L, contactsFields.get("count"));
        assertEquals(false, contactsFields.get("truncated"));
        assertEquals("[{\"name\":\"Alice\",\"number_1\":\"08123\",\"number_2\":\"08124\"},"
                        + "{\"name\":\"Bob\",\"number_1\":\"08125\"}]",
                contactsFields.get("rows"));

        AndroidCapabilityProtocol.Response callLogResponse = handler.handle(
                request("2", "calllog.list"));
        assertTrue(callLogResponse.isOk());
        assertEquals(1L, callLogResponse.getFields().get("count"));
        assertEquals("[{\"number\":\"08123\",\"name\":\"Alice\",\"type\":\"incoming\","
                        + "\"timestamp_utc_ms\":1700000000000,\"duration_seconds\":12}]",
                callLogResponse.getFields().get("rows"));

        AndroidCapabilityProtocol.Response smsResponse = handler.handle(
                request("3", "sms.inbox"));
        assertTrue(smsResponse.isOk());
        assertEquals(1L, smsResponse.getFields().get("count"));
        assertEquals("[{\"address\":\"08123\",\"timestamp_utc_ms\":1700000000000,"
                        + "\"read\":true,\"snippet\":\"hello {world} \\\"quoted\\\" "
                        + "\\\\ backslash\"}]",
                smsResponse.getFields().get("rows"));

        AndroidCapabilityProtocol.Response infoResponse = handler.handle(
                request("4", "telephony.info"));
        assertTrue(infoResponse.isOk());
        assertEquals("gsm", infoResponse.getFields().get("phone_type"));
        assertEquals("ready", infoResponse.getFields().get("sim_state"));
        assertEquals("lte", infoResponse.getFields().get("network_type"));
        assertEquals("lte", infoResponse.getFields().get("data_network_type"));

        AndroidCapabilityProtocol.Response cellResponse = handler.handle(
                request("5", "telephony.cellinfo"));
        assertTrue(cellResponse.isOk());
        assertEquals(1L, cellResponse.getFields().get("count"));
        assertEquals("[{\"technology\":\"lte\",\"signal_dbm\":-95,\"signal_level\":2}]",
                cellResponse.getFields().get("rows"));
    }

    @Test
    public void messagingReadsMapPermissionUnavailableNoTelephonyAndErrorToTypedErrors() {
        final int[] contactsCalls = {0};
        final int[] callLogCalls = {0};
        final int[] smsCalls = {0};
        final int[] telephonyInfoCalls = {0};
        final int[] cellInfoCalls = {0};
        AndroidCapabilityRequestHandler handler = handler("right-token",
                query -> contactsCalls[0]++ == 0
                        ? ContactsSnapshot.permissionRequired() : ContactsSnapshot.error(),
                query -> callLogCalls[0]++ == 0
                        ? CallLogSnapshot.permissionDenied() : CallLogSnapshot.error(),
                query -> smsCalls[0]++ == 0
                        ? SmsSnapshot.unavailable() : SmsSnapshot.error(),
                () -> telephonyInfoCalls[0]++ == 0
                        ? TelephonyDeviceInfo.noTelephony() : TelephonyDeviceInfo.error(),
                () -> cellInfoCalls[0]++ == 0
                        ? TelephonyCellInfo.noTelephony() : TelephonyCellInfo.permissionRequired());

        assertError(handler, "1", "contacts.list", "contacts-permission-required");
        assertError(handler, "2", "calllog.list", "calllog-permission-denied");
        assertError(handler, "3", "sms.inbox", "sms-unavailable");
        assertError(handler, "4", "telephony.info", "telephony-no-telephony");
        assertError(handler, "5", "telephony.cellinfo", "cellinfo-no-telephony");
        assertError(handler, "6", "contacts.list", "capability-unavailable");
        assertError(handler, "7", "calllog.list", "capability-unavailable");
        assertError(handler, "8", "telephony.cellinfo", "cellinfo-permission-required");
    }

    @Test
    public void messagingReadFailuresNeverExposePlatformExceptions() {
        ContactsSource contacts = query -> {
            throw new IllegalStateException("provider detail must not cross boundary");
        };
        AndroidCapabilityRequestHandler handler = handler("right-token", contacts,
                query -> {
                    throw new IllegalStateException("provider detail must not cross boundary");
                }, query -> {
                    throw new IllegalStateException("provider detail must not cross boundary");
                }, () -> {
                    throw new IllegalStateException("provider detail must not cross boundary");
                }, () -> {
                    throw new IllegalStateException("provider detail must not cross boundary");
                });

        assertError(handler, "1", "contacts.list", "capability-unavailable");
        assertError(handler, "2", "calllog.list", "capability-unavailable");
        assertError(handler, "3", "sms.inbox", "capability-unavailable");
        assertError(handler, "4", "telephony.info", "capability-unavailable");
        assertError(handler, "5", "telephony.cellinfo", "capability-unavailable");
    }

    @Test
    public void sideEffectMethodsMapToActionUnsupportedAndAreNeverDispatched() {
        AndroidCapabilityRequestHandler handler = handler("right-token", query -> {
            throw new AssertionError("sms source must not be called for sms.send");
        });
        AndroidCapabilityProtocol.Response smsSend = handler.handle(
                request("1", "sms.send"));
        assertFalse(smsSend.isOk());
        assertEquals("action-unsupported", smsSend.getError());

        AndroidCapabilityProtocol.Response phoneCall = handler.handle(
                request("2", "phone.call"));
        assertFalse(phoneCall.isOk());
        assertEquals("action-unsupported", phoneCall.getError());
    }

    @Test
    public void locationStreamStartUsesTheFixedBoundedRequest() {
        FakeLocationStream stream = new FakeLocationStream();
        AndroidCapabilityRequestHandler handler = handler("right-token", stream);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "location.stream.start"));
        assertTrue(response.isOk());
        assertEquals(true, response.getFields().get("stream_started"));
        assertEquals(1, stream.startCalls);
        assertNotNull(stream.lastRequest);
        assertEquals(AndroidCapabilityRequestHandler.LOCATION_STREAM_INTERVAL_MILLIS,
                stream.lastRequest.getIntervalMillis());
        assertEquals(LocationStreamRequest.Accuracy.FINE, stream.lastRequest.getAccuracy());
    }

    @Test
    public void locationStreamStartMapsTypedFailuresAndAlreadyStarted() {
        FakeLocationStream stream = new FakeLocationStream();
        AndroidCapabilityRequestHandler handler = handler("right-token", stream);

        stream.failWith = LocationStreamEvent.permissionRequired();
        assertError(handler, "1", "location.stream.start",
                "location-stream-permission-required");

        stream.failWith = LocationStreamEvent.permissionDenied();
        assertError(handler, "2", "location.stream.start",
                "location-stream-permission-denied");

        stream.failWith = LocationStreamEvent.unavailable();
        assertError(handler, "3", "location.stream.start", "location-stream-unavailable");

        stream.failWith = LocationStreamEvent.error();
        assertError(handler, "4", "location.stream.start", "capability-unavailable");

        stream.startFailure = new IllegalStateException("already streaming");
        stream.state = FakeLocationStream.State.STREAMING;
        assertError(handler, "5", "location.stream.start",
                "location-stream-already-started");

        stream.startFailure = new IllegalStateException("session is closed");
        stream.state = FakeLocationStream.State.CLOSED;
        assertError(handler, "6", "location.stream.start", "location-stream-closed");
    }

    @Test
    public void locationStreamPollMapsReadingEmptyStoppedAndTypedStates() {
        FakeLocationStream stream = new FakeLocationStream();
        AndroidCapabilityRequestHandler handler = handler("right-token", stream);

        LocationSnapshot fix = LocationSnapshot.reading(
                -6.9175, 107.6191, 12.5, "gps", 1_700_000_000_000L, null, null, null);
        stream.enqueue(LocationStreamEvent.reading(fix));
        AndroidCapabilityProtocol.Response reading = handler.handle(
                request("1", "location.stream.poll"));
        assertTrue(reading.isOk());
        assertEquals(true, reading.getFields().get("available"));
        assertEquals(-6.9175, (Double) reading.getFields().get("latitude"), 0.0);

        AndroidCapabilityProtocol.Response empty = handler.handle(
                request("2", "location.stream.poll"));
        assertTrue(empty.isOk());
        assertEquals(true, empty.getFields().get("stream_empty"));
        assertEquals(AndroidCapabilityRequestHandler.LOCATION_POLL_TIMEOUT_MILLIS,
                stream.lastPollTimeoutMillis);

        stream.enqueue(LocationStreamEvent.stopped());
        AndroidCapabilityProtocol.Response stopped = handler.handle(
                request("3", "location.stream.poll"));
        assertTrue(stopped.isOk());
        assertEquals(true, stopped.getFields().get("stream_stopped"));

        stream.enqueue(LocationStreamEvent.timeout());
        assertError(handler, "4", "location.stream.poll", "location-stream-timeout");
        stream.enqueue(LocationStreamEvent.unavailable());
        assertError(handler, "5", "location.stream.poll", "location-stream-unavailable");
        stream.enqueue(LocationStreamEvent.permissionDenied());
        assertError(handler, "6", "location.stream.poll", "location-stream-permission-denied");
        stream.enqueue(LocationStreamEvent.error());
        assertError(handler, "7", "location.stream.poll", "capability-unavailable");
    }

    @Test
    public void locationStreamStopReportsTheExplicitStoppedState() {
        FakeLocationStream stream = new FakeLocationStream();
        AndroidCapabilityRequestHandler handler = handler("right-token", stream);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "location.stream.stop"));
        assertTrue(response.isOk());
        assertEquals(true, response.getFields().get("stream_stopped"));
        assertEquals(1, stream.stopCalls);
    }

    private static void assertError(AndroidCapabilityRequestHandler handler,
                                    String id, String method, String expectedError) {
        AndroidCapabilityProtocol.Response response = handler.handle(request(id, method));
        assertFalse(response.isOk());
        assertEquals(expectedError, response.getError());
    }

    private static LocationSource stateSource() {
        final int[] calls = {0};
        return () -> {
            switch (calls[0]++) {
                case 0:
                    return LocationSnapshot.permissionRequired();
                case 1:
                    return LocationSnapshot.permissionDenied();
                case 2:
                    return LocationSnapshot.unavailable();
                case 3:
                    return LocationSnapshot.timeout();
                default:
                    return LocationSnapshot.error();
            }
        };
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"right-token\","
                        + "\"method\":\"" + method + "\"}");
    }

    /** Request carrying a raw bounded {@code params} object. */
    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                             String paramsJson) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"right-token\","
                        + "\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
    }

    /** Calendar read fake: scriptable snapshot and observable query. */
    private static final class FakeCalendarSource implements CalendarSource {
        CalendarEventSnapshot result = CalendarEventSnapshot.reading(
                java.util.Collections.emptyList(), false);
        RuntimeException failure;
        CalendarQuery lastQuery;
        int calls;

        @Override
        public CalendarEventSnapshot read(CalendarQuery query) {
            calls++;
            lastQuery = query;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** Calendar write fake: scriptable result and recorded validated requests. */
    private static final class FakeCalendarWriter implements CalendarWriter {
        CalendarWriteResult result = CalendarWriteResult.ok(7L);
        RuntimeException failure;
        final java.util.List<CalendarWriteRequest> requests = new java.util.ArrayList<>();

        @Override
        public CalendarWriteResult insert(CalendarWriteRequest request) {
            return record(request);
        }

        @Override
        public CalendarWriteResult update(CalendarWriteRequest request) {
            return record(request);
        }

        @Override
        public CalendarWriteResult delete(CalendarWriteRequest request) {
            return record(request);
        }

        private CalendarWriteResult record(CalendarWriteRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** Handler with a default non-interacting fake for every new capability source. */
    private static AndroidCapabilityRequestHandler handler(String token,
                                                           BatteryStatusSource battery,
                                                           SensorReadingSource sensor,
                                                           LocationSource location) {
        return new AndroidCapabilityRequestHandler(token, battery, sensor, location,
                query -> ContactsSnapshot.unavailable(),
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                () -> TelephonyDeviceInfo.unavailable(),
                () -> TelephonyCellInfo.unavailable(),
                new FakeLocationStream(), new FakeLiveMediaController(),
                new FakeCalendarSource(), new FakeCalendarWriter());
    }

    private static AndroidCapabilityRequestHandler handler(String token,
                                                           ContactsSource contacts,
                                                           CallLogSource callLog,
                                                           SmsSource sms,
                                                           TelephonyInfoSource telephonyInfo,
                                                           TelephonyCellSource cellInfo) {
        return new AndroidCapabilityRequestHandler(token, () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable(),
                contacts, callLog, sms, telephonyInfo,
                cellInfo, new FakeLocationStream(), new FakeLiveMediaController(),
                new FakeCalendarSource(), new FakeCalendarWriter());
    }

    private static AndroidCapabilityRequestHandler handler(String token, ContactsSource contacts) {
        return new AndroidCapabilityRequestHandler(token, () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable(),
                contacts,
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                () -> TelephonyDeviceInfo.unavailable(),
                () -> TelephonyCellInfo.unavailable(),
                new FakeLocationStream(), new FakeLiveMediaController(),
                new FakeCalendarSource(), new FakeCalendarWriter());
    }

    private static AndroidCapabilityRequestHandler handler(String token,
                                                           FakeLocationStream stream) {
        return new AndroidCapabilityRequestHandler(token, () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable(),
                query -> ContactsSnapshot.unavailable(),
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                () -> TelephonyDeviceInfo.unavailable(),
                () -> TelephonyCellInfo.unavailable(),
                stream, new FakeLiveMediaController(),
                new FakeCalendarSource(), new FakeCalendarWriter());
    }

    private static AndroidCapabilityRequestHandler handler(String token,
                                                           FakeLiveMediaController media) {
        return new AndroidCapabilityRequestHandler(token, () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable(),
                query -> ContactsSnapshot.unavailable(),
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                () -> TelephonyDeviceInfo.unavailable(),
                () -> TelephonyCellInfo.unavailable(),
                new FakeLocationStream(), media,
                new FakeCalendarSource(), new FakeCalendarWriter());
    }

    private static AndroidCapabilityRequestHandler calendarHandler(
            FakeCalendarSource source, FakeCalendarWriter writer) {
        return new AndroidCapabilityRequestHandler("right-token", () -> BATTERY,
                kind -> SensorReading.unavailable(kind.getName()),
                () -> LocationSnapshot.unavailable(),
                query -> ContactsSnapshot.unavailable(),
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                () -> TelephonyDeviceInfo.unavailable(),
                () -> TelephonyCellInfo.unavailable(),
                new FakeLocationStream(), new FakeLiveMediaController(),
                source, writer);
    }

    @Test
    public void calendarListReturnsBoundedRowsAndUsesTheSevenDayWindow() {
        FakeCalendarSource source = new FakeCalendarSource();
        long begin = System.currentTimeMillis() + 3_600_000L;
        source.result = CalendarEventSnapshot.reading(java.util.List.of(
                new CalendarEventSnapshot.EventEntry(11L, "Rapat", begin,
                        begin + 3_600_000L, false, 3L, "Pribadi",
                        "Asia/Jakarta", "Ruang 2")), false);
        AndroidCapabilityRequestHandler handler =
                calendarHandler(source, new FakeCalendarWriter());

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "calendar.list"));

        assertTrue(response.isOk());
        assertEquals(true, response.getFields().get("available"));
        assertEquals(1L, response.getFields().get("count"));
        assertEquals(false, response.getFields().get("truncated"));
        String rows = String.valueOf(response.getFields().get("rows"));
        assertTrue("rows carry the event", rows.contains("\"title\":\"Rapat\""));
        assertTrue(rows.contains("\"calendar_name\":\"Pribadi\""));
        assertTrue("attendees never cross the bridge",
                !rows.contains("attendee") && !rows.contains("email"));
        assertEquals("the guest cannot choose a window", 1, source.calls);
        long window = source.lastQuery.getEndMillis() - source.lastQuery.getBeginMillis();
        assertEquals(CalendarQuery.DEFAULT_WINDOW_MILLIS, window);
    }

    @Test
    public void calendarListMapsPermissionAndProviderStatesToTypedErrors() {
        FakeCalendarSource source = new FakeCalendarSource();
        AndroidCapabilityRequestHandler handler =
                calendarHandler(source, new FakeCalendarWriter());

        source.result = CalendarEventSnapshot.permissionRequired();
        assertError(handler, "1", "calendar.list", "calendar-permission-required");
        source.result = CalendarEventSnapshot.permissionDenied();
        assertError(handler, "2", "calendar.list", "calendar-permission-denied");
        source.result = CalendarEventSnapshot.unavailable();
        assertError(handler, "3", "calendar.list", "calendar-unavailable");
        source.result = CalendarEventSnapshot.error();
        assertError(handler, "4", "calendar.list", "capability-unavailable");
        source.failure = new IllegalStateException("provider detail must not cross");
        assertError(handler, "5", "calendar.list", "capability-unavailable");
    }

    @Test
    public void calendarInsertAppliesValidatedParamsAndReportsTheEventId() {
        FakeCalendarWriter writer = new FakeCalendarWriter();
        writer.result = CalendarWriteResult.ok(42L);
        AndroidCapabilityRequestHandler handler =
                calendarHandler(new FakeCalendarSource(), writer);
        long begin = System.currentTimeMillis() + 3_600_000L;

        AndroidCapabilityProtocol.Response response = handler.handle(request("1",
                "calendar.insert", "{\"title\":\"Rapat\",\"begin_ms\":" + begin
                        + ",\"end_ms\":" + (begin + 3_600_000L) + "}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("written"));
        assertEquals(42L, response.getFields().get("event_id"));
        assertEquals(1, writer.requests.size());
        CalendarWriteRequest recorded = writer.requests.get(0);
        assertEquals(CalendarWriteRequest.Op.INSERT, recorded.getOp());
        assertEquals("Rapat", recorded.getTitle());
        assertEquals(Long.valueOf(begin), recorded.getBeginMillis());
        assertEquals(Long.valueOf(begin + 3_600_000L), recorded.getEndMillis());
    }

    @Test
    public void calendarWriteRejectsInvalidParamsWithATypedCode() {
        FakeCalendarWriter writer = new FakeCalendarWriter();
        AndroidCapabilityRequestHandler handler =
                calendarHandler(new FakeCalendarSource(), writer);
        long begin = System.currentTimeMillis() + 3_600_000L;

        // Missing title.
        assertParamsError(handler, "1", "calendar.insert",
                "{\"begin_ms\":" + begin + ",\"end_ms\":" + (begin + 60_000L) + "}");
        // End before begin.
        assertParamsError(handler, "2", "calendar.insert",
                "{\"title\":\"X\",\"begin_ms\":" + begin + ",\"end_ms\":"
                        + (begin - 60_000L) + "}");
        // Over-long title.
        assertParamsError(handler, "3", "calendar.insert",
                "{\"title\":\"" + "x".repeat(201) + "\",\"begin_ms\":" + begin
                        + ",\"end_ms\":" + (begin + 60_000L) + "}");
        // Unknown key fails closed.
        assertParamsError(handler, "4", "calendar.insert",
                "{\"title\":\"X\",\"begin_ms\":" + begin + ",\"end_ms\":"
                        + (begin + 60_000L) + ",\"attendees\":\"a@b.c\"}");
        // Wrong type.
        assertParamsError(handler, "5", "calendar.delete", "{\"event_id\":\"7\"}");
        // Too far in the future.
        assertParamsError(handler, "6", "calendar.insert",
                "{\"title\":\"X\",\"begin_ms\":"
                        + (System.currentTimeMillis() + 400L * 24L * 3600_000L)
                        + ",\"end_ms\":"
                        + (System.currentTimeMillis() + 400L * 24L * 3600_000L + 60_000L) + "}");
        assertEquals("no invalid request reaches the writer", 0, writer.requests.size());
    }

    @Test
    public void paramsOnAMethodThatDeclaresNoneAreRejected() {
        AndroidCapabilityRequestHandler handler = handler("right-token",
                new FakeLiveMediaController());

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "media.status", "{\"title\":\"X\"}"));

        assertFalse(response.isOk());
        assertEquals("unsupported-parameter", response.getError());
    }

    @Test
    public void calendarWriteFailureStaysATypedCode() {
        FakeCalendarWriter writer = new FakeCalendarWriter();
        writer.result = CalendarWriteResult.failed(CalendarWriteResult.ERROR_READ_ONLY);
        AndroidCapabilityRequestHandler handler =
                calendarHandler(new FakeCalendarSource(), writer);

        assertParamsError(handler, "1", "calendar.delete", "{\"event_id\":7}",
                "calendar-read-only");

        writer.failure = new IllegalStateException("provider detail must not cross");
        assertParamsError(handler, "2", "calendar.delete", "{\"event_id\":7}",
                "calendar-failed");
    }

    /** Assert a typed error for a request that carries bounded params. */
    private static void assertParamsError(AndroidCapabilityRequestHandler handler,
                                          String id, String method, String paramsJson) {
        assertParamsError(handler, id, method, paramsJson, "calendar-invalid-argument");
    }

    private static void assertParamsError(AndroidCapabilityRequestHandler handler,
                                          String id, String method, String paramsJson,
                                          String expectedError) {
        AndroidCapabilityProtocol.Response response = handler.handle(
                request(id, method, paramsJson));
        assertFalse("expected a typed error for " + method + " " + paramsJson,
                response.isOk());
        assertEquals(expectedError, response.getError());
    }

    @Test
    public void mediaStartSuccessCarriesTheFlatRunningContractFields() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        media.startResult = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:39871/", "h264", 1280, 720, 30, "aac", 2);
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "media.start"));
        assertTrue(response.isOk());
        Map<String, Object> fields = response.getFields();
        assertEquals("running", fields.get("state"));
        assertEquals("both", fields.get("mode"));
        assertEquals("rtsp://127.0.0.1:39871/", fields.get("rtsp_url"));
        assertEquals("h264", fields.get("video_codec"));
        assertEquals("aac", fields.get("audio_codec"));
        assertEquals(1280L, fields.get("video_width"));
        assertEquals(720L, fields.get("video_height"));
        assertEquals(30L, fields.get("video_fps"));
        assertEquals(2L, fields.get("client_limit"));
        assertFalse("the success response must not carry a file path",
                fields.keySet().stream().anyMatch(key -> key.contains("path")));
        assertEquals(1, media.startCalls);
        assertEquals("media.start is the combined mode",
                LiveMediaMode.BOTH, media.lastStartMode);
    }

    @Test
    public void mediaCameraStartUsesTheCameraModeAndOmitsAudioFields() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        media.startResult = LiveMediaStatus.running(LiveMediaMode.CAMERA,
                "rtsp://127.0.0.1:39871/", "h264", 1280, 720, 30, null, 2);
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "media.camera.start"));

        assertTrue(response.isOk());
        assertEquals(LiveMediaMode.CAMERA, media.lastStartMode);
        assertEquals("camera", response.getFields().get("mode"));
        assertEquals("h264", response.getFields().get("video_codec"));
        assertFalse("a camera-only response carries no audio codec",
                response.getFields().containsKey("audio_codec"));
    }

    @Test
    public void mediaMicrophoneStartUsesTheMicrophoneModeAndOmitsVideoFields() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        media.startResult = LiveMediaStatus.running(LiveMediaMode.MICROPHONE,
                "rtsp://127.0.0.1:39871/", null, 0, 0, 0, "aac", 2);
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "media.microphone.start"));

        assertTrue(response.isOk());
        assertEquals(LiveMediaMode.MICROPHONE, media.lastStartMode);
        assertEquals("microphone", response.getFields().get("mode"));
        assertEquals("aac", response.getFields().get("audio_codec"));
        assertFalse("a microphone-only response carries no video codec",
                response.getFields().containsKey("video_codec"));
        assertFalse(response.getFields().containsKey("video_width"));
    }

    @Test
    public void mediaModeConflictIsATypedErrorOnEveryStartMethod() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        media.startResult = LiveMediaStatus.failed(LiveMediaMode.CAMERA,
                LiveMediaError.MODE_CONFLICT);
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        assertError(handler, "1", "media.camera.start", "media-mode-conflict");
        assertError(handler, "2", "media.microphone.start", "media-mode-conflict");
    }

    @Test
    public void mediaStartMapsEveryTypedFailureToItsWireCode() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        media.startResult = LiveMediaStatus.failed(LiveMediaError.PERMISSION_REQUIRED);
        assertError(handler, "1", "media.start", "media-permission-required");
        media.startResult = LiveMediaStatus.failed(LiveMediaError.PERMISSION_DENIED);
        assertError(handler, "2", "media.start", "media-permission-denied");
        media.startResult = LiveMediaStatus.failed(LiveMediaError.FOREGROUND_REQUIRED);
        assertError(handler, "3", "media.start", "media-foreground-required");
        media.startResult = LiveMediaStatus.failed(LiveMediaError.UNAVAILABLE);
        assertError(handler, "4", "media.start", "media-unavailable");
        media.startResult = LiveMediaStatus.failed(LiveMediaError.BUSY);
        assertError(handler, "5", "media.start", "media-busy");
        media.startResult = LiveMediaStatus.failed(LiveMediaError.ENCODER_UNAVAILABLE);
        assertError(handler, "6", "media.start", "media-encoder-unavailable");
        media.startResult = LiveMediaStatus.failed(LiveMediaError.START_FAILED);
        assertError(handler, "7", "media.start", "media-start-failed");
    }

    @Test
    public void mediaStartControllerFailureMapsToTypedStartFailed() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        media.startFailure = new IllegalStateException("controller detail must not cross");
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        assertError(handler, "1", "media.start", "media-start-failed");
    }

    @Test
    public void mediaStatusIsAlwaysSuccessWithTheExplicitState() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        media.statusResult = LiveMediaStatus.stopped();
        AndroidCapabilityProtocol.Response stopped = handler.handle(
                request("1", "media.status"));
        assertTrue(stopped.isOk());
        assertEquals("stopped", stopped.getFields().get("state"));

        media.statusResult = LiveMediaStatus.starting(LiveMediaMode.CAMERA);
        AndroidCapabilityProtocol.Response starting = handler.handle(
                request("2", "media.status"));
        assertTrue(starting.isOk());
        assertEquals("starting", starting.getFields().get("state"));

        media.statusResult = LiveMediaStatus.failed(LiveMediaError.BUSY);
        AndroidCapabilityProtocol.Response failed = handler.handle(
                request("3", "media.status"));
        assertTrue("a failed media.status is still a success response",
                failed.isOk());
        assertEquals("failed", failed.getFields().get("state"));
        assertEquals("media-busy", failed.getFields().get("error"));

        media.statusFailure = new IllegalStateException("status detail must not cross");
        AndroidCapabilityProtocol.Response degraded = handler.handle(
                request("4", "media.status"));
        assertTrue(degraded.isOk());
        assertEquals("failed", degraded.getFields().get("state"));
        assertEquals("media-start-failed", degraded.getFields().get("error"));
    }

    @Test
    public void mediaStopIsIdempotentSuccessAndAlwaysReportsStopped() {
        FakeLiveMediaController media = new FakeLiveMediaController();
        media.stopFailure = new IllegalStateException("stop detail must not cross");
        AndroidCapabilityRequestHandler handler = handler("right-token", media);

        AndroidCapabilityProtocol.Response first = handler.handle(
                request("1", "media.stop"));
        assertTrue(first.isOk());
        assertEquals("stopped", first.getFields().get("state"));

        AndroidCapabilityProtocol.Response second = handler.handle(
                request("2", "media.stop"));
        assertTrue(second.isOk());
        assertEquals("stopped", second.getFields().get("state"));
        assertEquals(2, media.stopCalls);
    }

    /** Location stream fake: scriptable events, state, and start failures. */
    private static final class FakeLocationStream implements LocationStreamSession {
        final ArrayDeque<LocationStreamEvent> events = new ArrayDeque<>();
        State state = State.IDLE;
        LocationStreamRequest lastRequest;
        LocationStreamEvent failWith;
        RuntimeException startFailure;
        int startCalls;
        int stopCalls;
        long lastPollTimeoutMillis = -1L;

        void enqueue(LocationStreamEvent event) {
            events.add(event);
        }

        @Override
        public void start(LocationStreamRequest request) {
            startCalls++;
            lastRequest = request;
            if (startFailure != null) {
                throw startFailure;
            }
            if (failWith != null) {
                state = State.STOPPED;
                events.add(failWith);
                return;
            }
            state = State.STREAMING;
        }

        @Override
        public void stop() {
            stopCalls++;
            state = State.STOPPED;
            events.add(LocationStreamEvent.stopped());
        }

        @Override
        public void close() {
            state = State.CLOSED;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public LocationStreamEvent poll(long timeoutMillis) {
            lastPollTimeoutMillis = timeoutMillis;
            return events.poll();
        }

        @Override
        public LocationSnapshot latestReading() {
            return null;
        }
    }

    /** Live media fake: scriptable start/status results and failures. */
    private static final class FakeLiveMediaController implements LiveMediaController {
        LiveMediaStatus startResult = LiveMediaStatus.failed(LiveMediaError.START_FAILED);
        LiveMediaStatus statusResult = LiveMediaStatus.stopped();
        RuntimeException startFailure;
        RuntimeException statusFailure;
        RuntimeException stopFailure;
        int startCalls;
        int stopCalls;
        LiveMediaMode lastStartMode;
        boolean closed;

        @Override
        public LiveMediaStatus start(LiveMediaMode mode) {
            startCalls++;
            lastStartMode = mode;
            if (startFailure != null) {
                throw startFailure;
            }
            return startResult;
        }

        @Override
        public LiveMediaStatus status() {
            if (statusFailure != null) {
                throw statusFailure;
            }
            return statusResult;
        }

        @Override
        public void stop() {
            stopCalls++;
            if (stopFailure != null) {
                throw stopFailure;
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

}
