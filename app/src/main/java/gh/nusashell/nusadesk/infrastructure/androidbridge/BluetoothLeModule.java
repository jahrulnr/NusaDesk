package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bluetooth Low Energy capability domain behind five bridge methods
 * (ADR-0050, NusaDesk-native surface — upstream has no BLE contract).
 *
 * <p>{@code bt.le.scan.start} (optional params {@code service_uuid} ≤64 chars,
 * {@code name_prefix} ≤64 chars) runs one {@link BluetoothLeScanner} scan at a
 * time with low-latency settings; the service uuid is a platform-side
 * {@link ScanFilter} (128-bit form only) and the prefix is applied to the
 * advertised local name at ingest since the platform cannot prefix-match. A
 * second start is {@code bt-scan-already-running}, a missing runtime grant is
 * {@code bt-permission-required}/{@code bt-permission-denied}
 * ({@code BLUETOOTH_SCAN} on API 31+, {@code ACCESS_FINE_LOCATION} below —
 * the manifest's {@code neverForLocation} flag only exists from API 31), a
 * switched-off adapter is {@code bt-unavailable:bluetooth is off}, and on the
 * legacy levels the scan is refused early with
 * {@code bt-unavailable:enable device location} because the platform
 * silently returns nothing while device location is off.</p>
 *
 * <p>{@code bt.le.scan.poll} (no params) reports the deduplicated sighting
 * table as {@code devices_json} — rows of {@code name}, {@code address},
 * {@code rssi}, {@code service_uuids}, {@code last_seen_ms}, newest first,
 * capped at {@link #MAX_DEVICE_ROWS} with a {@code truncated} flag — plus
 * {@code running}. A scan that the platform ended with
 * {@link ScanCallback#onScanFailed} reports {@code running=false} and the
 * platform code in {@code scan_error}, never a fabricated active scan.</p>
 *
 * <p>{@code bt.le.scan.stop} (no params) is idempotent: it always answers
 * {@code stopped=true} with the final {@code devices_json} and
 * {@code was_running} for whether this call stopped a live scan.</p>
 *
 * <p>{@code bt.le.advertise.start} (optional params {@code service_uuid} ≤64
 * chars, {@code name} ≤32 chars) starts one connectable low-latency legacy
 * advertisement. A device without advertise support answers
 * {@code bt-advertise-unsupported:this device does not support BLE
 * advertising} (the platform's {@code isMultipleAdvertisementSupported}
 * check), a second start is {@code bt-advertise-already-running}, and the
 * API 31+ gate is {@code BLUETOOTH_ADVERTISE} — plus {@code BLUETOOTH_CONNECT}
 * when {@code name} is given, because the advertised local name is the
 * adapter name: the module sets it before starting and restores the previous
 * name on stop. A 128-bit {@code service_uuid} nearly fills the 31-byte
 * legacy payload, so the device name is only included in the advertise data
 * when no service uuid is given (the adapter rename still applies); adding
 * both is {@code ADVERTISE_FAILED_DATA_TOO_LARGE} on real stacks. The
 * platform's asynchronous answer is awaited bounded
 * ({@link #ADVERTISE_CALLBACK_TIMEOUT_MS} ms); a stack that never calls back
 * is {@code bt-advertise-failed:no callback from bluetooth stack} and a
 * reported failure maps to {@code bt-advertise-data-too-large},
 * {@code bt-advertise-too-many-advertisers},
 * {@code bt-advertise-already-running}, {@code bt-advertise-internal-error},
 * or {@code bt-advertise-unsupported} — unknown codes keep
 * {@code bt-advertise-failed:error <code>}.</p>
 *
 * <p>{@code bt.le.advertise.stop} (no params) is idempotent with
 * {@code stopped} / {@code was_running}. {@link #close()} releases both a
 * live scan and a live advertisement when the bridge ends.</p>
 */
public final class BluetoothLeModule implements CapabilityModule {
    private static final String TAG = "BluetoothLeModule";

    private static final String METHOD_SCAN_START = "bt.le.scan.start";
    private static final String METHOD_SCAN_POLL = "bt.le.scan.poll";
    private static final String METHOD_SCAN_STOP = "bt.le.scan.stop";
    private static final String METHOD_ADVERTISE_START = "bt.le.advertise.start";
    private static final String METHOD_ADVERTISE_STOP = "bt.le.advertise.stop";

    private static final int UUID_MAX_CHARS = 64;
    private static final int NAME_PREFIX_MAX_CHARS = 64;
    private static final int ADVERTISE_NAME_MAX_CHARS = 32;
    /** Response rows of {@code bt.le.scan.poll}/{@code stop}; more is truncated. */
    private static final int MAX_DEVICE_ROWS = 50;
    /**
     * Addresses tracked during one scan; new arrivals beyond the cap are
     * dropped so a crowded rf environment can never grow the table without
     * bound, and {@code truncated} still reports the overflow at the row cap.
     */
    private static final int MAX_TRACKED_DEVICES = 256;
    /** Bounded wait for the platform's advertise-start callback. */
    private static final long ADVERTISE_CALLBACK_TIMEOUT_MS = 2_000L;
    /** Bound on an advertised local name kept from a scan record. */
    private static final int DEVICE_NAME_MAX_CHARS = 128;
    private static final int MAX_SERVICE_UUIDS_PER_DEVICE = 16;
    private static final String ADVERTISE_UNSUPPORTED =
            "bt-advertise-unsupported:this device does not support BLE advertising";

    /**
     * Resolves the platform adapter per call; a {@code null} answer means no
     * bluetooth hardware. The seam lets a test pin the adapter (or its
     * absence) without a real stack.
     */
    interface AdapterSource {
        BluetoothAdapter get();
    }

    private final Context context;
    private final AndroidPermissionChecker permissions;
    private final AdapterSource adapterSource;

    private final Object lock = new Object();
    /** Sightings of the active or last scan, keyed by address; guarded by {@link #lock}. */
    private final Map<String, BleDevice> devices = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private String scanNamePrefix;
    private boolean scanRunning;
    /** Platform {@link ScanCallback} error code of the ended scan; -1 while healthy. */
    private long scanFailure = -1L;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;
    private boolean advertising;
    /** Adapter name before a {@code name}-carrying advertise start; null when untouched. */
    private String adapterNameToRestore;

    public BluetoothLeModule(Context context) {
        this(context, null);
    }

    BluetoothLeModule(Context context, AdapterSource adapterSource) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissions = new AndroidPermissionChecker(this.context);
        this.adapterSource = adapterSource != null
                ? adapterSource : new PlatformAdapterSource(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_SCAN_START, METHOD_SCAN_POLL, METHOD_SCAN_STOP,
                METHOD_ADVERTISE_START, METHOD_ADVERTISE_STOP);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_SCAN_START, METHOD_ADVERTISE_START);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_SCAN_START:
                return scanStart(request);
            case METHOD_SCAN_POLL:
                return scanPoll(request);
            case METHOD_SCAN_STOP:
                return scanStop(request);
            case METHOD_ADVERTISE_START:
                return advertiseStart(request);
            case METHOD_ADVERTISE_STOP:
                return advertiseStop(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code bt.le.scan.start}. Params validate first (fail closed); the
     * single-scan rule, adapter presence, enable state, and the runtime grant
     * gate run before the platform call so each failure is its typed error
     * rather than a {@link SecurityException}.
     */
    @SuppressLint("MissingPermission") // the grant gate runs before startScan
    private AndroidCapabilityProtocol.Response scanStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "name_prefix"));
        String uuidText = params.optionalString("service_uuid", UUID_MAX_CHARS, "");
        String namePrefix = params.optionalString("name_prefix", NAME_PREFIX_MAX_CHARS, "");
        ParcelUuid serviceUuid = parseUuid(uuidText);

        synchronized (lock) {
            if (scanRunning) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-scan-already-running");
            }
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable:no bluetooth adapter");
        }
        if (!enabledOrFalse(adapter)) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable:bluetooth is off");
        }
        String grantError = scanGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        BluetoothLeScanner leScanner = scannerOf(adapter);
        if (leScanner == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable:ble scanner unavailable");
        }

        List<ScanFilter> filters = serviceUuid == null ? null
                : List.of(new ScanFilter.Builder().setServiceUuid(serviceUuid).build());
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        ModuleScanCallback callback = new ModuleScanCallback();
        synchronized (lock) {
            if (scanRunning) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-scan-already-running");
            }
            // Marked running before the platform call: the stack may deliver
            // results on a binder thread the moment it is registered, and a
            // sighting arriving that early must land rather than be dropped.
            devices.clear();
            scanFailure = -1L;
            scanNamePrefix = namePrefix;
            scanner = leScanner;
            scanCallback = callback;
            scanRunning = true;
            try {
                leScanner.startScan(filters, settings, callback);
            } catch (SecurityException e) {
                // The grant was revoked between the gate and the call.
                stopScanLocked();
                Log.w(TAG, "bt.le.scan.start refused", e);
                String refusal = scanGrantError();
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        refusal != null ? refusal : "bt-permission-denied");
            } catch (RuntimeException e) {
                stopScanLocked();
                Log.w(TAG, "bt.le.scan.start failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-unavailable");
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("started", true);
        if (serviceUuid != null) {
            fields.put("service_uuid", serviceUuid.toString());
        }
        if (!namePrefix.isEmpty()) {
            fields.put("name_prefix", namePrefix);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code bt.le.scan.poll} — the deduplicated sightings plus the live flag. */
    private AndroidCapabilityProtocol.Response scanPoll(
            AndroidCapabilityProtocol.Request request) {
        synchronized (lock) {
            Map<String, Object> fields = deviceFields();
            fields.put("running", scanRunning);
            if (scanFailure >= 0L) {
                fields.put("scan_error", scanFailure);
            }
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
    }

    /** {@code bt.le.scan.stop} — idempotent; always reports the final table. */
    @SuppressLint("MissingPermission") // stop is a cleanup path, never grant-gated
    private AndroidCapabilityProtocol.Response scanStop(
            AndroidCapabilityProtocol.Request request) {
        synchronized (lock) {
            boolean wasRunning = scanRunning;
            stopScanLocked();
            Map<String, Object> fields = deviceFields();
            fields.put("stopped", true);
            fields.put("was_running", wasRunning);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
    }

    /**
     * {@code bt.le.advertise.start}. The advertised local name is the adapter
     * name, so a {@code name} param renames the adapter for the session (the
     * previous name is restored on stop). The platform's callback is awaited
     * bounded: {@code started=true} is only reported after the stack confirms.
     */
    @SuppressLint("MissingPermission") // the grant gate runs before startAdvertising
    private AndroidCapabilityProtocol.Response advertiseStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("service_uuid", "name"));
        String uuidText = params.optionalString("service_uuid", UUID_MAX_CHARS, "");
        String name = params.optionalString("name", ADVERTISE_NAME_MAX_CHARS, "");
        ParcelUuid serviceUuid = parseUuid(uuidText);

        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable:no bluetooth adapter");
        }
        if (!enabledOrFalse(adapter)) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable:bluetooth is off");
        }
        synchronized (lock) {
            if (advertising) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-advertise-already-running");
            }
        }
        String grantError = advertiseGrantError(!name.isEmpty());
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        boolean supported;
        try {
            supported = adapter.isMultipleAdvertisementSupported();
        } catch (RuntimeException e) {
            Log.w(TAG, "bt.le.advertise.start support check failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-unavailable");
        }
        if (!supported) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), ADVERTISE_UNSUPPORTED);
        }
        BluetoothLeAdvertiser leAdvertiser = advertiserOf(adapter);
        if (leAdvertiser == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), ADVERTISE_UNSUPPORTED);
        }

        String previousName = null;
        if (!name.isEmpty()) {
            previousName = nameOrNull(adapter);
            try {
                if (!adapter.setName(name)) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "bt-unavailable:could not set adapter name");
                }
            } catch (SecurityException e) {
                Log.w(TAG, "bt.le.advertise.start rename refused", e);
                String refusal = advertiseGrantError(true);
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        refusal != null ? refusal : "bt-permission-denied");
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.le.advertise.start rename failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-unavailable");
            }
        }

        AdvertiseData.Builder data = new AdvertiseData.Builder()
                // A 128-bit uuid already fills most of the 31-byte legacy
                // payload; adding the name overflows it (error 1 on a Samsung
                // S10e). The adapter rename above still applies either way.
                .setIncludeDeviceName(!name.isEmpty() && serviceUuid == null);
        if (serviceUuid != null) {
            data.addServiceUuid(serviceUuid);
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        ModuleAdvertiseCallback callback = new ModuleAdvertiseCallback();
        // The lock is held through the bounded callback wait so a concurrent
        // stop can never run between the platform accept and the state update.
        synchronized (lock) {
            if (advertising) {
                restoreAdapterName(adapter, previousName);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-advertise-already-running");
            }
            try {
                leAdvertiser.startAdvertising(settings, data.build(), callback);
            } catch (SecurityException e) {
                Log.w(TAG, "bt.le.advertise.start refused", e);
                restoreAdapterName(adapter, previousName);
                String refusal = advertiseGrantError(!name.isEmpty());
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        refusal != null ? refusal : "bt-permission-denied");
            } catch (RuntimeException e) {
                Log.w(TAG, "bt.le.advertise.start failed", e);
                restoreAdapterName(adapter, previousName);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-unavailable");
            }
            if (!callback.awaitStart(ADVERTISE_CALLBACK_TIMEOUT_MS)) {
                stopAdvertisingQuietly(leAdvertiser, callback);
                restoreAdapterName(adapter, previousName);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "bt-advertise-failed:no callback from bluetooth stack");
            }
            if (!callback.succeeded()) {
                restoreAdapterName(adapter, previousName);
                return AndroidCapabilityProtocol.Response.error(request.getId(),
                        advertiseStartError(callback.errorCode()));
            }
            advertising = true;
            advertiser = leAdvertiser;
            advertiseCallback = callback;
            adapterNameToRestore = previousName;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("started", true);
        if (serviceUuid != null) {
            fields.put("service_uuid", serviceUuid.toString());
        }
        if (!name.isEmpty()) {
            fields.put("name", name);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code bt.le.advertise.stop} — idempotent; restores a renamed adapter. */
    @SuppressLint("MissingPermission") // stop is a cleanup path, never grant-gated
    private AndroidCapabilityProtocol.Response advertiseStop(
            AndroidCapabilityProtocol.Request request) {
        synchronized (lock) {
            boolean wasRunning = advertising;
            stopAdvertiseLocked();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("stopped", true);
            fields.put("was_running", wasRunning);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopScanLocked();
            stopAdvertiseLocked();
        }
    }

    /** Platform {@link AdvertiseCallback} error code → the module's typed error. */
    private static String advertiseStartError(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "bt-advertise-data-too-large:drop --name or the service uuid";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "bt-advertise-too-many-advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "bt-advertise-already-running";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "bt-advertise-internal-error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return ADVERTISE_UNSUPPORTED;
            default:
                return "bt-advertise-failed:error " + errorCode;
        }
    }

    /** Caller must hold {@link #lock}. */
    @SuppressLint("MissingPermission") // cleanup path, never grant-gated
    private void stopScanLocked() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // Releasing an ended scan is harmless; the state is terminal.
            }
        }
        scanner = null;
        scanCallback = null;
        scanNamePrefix = null;
        scanRunning = false;
    }

    /** Caller must hold {@link #lock}. */
    @SuppressLint("MissingPermission") // cleanup path, never grant-gated
    private void stopAdvertiseLocked() {
        if (advertiser != null && advertiseCallback != null) {
            stopAdvertisingQuietly(advertiser, advertiseCallback);
        }
        advertiser = null;
        advertiseCallback = null;
        advertising = false;
        restoreAdapterName(adapter(), adapterNameToRestore);
        adapterNameToRestore = null;
    }

    @SuppressLint("MissingPermission") // cleanup path, never grant-gated
    private static void stopAdvertisingQuietly(BluetoothLeAdvertiser leAdvertiser,
                                               AdvertiseCallback callback) {
        try {
            leAdvertiser.stopAdvertising(callback);
        } catch (RuntimeException ignored) {
            // A revoked grant or dead stack still leaves the session ended here.
        }
    }

    /** Best-effort restore of the adapter name an advertise start replaced. */
    @SuppressLint("MissingPermission") // the grant may be gone by stop; restore is best-effort
    private static void restoreAdapterName(BluetoothAdapter adapter, String previousName) {
        if (adapter == null || previousName == null) {
            return;
        }
        try {
            adapter.setName(previousName);
        } catch (RuntimeException ignored) {
            // A revoked grant must not fail the stop; the name stays as-is.
        }
    }

    /** One sighting of a scanned device; mutable, guarded by {@link #lock}. */
    private static final class BleDevice {
        private final String address;
        private String name;
        private int rssi;
        private List<String> serviceUuids;
        private long lastSeenMs;

        BleDevice(String name, String address, int rssi, List<String> serviceUuids,
                  long lastSeenMs) {
            this.name = name;
            this.address = address;
            this.rssi = rssi;
            this.serviceUuids = serviceUuids;
            this.lastSeenMs = lastSeenMs;
        }
    }

    /**
     * The {@code devices_json} array plus {@code count}/{@code truncated},
     * newest sightings first. Caller must hold {@link #lock}.
     */
    private Map<String, Object> deviceFields() {
        List<BleDevice> sorted = new ArrayList<>(devices.values());
        sorted.sort((a, b) -> Long.compare(b.lastSeenMs, a.lastSeenMs));
        int count = Math.min(sorted.size(), MAX_DEVICE_ROWS);
        StringBuilder json = new StringBuilder(count * 160);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            BleDevice device = sorted.get(i);
            json.append("{\"name\":").append(encodeNullable(device.name))
                    .append(",\"address\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(device.address))
                    .append(",\"rssi\":").append(device.rssi)
                    .append(",\"service_uuids\":[");
            for (int u = 0; u < device.serviceUuids.size(); u++) {
                if (u > 0) {
                    json.append(',');
                }
                json.append(AndroidCapabilityProtocol.encodeStringValue(
                        device.serviceUuids.get(u)));
            }
            json.append("],\"last_seen_ms\":").append(device.lastSeenMs)
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("devices_json", json.toString());
        fields.put("count", (long) count);
        fields.put("truncated", sorted.size() > count);
        return fields;
    }

    /**
     * Record one sighting for the running scan. The name prefix filters at
     * ingest (the platform cannot prefix-match); the address is the dedup key
     * and the table is capped so an unbounded rf crowd stays bounded.
     */
    @SuppressLint("MissingPermission") // getAddress is a field read; the scan grant covers it
    private void ingest(ScanResult result) {
        if (result == null) {
            return;
        }
        String address;
        try {
            address = result.getDevice() == null ? null : result.getDevice().getAddress();
        } catch (RuntimeException e) {
            return;
        }
        if (address == null || address.isEmpty()) {
            return;
        }
        String name = null;
        List<String> serviceUuids = List.of();
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            try {
                name = record.getDeviceName();
            } catch (RuntimeException ignored) {
                // An unreadable record still contributes address/rssi.
            }
            serviceUuids = serviceUuidsOf(record);
        }
        if (name != null && name.length() > DEVICE_NAME_MAX_CHARS) {
            name = name.substring(0, DEVICE_NAME_MAX_CHARS);
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (!scanRunning) {
                return;
            }
            if (scanNamePrefix != null && !scanNamePrefix.isEmpty()
                    && (name == null || !name.startsWith(scanNamePrefix))) {
                return;
            }
            BleDevice existing = devices.get(address);
            if (existing == null) {
                if (devices.size() >= MAX_TRACKED_DEVICES) {
                    return;
                }
                devices.put(address, new BleDevice(
                        name, address, result.getRssi(), serviceUuids, now));
            } else {
                existing.name = name != null ? name : existing.name;
                existing.rssi = result.getRssi();
                if (!serviceUuids.isEmpty()) {
                    existing.serviceUuids = serviceUuids;
                }
                existing.lastSeenMs = now;
            }
        }
    }

    private static List<String> serviceUuidsOf(ScanRecord record) {
        List<ParcelUuid> uuids;
        try {
            uuids = record.getServiceUuids();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (uuids == null || uuids.isEmpty()) {
            return List.of();
        }
        int count = Math.min(uuids.size(), MAX_SERVICE_UUIDS_PER_DEVICE);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(uuids.get(i).toString());
        }
        return values;
    }

    /**
     * The scan grant: {@code BLUETOOTH_SCAN} on API 31+, fine location below —
     * results are location-derived there and {@code neverForLocation} is a
     * 31+ flag. On the legacy levels a granted-but-dark location switch is the
     * documented silent-empty quirk, refused early.
     */
    private String scanGrantError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return grantError(Manifest.permission.BLUETOOTH_SCAN,
                    "BLUETOOTH_SCAN", "nearby devices");
        }
        CapabilityPermission fine =
                permissions.check(Manifest.permission.ACCESS_FINE_LOCATION);
        if (fine != CapabilityPermission.GRANTED) {
            return fine == CapabilityPermission.DENIED
                    ? "bt-permission-denied:grant the location permission in app settings"
                    : "bt-permission-required:grant ACCESS_FINE_LOCATION"
                            + " via permission.request";
        }
        if (!locationEnabled()) {
            return "bt-unavailable:enable device location";
        }
        return null;
    }

    /**
     * The advertise grant on API 31+: {@code BLUETOOTH_ADVERTISE} always, plus
     * {@code BLUETOOTH_CONNECT} when a local name is advertised (the name is
     * the adapter name and both calls require it). Below 31 the install-time
     * legacy pair covers everything, so there is no runtime gate.
     */
    private String advertiseGrantError(boolean needsConnect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        String error = grantError(Manifest.permission.BLUETOOTH_ADVERTISE,
                "BLUETOOTH_ADVERTISE", "nearby devices");
        if (error != null) {
            return error;
        }
        if (needsConnect) {
            return grantError(Manifest.permission.BLUETOOTH_CONNECT,
                    "BLUETOOTH_CONNECT", "nearby devices");
        }
        return null;
    }

    private String grantError(String permission, String manifestName, String label) {
        CapabilityPermission state = permissions.check(permission);
        if (state == CapabilityPermission.GRANTED) {
            return null;
        }
        return state == CapabilityPermission.DENIED
                ? "bt-permission-denied:grant the " + label + " permission in app settings"
                : "bt-permission-required:grant " + manifestName + " via permission.request";
    }

    /** Device location master switch; treated as on when the manager is absent. */
    private boolean locationEnabled() {
        try {
            LocationManager location =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return location == null || location.isLocationEnabled();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private BluetoothAdapter adapter() {
        try {
            return adapterSource.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission") // isEnabled needs BLUETOOTH_CONNECT on API 31+
    private static boolean enabledOrFalse(BluetoothAdapter adapter) {
        try {
            return adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission") // callers gate on the operation grant first
    private static BluetoothLeScanner scannerOf(BluetoothAdapter adapter) {
        try {
            return adapter.getBluetoothLeScanner();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission") // callers gate on the operation grant first
    private static BluetoothLeAdvertiser advertiserOf(BluetoothAdapter adapter) {
        try {
            return adapter.getBluetoothLeAdvertiser();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission") // getName needs BLUETOOTH_CONNECT on API 31+
    private static String nameOrNull(BluetoothAdapter adapter) {
        try {
            return adapter.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ParcelUuid parseUuid(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return ParcelUuid.fromString(text);
        } catch (RuntimeException e) {
            throw new CapabilityParams.Invalid("invalid uuid parameter: service_uuid");
        }
    }

    private static String encodeNullable(String value) {
        return value == null
                ? "null" : AndroidCapabilityProtocol.encodeStringValue(value);
    }

    /** The {@link BluetoothManager} lookup used in production. */
    private static final class PlatformAdapterSource implements AdapterSource {
        private final Context context;

        PlatformAdapterSource(Context context) {
            this.context = context;
        }

        @Override
        public BluetoothAdapter get() {
            BluetoothManager manager;
            try {
                manager = (BluetoothManager)
                        context.getSystemService(Context.BLUETOOTH_SERVICE);
            } catch (RuntimeException e) {
                return null;
            }
            return manager == null ? null : manager.getAdapter();
        }
    }

    /** Scan sink for the running session; ingest holds {@link #lock} briefly. */
    private final class ModuleScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ingest(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results != null) {
                for (ScanResult result : results) {
                    ingest(result);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            synchronized (lock) {
                scanRunning = false;
                scanFailure = errorCode;
            }
        }
    }

    /**
     * Advertise-start answer collector. The callback only touches its own
     * latch — it never takes {@link #lock} — so the bounded wait inside the
     * lock cannot deadlock whatever thread the stack answers on.
     */
    private static final class ModuleAdvertiseCallback extends AdvertiseCallback {
        private final CountDownLatch answered = new CountDownLatch(1);
        private volatile int errorCode = -1;
        private volatile boolean success;

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            success = true;
            answered.countDown();
        }

        @Override
        public void onStartFailure(int code) {
            errorCode = code;
            answered.countDown();
        }

        boolean awaitStart(long timeoutMillis) {
            try {
                return answered.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean succeeded() {
            return success;
        }

        int errorCode() {
            return errorCode;
        }
    }
}
