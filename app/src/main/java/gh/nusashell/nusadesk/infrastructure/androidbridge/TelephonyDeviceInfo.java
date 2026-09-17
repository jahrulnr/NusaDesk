package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable bounded snapshot of the device telephony state, kept Android-free
 * for testing.
 *
 * <p>Phone type and SIM state are always carried when telephony exists.
 * Network type and data-network type are permission-gated enrichment: they
 * are present only when the {@code READ_PHONE_STATE} grant was observed at
 * read time, and are never fabricated. IMEI, serial numbers, subscriber id,
 * phone numbers, and operator identity are deliberately omitted. No telephony
 * support is an explicit state, never a fabricated reading; the bridge
 * encodes only a {@link MessagingReadState#READING} snapshot with fields and
 * reports every other state as a typed error.</p>
 */
public final class TelephonyDeviceInfo {
    private final MessagingReadState state;
    private final String phoneType;
    private final String simState;
    private final String networkType;
    private final String dataNetworkType;

    private TelephonyDeviceInfo(MessagingReadState state, String phoneType, String simState,
                                String networkType, String dataNetworkType) {
        this.state = state;
        this.phoneType = bounded(phoneType, "phoneType", 16);
        this.simState = bounded(simState, "simState", 32);
        this.networkType = optional(networkType, "networkType", 32);
        this.dataNetworkType = optional(dataNetworkType, "dataNetworkType", 32);
    }

    /** A complete telephony snapshot; network fields may be {@code null} when not granted. */
    public static TelephonyDeviceInfo reading(String phoneType, String simState,
                                              String networkType, String dataNetworkType) {
        return new TelephonyDeviceInfo(MessagingReadState.READING,
                phoneType, simState, networkType, dataNetworkType);
    }

    /** No telephony radio on this device. */
    public static TelephonyDeviceInfo noTelephony() {
        return new TelephonyDeviceInfo(MessagingReadState.NO_TELEPHONY, "none", "unknown",
                null, null);
    }

    /** Phone-state grant absent and no recorded denial; the later consent flow may ask. */
    public static TelephonyDeviceInfo permissionRequired() {
        return new TelephonyDeviceInfo(MessagingReadState.PERMISSION_REQUIRED,
                "unknown", "unknown", null, null);
    }

    /** Phone-state grant absent and the user previously denied it. */
    public static TelephonyDeviceInfo permissionDenied() {
        return new TelephonyDeviceInfo(MessagingReadState.PERMISSION_DENIED,
                "unknown", "unknown", null, null);
    }

    /** The telephony service is absent. */
    public static TelephonyDeviceInfo unavailable() {
        return new TelephonyDeviceInfo(MessagingReadState.UNAVAILABLE,
                "unknown", "unknown", null, null);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static TelephonyDeviceInfo error() {
        return new TelephonyDeviceInfo(MessagingReadState.ERROR,
                "unknown", "unknown", null, null);
    }

    public MessagingReadState getState() {
        return state;
    }

    /** Lower-case phone type name; {@code "none"} without telephony. */
    public String getPhoneType() {
        return phoneType;
    }

    /** Lower-case SIM state name; {@code "unknown"} when not readable. */
    public String getSimState() {
        return simState;
    }

    /** {@code null} unless the {@code READ_PHONE_STATE} grant was observed. */
    public String getNetworkType() {
        return networkType;
    }

    /** {@code null} unless the {@code READ_PHONE_STATE} grant was observed. */
    public String getDataNetworkType() {
        return dataNetworkType;
    }

    /**
     * Flat fields for the RPC response. Only a reading has a field
     * representation; other states are reported as typed errors by the
     * request handler and must not look like real data here. Permission-gated
     * network fields are omitted, never fabricated.
     */
    public Map<String, Object> responseFields() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("response fields are defined only for a reading");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("available", true);
        fields.put("phone_type", phoneType);
        fields.put("sim_state", simState);
        if (networkType != null) {
            fields.put("network_type", networkType);
        }
        if (dataNetworkType != null) {
            fields.put("data_network_type", dataNetworkType);
        }
        return fields;
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return MessagingReadPolicy.truncate(value.trim(), max);
    }

    private static String optional(String value, String field, int max) {
        return value == null ? null : bounded(value, field, max);
    }
}
