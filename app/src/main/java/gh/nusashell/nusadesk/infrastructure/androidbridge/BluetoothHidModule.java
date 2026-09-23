package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bluetooth HID keyboard and mouse peripheral capability. */
public final class BluetoothHidModule implements CapabilityModule {
    static final int REPORT_ID_KEYBOARD = 1;
    static final int REPORT_ID_MOUSE = 2;
    private static final int MAX_NAME = 32;
    private static final int MAX_ADDRESS = 17;
    private static final int MAX_TEXT = 128;
    private static final int MAX_HOST_NAME = 128;
    private static final long REGISTER_TIMEOUT_MS = 10_000L;
    private static final long CONNECT_TIMEOUT_MS = 15_000L;
    private static final long TYPE_DELAY_MS = 5L;
    private static final String DEFAULT_NAME = "NusaDesk HID";

    private static final String START = "bt.hid.start";
    private static final String STATUS = "bt.hid.status";
    private static final String CONNECT = "bt.hid.connect";
    private static final String DISCONNECT = "bt.hid.disconnect";
    private static final String STOP = "bt.hid.stop";
    private static final String TYPE = "bt.hid.type";
    private static final String KEY = "bt.hid.key";
    private static final String MOVE = "bt.hid.mouse.move";
    private static final String CLICK = "bt.hid.mouse.click";

    private static final Set<String> KEYS = Set.of("ENTER", "TAB", "BACKSPACE", "ESCAPE",
            "SPACE", "UP", "DOWN", "LEFT", "RIGHT");
    private static final Set<String> BUTTONS = Set.of("LEFT", "RIGHT", "MIDDLE");

    /** Keyboard report ID 1 followed by mouse report ID 2. */
    private static final byte[] REPORT_DESCRIPTOR = new byte[]{
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01,
            (byte) 0x85, 0x01, 0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00, (byte) 0xC0,
            0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
            0x09, 0x01, (byte) 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03,
            (byte) 0x81, 0x06, (byte) 0xC0, (byte) 0xC0
    };

    /** Backend result is derived from asynchronous HID callbacks by AndroidHidBackend. */
    enum HidResult { OK, FAILED, TIMEOUT, UNSUPPORTED }

    /** Small package-private boundary used by the JVM fake and the Android adapter. */
    interface HidBackend {
        String unavailableError();

        boolean foregrounded();

        boolean paired(String address);

        HidResult register(String name, byte[] descriptor, long timeoutMillis);

        HidResult connect(String address, long timeoutMillis);

        boolean disconnect(String address);

        boolean registered();

        String connectedAddress();

        String connectedName();

        boolean sendReport(int reportId, byte[] data);

        void unregister();

        void close();
    }

    interface PermissionSource {
        CapabilityPermission get();
    }

    private final HidBackend backend;
    private final PermissionSource permission;
    private String name = DEFAULT_NAME;
    private boolean closed;

