package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import gh.nusashell.nusadesk.infrastructure.androidbridge.BluetoothRfcommModule.RfcommTransport.RfcommLink;
import gh.nusashell.nusadesk.infrastructure.androidbridge.BluetoothRfcommModule.RfcommTransport.RfcommListener;

/**
 * Bluetooth Classic serial (RFCOMM) capability domain behind six bridge
 * methods: {@code bt.rfcomm.listen} / {@code accept} / {@code connect} /
 * {@code read} / {@code write} / {@code close}.
 *
 * <p>The module proxies bytes between the guest and one RFCOMM peer at a
 * time; no descriptor is handed off in this slice (the ADR-0041 SCM_RIGHTS
 * path stays a follow-up option). Every blocking platform call — accept,
 * connect, read, write — is bounded by an explicit timeout so a stuck radio
 * can never pin a bridge connection worker: the {@code timeout_ms} param is
 * bounded to {@link #MIN_TIMEOUT_MS}..{@link #MAX_TIMEOUT_MS} and defaults to
 * {@link #DEFAULT_TIMEOUT_MS}.</p>
 *
 * <p>{@code bt.rfcomm.listen} opens a service record and waits one bounded
 * window for a peer. Because the bridge serves concurrent connections, a
 * second {@code bt.rfcomm.accept} call while that window is still open joins
 * the in-flight listen for the rest of its window instead of starting a
 * second accept on the same socket; after the window the listener is closed
 * and {@code accept} answers {@code bt-rfcomm-not-listening}. A successful
 * accept or connect promotes the module to the connected state, which
 * {@code bt.rfcomm.accept} and {@code bt.rfcomm.close} report idempotently.
 * The platform never exposes the negotiated RFCOMM channel, so
 * {@code channel} is always {@code -1} (unreported).</p>
 *
 * <p>Reads carry bytes as lowercase hex in {@code data_hex} (at most
 * {@link #MAX_READ_BYTES} per call) plus {@code length} and an {@code eof}
 * flag; a clean peer close or a dead link reports {@code eof=true} and
 * retires the link. Writes take exactly one of {@code value_hex} or
 * {@code value_utf8}. Runtime work goes through {@link RfcommTransport} so
 * the state machine and typed errors are testable without a device;
 * {@link AndroidRfcommTransport} is the platform implementation.</p>
 */
public final class BluetoothRfcommModule implements CapabilityModule {
    private static final String TAG = "BluetoothRfcommModule";

    static final String METHOD_LISTEN = "bt.rfcomm.listen";
    static final String METHOD_ACCEPT = "bt.rfcomm.accept";
    static final String METHOD_CONNECT = "bt.rfcomm.connect";
    static final String METHOD_READ = "bt.rfcomm.read";
    static final String METHOD_WRITE = "bt.rfcomm.write";
    static final String METHOD_CLOSE = "bt.rfcomm.close";

    /** Default service record: the well-known Serial Port Profile UUID. */
    static final String DEFAULT_SERVICE_UUID =
            "00001101-0000-1000-8000-00805f9b34fb";
    static final String DEFAULT_SERVICE_NAME = "NusaDesk";

    static final long MIN_TIMEOUT_MS = 1L;
    static final long MAX_TIMEOUT_MS = 30_000L;
    static final long DEFAULT_TIMEOUT_MS = 10_000L;

    static final int MAX_ADDRESS_CHARS = 17;
    static final int MAX_UUID_CHARS = 64;
    static final int MAX_NAME_CHARS = 32;
    static final int MAX_VALUE_CHARS = 8192;
    static final int MAX_READ_BYTES = 8192;

    private static final Pattern MAC_ADDRESS =
            Pattern.compile("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}");

    private final RfcommTransport transport;
    private final Supplier<CapabilityPermission> connectGrant;
    private final Object stateLock = new Object();

    /** The one established link, or {@code null}. Guarded by {@link #stateLock}. */
    private RfcommLink link;
    /** The one in-flight listen window, or {@code null}. Guarded by {@link #stateLock}. */
    private ListenWait listenWait;

    public BluetoothRfcommModule(Context context) {
        this(new AndroidRfcommTransport(context), connectGrant(context));
    }

