package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.BluetoothConsentForegroundOperation;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;

/**
 * Bluetooth capability domain: adapter state, bonded/discovered device
 * reads, classic bonding, and the two system consent dialogs. There is no
 * upstream Termux:API counterpart; the {@code bt.*} surface is
 * NusaDesk-native.
 *
 * <p>Permission gates follow the platform split: on API 31+ the runtime
 * {@code BLUETOOTH_CONNECT}/{@code BLUETOOTH_SCAN}/{@code BLUETOOTH_ADVERTISE}
 * grants are checked through {@link AndroidPermissionChecker} and a missing
 * grant answers {@code bt-permission-required} / {@code bt-permission-denied}
 * (the module never prompts itself). On API 30 and below the install-granted
 * legacy {@code BLUETOOTH}/{@code BLUETOOTH_ADMIN} pair covers everything
 * except classic discovery, whose results are still gated on a location grant
 * by the platform. A device without a Bluetooth adapter reports
 * {@code bt-unsupported:no bluetooth adapter}; a present-but-off adapter
 * reports {@code bt-unavailable:bluetooth is off} on the methods that need a
 * live radio.</p>
 *
 * <p>{@code bt.status} reports {@code state} ({@code on}/{@code off}/
 * {@code turning_on}/{@code turning_off}), {@code name}, {@code scan_mode}
 * ({@code none}/{@code connectable}/{@code connectable_discoverable}), and
 * {@code bonded_count}. {@code bt.devices} reports the bonded set as
 * {@code devices_json} (bounded to {@value #MAX_DEVICE_ROWS} rows with a
 * {@code truncated} flag), one row per device carrying {@code name},
 * {@code address}, {@code type} ({@code classic}/{@code le}/{@code dual}/
 * {@code unknown}), {@code bond_state} ({@code none}/{@code bonding}/
 * {@code bonded}), and {@code uuids}.</p>
 *
 * <p>{@code bt.discover.start} runs one classic discovery through the
 * platform's own ~12 s window: a second start while one runs answers
 * {@code bt-discover-already-running}. {@code bt.discover.poll} reports the
 * devices collected so far plus {@code running} and {@code elapsed_ms};
 * {@code bt.discover.stop} is idempotent and reports {@code stopped} —
 * whether this call actually stopped a running scan — and the final
 * {@code devices_json}.</p>
 *
 * <p>{@code bt.pair} (param {@code address}) calls {@code createBond} and
 * blocks the connection thread for the terminal
 * {@code ACTION_BOND_STATE_CHANGED} broadcast, bounded at
 * {@value #PAIR_TIMEOUT_MS} ms: {@code state=bonded}/{@code failed} on a
 * broadcast verdict, and {@code bt-device-unknown:<address>} when the
 * address is malformed or the stack refuses to start bonding. At expiry the
 * bond state is re-read — some stacks land the bond without ever delivering
 * the terminal broadcast — so {@code bt-pair-timeout} is only reported
 * while the device is genuinely still unbonded. A device already mid-bond
 * reports {@code state=bonding} without re-initiating. {@code bt.unpair} is
 * typed-absent: {@code removeBond} is a platform-hidden API, so it always
 * answers {@code bt-unpair-unsupported}.</p>
 *
 * <p>{@code bt.enable.request} and {@code bt.discoverable.request} park a
 * {@link BluetoothConsentForegroundOperation} through
 * {@link CapabilityForegroundHost} — on API 33+ the enable dialog is the
 * only enable path a third-party app has. Host failures map onto the
 * capability taxonomy ({@code bt-consent-timeout}, {@code bt-busy},
 * {@code bt-cancelled}, {@code bt-unavailable}).</p>
 */
public final class BluetoothModule implements CapabilityModule {
    private static final String TAG = "BluetoothModule";

