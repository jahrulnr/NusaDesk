package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authorizes one parsed request and dispatches only the allowlisted capability
 * methods. There is deliberately no command, URI, class, or shell dispatch,
 * and no method takes request parameters: the strict envelope ({@code v},
 * {@code id}, {@code token}, {@code method}) stays unchanged, so every guest
 * call is one fixed allowlist string with host-owned defaults.
 *
 * <p>The unified live media session ({@code media.start} / {@code media.status}
 * / {@code media.stop}) is live-only: Android captures camera + microphone
 * and serves one loopback RTSP stream to the guest, no capture artifact is
 * ever written, and no artifact directory or path is advertised or exposed.
 * The session is owned by the current bridge session and stops when the
 * bridge closes.</p>
 *
 * <p>The messaging/telephony/contacts reads are read-only with fixed
 * {@link MessagingQuery#all()} defaults; the reserved side-effecting methods
 * ({@code sms.send}, {@code phone.call}) map to a typed
 * {@code action-unsupported} and are never dispatched. The location stream is
 * a foreground-only pull contract ({@code location.stream.start} /
 * {@code location.stream.poll} / {@code location.stream.stop}) with one fixed
 * bounded request ({@link #LOCATION_STREAM_INTERVAL_MILLIS} ms, fine accuracy
 * that the adapter degrades under a coarse-only grant). Background location
 * and a location foreground-service start are deliberately not wired in this
 * phase: the stream lives only while the bridge session is live, and closing
 * the bridge stops it.</p>
 */
public final class AndroidCapabilityRequestHandler {
    public static final String METHOD_INFO = "bridge.info";
    public static final String METHOD_BATTERY = "battery.status";
    public static final String METHOD_SENSOR_ACCELEROMETER = "sensor.accelerometer";
    public static final String METHOD_SENSOR_GYROSCOPE = "sensor.gyroscope";
    public static final String METHOD_LOCATION = "location.get";
    public static final String METHOD_CONTACTS_LIST = "contacts.list";
    public static final String METHOD_CALL_LOG_LIST = "calllog.list";
    public static final String METHOD_SMS_INBOX = "sms.inbox";
    public static final String METHOD_TELEPHONY_INFO = "telephony.info";
    public static final String METHOD_TELEPHONY_CELL_INFO = "telephony.cellinfo";
    public static final String METHOD_LOCATION_STREAM_START = "location.stream.start";
    public static final String METHOD_LOCATION_STREAM_POLL = "location.stream.poll";
    public static final String METHOD_LOCATION_STREAM_STOP = "location.stream.stop";
    public static final String METHOD_MEDIA_START = "media.start";
    public static final String METHOD_MEDIA_STATUS = "media.status";
    public static final String METHOD_MEDIA_STOP = "media.stop";

    /**
     * Fixed bounded location stream request: 5 s interval, fine accuracy.
     * The adapter itself degrades a fine request to the network provider
     * under a coarse-only grant; no other request shape is accepted.
     */
    public static final long LOCATION_STREAM_INTERVAL_MILLIS = 5_000L;
    /**
     * Bounded wait of one {@code location.stream.poll}: the stream keeps
     * listening between polls and the poll blocks at most this long per
     * request, so a guest polling loop can never pin a connection worker
     * indefinitely.
     */
    public static final long LOCATION_POLL_TIMEOUT_MILLIS = 2_000L;

    /** Stable comma-separated capability list reported by {@code bridge.info}. */
    public static final String CAPABILITIES = METHOD_BATTERY + ","
            + METHOD_SENSOR_ACCELEROMETER + "," + METHOD_SENSOR_GYROSCOPE + ","
            + METHOD_LOCATION + "," + METHOD_CONTACTS_LIST + ","
            + METHOD_CALL_LOG_LIST + "," + METHOD_SMS_INBOX + ","
            + METHOD_TELEPHONY_INFO + "," + METHOD_TELEPHONY_CELL_INFO + ","
            + METHOD_LOCATION_STREAM_START + "," + METHOD_LOCATION_STREAM_POLL + ","
            + METHOD_LOCATION_STREAM_STOP + "," + METHOD_MEDIA_START + ","
            + METHOD_MEDIA_STATUS + "," + METHOD_MEDIA_STOP;

    private final String expectedToken;
    private final BatteryStatusSource batterySource;
    private final SensorReadingSource sensorSource;
    private final LocationSource locationSource;
    private final ContactsSource contactsSource;
    private final CallLogSource callLogSource;
    private final SmsSource smsSource;
    private final TelephonyInfoSource telephonyInfoSource;
    private final TelephonyCellSource telephonyCellSource;
    private final LocationStreamSession locationStream;
    private final LiveMediaController mediaController;

    public AndroidCapabilityRequestHandler(String expectedToken,
                                           BatteryStatusSource batterySource,
                                           SensorReadingSource sensorSource,
                                           LocationSource locationSource,
                                           ContactsSource contactsSource,
                                           CallLogSource callLogSource,
                                           SmsSource smsSource,
                                           TelephonyInfoSource telephonyInfoSource,
                                           TelephonyCellSource telephonyCellSource,
                                           LocationStreamSession locationStream,
                                           LiveMediaController mediaController) {
        if (expectedToken == null || expectedToken.isEmpty()) {
            throw new IllegalArgumentException("expectedToken must not be blank");
        }
        requireNonNull(batterySource, "batterySource");
        requireNonNull(sensorSource, "sensorSource");
        requireNonNull(locationSource, "locationSource");
        requireNonNull(contactsSource, "contactsSource");
        requireNonNull(callLogSource, "callLogSource");
        requireNonNull(smsSource, "smsSource");
        requireNonNull(telephonyInfoSource, "telephonyInfoSource");
        requireNonNull(telephonyCellSource, "telephonyCellSource");
        requireNonNull(locationStream, "locationStream");
        requireNonNull(mediaController, "mediaController");
        this.expectedToken = expectedToken;
        this.batterySource = batterySource;
        this.sensorSource = sensorSource;
        this.locationSource = locationSource;
        this.contactsSource = contactsSource;
        this.callLogSource = callLogSource;
        this.smsSource = smsSource;
        this.telephonyInfoSource = telephonyInfoSource;
        this.telephonyCellSource = telephonyCellSource;
        this.locationStream = locationStream;
        this.mediaController = mediaController;
    }

    /** Return a bounded protocol response for the request, never throw to the socket loop. */
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null) {
            return AndroidCapabilityProtocol.Response.error("", "malformed-request");
        }
        if (request.getVersion() != AndroidCapabilityProtocol.VERSION) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "unsupported-version");
        }
        if (!constantTimeEquals(expectedToken, request.getToken())) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "unauthorized");
        }
        if (METHOD_INFO.equals(request.getMethod())) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("transport", "tcp-loopback");
            fields.put("capabilities", CAPABILITIES);
            fields.put("best_effort", true);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
        if (METHOD_BATTERY.equals(request.getMethod())) {
            return batteryReading(request);
        }
        if (METHOD_SENSOR_ACCELEROMETER.equals(request.getMethod())) {
            return sensorReading(request, SensorKind.ACCELEROMETER);
        }
        if (METHOD_SENSOR_GYROSCOPE.equals(request.getMethod())) {
            return sensorReading(request, SensorKind.GYROSCOPE);
        }
        if (METHOD_LOCATION.equals(request.getMethod())) {
            return locationReading(request);
        }
        if (METHOD_CONTACTS_LIST.equals(request.getMethod())) {
            return contactsReading(request);
        }
        if (METHOD_CALL_LOG_LIST.equals(request.getMethod())) {
            return callLogReading(request);
        }
        if (METHOD_SMS_INBOX.equals(request.getMethod())) {
            return smsReading(request);
        }
        if (METHOD_TELEPHONY_INFO.equals(request.getMethod())) {
            return telephonyInfoReading(request);
        }
        if (METHOD_TELEPHONY_CELL_INFO.equals(request.getMethod())) {
            return telephonyCellInfoReading(request);
        }
        if (METHOD_LOCATION_STREAM_START.equals(request.getMethod())) {
            return locationStreamStart(request);
        }
        if (METHOD_LOCATION_STREAM_POLL.equals(request.getMethod())) {
            return locationStreamPoll(request, LOCATION_POLL_TIMEOUT_MILLIS);
        }
        if (METHOD_LOCATION_STREAM_STOP.equals(request.getMethod())) {
            return locationStreamStop(request);
        }
        if (METHOD_MEDIA_START.equals(request.getMethod())) {
            return mediaStart(request);
        }
        if (METHOD_MEDIA_STATUS.equals(request.getMethod())) {
            return mediaStatus(request);
        }
        if (METHOD_MEDIA_STOP.equals(request.getMethod())) {
            return mediaStop(request);
        }
        if (MessagingReadPolicy.isSideEffectMethod(request.getMethod())) {
            // Reserved side-effecting methods are never dispatched: the
            // guest gets a typed unsupported result instead of a fake action.
            return MessagingReadPolicy.sideEffectUnsupported(
                    request.getId(), request.getMethod());
        }
        return AndroidCapabilityProtocol.Response.error(request.getId(), "unsupported-method");
    }

    private AndroidCapabilityProtocol.Response batteryReading(
            AndroidCapabilityProtocol.Request request) {
        try {
            BatteryStatus status = batterySource.read();
            if (status == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "capability-unavailable");
            }
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), status.responseFields());
        } catch (RuntimeException e) {
            // Capability failure is reported to the guest; the bridge thread
            // must not die and no platform exception is exposed over the wire.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
    }

    private AndroidCapabilityProtocol.Response contactsReading(
            AndroidCapabilityProtocol.Request request) {
        ContactsSnapshot snapshot;
        try {
            snapshot = contactsSource.read(MessagingQuery.all());
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (snapshot == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        AndroidCapabilityProtocol.Response stateError =
                messagingStateError(request, snapshot.getState(), "contacts");
        return stateError != null ? stateError
                : AndroidCapabilityProtocol.Response.success(request.getId(),
                        readingFields(snapshot.responseFields(), snapshot.encodeRows()));
    }

    private AndroidCapabilityProtocol.Response callLogReading(
            AndroidCapabilityProtocol.Request request) {
        CallLogSnapshot snapshot;
        try {
            snapshot = callLogSource.read(MessagingQuery.all());
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (snapshot == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        AndroidCapabilityProtocol.Response stateError =
                messagingStateError(request, snapshot.getState(), "calllog");
        return stateError != null ? stateError
                : AndroidCapabilityProtocol.Response.success(request.getId(),
                        readingFields(snapshot.responseFields(), snapshot.encodeRows()));
    }

    private AndroidCapabilityProtocol.Response smsReading(
            AndroidCapabilityProtocol.Request request) {
        SmsSnapshot snapshot;
        try {
            snapshot = smsSource.read(MessagingQuery.all());
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (snapshot == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        AndroidCapabilityProtocol.Response stateError =
                messagingStateError(request, snapshot.getState(), "sms");
        return stateError != null ? stateError
                : AndroidCapabilityProtocol.Response.success(request.getId(),
                        readingFields(snapshot.responseFields(), snapshot.encodeRows()));
    }

    private AndroidCapabilityProtocol.Response telephonyInfoReading(
            AndroidCapabilityProtocol.Request request) {
        TelephonyDeviceInfo info;
        try {
            info = telephonyInfoSource.read();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (info == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        AndroidCapabilityProtocol.Response stateError =
                messagingStateError(request, info.getState(), "telephony");
        return stateError != null ? stateError
                : AndroidCapabilityProtocol.Response.success(request.getId(),
                        info.responseFields());
    }

    private AndroidCapabilityProtocol.Response telephonyCellInfoReading(
            AndroidCapabilityProtocol.Request request) {
        TelephonyCellInfo info;
        try {
            info = telephonyCellSource.read();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (info == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        AndroidCapabilityProtocol.Response stateError =
                messagingStateError(request, info.getState(), "cellinfo");
        return stateError != null ? stateError
                : AndroidCapabilityProtocol.Response.success(request.getId(),
                        readingFields(info.responseFields(), info.encodeRows()));
    }

    /**
     * Map one non-READING messaging/telephony/contacts state to its typed
     * error; {@code null} for a READING state.
     */
    private static AndroidCapabilityProtocol.Response messagingStateError(
            AndroidCapabilityProtocol.Request request, MessagingReadState state,
            String prefix) {
        switch (state) {
            case PERMISSION_REQUIRED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), prefix + "-permission-required");
            case PERMISSION_DENIED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), prefix + "-permission-denied");
            case UNAVAILABLE:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), prefix + "-unavailable");
            case NO_TELEPHONY:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), prefix + "-no-telephony");
            case ERROR:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "capability-unavailable");
            default:
                return null;
        }
    }

    /**
     * Compose the bounded RPC fields of one reading: the snapshot envelope
     * (available/count/truncated), the bounded encoded JSON row array, and a
     * count that exactly matches the rows actually carried (rows dropped by
     * the byte budget set {@code truncated}). The row array is a single
     * string value, so the flat response contract is preserved.
     */
    private static Map<String, Object> readingFields(
            Map<String, Object> envelope, MessagingReadPolicy.EncodedRows rows) {
        Map<String, Object> fields = new LinkedHashMap<>(envelope);
        fields.put("rows", rows.getJson());
        if (rows.isTruncated()) {
            fields.put("truncated", true);
        }
        fields.put("count", countEncodedRows(rows.getJson()));
        return fields;
    }

    /**
     * Count the top-level rows of an encoded JSON array string without
     * parsing it: row values are flat primitives, so every {@code {}}
     * outside a quoted string is exactly one row.
     */
    private static long countEncodedRows(String json) {
        long count = 0L;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                count++;
            }
        }
        return count;
    }

    /**
     * Start the foreground location stream with the fixed bounded request.
     * A typed start failure surfaces as the matching error; an already
     * running (or closed) session is its own typed error. Background
     * location, a foreground-service start, and a permission activity are
     * never wired here: the stream lives only while the bridge session is
     * live.
     */
    private AndroidCapabilityProtocol.Response locationStreamStart(
            AndroidCapabilityProtocol.Request request) {
        try {
            locationStream.start(LocationStreamRequest.create(
                    LOCATION_STREAM_INTERVAL_MILLIS, LocationStreamRequest.Accuracy.FINE));
        } catch (IllegalStateException e) {
            if (locationStream.state() == LocationStreamSession.State.CLOSED) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-stream-closed");
            }
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "location-stream-already-started");
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (locationStream.state() == LocationStreamSession.State.STOPPED) {
            // start() failed typed (permission, denial, unavailable, error):
            // drain the terminal event and map it to the matching error.
            return locationStreamPoll(request, 0L);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stream_started", true);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * Poll the location stream once with a bounded wait. A reading carries
     * the validated fix fields; {@code null} is an explicit stream-empty
     * state (the stream keeps listening); a stopped stream is an explicit
     * success state; timeout and the typed failure states are errors.
     */
    private AndroidCapabilityProtocol.Response locationStreamPoll(
            AndroidCapabilityProtocol.Request request, long timeoutMillis) {
        LocationStreamEvent event;
        try {
            event = locationStream.poll(timeoutMillis);
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (event == null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("stream_empty", true);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
        switch (event.getState()) {
            case READING:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), event.getSnapshot().responseFields());
            case STOPPED:
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("stream_stopped", true);
                return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
            case TIMEOUT:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-stream-timeout");
            case PERMISSION_REQUIRED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-stream-permission-required");
            case PERMISSION_DENIED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-stream-permission-denied");
            case UNAVAILABLE:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-stream-unavailable");
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "capability-unavailable");
        }
    }

    /**
     * Stop the location stream. {@link LocationStreamSession#stop()} is
     * idempotent, so stopping an idle stream is a clean no-op that still
     * reports the explicit stopped state.
     */
    private AndroidCapabilityProtocol.Response locationStreamStop(
            AndroidCapabilityProtocol.Request request) {
        try {
            locationStream.stop();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stream_stopped", true);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * Start the unified live media stream with the host-owned fixed defaults.
     * The controller blocks for a bounded start result, so a success response
     * is only returned once the RTSP listener is bound on loopback and the
     * encoder metadata is ready. A failed start is a typed error response
     * (the {@code media-*} codes); a controller exception maps to
     * {@code media-start-failed} and never crosses the wire.
     */
    private AndroidCapabilityProtocol.Response mediaStart(
            AndroidCapabilityProtocol.Request request) {
        LiveMediaStatus status;
        try {
            status = mediaController.start();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "media-start-failed");
        }
        if (status.getState() == LiveMediaState.FAILED && status.getError() != null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), status.getError());
        }
        if (status.getState() != LiveMediaState.RUNNING) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "media-start-failed");
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(),
                status.responseFields());
    }

    /**
     * Current media status. The contract says this is always a success: the
     * explicit state is always carried, a failed state carries its bounded
     * error, and a controller failure degrades to the explicit failed state
     * rather than a transport error.
     */
    private AndroidCapabilityProtocol.Response mediaStatus(
            AndroidCapabilityProtocol.Request request) {
        LiveMediaStatus status;
        try {
            status = mediaController.status();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.success(request.getId(),
                    LiveMediaStatus.failed(LiveMediaError.START_FAILED).responseFields());
        }
        return mediaStatusResponse(request, status);
    }

    /**
     * Stop the live media stream. Idempotent by contract: the explicit
     * stopped state is always returned, even when the stop races a teardown
     * that already happened.
     */
    private AndroidCapabilityProtocol.Response mediaStop(
            AndroidCapabilityProtocol.Request request) {
        try {
            mediaController.stop();
        } catch (RuntimeException e) {
            // A failed stop still leaves the service stoppable via its Stop
            // action; the contract's idempotent stopped state is preserved.
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(),
                LiveMediaStatus.stopped().responseFields());
    }

    private static AndroidCapabilityProtocol.Response mediaStatusResponse(
            AndroidCapabilityProtocol.Request request, LiveMediaStatus status) {
        return AndroidCapabilityProtocol.Response.success(request.getId(),
                status.responseFields());
    }

    /**
     * Map one one-shot location read to a bounded protocol response: only a
     * complete fix carries fields; permission, unavailable, timeout, and error
     * states are typed errors, and a platform exception never crosses the
     * wire. The location source checks the foreground grant itself; this
     * handler never prompts for permission.
     */
    private AndroidCapabilityProtocol.Response locationReading(
            AndroidCapabilityProtocol.Request request) {
        LocationSnapshot snapshot;
        try {
            snapshot = locationSource.read();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (snapshot == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        switch (snapshot.getState()) {
            case READING:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), snapshot.responseFields());
            case PERMISSION_REQUIRED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-permission-required");
            case PERMISSION_DENIED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-permission-denied");
            case UNAVAILABLE:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-unavailable");
            case TIMEOUT:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "location-timeout");
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "capability-unavailable");
        }
    }

    /**
     * Map one sensor read to a bounded protocol response: only a complete
     * reading carries fields; unavailable, timeout, and error states are
     * typed errors, and a platform exception never crosses the wire.
     */
    private AndroidCapabilityProtocol.Response sensorReading(
            AndroidCapabilityProtocol.Request request, SensorKind kind) {
        SensorReading reading;
        try {
            reading = sensorSource.read(kind);
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (reading == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        switch (reading.getState()) {
            case READING:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), reading.responseFields());
            case UNAVAILABLE:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-unavailable");
            case TIMEOUT:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "sensor-timeout");
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "capability-unavailable");
        }
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