    /**
     * Test seam: the transport boundary plus the effective
     * {@code BLUETOOTH_CONNECT} grant. Production builds both from the app
     * context; unit tests substitute fakes.
     */
    BluetoothRfcommModule(RfcommTransport transport,
                          Supplier<CapabilityPermission> connectGrant) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (connectGrant == null) {
            throw new IllegalArgumentException("connectGrant must not be null");
        }
        this.transport = transport;
        this.connectGrant = connectGrant;
    }

    /**
     * The grant gate for this domain: {@code BLUETOOTH_CONNECT} is a runtime
     * permission from API 31; below it the install-granted legacy pair covers
     * RFCOMM, so the check reports granted without touching the checker.
     */
    private static Supplier<CapabilityPermission> connectGrant(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        AndroidPermissionChecker checker = new AndroidPermissionChecker(context);
        return () -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? checker.check(Manifest.permission.BLUETOOTH_CONNECT)
                : CapabilityPermission.GRANTED;
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_LISTEN, METHOD_ACCEPT, METHOD_CONNECT,
                METHOD_READ, METHOD_WRITE, METHOD_CLOSE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_LISTEN, METHOD_CONNECT, METHOD_READ, METHOD_WRITE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_LISTEN:
                return listen(request);
            case METHOD_ACCEPT:
                return accept(request);
            case METHOD_CONNECT:
                return connect(request);
            case METHOD_READ:
                return read(request);
            case METHOD_WRITE:
                return write(request);
            case METHOD_CLOSE:
                return closeSession(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code bt.rfcomm.listen} — optional params {@code service_uuid}
     * (≤64 chars, defaults to SPP), {@code name} (≤32), {@code timeout_ms}.
     * Opens the service record, then this thread owns the bounded accept; a
     * timed-out window closes the listener again so a retry starts clean.
     */
    private AndroidCapabilityProtocol.Response listen(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "name", "timeout_ms"));
        String name = params.optionalString(
                "name", MAX_NAME_CHARS, DEFAULT_SERVICE_NAME);
        UUID serviceUuid = serviceUuid(params);
        long timeoutMs = timeout(params);

        AndroidCapabilityProtocol.Response denied = unusable(request);
        if (denied != null) {
            return denied;
        }
        ListenWait wait;
        synchronized (stateLock) {
            if (link != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-already-connected");
            }
            if (listenWait != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-already-listening");
            }
            RfcommListener listener;
            try {
                listener = transport.listen(name, serviceUuid);
            } catch (SecurityException e) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-permission-denied");
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "bt.rfcomm.listen: service record failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-listen-failed");
            }
            if (listener == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-listen-failed");
            }
            wait = new ListenWait(listener, System.currentTimeMillis() + timeoutMs);
            listenWait = wait;
        }
        String error = runAccept(wait);
        synchronized (stateLock) {
            if (link != null) {
                return connected(request, link);
            }
        }
        return AndroidCapabilityProtocol.Response.error(request.getId(),
                error != null ? error : "bt-rfcomm-listen-failed");
    }

    /**
     * {@code bt.rfcomm.accept} — no params. Reports the established link, or
     * joins an in-flight listen for the rest of its window.
     */
    private AndroidCapabilityProtocol.Response accept(
            AndroidCapabilityProtocol.Request request) {
        AndroidCapabilityProtocol.Response denied = unusable(request);
        if (denied != null) {
            return denied;
        }
        ListenWait wait;
        synchronized (stateLock) {
            if (link != null) {
                return connected(request, link);
            }
            wait = listenWait;
            if (wait == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-not-listening");
            }
        }
        return settle(request, wait);
    }

    /**
     * The listen owner's platform accept; runs outside {@link #stateLock} so
     * concurrent {@code accept} joiners and {@code close} can proceed. The
     * outcome is published on the {@link ListenWait} for joiners; the owner
     * reports its own typed error.
     */
    private String runAccept(ListenWait wait) {
        RfcommLink accepted = null;
        String error;
        long remaining = wait.deadlineMillis - System.currentTimeMillis();
        try {
            accepted = wait.listener.accept(Math.max(remaining, 1L));
            error = accepted == null ? "bt-rfcomm-listen-failed" : null;
        } catch (SocketTimeoutException e) {
            error = "bt-rfcomm-listen-timeout";
        } catch (IOException | RuntimeException e) {
            error = "bt-rfcomm-listen-failed";
        }
        synchronized (stateLock) {
            wait.accepted = accepted;
            if (wait.error == null) {
                wait.error = error;
            }
            wait.settled = true;
            if (listenWait == wait) {
                listenWait = null;
                if (accepted != null) {
                    link = accepted;
                }
            } else if (accepted != null) {
                // bt.rfcomm.close ran underneath the accept; the link is an
                // orphan and must not leak.
                closeQuietly(accepted);
            }
            closeQuietly(wait.listener);
            stateLock.notifyAll();
        }
        return error;
    }

    /** The shared result of a listen window, for the owner and joiners. */
    private AndroidCapabilityProtocol.Response settle(
            AndroidCapabilityProtocol.Request request, ListenWait wait) {
        synchronized (stateLock) {
            while (!wait.settled) {
                long remaining = wait.deadlineMillis - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    stateLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (link != null) {
                return connected(request, link);
            }
            String error = wait.error != null ? wait.error : "bt-rfcomm-listen-timeout";
            return AndroidCapabilityProtocol.Response.error(request.getId(), error);
        }
    }

    /**
     * {@code bt.rfcomm.connect} — params {@code address} (required peer MAC),
     * {@code service_uuid} (optional, defaults to SPP), {@code timeout_ms}.
     * The platform connect is bounded; on timeout the half-open socket is
     * closed, which is the documented way to abort a pending connect.
     */
    private AndroidCapabilityProtocol.Response connect(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address", "service_uuid", "timeout_ms"));
        String address = params.requireString("address", MAX_ADDRESS_CHARS);
        UUID serviceUuid = serviceUuid(params);
        long timeoutMs = timeout(params);

        if (!MAC_ADDRESS.matcher(address).matches()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-device-unknown:" + address);
        }
        AndroidCapabilityProtocol.Response denied = unusable(request);
        if (denied != null) {
            return denied;
        }
        synchronized (stateLock) {
            if (link != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-already-connected");
            }
            if (listenWait != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-rfcomm-already-listening");
            }
        }
        RfcommLink connected;
        try {
            connected = transport.connect(address, serviceUuid, timeoutMs);
        } catch (SocketTimeoutException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-connect-failed:timeout");
        } catch (SecurityException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-permission-denied");
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-device-unknown:" + address);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "bt.rfcomm.connect failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-connect-failed:io");
        }
        if (connected == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-connect-failed:io");
        }
        synchronized (stateLock) {
            if (link != null || listenWait != null) {
                // A listener/connection won the race while connect blocked.
                closeQuietly(connected);
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        link != null
                                ? "bt-rfcomm-already-connected"
                                : "bt-rfcomm-already-listening");
            }
            link = connected;
        }
        return connected(request, connected);
    }

    /**
     * {@code bt.rfcomm.read} — optional {@code timeout_ms}. One bounded read
     * of up to {@link #MAX_READ_BYTES} bytes, hex-encoded in
     * {@code data_hex}; {@code eof=true} when the peer is gone and retires
     * the link.
     */
    private AndroidCapabilityProtocol.Response read(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("timeout_ms"));
        long timeoutMs = timeout(params);

        AndroidCapabilityProtocol.Response denied = unusable(request);
        if (denied != null) {
            return denied;
        }
        RfcommLink current = currentLink();
        if (current == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-not-connected");
        }
        byte[] buffer = new byte[MAX_READ_BYTES];
        int count;
        try {
            // Serialise stream access: two concurrent reads on one RFCOMM
            // socket would interleave bytes.
            synchronized (current) {
                count = current.read(buffer, timeoutMs);
            }
        } catch (SocketTimeoutException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-timeout");
        } catch (SecurityException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-permission-denied");
        } catch (IOException | RuntimeException e) {
            // A dead link is an end of stream: report it as one and retire it.
            retireLink(current);
            return eof(request.getId());
        }
        if (count < 0) {
            retireLink(current);
            return eof(request.getId());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("data_hex", hex(buffer, count));
        fields.put("length", (long) count);
        fields.put("eof", false);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code bt.rfcomm.write} — exactly one of {@code value_hex} or
     * {@code value_utf8} (each ≤8192 chars), optional {@code timeout_ms}.
     */
    private AndroidCapabilityProtocol.Response write(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("value_hex", "value_utf8", "timeout_ms"));
        boolean hasHex = params.has("value_hex");
        boolean hasUtf8 = params.has("value_utf8");
        if (hasHex == hasUtf8) {
            throw new CapabilityParams.Invalid(
                    "exactly one of value_hex or value_utf8 is required");
        }
        byte[] data = hasHex
                ? decodeHex(params.requireString("value_hex", MAX_VALUE_CHARS))
                : params.requireString("value_utf8", MAX_VALUE_CHARS)
                        .getBytes(StandardCharsets.UTF_8);
        long timeoutMs = timeout(params);

        AndroidCapabilityProtocol.Response denied = unusable(request);
        if (denied != null) {
            return denied;
        }
        RfcommLink current = currentLink();
        if (current == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-not-connected");
        }
        try {
            synchronized (current) {
                current.write(data, timeoutMs);
            }
        } catch (SocketTimeoutException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-write-failed:timeout");
        } catch (SecurityException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-permission-denied");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "bt.rfcomm.write failed", e);
            retireLink(current);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-rfcomm-write-failed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("written", (long) data.length);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code bt.rfcomm.close} — no params; idempotent. Closes the in-flight
     * listen (which aborts the owner's accept with a failure) and the link.
     */
    private AndroidCapabilityProtocol.Response closeSession(
            AndroidCapabilityProtocol.Request request) {
        teardown();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("closed", true);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    @Override
    public void close() {
        teardown();
        transport.close();
    }

    /** Closes the link and any in-flight listen; safe to call twice. */
    private void teardown() {
        ListenWait wait;
        RfcommLink current;
        synchronized (stateLock) {
            wait = listenWait;
            listenWait = null;
            current = link;
            link = null;
            if (wait != null) {
                wait.settled = true;
                if (wait.error == null) {
                    wait.error = "bt-rfcomm-not-listening";
                }
                stateLock.notifyAll();
            }
        }
        if (wait != null) {
            closeQuietly(wait.listener);
        }
        if (current != null) {
            closeQuietly(current);
        }
    }

    /** Drops {@code dead} as the current link after it failed underneath us. */
    private void retireLink(RfcommLink dead) {
        synchronized (stateLock) {
            if (link == dead) {
                link = null;
            }
        }
        closeQuietly(dead);
    }

    private RfcommLink currentLink() {
        synchronized (stateLock) {
            return link;
        }
    }

    /**
     * The gate every radio-facing method runs: the runtime grant first, then
     * the adapter's own availability.
     */
    private AndroidCapabilityProtocol.Response unusable(
            AndroidCapabilityProtocol.Request request) {
        CapabilityPermission grant = connectGrant.get();
        if (grant != CapabilityPermission.GRANTED) {
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    grant == CapabilityPermission.DENIED
                            ? "bt-permission-denied"
                            : "bt-permission-required:grant BLUETOOTH_CONNECT"
                                    + " via permission.request");
        }
        String unavailable = transport.unavailableError();
        if (unavailable != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), unavailable);
        }
        return null;
    }

    private AndroidCapabilityProtocol.Response connected(
            AndroidCapabilityProtocol.Request request, RfcommLink current) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("connected", true);
        String address = null;
        try {
            address = current.peerAddress();
        } catch (RuntimeException e) {
            // The peer identity is informational; never fail the answer on it.
        }
        if (address != null) {
            fields.put("address", address);
        }
        // The public API never reports the negotiated RFCOMM channel.
        fields.put("channel", -1L);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private static AndroidCapabilityProtocol.Response eof(String requestId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("data_hex", "");
        fields.put("length", 0L);
        fields.put("eof", true);
        return AndroidCapabilityProtocol.Response.success(requestId, fields);
    }

    private static UUID serviceUuid(CapabilityParams params) {
        String raw = params.optionalString(
                "service_uuid", MAX_UUID_CHARS, DEFAULT_SERVICE_UUID);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new CapabilityParams.Invalid(
                    "parameter is not a UUID: service_uuid");
        }
    }

    private static long timeout(CapabilityParams params) {
        return params.optionalLong(
                "timeout_ms", MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    }

    private static void closeQuietly(RfcommListener listener) {
        try {
            listener.close();
        } catch (RuntimeException ignored) {
            // Closing an already-broken listener is a no-op.
        }
    }

    private static void closeQuietly(RfcommLink socket) {
        try {
            socket.close();
        } catch (RuntimeException ignored) {
            // Closing an already-broken link is a no-op.
        }
    }

    private static String hex(byte[] data, int count) {
        char[] out = new char[count * 2];
        for (int i = 0; i < count; i++) {
            int value = data[i] & 0xff;
            out[i * 2] = Character.forDigit(value >>> 4, 16);
            out[i * 2 + 1] = Character.forDigit(value & 0xf, 16);
        }
        return new String(out);
    }

    private static byte[] decodeHex(String value) {
        if ((value.length() & 1) != 0) {
            throw new CapabilityParams.Invalid("odd-length hex: value_hex");
        }
        byte[] data = new byte[value.length() / 2];
        for (int i = 0; i < data.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new CapabilityParams.Invalid("bad hex: value_hex");
            }
            data[i] = (byte) ((high << 4) | low);
        }
        return data;
    }

    /** One in-flight listen window and its published outcome. */
    private static final class ListenWait {
        final RfcommListener listener;
        final long deadlineMillis;
        boolean settled;
        RfcommLink accepted;
        String error;

        ListenWait(RfcommListener listener, long deadlineMillis) {
            this.listener = listener;
            this.deadlineMillis = deadlineMillis;
        }
    }

    /**
     * The boundary between the module's state machine and a socket stack.
     * Timeouts are {@link SocketTimeoutException}; a clean peer close reads
     * {@code -1}; a dead link fails with {@link IOException}.
     */
    interface RfcommTransport {
        /**
         * {@code null} when the radio can serve requests, else the typed
         * error (no adapter → {@code bt-unavailable}; switched off →
         * {@code bt-unavailable:bluetooth is off}).
         */
        String unavailableError();

        /** Open a service record; never blocks for a peer here. */
        RfcommListener listen(String name, UUID serviceUuid) throws IOException;

        /** Connect one peer's service record, bounded by {@code timeoutMillis}. */
        RfcommLink connect(String address, UUID serviceUuid, long timeoutMillis)
                throws IOException;

        /** Release sockets and workers the transport owns. */
        void close();

        /** One open service record; {@link #accept} is the bounded peer wait. */
        interface RfcommListener {
            RfcommLink accept(long timeoutMillis) throws IOException;

            void close();
        }

        /** One established serial link to a peer. */
        interface RfcommLink {
            String peerAddress();

            /** Reads up to {@code buffer.length} bytes; {@code -1} on peer close. */
            int read(byte[] buffer, long timeoutMillis) throws IOException;

            void write(byte[] data, long timeoutMillis) throws IOException;

            void close();
        }
    }

    /**
     * {@link RfcommTransport} over the platform {@link BluetoothAdapter}.
     * The platform's own bounded calls do not cover connect/read/write, so
     * every blocking op runs on a small daemon pool and is bounded by
     * {@link Future#get(long, TimeUnit)}; a connect that outlives its window
     * is aborted by closing the socket.
     */
    static final class AndroidRfcommTransport implements RfcommTransport {
        private static final int MAX_IO_THREADS = 4;
        private static final long READ_POLL_MS = 20L;

        private final BluetoothAdapter adapter;
        private final ExecutorService io;

        AndroidRfcommTransport(Context context) {
            BluetoothManager manager = null;
            try {
                manager = (BluetoothManager) context.getApplicationContext()
                        .getSystemService(Context.BLUETOOTH_SERVICE);
            } catch (RuntimeException ignored) {
                // No bluetooth service: unavailableError() reports it.
            }
            BluetoothAdapter resolved = null;
            if (manager != null) {
                try {
                    resolved = manager.getAdapter();
                } catch (RuntimeException ignored) {
                    // As above.
                }
            }
            this.adapter = resolved;
            this.io = new ThreadPoolExecutor(0, MAX_IO_THREADS,
                    30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                    daemonFactory("bt-rfcomm-io"));
        }

        @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is gated by the module
        @Override
        public String unavailableError() {
            if (adapter == null) {
                return "bt-unavailable";
            }
            try {
                return adapter.isEnabled() ? null : "bt-unavailable:bluetooth is off";
            } catch (RuntimeException e) {
                return "bt-unavailable";
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public RfcommListener listen(String name, UUID serviceUuid) throws IOException {
            if (adapter == null) {
                throw new IOException("no bluetooth adapter");
            }
            return new Listener(adapter.listenUsingRfcommWithServiceRecord(
                    name, serviceUuid));
        }

        @SuppressLint("MissingPermission")
        @Override
        public RfcommLink connect(String address, UUID serviceUuid, long timeoutMillis)
                throws IOException {
            if (adapter == null) {
                throw new IOException("no bluetooth adapter");
            }
            // getRemoteDevice throws IllegalArgumentException on a malformed
            // address; the module maps that to bt-device-unknown.
            BluetoothDevice device = adapter.getRemoteDevice(address);
            try {
                // The documented precondition for a reliable classic connect.
                adapter.cancelDiscovery();
            } catch (RuntimeException ignored) {
                // Best effort only: BLUETOOTH_SCAN is not this module's grant.
            }
            BluetoothSocket socket;
            try {
                socket = device.createRfcommSocketToServiceRecord(serviceUuid);
            } catch (SecurityException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw e instanceof IOException
                        ? (IOException) e : new IOException(e);
            }
            Future<?> pending = io.submit(() -> {
                socket.connect();
                return null;
            });
            try {
                pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                closeQuietly(socket);
                throw new SocketTimeoutException("rfcomm connect timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeQuietly(socket);
                throw new IOException("rfcomm connect interrupted", e);
            } catch (ExecutionException e) {
                closeQuietly(socket);
                throw rethrow(e.getCause());
            }
            return new Link(socket);
        }

        @Override
        public void close() {
            io.shutdownNow();
        }

        /** Bounded platform wait for one incoming connection. */
        private final class Listener implements RfcommListener {
            private final BluetoothServerSocket server;

            Listener(BluetoothServerSocket server) {
                this.server = server;
            }

            @Override
            public RfcommLink accept(long timeoutMillis) throws IOException {
                // server.accept(int) signals its timeout as an undifferentiated
                // IOException, so the bound is applied around the unbounded
                // call instead; the module closes the listener on timeout,
                // which releases the worker.
                Future<BluetoothSocket> pending = io.submit(
                        (Callable<BluetoothSocket>) server::accept);
                try {
                    BluetoothSocket socket = pending.get(
                            timeoutMillis, TimeUnit.MILLISECONDS);
                    return socket == null ? null : new Link(socket);
                } catch (TimeoutException e) {
                    throw new SocketTimeoutException("rfcomm accept timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("rfcomm accept interrupted", e);
                } catch (ExecutionException e) {
                    throw rethrow(e.getCause());
                }
            }

            @Override
            public void close() {
                closeQuietly(server);
            }
        }

        /** One connected RFCOMM socket. */
        private final class Link implements RfcommLink {
            private final BluetoothSocket socket;

            Link(BluetoothSocket socket) {
                this.socket = socket;
            }

            @SuppressLint({"MissingPermission", "HardwareIds"})
            @Override
            public String peerAddress() {
                try {
                    BluetoothDevice peer = socket.getRemoteDevice();
                    return peer == null ? null : peer.getAddress();
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            public int read(byte[] buffer, long timeoutMillis) throws IOException {
                // BluetoothSocket streams have no SO_TIMEOUT; poll the
                // platform's buffered byte count so the wait stays bounded
                // without parking a worker on a dead stream.
                InputStream input = socket.getInputStream();
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                for (;;) {
                    if (!socket.isConnected()) {
                        // Peer gone: one final read reports -1 or throws.
                        return input.read(buffer);
                    }
                    int available;
                    try {
                        available = input.available();
                    } catch (IOException e) {
                        return input.read(buffer);
                    }
                    if (available > 0) {
                        return input.read(buffer, 0, Math.min(available, buffer.length));
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new SocketTimeoutException("rfcomm read timed out");
                    }
                    try {
                        Thread.sleep(Math.min(READ_POLL_MS,
                                TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("rfcomm read interrupted", e);
                    }
                }
            }

            @Override
            public void write(byte[] data, long timeoutMillis) throws IOException {
                // A flow-controlled peer can stall the platform write, so it
                // too runs on the bounded pool.
                OutputStream output = socket.getOutputStream();
                Future<?> pending = io.submit(() -> {
                    output.write(data);
                    output.flush();
                    return null;
                });
                try {
                    pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    throw new SocketTimeoutException("rfcomm write timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("rfcomm write interrupted", e);
                } catch (ExecutionException e) {
                    throw rethrow(e.getCause());
                }
            }

            @Override
            public void close() {
                closeQuietly(socket);
            }
        }

        private static IOException rethrow(Throwable cause) {
            if (cause instanceof IOException) {
                return (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            return cause == null
                    ? new IOException("rfcomm io failed")
                    : new IOException(cause);
        }

        private static void closeQuietly(BluetoothSocket socket) {
            try {
                socket.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing an already-broken socket is a no-op.
            }
        }

        private static void closeQuietly(BluetoothServerSocket socket) {
            try {
                socket.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing an already-broken socket is a no-op.
            }
        }

        private static ThreadFactory daemonFactory(String name) {
            return task -> {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