    private static final String METHOD_STATUS = "bt.status";
    private static final String METHOD_DEVICES = "bt.devices";
    private static final String METHOD_DISCOVER_START = "bt.discover.start";
    private static final String METHOD_DISCOVER_POLL = "bt.discover.poll";
    private static final String METHOD_DISCOVER_STOP = "bt.discover.stop";
    private static final String METHOD_PAIR = "bt.pair";
    private static final String METHOD_UNPAIR = "bt.unpair";
    private static final String METHOD_ENABLE_REQUEST = "bt.enable.request";
    private static final String METHOD_DISCOVERABLE_REQUEST = "bt.discoverable.request";

    private static final int MAX_DEVICE_ROWS = 50;
    private static final int MAX_NAME_CHARS = 64;
    private static final int ADDRESS_MAX_CHARS = 32;
    private static final long PAIR_TIMEOUT_MS = 30_000L;
    private static final long CONSENT_TIMEOUT_MS = 60_000L;

    /** Test seam for the consent foreground host; production wires the real one. */
    interface ForegroundRunner {
        AndroidCapabilityProtocol.Response execute(String kind, Map<String, Object> params,
                                                   long timeoutMillis);
    }

    private final Context context;
    private final AndroidPermissionChecker permissions;
    private final BluetoothAdapter adapter;
    private final CapabilityForegroundHost foregroundHost;
    private final ForegroundRunner foreground;
    private final long pairTimeoutMillis;

    /** The current or most recent discovery session; retained for post-stop polls. */
    private final AtomicReference<DiscoverySession> discovery = new AtomicReference<>();
    /** Live pair waits so {@link #close()} can settle them instead of stranding the thread. */
    private final Set<PairWait> pairWaits = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public BluetoothModule(Context context) {
        this(context, defaultAdapter(context), null, PAIR_TIMEOUT_MS);
    }

