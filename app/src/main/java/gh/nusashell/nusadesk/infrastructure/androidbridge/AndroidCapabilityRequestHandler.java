package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authorizes one parsed request and dispatches only the allowlisted capability
 * methods. There is deliberately no command, URI, class, or shell dispatch and
 * no guest-supplied method name, so every call is one fixed allowlist string
 * with host-owned defaults. The envelope ({@code v}, {@code id}, {@code token},
 * {@code method}) is param-free except for the declaring calendar writes, which
 * may carry one bounded flat {@code params} object: a method that does not
 * declare parameters answers {@link #ERROR_UNSUPPORTED_PARAMETER} when one is
 * sent, an unknown or malformed parameter inside a declaring method is
 * {@link CalendarWriteRequest#ERROR_INVALID_ARGUMENT}, and a top-level field
 * other than {@code params} is rejected while decoding.
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
 *
 * <p>The calendar slice is bounded in both directions. {@code calendar.list}
 * reads a fixed seven-day window of the provider's instance table with a
 * minimal projection (no description, attendee, organizer, or reminder
 * column), and {@code calendar.insert} / {@code calendar.update} /
 * {@code calendar.delete} validate every parameter before a provider call,
 * target only a calendar the user can write, and never write an attendee row
 * or send an invitation. A write logs only its operation and ids.</p>
 *
 * <p>The USB slice ({@code usb.list} / {@code usb.open}) is an explicit
 * pass-through: the host enumerates and opens the device through the
 * platform's own consent dialog, then hands the usbfs descriptor to the
 * guest's abstract unix socket with SCM_RIGHTS. Raw transfers stay the
 * guest's business; the app keeps every connection it opened so the bridge
 * session can release them all (ADR-0041).</p>
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
    public static final String METHOD_CALENDAR_LIST = "calendar.list";
    public static final String METHOD_CALENDAR_INSERT = "calendar.insert";
    public static final String METHOD_CALENDAR_UPDATE = "calendar.update";
    public static final String METHOD_CALENDAR_DELETE = "calendar.delete";
    public static final String METHOD_LOCATION_STREAM_START = "location.stream.start";
    public static final String METHOD_LOCATION_STREAM_POLL = "location.stream.poll";
    public static final String METHOD_LOCATION_STREAM_STOP = "location.stream.stop";
    public static final String METHOD_MEDIA_START = "media.start";
    public static final String METHOD_MEDIA_CAMERA_START = "media.camera.start";
    public static final String METHOD_MEDIA_MICROPHONE_START = "media.microphone.start";
    public static final String METHOD_MEDIA_STATUS = "media.status";
    public static final String METHOD_MEDIA_STOP = "media.stop";
    public static final String METHOD_USB_LIST = "usb.list";
    public static final String METHOD_USB_OPEN = "usb.open";

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

    /** Typed error for a method that carries parameters it does not declare. */
    public static final String ERROR_UNSUPPORTED_PARAMETER = "unsupported-parameter";
    /** Methods that accept a bounded {@code params} object. */
    private static final java.util.Set<String> PARAMETER_METHODS = java.util.Set.of(
            METHOD_CALENDAR_INSERT, METHOD_CALENDAR_UPDATE, METHOD_CALENDAR_DELETE,
            METHOD_USB_OPEN);

    /** Stable comma-separated capability list reported by {@code bridge.info}. */
    public static final String CAPABILITIES = METHOD_BATTERY + ","
            + METHOD_SENSOR_ACCELEROMETER + "," + METHOD_SENSOR_GYROSCOPE + ","
            + METHOD_LOCATION + "," + METHOD_CONTACTS_LIST + ","
            + METHOD_CALL_LOG_LIST + "," + METHOD_SMS_INBOX + ","
            + METHOD_TELEPHONY_INFO + "," + METHOD_TELEPHONY_CELL_INFO + ","
            + METHOD_LOCATION_STREAM_START + "," + METHOD_LOCATION_STREAM_POLL + ","
            + METHOD_LOCATION_STREAM_STOP + "," + METHOD_MEDIA_START + ","
            + METHOD_MEDIA_CAMERA_START + "," + METHOD_MEDIA_MICROPHONE_START + ","
            + METHOD_MEDIA_STATUS + "," + METHOD_MEDIA_STOP + ","
            + METHOD_CALENDAR_LIST + "," + METHOD_CALENDAR_INSERT + ","
            + METHOD_CALENDAR_UPDATE + "," + METHOD_CALENDAR_DELETE + ","
            + METHOD_USB_LIST + "," + METHOD_USB_OPEN;

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
    private final CalendarSource calendarSource;
    private final CalendarWriter calendarWriter;
    private final UsbPassThroughSource usbSource;

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
                                           LiveMediaController mediaController,
                                           CalendarSource calendarSource,
                                           CalendarWriter calendarWriter,
                                           UsbPassThroughSource usbSource) {
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
        requireNonNull(calendarSource, "calendarSource");
        requireNonNull(calendarWriter, "calendarWriter");
        requireNonNull(usbSource, "usbSource");
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
        this.calendarSource = calendarSource;
        this.calendarWriter = calendarWriter;
        this.usbSource = usbSource;
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
        if (!request.getParams().isEmpty()
                && !PARAMETER_METHODS.contains(request.getMethod())) {
            // Only the methods that declare parameters may carry them; every
            // other method fails closed instead of silently ignoring input.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), ERROR_UNSUPPORTED_PARAMETER);
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
            return mediaStart(request, LiveMediaMode.BOTH);
        }
        if (METHOD_MEDIA_CAMERA_START.equals(request.getMethod())) {
            return mediaStart(request, LiveMediaMode.CAMERA);
        }
        if (METHOD_MEDIA_MICROPHONE_START.equals(request.getMethod())) {
            return mediaStart(request, LiveMediaMode.MICROPHONE);
        }
        if (METHOD_MEDIA_STATUS.equals(request.getMethod())) {
            return mediaStatus(request);
        }
        if (METHOD_MEDIA_STOP.equals(request.getMethod())) {
            return mediaStop(request);
        }
        if (METHOD_CALENDAR_LIST.equals(request.getMethod())) {
            return calendarReading(request);
        }
        if (METHOD_CALENDAR_INSERT.equals(request.getMethod())) {
            return calendarWrite(request, CalendarWriteRequest.Op.INSERT);
        }
        if (METHOD_CALENDAR_UPDATE.equals(request.getMethod())) {
            return calendarWrite(request, CalendarWriteRequest.Op.UPDATE);
        }
        if (METHOD_CALENDAR_DELETE.equals(request.getMethod())) {
            return calendarWrite(request, CalendarWriteRequest.Op.DELETE);
        }
        if (METHOD_USB_LIST.equals(request.getMethod())) {
            return usbList(request);
        }
        if (METHOD_USB_OPEN.equals(request.getMethod())) {
            return usbOpen(request);
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
     * Start a live media session in the requested mode with the host-owned
     * fixed defaults. The mode comes from the fixed allowlist method
     * ({@code media.start} = camera + microphone, {@code media.camera.start},
     * {@code media.microphone.start}); there is still no request parameter.
     * The controller blocks for a bounded start result, so a success response
     * is only returned once the RTSP listener is bound on loopback and the
     * metadata of every track the mode carries is ready. A failed start is a
     * typed error response (the {@code media-*} codes, including
     * {@code media-mode-conflict} when another mode is already running).
     */
    private AndroidCapabilityProtocol.Response mediaStart(
            AndroidCapabilityProtocol.Request request, LiveMediaMode mode) {
        LiveMediaStatus status;
        try {
            status = mediaController.start(mode);
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

    /**
     * Read the next seven days of calendar events through the provider's
     * instance table. Permission, unavailable, and provider-error states are
     * typed errors; only a reading carries fields and rows.
     */
    private AndroidCapabilityProtocol.Response calendarReading(
            AndroidCapabilityProtocol.Request request) {
        CalendarEventSnapshot snapshot;
        try {
            snapshot = calendarSource.read(
                    CalendarQuery.upcoming(System.currentTimeMillis()));
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
                return AndroidCapabilityProtocol.Response.success(request.getId(),
                        readingFields(snapshot.responseFields(), snapshot.encodeRows()));
            case PERMISSION_REQUIRED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "calendar-permission-required");
            case PERMISSION_DENIED:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "calendar-permission-denied");
            case UNAVAILABLE:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "calendar-unavailable");
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "capability-unavailable");
        }
    }

    /**
     * Validate the bounded {@code params} of one calendar write and apply it.
     * A rejected parameter is the typed {@code calendar-invalid-argument}, and
     * every write failure stays a bounded code — a provider exception never
     * crosses the wire. Success reports the affected {@code event_id}.
     */
    private AndroidCapabilityProtocol.Response calendarWrite(
            AndroidCapabilityProtocol.Request request, CalendarWriteRequest.Op op) {
        Map<String, Object> params = request.getParams();
        long now = System.currentTimeMillis();
        CalendarWriteRequest.Parse parsed;
        switch (op) {
            case INSERT:
                parsed = CalendarWriteRequest.insert(params, now);
                break;
            case UPDATE:
                parsed = CalendarWriteRequest.update(params, now);
                break;
            default:
                parsed = CalendarWriteRequest.delete(params);
                break;
        }
        if (!parsed.isOk()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), parsed.getErrorCode());
        }
        CalendarWriteResult result;
        try {
            switch (op) {
                case INSERT:
                    result = calendarWriter.insert(parsed.getRequest());
                    break;
                case UPDATE:
                    result = calendarWriter.update(parsed.getRequest());
                    break;
                default:
                    result = calendarWriter.delete(parsed.getRequest());
                    break;
            }
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), CalendarWriteResult.ERROR_FAILED);
        }
        if (result == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), CalendarWriteResult.ERROR_FAILED);
        }
        if (!result.isOk()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), result.getErrorCode());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("written", true);
        fields.put("event_id", result.getEventId());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
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

    /**
     * List the attached USB devices through the platform's USB manager. The
     * device array is one pre-encoded JSON string value so the flat response
     * contract (and its bounds) is preserved.
     */
    private AndroidCapabilityProtocol.Response usbList(
            AndroidCapabilityProtocol.Request request) {
        List<UsbPassThroughSource.UsbDeviceEntry> devices;
        try {
            devices = usbSource.list();
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        if (devices == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "capability-unavailable");
        }
        StringBuilder json = new StringBuilder(64);
        json.append('[');
        for (int i = 0; i < devices.size(); i++) {
            UsbPassThroughSource.UsbDeviceEntry entry = devices.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"vendorId\":").append(entry.getVendorId())
                    .append(",\"productId\":").append(entry.getProductId())
                    .append(",\"name\":").append(encodeNullable(entry.getName()))
                    .append(",\"manufacturer\":").append(encodeNullable(entry.getManufacturer()))
                    .append(",\"product\":").append(encodeNullable(entry.getProduct()))
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("devices", json.toString());
        fields.put("count", devices.size());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * Open one USB device with the platform's consent flow and deliver its
     * descriptor to the guest's abstract socket. Every rejected parameter is
     * the typed {@code invalid-argument}; every platform outcome maps to its
     * bounded {@code usb-*} code and no exception crosses the wire.
     */
    private AndroidCapabilityProtocol.Response usbOpen(
            AndroidCapabilityProtocol.Request request) {
        Map<String, Object> params = request.getParams();
        Object vendorRaw = params.get("vendorId");
        Object productRaw = params.get("productId");
        Object socketRaw = params.get("socket");
        if (!(vendorRaw instanceof Long) || !(productRaw instanceof Long)
                || !(socketRaw instanceof String)
                || !validUsbId((Long) vendorRaw) || !validUsbId((Long) productRaw)
                || !validSocketName((String) socketRaw)) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "invalid-argument");
        }
        UsbPassThroughSource.OpenResult result;
        try {
            result = usbSource.open(((Long) vendorRaw).intValue(),
                    ((Long) productRaw).intValue(), (String) socketRaw);
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "usb-open-failed");
        }
        if (result == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "usb-open-failed");
        }
        if (!result.opened()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), usbErrorCode(result.getState()));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("opened", true);
        fields.put("vendorId", result.getVendorId());
        fields.put("productId", result.getProductId());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** Map one non-OPENED state to its typed guest-visible error code. */
    private static String usbErrorCode(UsbPassThroughSource.OpenState state) {
        switch (state) {
            case DEVICE_NOT_FOUND:
                return "usb-device-not-found";
            case PERMISSION_DENIED:
                return "usb-permission-denied";
            case PERMISSION_TIMEOUT:
                return "usb-permission-timeout";
            case SOCKET_FAILED:
                return "usb-socket-failed";
            default:
                return "usb-open-failed";
        }
    }

    /** USB ids are 16-bit; anything else is a rejected parameter. */
    private static boolean validUsbId(Long value) {
        return value != null && value >= 0 && value <= 0xFFFF;
    }

    /**
     * Bound the guest-supplied abstract socket name: the guest owns the
     * listener, but a name is still validated so a request can never carry a
     * path or an unbounded string into the host's socket namespace.
     */
    private static boolean validSocketName(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = c == '-' || c == '_' || c == '.'
                    || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** Encode one optional string field; absent values stay JSON null. */
    private static String encodeNullable(String value) {
        return value == null || value.isEmpty()
                ? "null" : AndroidCapabilityProtocol.encodeStringValue(value);
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
