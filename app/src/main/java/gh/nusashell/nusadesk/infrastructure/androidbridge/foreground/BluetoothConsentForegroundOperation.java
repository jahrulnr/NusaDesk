package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code bluetooth} foreground operation behind {@code bt.enable.request}
 * and {@code bt.discoverable.request}.
 *
 * <p>Param {@code op} selects the consent dialog: {@code enable} launches
 * {@link BluetoothAdapter#ACTION_REQUEST_ENABLE} and reports the settled
 * adapter {@code state} ({@code on}/{@code off}/{@code turning_on}/
 * {@code turning_off}) — the radio powers up asynchronously, so a granted
 * request is given a bounded window to reach {@code STATE_ON} before the
 * state is read. {@code discoverable} launches
 * {@link BluetoothAdapter#ACTION_REQUEST_DISCOVERABLE} with the
 * caller-bounded {@code seconds} extra and reports the platform's granted
 * {@code duration_seconds} (0 when the user declined) plus the resulting
 * {@code scan_mode}.</p>
 *
 * <p>The instance is a shared catalog entry: per-request state lives in the
 * {@code run} call and its result-handler closure, never in fields.</p>
 */
public final class BluetoothConsentForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name the {@code bt.*} requests run. */
    public static final String KIND = "bluetooth";

    private static final String TAG = "BtConsentOp";
    private static final int REQUEST_ENABLE = 0xB701;
    private static final int REQUEST_DISCOVERABLE = 0xB702;
    private static final long ENABLE_SETTLE_TIMEOUT_MS = 5_000L;
    private static final long ENABLE_SETTLE_STEP_MS = 150L;
    private static final long DURATION_MIN_SECONDS = 1L;
    private static final long DURATION_MAX_SECONDS = 300L;
    private static final long DURATION_DEFAULT_SECONDS = 120L;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        String op = params.get("op") instanceof String ? (String) params.get("op") : "";
        BluetoothAdapter adapter = adapter(activity);
        if (adapter == null) {
            sink.error("bt-unsupported:no bluetooth adapter");
            return;
        }
        switch (op) {
            case "enable":
                requestEnable(activity, adapter, sink);
                break;
            case "discoverable":
                requestDiscoverable(activity, params, adapter, sink);
                break;
            default:
                sink.error("invalid-argument");
        }
    }

    /**
     * Show the system enable prompt. A consenting result lets the radio
     * settle toward {@code STATE_ON} off the main thread before the state is
     * reported; a refusal reports the state as observed.
     */
    // The calling module gates BLUETOOTH_CONNECT before dispatching the op.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void requestEnable(CapabilityForegroundActivity activity,
                               BluetoothAdapter adapter, ResultSink sink) {
        activity.setActivityResultHandler((requestCode, resultCode, data) -> {
            if (requestCode != REQUEST_ENABLE) {
                return;
            }
            if (resultCode == Activity.RESULT_OK) {
                new Thread(() -> sink.success(stateFields(adapter, true)),
                        "bt-enable-settle").start();
            } else {
                sink.success(stateFields(adapter, false));
            }
        });
        try {
            activity.startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE);
        } catch (RuntimeException e) {
            Log.w(TAG, "enable dialog could not start", e);
            sink.error("bt-unavailable:enable dialog could not start");
        }
    }

    /**
     * Show the system discoverable prompt. The result code is the granted
     * duration in seconds — {@code RESULT_CANCELED} (0) when declined — so
     * the reported {@code duration_seconds} is the platform's own answer,
     * never the requested one.
     */
    // The calling module gates BLUETOOTH_ADVERTISE before dispatching the op.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void requestDiscoverable(CapabilityForegroundActivity activity,
                                     Map<String, Object> params,
                                     BluetoothAdapter adapter, ResultSink sink) {
        Object raw = params.get("seconds");
        long seconds = raw instanceof Long ? (Long) raw : DURATION_DEFAULT_SECONDS;
        if (seconds < DURATION_MIN_SECONDS || seconds > DURATION_MAX_SECONDS) {
            sink.error("invalid-argument");
            return;
        }
        activity.setActivityResultHandler((requestCode, resultCode, data) -> {
            if (requestCode != REQUEST_DISCOVERABLE) {
                return;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("duration_seconds", (long) Math.max(resultCode, 0));
            fields.put("scan_mode", scanModeName(scanModeOf(adapter)));
            sink.success(fields);
        });
        try {
            activity.startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                    (int) seconds),
                    REQUEST_DISCOVERABLE);
        } catch (RuntimeException e) {
            Log.w(TAG, "discoverable dialog could not start", e);
            sink.error("bt-unavailable:discoverable dialog could not start");
        }
    }

    /** Report the adapter state, waiting briefly for a consent-initiated enable to settle. */
    private static Map<String, Object> stateFields(BluetoothAdapter adapter, boolean settle) {
        if (settle) {
            long deadline = System.currentTimeMillis() + ENABLE_SETTLE_TIMEOUT_MS;
            while (stateOf(adapter) != BluetoothAdapter.STATE_ON
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(ENABLE_SETTLE_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", stateName(stateOf(adapter)));
        return fields;
    }

    private static BluetoothAdapter adapter(Context context) {
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return manager == null ? null : manager.getAdapter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static int stateOf(BluetoothAdapter adapter) {
        try {
            return adapter.getState();
        } catch (RuntimeException e) {
            return BluetoothAdapter.STATE_OFF;
        }
    }

    @SuppressLint("MissingPermission")
    private static int scanModeOf(BluetoothAdapter adapter) {
        try {
            return adapter.getScanMode();
        } catch (RuntimeException e) {
            return BluetoothAdapter.SCAN_MODE_NONE;
        }
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
}