    /**
     * Package-private seam constructor: the adapter is injectable because the
     * platform class is final, and the consent host and pair bound are
     * injectable so the method contract is testable without a device.
     */
    BluetoothModule(Context context, BluetoothAdapter adapter,
                    ForegroundRunner foreground, long pairTimeoutMillis) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissions = new AndroidPermissionChecker(this.context);
        this.adapter = adapter;
        this.pairTimeoutMillis = pairTimeoutMillis;
        this.foregroundHost = new CapabilityForegroundHost(this.context);
        this.foreground = foreground != null ? foreground : foregroundHost::execute;
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_STATUS, METHOD_DEVICES, METHOD_DISCOVER_START,
                METHOD_DISCOVER_POLL, METHOD_DISCOVER_STOP, METHOD_PAIR,
                METHOD_UNPAIR, METHOD_ENABLE_REQUEST, METHOD_DISCOVERABLE_REQUEST);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_PAIR, METHOD_UNPAIR, METHOD_DISCOVERABLE_REQUEST);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_STATUS:
                return status(request);
            case METHOD_DEVICES:
                return devices(request);
            case METHOD_DISCOVER_START:
                return discoverStart(request);
            case METHOD_DISCOVER_POLL:
                return discoverPoll(request);
            case METHOD_DISCOVER_STOP:
                return discoverStop(request);
            case METHOD_PAIR:
                return pair(request);
            case METHOD_UNPAIR:
                return unpair(request);
            case METHOD_ENABLE_REQUEST:
                return enableRequest(request);
            case METHOD_DISCOVERABLE_REQUEST:
                return discoverableRequest(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void close() {
        closed = true;
        DiscoverySession session = discovery.getAndSet(null);
        if (session != null) {
            if (session.running() && adapter != null) {
                try {
                    adapter.cancelDiscovery();
                } catch (RuntimeException e) {
                    // Best effort; the platform window expires on its own.
                }
            }
            session.stop();
        }
        for (PairWait wait : pairWaits) {
            wait.cancel();
        }
        foregroundHost.close();
    }

    /** {@code bt.status} — adapter state, name, scan mode, and bonded count. */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response status(AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unsupported:no bluetooth adapter");
        }
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("state", stateName(adapter.getState()));
            String name = adapter.getName();
            if (name != null) {
                fields.put("name", name);
            }
            fields.put("scan_mode", scanModeName(adapter.getScanMode()));
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            fields.put("bonded_count", (long) (bonded == null ? 0 : bonded.size()));
            return AndroidCapabilityProtocol.Response.success(id, fields);
        } catch (SecurityException e) {
            Log.w(TAG, "bt.status refused", e);
            String refreshed = connectGrantError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "bt-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.status failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
    }

    /** {@code bt.devices} — the bonded set as a bounded JSON array. */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response devices(AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unsupported:no bluetooth adapter");
        }
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Log.w(TAG, "bt.devices refused", e);
            String refreshed = connectGrantError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "bt-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.devices failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        if (bonded == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        List<BluetoothDevice> sorted = new ArrayList<>(bonded);
        sorted.sort((a, b) -> safeAddress(a).compareTo(safeAddress(b)));
        return devicesResponse(id, sorted);
    }

    /**
     * {@code bt.discover.start} — begin the platform's ~12 s classic
     * discovery window. On API 30 and below the broadcast results are gated
     * on a location grant; on API 31+ on {@code BLUETOOTH_SCAN} (declared
     * {@code neverForLocation}) plus {@code BLUETOOTH_CONNECT} for the device
     * fields each found row carries.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response discoverStart(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unsupported:no bluetooth adapter");
        }
        String grantError = scanGrantError();
        if (grantError == null) {
            grantError = connectGrantError();
        }
        if (grantError == null) {
            grantError = legacyDiscoveryLocationError();
        }
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        if (!enabledOrFalse(adapter)) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable:bluetooth is off");
        }
        DiscoverySession existing = discovery.get();
        if ((existing != null && existing.running()) || discoveringOrFalse(adapter)) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-discover-already-running");
        }
        if (closed) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        DiscoverySession session = new DiscoverySession();
        try {
            session.register();
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.discover.start receiver refused", e);
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        boolean started;
        try {
            started = adapter.startDiscovery();
        } catch (SecurityException e) {
            session.unregister();
            Log.w(TAG, "bt.discover.start refused", e);
            String refreshed = scanGrantError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "bt-unavailable");
        } catch (RuntimeException e) {
            session.unregister();
            Log.w(TAG, "bt.discover.start failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        if (!started) {
            session.unregister();
            return AndroidCapabilityProtocol.Response.error(
                    id, "bt-unavailable:discovery could not start");
        }
        discovery.set(session);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("started", true);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /** {@code bt.discover.poll} — devices collected so far, running flag, elapsed ms. */
    private AndroidCapabilityProtocol.Response discoverPoll(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        DiscoverySession session = discovery.get();
        Map<String, Object> fields = new LinkedHashMap<>();
        if (session == null) {
            fields.put("devices_json", "[]");
            fields.put("count", 0L);
            fields.put("running", false);
            fields.put("elapsed_ms", 0L);
            return AndroidCapabilityProtocol.Response.success(id, fields);
        }
        List<BluetoothDevice> found = session.devices();
        fields.put("devices_json", devicesJson(found));
        fields.put("count", (long) Math.min(found.size(), MAX_DEVICE_ROWS));
        if (found.size() > MAX_DEVICE_ROWS) {
            fields.put("truncated", true);
        }
        fields.put("running", session.running());
        fields.put("elapsed_ms", session.elapsedMs());
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /**
     * {@code bt.discover.stop} — end the active discovery. Idempotent: an
     * already-finished or absent session answers {@code stopped=false} with
     * the last collected list, never an error.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response discoverStop(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        DiscoverySession session = discovery.get();
        Map<String, Object> fields = new LinkedHashMap<>();
        if (session == null) {
            fields.put("stopped", false);
            fields.put("devices_json", "[]");
            return AndroidCapabilityProtocol.Response.success(id, fields);
        }
        boolean wasRunning = session.running();
        if (wasRunning && adapter != null) {
            try {
                adapter.cancelDiscovery();
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.discover.stop cancel failed", e);
            }
        }
        session.stop();
        fields.put("stopped", wasRunning);
        List<BluetoothDevice> found = session.devices();
        fields.put("devices_json", devicesJson(found));
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /**
     * {@code bt.pair} — params {@code address}. Registers the bond broadcast
     * before {@code createBond} so a fast verdict can never be missed, then
     * blocks the connection thread for at most {@link #pairTimeoutMillis}.
     * An expired wait re-reads the live bond state: a stack that completes
     * bonding but drops the terminal broadcast still answers
     * {@code state=bonded}.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response pair(AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        String address = params.requireString("address", ADDRESS_MAX_CHARS)
                .trim().toUpperCase(Locale.ROOT);
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unsupported:no bluetooth adapter");
        }
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        if (!enabledOrFalse(adapter)) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable:bluetooth is off");
        }
        BluetoothDevice device;
        try {
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return AndroidCapabilityProtocol.Response.error(
                        id, "bt-device-unknown:" + address);
            }
            device = adapter.getRemoteDevice(address);
        } catch (RuntimeException e) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-device-unknown:" + address);
        }
        int bondState;
        try {
            bondState = device.getBondState();
        } catch (SecurityException e) {
            String refreshed = connectGrantError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "bt-unavailable");
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.pair state read failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        if (bondState == BluetoothDevice.BOND_BONDED) {
            return pairResult(id, address, "bonded");
        }
        if (bondState == BluetoothDevice.BOND_BONDING) {
            // Another actor already started the bond; report the live state
            // rather than re-initiating or parking on someone else's flow.
            return pairResult(id, address, "bonding");
        }
        PairWait wait = new PairWait(address);
        try {
            wait.register();
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.pair receiver refused", e);
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        pairWaits.add(wait);
        boolean initiated;
        try {
            initiated = !closed && device.createBond();
        } catch (SecurityException e) {
            pairWaits.remove(wait);
            wait.unregister();
            String refreshed = connectGrantError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "bt-unavailable");
        } catch (RuntimeException e) {
            initiated = false;
        }
        if (!initiated) {
            pairWaits.remove(wait);
            wait.unregister();
            return AndroidCapabilityProtocol.Response.error(id, "bt-device-unknown:" + address);
        }
        boolean finished = false;
        boolean interrupted = false;
        try {
            finished = wait.await(pairTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } finally {
            pairWaits.remove(wait);
            wait.unregister();
        }
        if (wait.cancelled() || interrupted) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-cancelled");
        }
        Integer terminal = wait.terminal();
        if (!finished || terminal == null) {
            // The terminal broadcast can be dropped even though the bond
            // landed (seen on a Samsung S10e, API 31); trust the state read
            // over the missing broadcast so only a genuinely unbonded device
            // reports a timeout.
            if (bondStateOf(device) == BluetoothDevice.BOND_BONDED) {
                return pairResult(id, address, "bonded");
            }
            return AndroidCapabilityProtocol.Response.error(id, "bt-pair-timeout");
        }
        return pairResult(id, address,
                terminal == BluetoothDevice.BOND_BONDED ? "bonded" : "failed");
    }

    /**
     * {@code bt.unpair} — params {@code address}. The platform hides
     * {@code removeBond} from third-party apps, so after params validate the
     * method always answers the typed-absent error and never claims an
     * unbond happened.
     */
    private AndroidCapabilityProtocol.Response unpair(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        params.requireString("address", ADDRESS_MAX_CHARS);
        return AndroidCapabilityProtocol.Response.error(request.getId(),
                "bt-unpair-unsupported:platform hides removeBond; "
                        + "use the system Bluetooth settings");
    }

    /**
     * {@code bt.enable.request} — the {@code ACTION_REQUEST_ENABLE} consent
     * dialog, the only enable path a third-party app has on API 33+. Success
     * carries {@code state} and {@code user_action=true}.
     */
    private AndroidCapabilityProtocol.Response enableRequest(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unsupported:no bluetooth adapter");
        }
        String grantError = connectGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", "enable");
        AndroidCapabilityProtocol.Response result = foreground.execute(
                BluetoothConsentForegroundOperation.KIND, opParams, CONSENT_TIMEOUT_MS);
        if (!result.isOk()) {
            return foregroundError(id, result.getError());
        }
        Map<String, Object> fields = result.getFields();
        fields.put("user_action", true);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /**
     * {@code bt.discoverable.request} — params {@code seconds} (optional,
     * 1..300, default 120). The {@code ACTION_REQUEST_DISCOVERABLE} consent
     * dialog needs {@code BLUETOOTH_ADVERTISE} on API 31+; success carries
     * {@code duration_seconds} (the platform's granted window, 0 when the
     * user declined) and the resulting {@code scan_mode}.
     */
    private AndroidCapabilityProtocol.Response discoverableRequest(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("seconds"));
        long seconds = params.optionalLong("seconds", 1L, 300L, 120L);
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unsupported:no bluetooth adapter");
        }
        String grantError = advertiseGrantError();
        if (grantError == null) {
            grantError = connectGrantError();
        }
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        if (!enabledOrFalse(adapter)) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable:bluetooth is off");
        }
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", "discoverable");
        opParams.put("seconds", seconds);
        AndroidCapabilityProtocol.Response result = foreground.execute(
                BluetoothConsentForegroundOperation.KIND, opParams, CONSENT_TIMEOUT_MS);
        if (!result.isOk()) {
            return foregroundError(id, result.getError());
        }
        return AndroidCapabilityProtocol.Response.success(id, result.getFields());
    }

    /** Translate a foreground-host failure into the bt-* taxonomy. */
    private static AndroidCapabilityProtocol.Response foregroundError(String id, String error) {
        if (error == null) {
            return AndroidCapabilityProtocol.Response.error(id, "bt-unavailable");
        }
        String code = error;
        String hint = "";
        int colon = error.indexOf(':');
        if (colon >= 0) {
            code = error.substring(0, colon);
            hint = error.substring(colon);
        }
        String mapped;
        switch (code) {
            case CapabilityForegroundHost.ERROR_TIMEOUT:
                mapped = "bt-consent-timeout";
                break;
            case CapabilityForegroundHost.ERROR_BUSY:
                mapped = "bt-busy";
                break;
            case CapabilityForegroundHost.ERROR_CANCELLED:
                mapped = "bt-cancelled";
                break;
            case CapabilityForegroundHost.ERROR_UNAVAILABLE:
            case "foreground-unknown":
                mapped = "bt-unavailable";
                break;
            default:
                mapped = code + hint;
        }
        return AndroidCapabilityProtocol.Response.error(id, mapped);
    }

    /**
     * The API 31+ runtime grant gate: {@code null} below S (the legacy pair
     * is install-granted there) or when the grant exists, else the typed
     * {@code bt-permission-*} error naming the missing permission.
     */
    @SuppressLint("InlinedApi") // the S-gated constant inlines to a plain String below S
    private String connectGrantError() {
        return runtimeGrantError(Manifest.permission.BLUETOOTH_CONNECT);
    }

    @SuppressLint("InlinedApi")
    private String scanGrantError() {
        return runtimeGrantError(Manifest.permission.BLUETOOTH_SCAN);
    }

    @SuppressLint("InlinedApi")
    private String advertiseGrantError() {
        return runtimeGrantError(Manifest.permission.BLUETOOTH_ADVERTISE);
    }

    private String runtimeGrantError(String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        CapabilityPermission state = permissions.check(permission);
        if (state == CapabilityPermission.GRANTED) {
            return null;
        }
        String name = permission.substring(permission.lastIndexOf('.') + 1);
        return state == CapabilityPermission.DENIED
                ? "bt-permission-denied:grant " + name + " in app settings"
                : "bt-permission-required:grant " + name + " via permission.request";
    }

    /**
     * Pre-S discovery results are only delivered while a location grant is
     * held; without one the platform starts discovery and silently reports
     * nothing, so the grant is gated up front instead.
     */
    private String legacyDiscoveryLocationError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return null;
        }
        CapabilityPermission fine =
                permissions.check(Manifest.permission.ACCESS_FINE_LOCATION);
        if (fine == CapabilityPermission.GRANTED
                || permissions.check(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == CapabilityPermission.GRANTED) {
            return null;
        }
        return fine == CapabilityPermission.DENIED
                ? "bt-permission-denied:grant ACCESS_FINE_LOCATION in app settings"
                : "bt-permission-required:grant ACCESS_FINE_LOCATION via permission.request";
    }

    @SuppressLint("MissingPermission")
    private boolean enabledOrFalse(BluetoothAdapter target) {
        try {
            return target.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Bond-state read that degrades to {@code BOND_NONE} on any stack refusal. */
    @SuppressLint("MissingPermission") // callers gate on BLUETOOTH_CONNECT first
    private static int bondStateOf(BluetoothDevice device) {
        try {
            return device.getBondState();
        } catch (RuntimeException e) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean discoveringOrFalse(BluetoothAdapter target) {
        try {
            return target.isDiscovering();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            return address == null ? "" : address;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static BluetoothAdapter defaultAdapter(Context context) {
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return manager == null ? null : manager.getAdapter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Both receivers subscribe only to protected system broadcasts; the flag
    // overload applies on API 33+ and the unflagged path is unreachable there.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiverCompat(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts still reach a not-exported receiver, and the
            // flag keeps third-party broadcasts out.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceExtra(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice.class);
            }
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static AndroidCapabilityProtocol.Response pairResult(String id, String address,
                                                               String state) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", state);
        fields.put("address", address);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    private static AndroidCapabilityProtocol.Response devicesResponse(
            String id, List<BluetoothDevice> devices) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("devices_json", devicesJson(devices));
        fields.put("count", (long) Math.min(devices.size(), MAX_DEVICE_ROWS));
        if (devices.size() > MAX_DEVICE_ROWS) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    private static String devicesJson(List<BluetoothDevice> devices) {
        int count = Math.min(devices.size(), MAX_DEVICE_ROWS);
        StringBuilder json = new StringBuilder(count * 192);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(deviceRowJson(devices.get(i)));
        }
        return json.append(']').toString();
    }

    /** One device row in the bridge's field spelling; unreadable fields degrade to null. */
    @SuppressLint("MissingPermission")
    private static String deviceRowJson(BluetoothDevice device) {
        String name;
        int type;
        int bondState;
        ParcelUuid[] uuids;
        try {
            name = device.getName();
        } catch (RuntimeException e) {
            name = null;
        }
        try {
            type = device.getType();
        } catch (RuntimeException e) {
            type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
        try {
            bondState = device.getBondState();
        } catch (RuntimeException e) {
            bondState = BluetoothDevice.BOND_NONE;
        }
        try {
            uuids = device.getUuids();
        } catch (RuntimeException e) {
            uuids = null;
        }
        if (name != null && name.length() > MAX_NAME_CHARS) {
            name = name.substring(0, MAX_NAME_CHARS);
        }
        StringBuilder row = new StringBuilder(192);
        row.append("{\"name\":").append(encodeNullable(name))
                .append(",\"address\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(safeAddress(device)))
                .append(",\"type\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(deviceTypeName(type)))
                .append(",\"bond_state\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(bondStateName(bondState)))
                .append(",\"uuids\":").append(uuidsJson(uuids));
        return row.append('}').toString();
    }

    private static String uuidsJson(ParcelUuid[] uuids) {
        if (uuids == null || uuids.length == 0) {
            return "[]";
        }
        StringBuilder json = new StringBuilder(uuids.length * 40);
        json.append('[');
        for (int i = 0; i < uuids.length; i++) {
            if (uuids[i] == null) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(AndroidCapabilityProtocol.encodeStringValue(
                    uuids[i].getUuid().toString()));
        }
        return json.append(']').toString();
    }

    private static String stateName(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                return "on";
            case BluetoothAdapter.STATE_TURNING_ON:
                return "turning_on";
            case BluetoothAdapter.STATE_TURNING_OFF:
                return "turning_off";
            case BluetoothAdapter.STATE_OFF:
            default:
                // Hidden BLE-only states land here too; "off" is the honest
                // classic-radio answer for them.
                return "off";
        }
    }

    private static String scanModeName(int scanMode) {
        switch (scanMode) {
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                return "connectable";
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return "connectable_discoverable";
            case BluetoothAdapter.SCAN_MODE_NONE:
            default:
                return "none";
        }
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                return "classic";
            case BluetoothDevice.DEVICE_TYPE_LE:
                return "le";
            case BluetoothDevice.DEVICE_TYPE_DUAL:
                return "dual";
            case BluetoothDevice.DEVICE_TYPE_UNKNOWN:
            default:
                return "unknown";
        }
    }

    private static String bondStateName(int bondState) {
        switch (bondState) {
            case BluetoothDevice.BOND_BONDING:
                return "bonding";
            case BluetoothDevice.BOND_BONDED:
                return "bonded";
            case BluetoothDevice.BOND_NONE:
            default:
                return "none";
        }
    }

    private static String encodeNullable(String value) {
        return value == null
                ? "null" : AndroidCapabilityProtocol.encodeStringValue(value);
    }

    /**
     * One classic discovery window: the receiver collects
     * {@code ACTION_FOUND} devices and settles on
     * {@code ACTION_DISCOVERY_FINISHED} (or the adapter turning off). The
     * session outlives the window so a later poll/stop still reports the
     * final device list.
     */
    private final class DiscoverySession {
        private final List<BluetoothDevice> devices = new ArrayList<>();
        private final long startedAtMs = System.currentTimeMillis();
        private volatile long finishedAtMs;
        private boolean registered;

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = deviceExtra(intent);
                    if (device != null) {
                        synchronized (devices) {
                            devices.add(device);
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    stop();
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                        stop();
                    }
                }
            }
        };

        synchronized void register() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            registerReceiverCompat(receiver, filter);
            registered = true;
        }

        synchronized void unregister() {
            if (registered) {
                registered = false;
                try {
                    context.unregisterReceiver(receiver);
                } catch (RuntimeException e) {
                    // Already unregistered; nothing else to do.
                }
            }
        }

        boolean running() {
            return finishedAtMs == 0L;
        }

        long elapsedMs() {
            long end = finishedAtMs;
            return (end == 0L ? System.currentTimeMillis() : end) - startedAtMs;
        }

        List<BluetoothDevice> devices() {
            synchronized (devices) {
                return new ArrayList<>(devices);
            }
        }

        /** End the session exactly once: freeze the clock and drop the receiver. */
        void stop() {
            if (finishedAtMs == 0L) {
                finishedAtMs = System.currentTimeMillis();
            }
            unregister();
        }
    }

    /**
     * One bounded wait for a device's terminal bond broadcast. Registered
     * before {@code createBond} runs so a fast verdict cannot be missed;
     * {@link #cancel} settles the wait when the bridge closes.
     */
    private final class PairWait extends BroadcastReceiver {
        private final String address;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Integer terminal;
        private volatile boolean cancelled;
        private boolean registered;

        PairWait(String address) {
            this.address = address;
        }

        synchronized void register() {
            registerReceiverCompat(this,
                    new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
            registered = true;
        }

        synchronized void unregister() {
            if (registered) {
                registered = false;
                try {
                    context.unregisterReceiver(this);
                } catch (RuntimeException e) {
                    // Already unregistered; nothing else to do.
                }
            }
        }

        boolean await(long timeoutMillis) throws InterruptedException {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        void cancel() {
            cancelled = true;
            latch.countDown();
        }

        boolean cancelled() {
            return cancelled;
        }

        Integer terminal() {
            return terminal;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = deviceExtra(intent);
            String seen;
            try {
                seen = device == null ? null : device.getAddress();
            } catch (RuntimeException e) {
                return;
            }
            if (seen == null || !address.equalsIgnoreCase(seen)) {
                return;
            }
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR);
            if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_NONE) {
                terminal = state;
                latch.countDown();
            }
        }
    }
}