    public BluetoothHidModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        AndroidPermissionChecker checker = new AndroidPermissionChecker(application);
        this.backend = new AndroidHidBackend(application);
        this.permission = () -> checker.check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH);
    }

    BluetoothHidModule(HidBackend backend, PermissionSource permission) {
        if (backend == null || permission == null) {
            throw new IllegalArgumentException("backend and permission must not be null");
        }
        this.backend = backend;
        this.permission = permission;
    }

    static byte[] reportDescriptor() {
        return REPORT_DESCRIPTOR.clone();
    }

    @Override
    public List<String> methods() {
        return List.of(START, STATUS, CONNECT, DISCONNECT, STOP, TYPE, KEY, MOVE, CLICK);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(START, CONNECT, DISCONNECT, TYPE, KEY, MOVE, CLICK);
    }

    @Override
    public synchronized AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case START: return start(request);
            case STATUS: return status(request);
            case CONNECT: return connect(request);
            case DISCONNECT: return disconnect(request);
            case STOP: return stop(request);
            case TYPE: return type(request);
            case KEY: return key(request);
            case MOVE: return move(request);
            case CLICK: return click(request);
            default: return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "unsupported-method");
        }
    }

    private AndroidCapabilityProtocol.Response start(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("name"));
        String requestedName = params.optionalString("name", MAX_NAME, DEFAULT_NAME);
        if (requestedName.trim().isEmpty()) {
            throw new CapabilityParams.Invalid("parameter is empty: name");
        }
        for (int i = 0; i < requestedName.length(); i++) {
            if (Character.isISOControl(requestedName.charAt(i))) {
                throw new CapabilityParams.Invalid("name must not contain control characters");
            }
        }
        AndroidCapabilityProtocol.Response gate = gate(request, true);
        if (gate != null) {
            return gate;
        }
        if (backend.registered()) {
            return error(request, "bt-hid-already-registered");
        }
        HidResult result = backend.register(requestedName, REPORT_DESCRIPTOR.clone(),
                REGISTER_TIMEOUT_MS);
        if (result == HidResult.UNSUPPORTED) {
            return error(request, "bt-hid-unsupported");
        }
        if (result == HidResult.TIMEOUT) {
            return error(request, "bt-hid-register-timeout");
        }
        if (result != HidResult.OK || !backend.registered()) {
            return error(request, "bt-hid-register-failed");
        }
        name = requestedName;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("registered", true);
        fields.put("name", name);
        return success(request, fields);
    }

    private AndroidCapabilityProtocol.Response status(AndroidCapabilityProtocol.Request request) {
        AndroidCapabilityProtocol.Response gate = gate(request, false);
        if (gate != null) {
            return gate;
        }
        boolean registered = backend.registered();
        String address = registered ? backend.connectedAddress() : null;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("registered", registered);
        fields.put("connected", address != null);
        fields.put("name", name);
        if (address != null) {
            fields.put("host_address", address);
            String hostName = sanitizeName(backend.connectedName());
            if (hostName != null) {
                fields.put("host_name", hostName);
            }
        }
        return success(request, fields);
    }

    private AndroidCapabilityProtocol.Response connect(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        String suppliedAddress = params.requireString("address", MAX_ADDRESS);
        String address = suppliedAddress.trim().toUpperCase(Locale.ROOT);
        AndroidCapabilityProtocol.Response gate = gate(request, false);
        if (gate != null) {
            return gate;
        }
        if (!validAddress(address) || !backend.paired(address)) {
            return error(request, "bt-device-unknown:" + suppliedAddress);
        }
        if (!backend.registered()) {
            return error(request, "bt-hid-not-registered");
        }
        String connected = backend.connectedAddress();
        if (connected != null) {
            if (connected.equals(address)) {
                return connectedResponse(request, address);
            }
            return error(request, "bt-hid-already-connected:" + connected);
        }
        HidResult result = backend.connect(address, CONNECT_TIMEOUT_MS);
        if (result == HidResult.TIMEOUT) {
            return error(request, "bt-hid-connect-timeout");
        }
        if (result != HidResult.OK || !address.equals(backend.connectedAddress())) {
            return error(request, "bt-hid-connect-failed");
        }
        return connectedResponse(request, address);
    }

    private AndroidCapabilityProtocol.Response disconnect(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        String requested = params.optionalString("address", MAX_ADDRESS, null);
        if (requested != null) {
            requested = requested.trim().toUpperCase(Locale.ROOT);
            if (!validAddress(requested)) {
                throw new CapabilityParams.Invalid("invalid bluetooth address");
            }
        }
        AndroidCapabilityProtocol.Response gate = gate(request, false);
        if (gate != null) {
            return gate;
        }
        String connected = backend.connectedAddress();
        if (connected == null || (requested != null && !requested.equalsIgnoreCase(connected))) {
            return booleanResponse(request, "disconnected", false);
        }
        if (!backend.disconnect(connected)) {
            return error(request, "bt-hid-disconnect-failed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("disconnected", true);
        fields.put("address", connected);
        return success(request, fields);
    }

    private AndroidCapabilityProtocol.Response stop(AndroidCapabilityProtocol.Request request) {
        AndroidCapabilityProtocol.Response permissionError = permissionGate(request);
        if (permissionError != null) {
            return permissionError;
        }
        boolean wasActive = backend.registered();
        backend.unregister();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stopped", wasActive);
        return success(request, fields);
    }

    private AndroidCapabilityProtocol.Response type(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text"));
        String text = params.requireString("text", MAX_TEXT);
        if (text.isEmpty()) {
            throw new CapabilityParams.Invalid("parameter is empty: text");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x20 || text.charAt(i) > 0x7e) {
                throw new CapabilityParams.Invalid("text must be printable ASCII");
            }
        }
        AndroidCapabilityProtocol.Response gate = reportGate(request);
        if (gate != null) {
            return gate;
        }
        for (int i = 0; i < text.length(); i++) {
            Stroke stroke = printable(text.charAt(i));
            if (!sendKeyboard(stroke)) {
                return error(request, "bt-hid-send-failed");
            }
            if (i + 1 < text.length()) {
                try {
                    Thread.sleep(TYPE_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return error(request, "bt-hid-send-interrupted");
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("typed", (long) text.length());
        return success(request, fields);
    }

    private AndroidCapabilityProtocol.Response key(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("key", "shift"));
        String raw = params.requireString("key", 16);
        String key = raw.toUpperCase(Locale.US);
        boolean shift = params.optionalBoolean("shift", false);
        if (!KEYS.contains(key)) {
            throw new CapabilityParams.Invalid("unsupported key: " + raw);
        }
        AndroidCapabilityProtocol.Response gate = reportGate(request);
        if (gate != null) {
            return gate;
        }
        if (!sendKeyboard(named(key, shift))) {
            return error(request, "bt-hid-send-failed");
        }
        return stringResponse(request, "key", key);
    }

    private AndroidCapabilityProtocol.Response move(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("dx", "dy", "wheel"));
        if (!params.has("dx") || !params.has("dy")) {
            throw new CapabilityParams.Invalid("dx and dy are required");
        }
        int dx = (int) params.optionalLong("dx", -127, 127, 0);
        int dy = (int) params.optionalLong("dy", -127, 127, 0);
        int wheel = (int) params.optionalLong("wheel", -127, 127, 0);
        AndroidCapabilityProtocol.Response gate = reportGate(request);
        if (gate != null) {
            return gate;
        }
        if (!send(REPORT_ID_MOUSE, new byte[]{0, (byte) dx, (byte) dy, (byte) wheel})) {
            return error(request, "bt-hid-send-failed");
        }
        return booleanResponse(request, "moved", true);
    }

    private AndroidCapabilityProtocol.Response click(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("button"));
        String button = params.requireString("button", 8).toUpperCase(Locale.US);
        if (!BUTTONS.contains(button)) {
            throw new CapabilityParams.Invalid("unsupported button: " + button);
        }
        AndroidCapabilityProtocol.Response gate = reportGate(request);
        if (gate != null) {
            return gate;
        }
        int mask = "LEFT".equals(button) ? 1 : "RIGHT".equals(button) ? 2 : 4;
        boolean pressed = send(REPORT_ID_MOUSE, new byte[]{(byte) mask, 0, 0, 0});
        boolean released = send(REPORT_ID_MOUSE, new byte[4]);
        if (!pressed || !released) {
            return error(request, "bt-hid-send-failed");
        }
        return stringResponse(request, "button", button);
    }

    @Override
    public synchronized void close() {
        closed = true;
        backend.close();
    }

    private AndroidCapabilityProtocol.Response permissionGate(
            AndroidCapabilityProtocol.Request request) {
        if (closed) {
            return error(request, "bt-unavailable");
        }
        CapabilityPermission state = permission.get();
        if (state != CapabilityPermission.GRANTED) {
            return error(request, state == CapabilityPermission.DENIED
                    ? "bt-permission-denied" : "bt-permission-required:grant BLUETOOTH_CONNECT");
        }
        return null;
    }

    private AndroidCapabilityProtocol.Response gate(AndroidCapabilityProtocol.Request request,
                                                     boolean requireForeground) {
        AndroidCapabilityProtocol.Response permissionError = permissionGate(request);
        if (permissionError != null) {
            return permissionError;
        }
        String unavailable = backend.unavailableError();
        if (unavailable != null) {
            return error(request, unavailable);
        }
        if (requireForeground && !backend.foregrounded()) {
            return error(request, "bt-hid-foreground-required:bring NusaDesk to foreground");
        }
        return null;
    }

    private AndroidCapabilityProtocol.Response reportGate(AndroidCapabilityProtocol.Request request) {
        AndroidCapabilityProtocol.Response gate = gate(request, false);
        if (gate != null) {
            return gate;
        }
        if (!backend.registered()) {
            return error(request, "bt-hid-not-registered");
        }
        if (backend.connectedAddress() == null) {
            return error(request, "bt-hid-not-connected");
        }
        return null;
    }

    private boolean send(int id, byte[] data) {
        try {
            return backend.sendReport(id, data);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_HOST_NAME));
        for (int i = 0; i < value.length() && clean.length() < MAX_HOST_NAME; i++) {
            char c = value.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        String result = clean.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean validAddress(String address) {
        if (address == null || address.length() != MAX_ADDRESS) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            if (i == 2 || i == 5 || i == 8 || i == 11 || i == 14) {
                if (address.charAt(i) != ':') {
                    return false;
                }
            } else if (Character.digit(address.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean sendKeyboard(Stroke stroke) {
        boolean pressed = send(REPORT_ID_KEYBOARD, new byte[]{(byte) stroke.modifier, 0,
                (byte) stroke.usage, 0, 0, 0, 0, 0});
        boolean released = send(REPORT_ID_KEYBOARD, new byte[8]);
        return pressed && released;
    }

    private static Stroke named(String key, boolean shift) {
        int usage;
        switch (key) {
            case "ENTER": usage = 0x28; break;
            case "TAB": usage = 0x2b; break;
            case "BACKSPACE": usage = 0x2a; break;
            case "ESCAPE": usage = 0x29; break;
            case "SPACE": usage = 0x2c; break;
            case "UP": usage = 0x52; break;
            case "DOWN": usage = 0x51; break;
            case "LEFT": usage = 0x50; break;
            case "RIGHT": usage = 0x4f; break;
            default: throw new IllegalArgumentException("unsupported key");
        }
        return new Stroke(usage, shift ? 2 : 0);
    }

    private static Stroke printable(char c) {
        if (c >= 'a' && c <= 'z') return new Stroke(4 + c - 'a', 0);
        if (c >= 'A' && c <= 'Z') return new Stroke(4 + c - 'A', 2);
        if (c >= '1' && c <= '9') return new Stroke(0x1e + c - '1', 0);
        if (c == '0') return new Stroke(0x27, 0);
        switch (c) {
            case ' ': return new Stroke(0x2c, 0);
            case '-': return new Stroke(0x2d, 0); case '_': return new Stroke(0x2d, 2);
            case '=': return new Stroke(0x2e, 0); case '+': return new Stroke(0x2e, 2);
            case '[': return new Stroke(0x2f, 0); case '{': return new Stroke(0x2f, 2);
            case ']': return new Stroke(0x30, 0); case '}': return new Stroke(0x30, 2);
            case '\\': return new Stroke(0x31, 0); case '|': return new Stroke(0x31, 2);
            case ';': return new Stroke(0x33, 0); case ':': return new Stroke(0x33, 2);
            case '\'': return new Stroke(0x34, 0); case '"': return new Stroke(0x34, 2);
            case '`': return new Stroke(0x35, 0); case '~': return new Stroke(0x35, 2);
            case ',': return new Stroke(0x36, 0); case '<': return new Stroke(0x36, 2);
            case '.': return new Stroke(0x37, 0); case '>': return new Stroke(0x37, 2);
            case '/': return new Stroke(0x38, 0); case '?': return new Stroke(0x38, 2);
            case '!': return new Stroke(0x1e, 2); case '@': return new Stroke(0x1f, 2);
            case '#': return new Stroke(0x20, 2); case '$': return new Stroke(0x21, 2);
            case '%': return new Stroke(0x22, 2); case '^': return new Stroke(0x23, 2);
            case '&': return new Stroke(0x24, 2); case '*': return new Stroke(0x25, 2);
            case '(': return new Stroke(0x26, 2); case ')': return new Stroke(0x27, 2);
            default: throw new IllegalArgumentException("unmapped printable ASCII");
        }
    }

    private static AndroidCapabilityProtocol.Response success(
            AndroidCapabilityProtocol.Request request, Map<String, Object> fields) {
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private static AndroidCapabilityProtocol.Response error(
            AndroidCapabilityProtocol.Request request, String message) {
        return AndroidCapabilityProtocol.Response.error(request.getId(), message);
    }

    private static AndroidCapabilityProtocol.Response booleanResponse(
            AndroidCapabilityProtocol.Request request, String key, boolean value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(key, value);
        return success(request, fields);
    }

    private static AndroidCapabilityProtocol.Response stringResponse(
            AndroidCapabilityProtocol.Request request, String key, String value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(key, value);
        return success(request, fields);
    }

    private static AndroidCapabilityProtocol.Response connectedResponse(
            AndroidCapabilityProtocol.Request request, String address) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("connected", true);
        fields.put("address", address);
        return success(request, fields);
    }

    private static final class Stroke {
        final int usage;
        final int modifier;

        Stroke(int usage, int modifier) {
            this.usage = usage;
            this.modifier = modifier;
        }
    }
}

/** Android public BluetoothHidDevice adapter. */
final class AndroidHidBackend implements BluetoothHidModule.HidBackend,
        BluetoothProfile.ServiceListener {
    private static final String TAG = "AndroidHidBackend";
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Object lock = new Object();
    private BluetoothHidDevice profile;
    private BluetoothHidDevice callbackProfile;
    private BluetoothHidDevice.Callback callback;
    private boolean registered;
    private boolean closed;
    private boolean abandonProfile;
    private boolean abandonApp;
    private String connectedAddress;
    private String connectedName;
    private CountDownLatch serviceWait;
    private CountDownLatch appWait;
    private CountDownLatch connectionWait;
    private String waitingAddress;
    private BluetoothHidModule.HidResult appResult;
    private BluetoothHidModule.HidResult connectionResult;

    AndroidHidBackend(Context context) {
        this.context = context.getApplicationContext();
        BluetoothAdapter resolved = null;
        try {
            android.bluetooth.BluetoothManager manager =
                    (android.bluetooth.BluetoothManager) this.context.getSystemService(
                            Context.BLUETOOTH_SERVICE);
            if (manager != null) resolved = manager.getAdapter();
        } catch (RuntimeException e) {
            Log.w(TAG, "adapter lookup failed", e);
        }
        adapter = resolved;
    }

    @Override
    public String unavailableError() {
        if (adapter == null) return "bt-unsupported:no bluetooth adapter";
        try {
            return adapter.isEnabled() ? null : "bt-unavailable:bluetooth is off";
        } catch (RuntimeException e) {
            return "bt-unavailable";
        }
    }

    @Override
    public boolean foregrounded() {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (manager == null || manager.getRunningAppProcesses() == null) return false;
            int pid = Process.myPid();
            for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                if (process.pid == pid) {
                    return process.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground check failed", e);
        }
        return false;
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean paired(String address) {
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) return false;
        try {
            return adapter.getRemoteDevice(address).getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public BluetoothHidModule.HidResult register(String name, byte[] descriptor,
                                                  long timeoutMillis) {
        if (adapter == null) return BluetoothHidModule.HidResult.UNSUPPORTED;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        CountDownLatch service;
        synchronized (lock) {
            if (closed) return BluetoothHidModule.HidResult.FAILED;
            if (registered) return BluetoothHidModule.HidResult.OK;
            abandonProfile = false;
            serviceWait = service = new CountDownLatch(1);
        }
        try {
            if (!adapter.getProfileProxy(context, this, BluetoothProfile.HID_DEVICE)) {
                abandonProfileAcquisition();
                return BluetoothHidModule.HidResult.UNSUPPORTED;
            }
        } catch (RuntimeException e) {
            abandonProfileAcquisition();
            return BluetoothHidModule.HidResult.UNSUPPORTED;
        }
        if (!awaitUntil(service, deadline)) {
            abandonProfileAcquisition();
            return BluetoothHidModule.HidResult.TIMEOUT;
        }
        BluetoothHidDevice hid;
        synchronized (lock) {
            if (closed) return BluetoothHidModule.HidResult.FAILED;
            hid = profile;
            appWait = new CountDownLatch(1);
            appResult = null;
            abandonApp = false;
            callback = new HidCallback();
            callbackProfile = hid;
        }
        if (hid == null) return BluetoothHidModule.HidResult.UNSUPPORTED;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                name, "NusaDesk HID keyboard and mouse", "NusaDesk",
                BluetoothHidDevice.SUBCLASS1_COMBO, descriptor);
        boolean accepted;
        try {
            accepted = hid.registerApp(sdp, (BluetoothHidDeviceAppQosSettings) null,
                    (BluetoothHidDeviceAppQosSettings) null, context.getMainExecutor(), callback);
        } catch (RuntimeException e) {
            return BluetoothHidModule.HidResult.FAILED;
        }
        if (!accepted) return BluetoothHidModule.HidResult.FAILED;
        CountDownLatch app;
        synchronized (lock) { app = appWait; }
        if (!awaitUntil(app, deadline)) {
            BluetoothHidDevice timedOutProfile;
            synchronized (lock) {
                abandonApp = true;
                registered = false;
                appResult = BluetoothHidModule.HidResult.TIMEOUT;
                timedOutProfile = callbackProfile;
            }
            if (timedOutProfile != null) {
                try { timedOutProfile.unregisterApp(); } catch (RuntimeException ignored) { }
            }
            return BluetoothHidModule.HidResult.TIMEOUT;
        }
        synchronized (lock) { return appResult == BluetoothHidModule.HidResult.OK && registered
                ? BluetoothHidModule.HidResult.OK : BluetoothHidModule.HidResult.FAILED; }
    }

    @Override
    @SuppressLint("MissingPermission")
    public BluetoothHidModule.HidResult connect(String address, long timeoutMillis) {
        BluetoothHidDevice hid;
        CountDownLatch wait;
        synchronized (lock) {
            hid = profile;
            waitingAddress = address;
            connectionWait = wait = new CountDownLatch(1);
            connectionResult = null;
        }
        if (hid == null || !registered) return BluetoothHidModule.HidResult.FAILED;
        boolean accepted;
        try {
            accepted = hid.connect(adapter.getRemoteDevice(address));
        } catch (RuntimeException e) {
            return BluetoothHidModule.HidResult.FAILED;
        }
        if (!accepted) return BluetoothHidModule.HidResult.FAILED;
        if (!awaitUntil(wait, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis))) {
            return BluetoothHidModule.HidResult.TIMEOUT;
        }
        synchronized (lock) {
            return connectionResult == BluetoothHidModule.HidResult.OK
                    && address.equals(connectedAddress)
                    ? BluetoothHidModule.HidResult.OK : BluetoothHidModule.HidResult.FAILED;
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean disconnect(String address) {
        BluetoothHidDevice hid;
        synchronized (lock) { hid = profile; }
        if (hid == null || adapter == null) return false;
        try {
            boolean result = hid.disconnect(adapter.getRemoteDevice(address));
            if (result) {
                synchronized (lock) {
                    if (address.equals(connectedAddress)) {
                        connectedAddress = null;
                        connectedName = null;
                    }
                }
            }
            return result;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean registered() { synchronized (lock) { return registered; } }

    @Override
    public String connectedAddress() { synchronized (lock) { return connectedAddress; } }

    @Override
    public String connectedName() { synchronized (lock) { return connectedName; } }

    @Override
    @SuppressLint("MissingPermission")
    public boolean sendReport(int reportId, byte[] data) {
        BluetoothHidDevice hid;
        String address;
        synchronized (lock) { hid = profile; address = connectedAddress; }
        if (hid == null || adapter == null || address == null) return false;
        try {
            return hid.sendReport(adapter.getRemoteDevice(address), reportId, data);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void unregister() {
        BluetoothHidDevice hid;
        synchronized (lock) {
            hid = profile;
            registered = false;
            connectedAddress = null;
            connectedName = null;
        }
        if (hid != null) {
            try { hid.unregisterApp(); } catch (RuntimeException ignored) { }
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void close() {
        BluetoothHidDevice hid;
        boolean wasRegistered;
        synchronized (lock) {
            hid = profile == null ? callbackProfile : profile;
            wasRegistered = registered || (appWait != null && appWait.getCount() > 0);
            closed = true;
            abandonProfile = true;
            abandonApp = true;
            profile = null;
            registered = false;
            connectedAddress = null;
            connectedName = null;
            if (serviceWait != null) serviceWait.countDown();
            if (appWait != null) appWait.countDown();
            if (connectionWait != null) connectionWait.countDown();
        }
        if (hid != null && adapter != null) {
            if (wasRegistered) {
                try { hid.unregisterApp(); } catch (RuntimeException ignored) { }
            }
            try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onServiceConnected(int profileId, BluetoothProfile proxy) {
        BluetoothHidDevice late = null;
        synchronized (lock) {
            if (profileId == BluetoothProfile.HID_DEVICE && proxy instanceof BluetoothHidDevice) {
                BluetoothHidDevice hid = (BluetoothHidDevice) proxy;
                if (closed || abandonProfile) {
                    late = hid;
                } else {
                    profile = hid;
                }
            }
            if (serviceWait != null) serviceWait.countDown();
        }
        closeProfileProxy(late);
    }

    private void abandonProfileAcquisition() {
        BluetoothHidDevice abandoned;
        synchronized (lock) {
            abandonProfile = true;
            abandoned = profile;
            profile = null;
            if (serviceWait != null) serviceWait.countDown();
        }
        closeProfileProxy(abandoned);
    }

    @SuppressLint("MissingPermission")
    private void closeProfileProxy(BluetoothHidDevice hid) {
        if (hid == null || adapter == null) {
            return;
        }
        try {
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        } catch (RuntimeException ignored) {
            // A late profile callback cannot revive the closed module.
        }
    }

    @Override
    public void onServiceDisconnected(int profileId) {
        synchronized (lock) {
            if (profileId == BluetoothProfile.HID_DEVICE) {
                profile = null;
                registered = false;
                connectedAddress = null;
                connectedName = null;
            }
            if (serviceWait != null) serviceWait.countDown();
            if (appWait != null) appWait.countDown();
            if (connectionWait != null) connectionWait.countDown();
        }
    }

    private static boolean awaitUntil(CountDownLatch latch, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return latch.getCount() == 0;
        }
        try {
            return latch.await(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private final class HidCallback extends BluetoothHidDevice.Callback {
        @Override
        @SuppressLint("MissingPermission")
        public void onAppStatusChanged(BluetoothDevice device, boolean open) {
            BluetoothHidDevice unregisterFrom = null;
            synchronized (lock) {
                if (closed || abandonApp) {
                    registered = false;
                    connectedAddress = null;
                    connectedName = null;
                    appResult = BluetoothHidModule.HidResult.FAILED;
                    if (open) unregisterFrom = callbackProfile;
                } else if (!open) {
                    registered = false;
                    connectedAddress = null;
                    connectedName = null;
                    appResult = BluetoothHidModule.HidResult.FAILED;
                } else {
                    registered = true;
                    appResult = BluetoothHidModule.HidResult.OK;
                }
                if (appWait != null) appWait.countDown();
            }
            if (unregisterFrom != null) {
                try { unregisterFrom.unregisterApp(); } catch (RuntimeException ignored) { }
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            String address = addressOf(device);
            synchronized (lock) {
                boolean terminal = state == BluetoothProfile.STATE_CONNECTED
                        || state == BluetoothProfile.STATE_DISCONNECTED;
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedAddress = address;
                    connectedName = nameOf(device);
                    connectionResult = BluetoothHidModule.HidResult.OK;
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (address == null || address.equals(connectedAddress)) {
                        connectedAddress = null;
                        connectedName = null;
                    }
                    connectionResult = BluetoothHidModule.HidResult.FAILED;
                }
                if (terminal && connectionWait != null && (waitingAddress == null
                        || waitingAddress.equals(address))) connectionWait.countDown();
            }
        }

        @Override
        public void onVirtualCableUnplug(BluetoothDevice device) {
            synchronized (lock) {
                String address = addressOf(device);
                if (address == null || address.equals(connectedAddress)) {
                    connectedAddress = null;
                    connectedName = null;
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private static String addressOf(BluetoothDevice device) {
        try { return device == null ? null : device.getAddress(); }
        catch (RuntimeException e) { return null; }
    }

    @SuppressLint("MissingPermission")
    private static String nameOf(BluetoothDevice device) {
        try { return device == null ? null : device.getName(); }
        catch (RuntimeException e) { return null; }
    }
}
