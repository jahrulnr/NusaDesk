package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.telephony.TelephonyManager;

/**
 * Bounded, permission-aware {@link TelephonyManager} device-info adapter.
 *
 * <p>The base contract — phone type and SIM state — is read whenever
 * telephony exists and needs no runtime permission, so a guest without the
 * phone-state grant still gets the device's telephony shape. Network type
 * and data-network type are sensitive telephony details, so they are read
 * only when the read-phone-state grant is observed per read and are omitted
 * (never fabricated) otherwise. IMEI, serial numbers, subscriber id, phone
 * numbers, and operator identity are never read. A device without telephony
 * is an explicit {@link MessagingReadState#NO_TELEPHONY} state, and this
 * adapter never requests permission and never opens a permission activity.</p>
 */
public final class AndroidTelephonyInfoSource implements TelephonyInfoSource {
    private final Context context;
    private final MessagingPermissionChecker permissionChecker;

    public AndroidTelephonyInfoSource(Context context) {
        this(context, new AndroidMessagingPermissionChecker(context));
    }

    AndroidTelephonyInfoSource(Context context, MessagingPermissionChecker permissionChecker) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (permissionChecker == null) {
            throw new IllegalArgumentException("permissionChecker must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissionChecker = permissionChecker;
    }

    /**
     * The network-type reads below are permission-gated twice: they run only
     * after the read-phone-state grant was observed via the per-read
     * permission checker, and a grant revoked mid-read is caught and the
     * field omitted. Lint cannot see the checker's grant, hence the
     * suppression; the explicit check is the enforcement.
     */
    @android.annotation.SuppressLint("MissingPermission")
    @Override
    public TelephonyDeviceInfo read() {
        TelephonyManager manager = telephonyManager();
        if (manager == null) {
            return TelephonyDeviceInfo.unavailable();
        }
        int phoneType;
        try {
            phoneType = manager.getPhoneType();
        } catch (SecurityException e) {
            return TelephonyDeviceInfo.permissionDenied();
        } catch (RuntimeException e) {
            return TelephonyDeviceInfo.error();
        }
        if (phoneType == TelephonyManager.PHONE_TYPE_NONE) {
            return TelephonyDeviceInfo.noTelephony();
        }
        int simState;
        try {
            simState = manager.getSimState();
        } catch (RuntimeException e) {
            simState = TelephonyManager.SIM_STATE_UNKNOWN;
        }
        String networkType = null;
        String dataNetworkType = null;
        if (permissionChecker.check(Manifest.permission.READ_PHONE_STATE)
                == CapabilityPermission.GRANTED) {
            // A grant revoked mid-read omits the sensitive field rather than
            // failing the whole read; the base contract stays available.
            try {
                networkType = networkTypeName(manager.getNetworkType());
            } catch (RuntimeException ignored) {
                // Omitted; never fabricated.
            }
            try {
                dataNetworkType = networkTypeName(manager.getDataNetworkType());
            } catch (RuntimeException ignored) {
                // Omitted; never fabricated.
            }
        }
        return TelephonyDeviceInfo.reading(phoneTypeName(phoneType),
                simStateName(simState), networkType, dataNetworkType);
    }

    private TelephonyManager telephonyManager() {
        try {
            return (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String phoneTypeName(int value) {
        switch (value) {
            case TelephonyManager.PHONE_TYPE_NONE:
                return "none";
            case TelephonyManager.PHONE_TYPE_GSM:
                return "gsm";
            case TelephonyManager.PHONE_TYPE_CDMA:
                return "cdma";
            case TelephonyManager.PHONE_TYPE_SIP:
                return "sip";
            default:
                return "unknown";
        }
    }

    private static String simStateName(int value) {
        switch (value) {
            case TelephonyManager.SIM_STATE_UNKNOWN:
                return "unknown";
            case TelephonyManager.SIM_STATE_ABSENT:
                return "absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return "pin_required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return "puk_required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return "network_locked";
            case TelephonyManager.SIM_STATE_READY:
                return "ready";
            case TelephonyManager.SIM_STATE_NOT_READY:
                return "not_ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED:
                // Value 7. On API 29/30 the same value was named
                // SIM_STATE_PERMISSION_DENIED; the constant was removed from
                // newer SDK surfaces and the slot reused. The SIM is
                // unusable either way, and this adapter's own permission gate
                // already models the "permission denied" read state.
                return "perm_disabled";
            default:
                return "unknown";
        }
    }

    private static String networkTypeName(int value) {
        switch (value) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "gprs";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "edge";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "umts";
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return "cdma";
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return "evdo_0";
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return "evdo_a";
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "1xrtt";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                return "hsdpa";
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "hsupa";
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "hspa";
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "iden";
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "evdo_b";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "lte";
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "ehrpd";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "hspap";
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "gsm";
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "td_scdma";
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "iwlan";
            case 19:
                // NETWORK_TYPE_LTE_CA was removed from newer SDK surfaces
                // (deprecated API 33 in favor of NETWORK_TYPE_LTE); the value
                // itself is stable platform state, so it maps defensively.
                return "lte_ca";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "nr";
            default:
                return "unknown";
        }
    }
}
