package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth GATT capability domain behind eleven bridge methods: one bounded
 * BLE GATT client session plus one fixed-shape GATT server.
 *
 * <p>The client owns exactly one connection at a time. {@code bt.gatt.connect}
 * (param {@code address}) opens a {@code TRANSPORT_LE} {@code connectGatt}
 * session and waits at most {@link #CONNECT_TIMEOUT_MILLIS} for
 * {@code onConnectionStateChange}: a platform failure status or an unreachable
 * device is {@code bt-device-unknown:<address>}, silence is
 * {@code bt-gatt-timeout}, and a live session is {@code bt-gatt-busy}.
 * {@code bt.gatt.services} runs service discovery on first use and caches the
 * result ({@code services_json} rows carry {@code uuid}, {@code type}, and a
 * {@code characteristics} array of {@code uuid}/{@code properties}/
 * {@code permissions} bitmasks). {@code bt.gatt.read} answers
 * {@code value_hex}, or {@code value_utf8} when the payload is printable ASCII,
 * plus {@code length}; {@code bt.gatt.write} accepts exactly one of
 * {@code value_hex}/{@code value_utf8}. {@code bt.gatt.notify.start} performs
 * the {@code setCharacteristicNotification} + CCCD write pair and buffers
 * {@code onCharacteristicChanged} payloads; {@code bt.gatt.notify.poll} drains
 * at most {@link #MAX_NOTIFY_POLL} records as {@code notifications_json} rows
 * {@code {char_uuid, value_hex, at_ms}}; {@code bt.gatt.notify.stop} and
 * {@code bt.gatt.disconnect} are idempotent teardown.</p>
 *
 * <p>Every async operation resolves through its callback inside a bounded
 * latch wait; an initiate call that reports refusal (or throws) is still
 * awaited rather than fast-failed, because the callback channel is the single
 * source of truth for the outcome — a refused initiate simply expires the
 * window into the operation's typed error. Only a {@link SecurityException}
 * resolves early through the permission gate.</p>
 *
 * <p>The server is the fixed NusaDesk shape ({@link #SERVICE_UUID}: one
 * read/write/notify characteristic {@link #CHARACTERISTIC_UUID} with a CCCD).
 * {@code bt.gatt.server.start} (optional {@code name} ≤32 chars, applied
 * best-effort as the adapter display name — advertising itself is a separate
 * slice) opens the server and registers the service; {@code addService}'s
 * asynchronous {@code onServiceAdded} confirmation is logged but not awaited,
 * because no advertising runs in this slice and no peer can race the
 * registration. {@code bt.gatt.server.notify} pushes the characteristic value
 * to devices that wrote the CCCD; with none subscribed it is
 * {@code bt-gatt-server-no-subscribers}. {@code bt.gatt.server.stop} is
 * idempotent.</p>
 *
 * <p>All entry points gate on the connect grant — {@code BLUETOOTH_CONNECT}
 * on API 31+, the install-granted legacy {@code BLUETOOTH} on older platforms
 * — resolving to {@code bt-permission-required} / {@code bt-permission-denied}
 * like every other capability checker. {@link #close()} releases the session
 * and the server with the bridge.</p>
 */
public final class BluetoothGattModule implements CapabilityModule {
    private static final String TAG = "BluetoothGattModule";

    private static final String METHOD_CONNECT = "bt.gatt.connect";
    private static final String METHOD_SERVICES = "bt.gatt.services";
    private static final String METHOD_READ = "bt.gatt.read";
    private static final String METHOD_WRITE = "bt.gatt.write";
    private static final String METHOD_NOTIFY_START = "bt.gatt.notify.start";
    private static final String METHOD_NOTIFY_POLL = "bt.gatt.notify.poll";
    private static final String METHOD_NOTIFY_STOP = "bt.gatt.notify.stop";
    private static final String METHOD_DISCONNECT = "bt.gatt.disconnect";
    private static final String METHOD_SERVER_START = "bt.gatt.server.start";
    private static final String METHOD_SERVER_STOP = "bt.gatt.server.stop";
    private static final String METHOD_SERVER_NOTIFY = "bt.gatt.server.notify";

    private static final int MAX_ADDRESS_CHARS = 17;
    private static final int MAX_UUID_CHARS = 64;
    private static final int MAX_VALUE_CHARS = 4096;
    private static final int MAX_SERVER_NAME_CHARS = 32;
    private static final int MAX_READ_BYTES = 8 * 1024;
    private static final int MAX_NOTIFY_POLL = 50;
    /** Buffered notifications kept between polls; oldest drop past the cap. */
    private static final int MAX_NOTIFY_BUFFER = 128;

    static final long CONNECT_TIMEOUT_MILLIS = 30_000L;
    static final long IO_TIMEOUT_MILLIS = 10_000L;

    /** Fixed NusaDesk GATT bridge service (minted once, stable). */
    static final UUID SERVICE_UUID =
            UUID.fromString("7a1f0001-3c4b-4d6e-9f2a-cb8a4d6e5f01");
    /** The server's single read/write/notify characteristic. */
    static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("7a1f0002-3c4b-4d6e-9f2a-cb8a4d6e5f01");
    /** Client Characteristic Configuration descriptor (Bluetooth SIG). */
    static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final AndroidPermissionChecker permissions;
    private final BluetoothManager bluetoothManager;
    private final long connectTimeoutMillis;
    private final long ioTimeoutMillis;
    private final Object lock = new Object();

    // Client session state, guarded by lock.
    private BluetoothGatt clientGatt;
    private boolean clientConnected;
    private List<BluetoothGattService> discoveredServices;
    private ConnectWait connectWait;
    private PendingOp pendingOp;
    private String subscribedUuid;
    private final Deque<NotificationRecord> notifications = new ArrayDeque<>();

    // Server state, guarded by lock.
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic serverCharacteristic;
    private boolean serverServiceAdded;
    private final Map<String, BluetoothDevice> subscribers = new LinkedHashMap<>();
    private byte[] serverValue = new byte[0];

    private volatile boolean closed;

    /**
     * The client callback is package-private as the test seam: a Robolectric
     * test injects an adopted {@link BluetoothGatt} through the constructor
     * and attaches this callback through the shadow so the state machine runs
     * against the same object the module uses.
     */
    final BluetoothGattCallback clientCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            synchronized (lock) {
                if (gatt != clientGatt) {
                    // A fast stack can report the state change before
                    // connectGatt returns and the session is stored; adopt
                    // the in-flight attempt's object instead of dropping it.
                    if (clientGatt != null || connectWait == null) {
                        return;
                    }
                    clientGatt = gatt;
                }
                if (status == BluetoothGatt.GATT_SUCCESS
                        && newState == BluetoothProfile.STATE_CONNECTED) {
                    clientConnected = true;
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                        || status != BluetoothGatt.GATT_SUCCESS) {
                    dropClientStateLocked();
                }
                ConnectWait wait = connectWait;
                if (wait != null) {
                    wait.status = status;
                    wait.newState = newState;
                    wait.done.countDown();
                }
                if (!clientConnected) {
                    abortPendingOpLocked();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            synchronized (lock) {
                if (gatt != clientGatt || pendingOp == null
                        || pendingOp.kind != OpKind.DISCOVER) {
                    return;
                }
                pendingOp.status = status;
                pendingOp.done.countDown();
            }
        }

        /** Pre-33 read callback: the value lives on the characteristic. */
        @Override
        @SuppressLint("Deprecation")
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            byte[] value = characteristic == null ? null : characteristic.getValue();
            completeRead(gatt, characteristic, value, status);
        }

        /** API 33+ read callback: the platform hands the value over directly. */
        @Override
        @SuppressLint("NewApi")
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         byte[] value, int status) {
            completeRead(gatt, characteristic, value, status);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            synchronized (lock) {
                if (gatt != clientGatt || pendingOp == null
                        || pendingOp.kind != OpKind.WRITE
                        || !sameCharacteristic(pendingOp.characteristic, characteristic)) {
                    return;
                }
                pendingOp.status = status;
                pendingOp.done.countDown();
            }
        }

        /** Pre-33 change callback: the value lives on the characteristic. */
        @Override
        @SuppressLint("Deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            recordNotification(gatt, characteristic,
                    characteristic == null ? null : characteristic.getValue());
        }

        /** API 33+ change callback: the platform hands the value over directly. */
        @Override
        @SuppressLint("NewApi")
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            recordNotification(gatt, characteristic, value);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            synchronized (lock) {
                if (gatt != clientGatt || pendingOp == null
                        || pendingOp.kind != OpKind.DESCRIPTOR
                        || !sameDescriptor(pendingOp.descriptor, descriptor)) {
                    return;
                }
                pendingOp.status = status;
                pendingOp.done.countDown();
            }
        }
    };

    /**
     * The server callback is package-private for the same test-seam reason as
     * {@link #clientCallback}: a test drives subscription and peer writes
     * through it without a radio.
     */
    final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status,
                                            int newState) {
            if (device == null) {
                return;
            }
            synchronized (lock) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribers.remove(device.getAddress());
                }
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {
            byte[] value;
            synchronized (lock) {
                value = serverValue;
            }
            byte[] response = offset <= 0 ? value
                    : offset < value.length
                            ? Arrays.copyOfRange(value, offset, value.length)
                            : new byte[0];
            sendResponseQuietly(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, response);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            synchronized (lock) {
                serverValue = mergeAtOffset(serverValue, offset, value);
            }
            if (responseNeeded) {
                sendResponseQuietly(device, requestId, BluetoothGatt.GATT_SUCCESS,
                        offset, value);
            }
        }

        @Override
        @SuppressLint("Deprecation")
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                            int offset, BluetoothGattDescriptor descriptor) {
            byte[] value = descriptor == null || descriptor.getValue() == null
                    ? new byte[0] : descriptor.getValue();
            sendResponseQuietly(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, value);
        }

        @Override
        @SuppressLint("Deprecation")
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (descriptor == null || !CCCD_UUID.equals(descriptor.getUuid())
                    || value == null) {
                sendResponseQuietly(device, requestId, BluetoothGatt.GATT_FAILURE,
                        offset, null);
                return;
            }
            if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    || Arrays.equals(value,
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                synchronized (lock) {
                    subscribers.put(device.getAddress(), device);
                }
            } else if (Arrays.equals(value,
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                synchronized (lock) {
                    subscribers.remove(device.getAddress());
                }
            } else {
                sendResponseQuietly(device, requestId, BluetoothGatt.GATT_FAILURE,
                        offset, null);
                return;
            }
            sendResponseQuietly(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, value);
        }
    };

    public BluetoothGattModule(Context context) {
        this(context, bluetoothManagerFrom(context), null, null,
                CONNECT_TIMEOUT_MILLIS, IO_TIMEOUT_MILLIS);
    }

    /**
     * Package-private test seam: inject the platform manager plus, optionally,
     * an already-obtained client {@link BluetoothGatt} or
     * {@link BluetoothGattServer} (either may be {@code null}), and bounded
     * timeouts. An adopted client session counts toward the one-session rule;
     * its connected state is still driven by {@link #clientCallback}.
     */
    BluetoothGattModule(Context context, BluetoothManager bluetoothManager,
                        BluetoothGatt adoptedClient, BluetoothGattServer adoptedServer,
                        long connectTimeoutMillis, long ioTimeoutMillis) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (connectTimeoutMillis <= 0 || ioTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.context = context.getApplicationContext();
        this.permissions = new AndroidPermissionChecker(context);
        this.bluetoothManager = bluetoothManager;
        this.clientGatt = adoptedClient;
        this.gattServer = adoptedServer;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.ioTimeoutMillis = ioTimeoutMillis;
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_CONNECT, METHOD_SERVICES, METHOD_READ, METHOD_WRITE,
                METHOD_NOTIFY_START, METHOD_NOTIFY_POLL, METHOD_NOTIFY_STOP,
                METHOD_DISCONNECT, METHOD_SERVER_START, METHOD_SERVER_STOP,
                METHOD_SERVER_NOTIFY);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_CONNECT, METHOD_READ, METHOD_WRITE,
                METHOD_NOTIFY_START, METHOD_NOTIFY_STOP, METHOD_SERVER_START,
                METHOD_SERVER_NOTIFY);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        if (closed) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable");
        }
        switch (request.getMethod()) {
            case METHOD_CONNECT:
                return connect(request);
            case METHOD_SERVICES:
                return services(request);
            case METHOD_READ:
                return read(request);
            case METHOD_WRITE:
                return write(request);
            case METHOD_NOTIFY_START:
                return notifyStart(request);
            case METHOD_NOTIFY_POLL:
                return notifyPoll(request);
            case METHOD_NOTIFY_STOP:
                return notifyStop(request);
            case METHOD_DISCONNECT:
                return disconnect(request);
            case METHOD_SERVER_START:
                return serverStart(request);
            case METHOD_SERVER_STOP:
                return serverStop(request);
            case METHOD_SERVER_NOTIFY:
                return serverNotify(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void close() {
        BluetoothGatt gatt;
        BluetoothGattServer server;
        synchronized (lock) {
            closed = true;
            gatt = clientGatt;
            server = gattServer;
            dropClientStateLocked();
            clientGatt = null;
            gattServer = null;
            serverServiceAdded = false;
            subscribers.clear();
            abortPendingOpLocked();
            ConnectWait wait = connectWait;
            if (wait != null) {
                wait.done.countDown();
            }
        }
        closeGattQuietly(gatt);
        if (server != null) {
            try {
                server.clearServices();
            } catch (RuntimeException e) {
                Log.w(TAG, "gatt server clearServices failed", e);
            }
            try {
                server.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "gatt server close failed", e);
            }
        }
    }

    /**
     * {@code bt.gatt.connect} — param {@code address} (required, ≤17 chars).
     * Waits up to {@link #connectTimeoutMillis} for the connection state
     * callback; success carries {@code connected=true} and {@code address}.
     */
    private AndroidCapabilityProtocol.Response connect(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        String address = params.requireString("address", MAX_ADDRESS_CHARS);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothAdapter adapter = adapterOrNull();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable");
        }
        if (!adapterEnabled(adapter)) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable:bluetooth is off");
        }
        ConnectWait wait = new ConnectWait();
        BluetoothDevice device;
        BluetoothGatt gatt;
        synchronized (lock) {
            if (clientGatt != null || connectWait != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-gatt-busy");
            }
            connectWait = wait;
        }
        try {
            device = adapter.getRemoteDevice(address);
        } catch (RuntimeException e) {
            endConnectWait(wait);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-device-unknown:" + address);
        }
        if (device == null) {
            endConnectWait(wait);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-device-unknown:" + address);
        }
        try {
            gatt = device.connectGatt(context, false, clientCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            endConnectWait(wait);
            Log.w(TAG, "bt.gatt.connect refused", e);
            String retry = connectGrantError();
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    retry != null ? retry : "bt-unavailable");
        } catch (RuntimeException e) {
            endConnectWait(wait);
            Log.w(TAG, "bt.gatt.connect failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-device-unknown:" + address);
        }
        if (gatt == null) {
            endConnectWait(wait);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-device-unknown:" + address);
        }
        synchronized (lock) {
            clientGatt = gatt;
        }
        boolean completed = awaitLatch(wait.done, connectTimeoutMillis);
        synchronized (lock) {
            connectWait = null;
            if (completed && clientConnected) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("connected", true);
                fields.put("address", deviceAddress(device));
                return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
            }
            // Failed or timed out: the session object is torn down so a retry
            // starts clean.
            dropClientStateLocked();
            clientGatt = null;
        }
        closeGattQuietly(gatt);
        return AndroidCapabilityProtocol.Response.error(request.getId(),
                completed ? "bt-device-unknown:" + address : "bt-gatt-timeout");
    }

    /**
     * {@code bt.gatt.services} — no params. Discovers on first use and answers
     * {@code services_json} plus {@code count}; a silent or refused discovery
     * is {@code bt-gatt-timeout}, a failed one {@code bt-gatt-discover-failed}.
     */
    private AndroidCapabilityProtocol.Response services(
            AndroidCapabilityProtocol.Request request) {
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothGatt gatt = connectedGatt();
        if (gatt == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-connected");
        }
        AndroidCapabilityProtocol.Response discoveryError =
                ensureDiscovered(gatt, request.getId());
        if (discoveryError != null) {
            return discoveryError;
        }
        List<BluetoothGattService> services;
        synchronized (lock) {
            services = discoveredServices == null
                    ? List.of() : new ArrayList<>(discoveredServices);
        }
        StringBuilder json = new StringBuilder(256);
        json.append('[');
        for (int i = 0; i < services.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(serviceJson(services.get(i)));
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("services_json", json.toString());
        fields.put("count", (long) services.size());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code bt.gatt.read} — params {@code service_uuid}, {@code char_uuid}.
     * Success carries {@code value_hex} (or {@code value_utf8} when the payload
     * is printable ASCII) and {@code length}; a missing characteristic is
     * {@code bt-gatt-unknown-characteristic} and a non-readable one is
     * {@code bt-gatt-not-readable}.
     */
    private AndroidCapabilityProtocol.Response read(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "char_uuid"));
        String serviceUuid = params.requireString("service_uuid", MAX_UUID_CHARS);
        String charUuid = params.requireString("char_uuid", MAX_UUID_CHARS);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothGatt gatt = connectedGatt();
        if (gatt == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-connected");
        }
        AndroidCapabilityProtocol.Response discoveryError =
                ensureDiscovered(gatt, request.getId());
        if (discoveryError != null) {
            return discoveryError;
        }
        BluetoothGattCharacteristic characteristic =
                findCharacteristic(gatt, serviceUuid, charUuid);
        if (characteristic == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-unknown-characteristic");
        }
        if ((characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-readable");
        }
        PendingOp op = beginOp(OpKind.READ, characteristic, null);
        if (op == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-busy");
        }
        try {
            try {
                // A refused initiation is still awaited: the callback channel
                // is the single source of truth for the outcome, and the
                // bounded window keeps a silent failure from hanging the
                // connection worker.
                gatt.readCharacteristic(characteristic);
            } catch (SecurityException e) {
                String retry = connectGrantError();
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        retry != null ? retry : "bt-unavailable");
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.gatt.read initiate failed", e);
            }
            boolean completed = awaitLatch(op.done, ioTimeoutMillis);
            if (completed && op.status == BluetoothGatt.GATT_SUCCESS
                    && op.value != null) {
                byte[] value = op.value.length > MAX_READ_BYTES
                        ? Arrays.copyOf(op.value, MAX_READ_BYTES) : op.value;
                Map<String, Object> fields = new LinkedHashMap<>();
                if (isPrintable(value)) {
                    fields.put("value_utf8", new String(value, StandardCharsets.UTF_8));
                } else {
                    fields.put("value_hex", hexEncode(value));
                }
                fields.put("length", (long) value.length);
                return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
            }
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-timeout");
        } finally {
            endOp(op);
        }
    }

    /**
     * {@code bt.gatt.write} — params {@code service_uuid}, {@code char_uuid},
     * and exactly one of {@code value_hex} / {@code value_utf8}. Success
     * carries {@code written=true} and {@code length}; a refused or failed
     * write is {@code bt-gatt-write-failed} and a silent stack is
     * {@code bt-gatt-timeout}.
     */
    private AndroidCapabilityProtocol.Response write(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "char_uuid",
                "value_hex", "value_utf8"));
        String serviceUuid = params.requireString("service_uuid", MAX_UUID_CHARS);
        String charUuid = params.requireString("char_uuid", MAX_UUID_CHARS);
        byte[] value = decodeValueParam(params);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothGatt gatt = connectedGatt();
        if (gatt == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-connected");
        }
        AndroidCapabilityProtocol.Response discoveryError =
                ensureDiscovered(gatt, request.getId());
        if (discoveryError != null) {
            return discoveryError;
        }
        BluetoothGattCharacteristic characteristic =
                findCharacteristic(gatt, serviceUuid, charUuid);
        if (characteristic == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-unknown-characteristic");
        }
        if ((characteristic.getProperties()
                & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-writable");
        }
        PendingOp op = beginOp(OpKind.WRITE, characteristic, null);
        if (op == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-busy");
        }
        try {
            try {
                initiateWrite(gatt, characteristic, value);
            } catch (SecurityException e) {
                String retry = connectGrantError();
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        retry != null ? retry : "bt-unavailable");
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.gatt.write initiate failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-gatt-write-failed");
            }
            boolean completed = awaitLatch(op.done, ioTimeoutMillis);
            if (completed && op.status == BluetoothGatt.GATT_SUCCESS) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("written", true);
                fields.put("length", (long) value.length);
                return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
            }
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    completed ? "bt-gatt-write-failed" : "bt-gatt-timeout");
        } finally {
            endOp(op);
        }
    }

    /**
     * {@code bt.gatt.notify.start} — params {@code service_uuid},
     * {@code char_uuid}. Enables local notification and writes the CCCD;
     * success carries {@code subscribed=true}. A characteristic without
     * notify/indicate is {@code bt-gatt-not-notifiable}; a refused local
     * enable, a missing CCCD, or a failed descriptor write is
     * {@code bt-gatt-subscribe-failed}.
     */
    private AndroidCapabilityProtocol.Response notifyStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "char_uuid"));
        String serviceUuid = params.requireString("service_uuid", MAX_UUID_CHARS);
        String charUuid = params.requireString("char_uuid", MAX_UUID_CHARS);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothGatt gatt = connectedGatt();
        if (gatt == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-connected");
        }
        AndroidCapabilityProtocol.Response discoveryError =
                ensureDiscovered(gatt, request.getId());
        if (discoveryError != null) {
            return discoveryError;
        }
        BluetoothGattCharacteristic characteristic =
                findCharacteristic(gatt, serviceUuid, charUuid);
        if (characteristic == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-unknown-characteristic");
        }
        int properties = characteristic.getProperties();
        boolean notifiable =
                (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean indicatable =
                (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        if (!notifiable && !indicatable) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-not-notifiable");
        }
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-subscribe-failed");
        }
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-gatt-subscribe-failed");
            }
        } catch (SecurityException e) {
            String retry = connectGrantError();
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    retry != null ? retry : "bt-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.gatt.notify.start local enable failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-subscribe-failed");
        }
        PendingOp op = beginOp(OpKind.DESCRIPTOR, characteristic, cccd);
        if (op == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-gatt-busy");
        }
        try {
            byte[] enable = notifiable
                    ? cccdEnableNotificationValue()
                    : cccdEnableIndicationValue();
            try {
                initiateDescriptorWrite(gatt, cccd, enable);
            } catch (SecurityException e) {
                String retry = connectGrantError();
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        retry != null ? retry : "bt-unavailable");
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.gatt.notify.start CCCD write failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-gatt-subscribe-failed");
            }
            boolean completed = awaitLatch(op.done, ioTimeoutMillis);
            if (completed && op.status == BluetoothGatt.GATT_SUCCESS) {
                synchronized (lock) {
                    subscribedUuid = charUuid;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("subscribed", true);
                return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
            }
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    completed ? "bt-gatt-subscribe-failed" : "bt-gatt-timeout");
        } finally {
            endOp(op);
        }
    }

    /**
     * {@code bt.gatt.notify.poll} — no params. Drains up to
     * {@link #MAX_NOTIFY_POLL} buffered notifications into
     * {@code notifications_json} ({@code char_uuid}, {@code value_hex},
     * {@code at_ms} rows) plus {@code count}.
     */
    private AndroidCapabilityProtocol.Response notifyPoll(
            AndroidCapabilityProtocol.Request request) {
        List<NotificationRecord> drained = new ArrayList<>();
        synchronized (lock) {
            while (!notifications.isEmpty() && drained.size() < MAX_NOTIFY_POLL) {
                drained.add(notifications.pollFirst());
            }
        }
        StringBuilder json = new StringBuilder(drained.size() * 96);
        json.append('[');
        for (int i = 0; i < drained.size(); i++) {
            NotificationRecord record = drained.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"char_uuid\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(record.charUuid))
                    .append(",\"value_hex\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(
                            hexEncode(record.value)))
                    .append(",\"at_ms\":").append(record.atMs)
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("notifications_json", json.toString());
        fields.put("count", (long) drained.size());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code bt.gatt.notify.stop} — params {@code service_uuid},
     * {@code char_uuid}. Idempotent: always answers {@code subscribed=false};
     * the CCCD disable write is attempted only while connected.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response notifyStop(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "char_uuid"));
        String serviceUuid = params.requireString("service_uuid", MAX_UUID_CHARS);
        String charUuid = params.requireString("char_uuid", MAX_UUID_CHARS);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothGatt gatt = connectedGatt();
        if (gatt != null) {
            BluetoothGattCharacteristic characteristic =
                    findCharacteristic(gatt, serviceUuid, charUuid);
            if (characteristic != null) {
                try {
                    gatt.setCharacteristicNotification(characteristic, false);
                } catch (RuntimeException e) {
                    Log.w(TAG, "bt.gatt.notify.stop local disable failed", e);
                }
                BluetoothGattDescriptor cccd =
                        characteristic.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    try {
                        initiateDescriptorWrite(gatt, cccd,
                                cccdDisableNotificationValue());
                    } catch (RuntimeException e) {
                        Log.w(TAG, "bt.gatt.notify.stop CCCD disable failed", e);
                    }
                }
            }
        }
        synchronized (lock) {
            if (charUuid.equals(subscribedUuid)) {
                subscribedUuid = null;
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("subscribed", false);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code bt.gatt.disconnect} — no params, idempotent. */
    private AndroidCapabilityProtocol.Response disconnect(
            AndroidCapabilityProtocol.Request request) {
        BluetoothGatt gatt;
        synchronized (lock) {
            gatt = clientGatt;
            dropClientStateLocked();
            clientGatt = null;
            abortPendingOpLocked();
        }
        closeGattQuietly(gatt);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("connected", false);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code bt.gatt.server.start} — optional {@code name} ≤32 chars applied
     * best-effort as the adapter display name. Opens the GATT server and
     * registers the fixed NusaDesk service; success carries {@code
     * started=true} and {@code service_uuid}. Starting an open server is a
     * no-op success.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response serverStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("name"));
        String name = params.optionalString("name", MAX_SERVER_NAME_CHARS, null);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothAdapter adapter = adapterOrNull();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable");
        }
        if (name != null) {
            try {
                adapter.setName(name);
            } catch (RuntimeException e) {
                // The display name only matters once a peer advertises/scans;
                // a refusal must not keep the server down.
                Log.w(TAG, "bt.gatt.server.start adapter name refused", e);
            }
        }
        synchronized (lock) {
            if (gattServer == null) {
                BluetoothGattServer opened;
                try {
                    opened = bluetoothManager.openGattServer(context, serverCallback);
                } catch (SecurityException e) {
                    Log.w(TAG, "bt.gatt.server.start refused", e);
                    String retry = connectGrantError();
                    return AndroidCapabilityProtocol.Response.error(request.getId(),
                            retry != null ? retry : "bt-unavailable");
                } catch (RuntimeException e) {
                    Log.w(TAG, "bt.gatt.server.start failed", e);
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "bt-unavailable");
                }
                if (opened == null) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "bt-unavailable");
                }
                gattServer = opened;
            }
            if (!serverServiceAdded) {
                BluetoothGattService service = buildServerService();
                boolean added;
                try {
                    added = gattServer.addService(service);
                } catch (RuntimeException e) {
                    Log.w(TAG, "bt.gatt.server.start addService failed", e);
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "bt-gatt-server-start-failed");
                }
                if (!added) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "bt-gatt-server-start-failed");
                }
                serverServiceAdded = true;
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("started", true);
        fields.put("service_uuid", SERVICE_UUID.toString());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code bt.gatt.server.stop} — no params, idempotent. */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response serverStop(
            AndroidCapabilityProtocol.Request request) {
        BluetoothGattServer server;
        synchronized (lock) {
            server = gattServer;
            gattServer = null;
            serverServiceAdded = false;
            subscribers.clear();
            serverValue = new byte[0];
        }
        if (server != null) {
            try {
                server.clearServices();
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.gatt.server.stop clearServices failed", e);
            }
            try {
                server.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.gatt.server.stop close failed", e);
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stopped", true);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code bt.gatt.server.notify} — exactly one of {@code value_hex} /
     * {@code value_utf8}. Pushes the server characteristic value to every
     * CCCD-subscribed device; success carries {@code delivered} (notifies
     * accepted by the stack) and {@code subscribers}. No subscribers is
     * {@code bt-gatt-server-no-subscribers}; a closed server is
     * {@code bt-gatt-server-not-started}.
     */
    private AndroidCapabilityProtocol.Response serverNotify(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("value_hex", "value_utf8"));
        byte[] value = decodeValueParam(params);
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothGattServer server;
        BluetoothGattCharacteristic characteristic;
        List<BluetoothDevice> targets;
        synchronized (lock) {
            if (gattServer == null || !serverServiceAdded) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-gatt-server-not-started");
            }
            if (subscribers.isEmpty()) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-gatt-server-no-subscribers");
            }
            server = gattServer;
            characteristic = serverCharacteristic;
            serverValue = value;
            targets = new ArrayList<>(subscribers.values());
        }
        int delivered = 0;
        for (BluetoothDevice device : targets) {
            if (notifySubscriber(server, device, characteristic, value)) {
                delivered++;
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("delivered", (long) delivered);
        fields.put("subscribers", (long) targets.size());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    // ----- client helpers -----

    private void completeRead(BluetoothGatt gatt,
                              BluetoothGattCharacteristic characteristic,
                              byte[] value, int status) {
        synchronized (lock) {
            if (gatt != clientGatt || pendingOp == null
                    || pendingOp.kind != OpKind.READ
                    || !sameCharacteristic(pendingOp.characteristic, characteristic)) {
                return;
            }
            pendingOp.status = status;
            pendingOp.value = value;
            pendingOp.done.countDown();
        }
    }

    private void recordNotification(BluetoothGatt gatt,
                                    BluetoothGattCharacteristic characteristic,
                                    byte[] value) {
        if (characteristic == null) {
            return;
        }
        NotificationRecord record = new NotificationRecord(
                characteristic.getUuid().toString(),
                value == null ? new byte[0] : value,
                System.currentTimeMillis());
        synchronized (lock) {
            if (gatt != clientGatt) {
                return;
            }
            if (notifications.size() >= MAX_NOTIFY_BUFFER) {
                notifications.pollFirst();
            }
            notifications.addLast(record);
        }
    }

    /**
     * Runs service discovery once per session and caches the outcome;
     * {@code null} when the cache is warm, else the typed error response.
     */
    private AndroidCapabilityProtocol.Response ensureDiscovered(
            BluetoothGatt gatt, String requestId) {
        synchronized (lock) {
            if (discoveredServices != null) {
                return null;
            }
        }
        PendingOp op = beginOp(OpKind.DISCOVER, null, null);
        if (op == null) {
            return AndroidCapabilityProtocol.Response.error(requestId, "bt-gatt-busy");
        }
        try {
            try {
                gatt.discoverServices();
            } catch (SecurityException e) {
                String retry = connectGrantError();
                return AndroidCapabilityProtocol.Response.error(requestId,
                        retry != null ? retry : "bt-unavailable");
            } catch (RuntimeException e) {
                Log.w(TAG, "service discovery initiate failed", e);
            }
            boolean completed = awaitLatch(op.done, ioTimeoutMillis);
            if (!completed) {
                return AndroidCapabilityProtocol.Response.error(
                        requestId, "bt-gatt-timeout");
            }
            if (op.status != BluetoothGatt.GATT_SUCCESS) {
                return AndroidCapabilityProtocol.Response.error(
                        requestId, "bt-gatt-discover-failed");
            }
            List<BluetoothGattService> services;
            try {
                services = gatt.getServices();
            } catch (RuntimeException e) {
                services = null;
            }
            synchronized (lock) {
                discoveredServices =
                        services == null ? List.of() : new ArrayList<>(services);
            }
            return null;
        } finally {
            endOp(op);
        }
    }

    private BluetoothGatt connectedGatt() {
        synchronized (lock) {
            return clientConnected ? clientGatt : null;
        }
    }

    private BluetoothGattCharacteristic findCharacteristic(
            BluetoothGatt gatt, String serviceUuid, String charUuid) {
        UUID serviceId;
        UUID charId;
        try {
            serviceId = UUID.fromString(serviceUuid);
            charId = UUID.fromString(charUuid);
        } catch (RuntimeException e) {
            throw new CapabilityParams.Invalid("malformed uuid parameter");
        }
        List<BluetoothGattService> services;
        synchronized (lock) {
            services = discoveredServices;
        }
        if (services != null) {
            for (BluetoothGattService service : services) {
                if (service.getUuid().equals(serviceId)) {
                    BluetoothGattCharacteristic characteristic =
                            service.getCharacteristic(charId);
                    if (characteristic != null) {
                        return characteristic;
                    }
                }
            }
        }
        BluetoothGattService service = gatt.getService(serviceId);
        return service == null ? null : service.getCharacteristic(charId);
    }

    private PendingOp beginOp(OpKind kind,
                              BluetoothGattCharacteristic characteristic,
                              BluetoothGattDescriptor descriptor) {
        synchronized (lock) {
            if (pendingOp != null || clientGatt == null) {
                return null;
            }
            PendingOp op = new PendingOp(kind, characteristic, descriptor);
            pendingOp = op;
            return op;
        }
    }

    private void endOp(PendingOp op) {
        synchronized (lock) {
            if (pendingOp == op) {
                pendingOp = null;
            }
        }
    }

    private void endConnectWait(ConnectWait wait) {
        synchronized (lock) {
            if (connectWait == wait) {
                connectWait = null;
            }
        }
    }

    private void dropClientStateLocked() {
        clientConnected = false;
        discoveredServices = null;
        subscribedUuid = null;
        notifications.clear();
    }

    private void abortPendingOpLocked() {
        PendingOp op = pendingOp;
        if (op != null) {
            op.status = BluetoothGatt.GATT_FAILURE;
            op.done.countDown();
            pendingOp = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGattQuietly(BluetoothGatt gatt) {
        if (gatt == null) {
            return;
        }
        try {
            gatt.disconnect();
        } catch (RuntimeException e) {
            Log.w(TAG, "gatt disconnect failed", e);
        }
        try {
            gatt.close();
        } catch (RuntimeException e) {
            Log.w(TAG, "gatt close failed", e);
        }
    }

    private static boolean awaitLatch(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ----- write initiation across API levels -----

    @SuppressLint({"MissingPermission", "Deprecation"})
    private void initiateWrite(BluetoothGatt gatt,
                               BluetoothGattCharacteristic characteristic,
                               byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int status = gatt.writeCharacteristic(characteristic, value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (status != BluetoothStatusCodes.SUCCESS) {
                throw new IllegalStateException("writeCharacteristic refused: " + status);
            }
            return;
        }
        if (!characteristic.setValue(value) || !gatt.writeCharacteristic(characteristic)) {
            throw new IllegalStateException("writeCharacteristic refused");
        }
    }

    @SuppressLint({"MissingPermission", "Deprecation"})
    private void initiateDescriptorWrite(BluetoothGatt gatt,
                                         BluetoothGattDescriptor descriptor,
                                         byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int status = gatt.writeDescriptor(descriptor, value);
            if (status != BluetoothStatusCodes.SUCCESS) {
                throw new IllegalStateException("writeDescriptor refused: " + status);
            }
            return;
        }
        if (!descriptor.setValue(value) || !gatt.writeDescriptor(descriptor)) {
            throw new IllegalStateException("writeDescriptor refused");
        }
    }

    // ----- server helpers -----

    private BluetoothGattService buildServerService() {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ
                        | BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
                        | BluetoothGattCharacteristic.PERMISSION_WRITE);
        characteristic.addDescriptor(new BluetoothGattDescriptor(CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE));
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(characteristic);
        synchronized (lock) {
            serverCharacteristic = characteristic;
        }
        return service;
    }

    @SuppressLint("MissingPermission")
    private void sendResponseQuietly(BluetoothDevice device, int requestId,
                                     int status, int offset, byte[] value) {
        BluetoothGattServer server;
        synchronized (lock) {
            server = gattServer;
        }
        if (server == null) {
            return;
        }
        try {
            server.sendResponse(device, requestId, status, offset, value);
        } catch (RuntimeException e) {
            Log.w(TAG, "gatt server sendResponse failed", e);
        }
    }

    /**
     * One notify to a subscribed device. The API 33+ call returns a status
     * code; the legacy call returns nothing, so an unthrown issue counts as
     * accepted-by-the-stack. A platform failure counts as undelivered.
     */
    @SuppressLint({"MissingPermission", "Deprecation"})
    private boolean notifySubscriber(BluetoothGattServer server,
                                     BluetoothDevice device,
                                     BluetoothGattCharacteristic characteristic,
                                     byte[] value) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return server.notifyCharacteristicChanged(device, characteristic,
                        false, value) == BluetoothStatusCodes.SUCCESS;
            }
            characteristic.setValue(value);
            server.notifyCharacteristicChanged(device, characteristic, false);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "gatt server notify failed", e);
            return false;
        }
    }

    private static byte[] mergeAtOffset(byte[] base, int offset, byte[] value) {
        if (offset <= 0) {
            return value == null ? new byte[0] : value;
        }
        int length = Math.min(offset + (value == null ? 0 : value.length),
                MAX_READ_BYTES);
        byte[] merged = Arrays.copyOf(base, Math.max(base.length, length));
        if (offset < merged.length && value != null) {
            System.arraycopy(value, 0, merged, offset,
                    Math.min(value.length, merged.length - offset));
        }
        return merged;
    }

    // ----- shared helpers -----

    private BluetoothAdapter adapterOrNull() {
        BluetoothManager manager = bluetoothManager;
        if (manager == null) {
            return null;
        }
        try {
            return manager.getAdapter();
        } catch (RuntimeException e) {
            Log.w(TAG, "bluetooth adapter lookup failed", e);
            return null;
        }
    }

    private static boolean adapterEnabled(BluetoothAdapter adapter) {
        try {
            return adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String deviceAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * The grant needed for every GATT entry point: {@code BLUETOOTH_CONNECT}
     * on API 31+, the install-granted legacy {@code BLUETOOTH} below it.
     * {@code null} when granted, else the typed {@code bt-permission-*} error.
     */
    private String connectGrantError() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Manifest.permission.BLUETOOTH_CONNECT
                : Manifest.permission.BLUETOOTH;
        CapabilityPermission state = permissions.check(permission);
        if (state == CapabilityPermission.GRANTED) {
            return null;
        }
        return state == CapabilityPermission.DENIED
                ? "bt-permission-denied:grant the bluetooth permission in app settings"
                : "bt-permission-required:grant " + permission
                        + " via permission.request";
    }

    private static BluetoothManager bluetoothManagerFrom(Context context) {
        try {
            return (BluetoothManager) context.getSystemService(
                    Context.BLUETOOTH_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean sameCharacteristic(BluetoothGattCharacteristic expected,
                                              BluetoothGattCharacteristic actual) {
        return expected != null && actual != null
                && (expected == actual
                        || expected.getUuid().equals(actual.getUuid()));
    }

    private static boolean sameDescriptor(BluetoothGattDescriptor expected,
                                          BluetoothGattDescriptor actual) {
        return expected != null && actual != null
                && (expected == actual
                        || expected.getUuid().equals(actual.getUuid()));
    }

    /** Exactly one of {@code value_hex} / {@code value_utf8}, decoded. */
    private static byte[] decodeValueParam(CapabilityParams params) {
        boolean hasHex = params.has("value_hex");
        boolean hasUtf8 = params.has("value_utf8");
        if (hasHex == hasUtf8) {
            throw new CapabilityParams.Invalid(
                    "exactly one of value_hex or value_utf8 is required");
        }
        if (hasUtf8) {
            return params.requireString("value_utf8", MAX_VALUE_CHARS)
                    .getBytes(StandardCharsets.UTF_8);
        }
        return decodeHex(params.requireString("value_hex", MAX_VALUE_CHARS));
    }

    private static byte[] decodeHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new CapabilityParams.Invalid("value_hex must have even length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = nibble(hex.charAt(i * 2));
            int low = nibble(hex.charAt(i * 2 + 1));
            if (high < 0 || low < 0) {
                throw new CapabilityParams.Invalid(
                        "value_hex must contain only hex digits");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static int nibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String hexEncode(byte[] value) {
        char[] out = new char[value.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            out[i * 2] = digits[(value[i] >> 4) & 0xf];
            out[i * 2 + 1] = digits[value[i] & 0xf];
        }
        return new String(out);
    }

    /** Printable = non-empty printable ASCII plus CR/LF/TAB. */
    private static boolean isPrintable(byte[] value) {
        if (value.length == 0) {
            return false;
        }
        for (byte b : value) {
            int c = b & 0xff;
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private static String serviceJson(BluetoothGattService service) {
        StringBuilder json = new StringBuilder(192);
        json.append("{\"uuid\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(
                        service.getUuid().toString()))
                .append(",\"type\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(
                        service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY
                                ? "primary" : "secondary"))
                .append(",\"characteristics\":[");
        List<BluetoothGattCharacteristic> characteristics =
                service.getCharacteristics();
        for (int i = 0; i < characteristics.size(); i++) {
            BluetoothGattCharacteristic characteristic = characteristics.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"uuid\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(
                            characteristic.getUuid().toString()))
                    .append(",\"properties\":").append(characteristic.getProperties())
                    .append(",\"permissions\":").append(characteristic.getPermissions())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    @SuppressLint("Deprecation")
    private static byte[] cccdEnableNotificationValue() {
        return BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
    }

    @SuppressLint("Deprecation")
    private static byte[] cccdEnableIndicationValue() {
        return BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
    }

    @SuppressLint("Deprecation")
    private static byte[] cccdDisableNotificationValue() {
        return BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
    }

    /** Which bridge op a {@link PendingOp} resolves. */
    private enum OpKind {
        DISCOVER, READ, WRITE, DESCRIPTOR
    }

    /** One bounded in-flight client operation completed by a callback. */
    private static final class PendingOp {
        final OpKind kind;
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final CountDownLatch done = new CountDownLatch(1);
        volatile int status = BluetoothGatt.GATT_FAILURE;
        volatile byte[] value;

        PendingOp(OpKind kind, BluetoothGattCharacteristic characteristic,
                  BluetoothGattDescriptor descriptor) {
            this.kind = kind;
            this.characteristic = characteristic;
            this.descriptor = descriptor;
        }
    }

    /** One in-flight connect attempt resolved by onConnectionStateChange. */
    private static final class ConnectWait {
        final CountDownLatch done = new CountDownLatch(1);
        volatile int status = BluetoothGatt.GATT_FAILURE;
        volatile int newState = BluetoothProfile.STATE_DISCONNECTED;
    }

    /** One buffered {@code onCharacteristicChanged} payload. */
    private static final class NotificationRecord {
        final String charUuid;
        final byte[] value;
        final long atMs;

        NotificationRecord(String charUuid, byte[] value, long atMs) {
            this.charUuid = charUuid;
            this.value = value;
            this.atMs = atMs;
        }
    }
}
