package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wifi capability domain behind three bridge methods.
 *
 * <p>{@code wifi.connectioninfo} (no params) reports the current
 * {@link WifiInfo} link in upstream's field spelling ({@code bssid},
 * {@code frequency_mhz}, {@code ip}, {@code link_speed_mbps},
 * {@code mac_address}, {@code network_id}, {@code rssi}, {@code ssid},
 * {@code ssid_hidden}, {@code supplicant_state}) plus a {@code connected}
 * flag for {@code network_id == -1}. SSID/BSSID reads need the location grant
 * the app already holds (or {@code NEARBY_WIFI_DEVICES} on API 33+): a missing
 * grant maps to {@code wifi-permission-required} /
 * {@code wifi-permission-denied}, and a null {@link WifiInfo} maps to
 * {@code wifi-unavailable:no current wifi connection}.</p>
 *
 * <p>{@code wifi.scaninfo} (no params) reports the platform's cached scan
 * results through {@code scan_json} (bounded to {@link #MAX_SCAN_ROWS} rows
 * with a {@code truncated} flag) in upstream's row shape. A platform refusal
 * — a {@link SecurityException} while the grant and device location are both
 * in place — is {@code wifi-scan-throttled}, never a fabricated empty list;
 * an empty cache with device location off is
 * {@code wifi-scan-unavailable:enable device location} (upstream's documented
 * quirk), and wifi switched off is {@code wifi-unavailable:wifi is off}.</p>
 *
 * <p>{@code wifi.set} (param {@code enabled}, required bool) can never toggle
 * wifi: since API 29 only the system Settings panel may. The method validates
 * its params and always answers
 * {@code wifi-toggle-unsupported:Android 10+ only allows the system Settings
 * panel} — it never claims a toggle happened.</p>
 */
public final class WifiModule implements CapabilityModule {
    private static final String TAG = "WifiModule";

    private static final String METHOD_CONNECTION_INFO = "wifi.connectioninfo";
    private static final String METHOD_SCAN_INFO = "wifi.scaninfo";
    private static final String METHOD_SET = "wifi.set";

    private static final int MAX_SCAN_ROWS = 64;
    private static final int MAX_SCAN_FIELD_CHARS = 512;

    private final Context context;
    private final AndroidPermissionChecker permissions;

    public WifiModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissions = new AndroidPermissionChecker(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_CONNECTION_INFO, METHOD_SCAN_INFO, METHOD_SET);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_SET);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_CONNECTION_INFO:
                return connectionInfo(request);
            case METHOD_SCAN_INFO:
                return scanInfo(request);
            case METHOD_SET:
                return set(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code wifi.connectioninfo}. The grant gate runs before the platform
     * call so a missing location permission is its typed error rather than a
     * {@link SecurityException}. The MAC read is part of the upstream
     * {@code termux-wifi-connectioninfo} contract; unprivileged callers get
     * the platform's redacted placeholder, so nothing is fabricated.
     */
    @SuppressLint({"MissingPermission", "Deprecation", "HardwareIds"})
    private AndroidCapabilityProtocol.Response connectionInfo(
            AndroidCapabilityProtocol.Request request) {
        WifiManager wifi = wifiManager();
        if (wifi == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable");
        }
        String grantError = wifiGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        WifiInfo info;
        try {
            info = wifi.getConnectionInfo();
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.connectioninfo refused", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), wifiRefusalError());
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.connectioninfo failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable");
        }
        if (info == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable:no current wifi connection");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        String ssid = info.getSSID();
        if (ssid != null) {
            fields.put("ssid", ssid.replace("\"", ""));
        }
        String bssid = info.getBSSID();
        if (bssid != null) {
            fields.put("bssid", bssid);
        }
        fields.put("ssid_hidden", info.getHiddenSSID());
        fields.put("ip", ipv4(info.getIpAddress()));
        fields.put("link_speed_mbps", (long) info.getLinkSpeed());
        fields.put("frequency_mhz", (long) info.getFrequency());
        fields.put("rssi", (long) info.getRssi());
        fields.put("network_id", (long) info.getNetworkId());
        fields.put("connected", info.getNetworkId() != -1);
        String mac = info.getMacAddress();
        if (mac != null) {
            fields.put("mac_address", mac);
        }
        if (info.getSupplicantState() != null) {
            fields.put("supplicant_state", info.getSupplicantState().toString());
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code wifi.scaninfo}. The read is the platform's cache — no
     * {@code startScan()} is triggered — so a refusal means the platform
     * declined to hand over results at all: grant problems surface as
     * {@code wifi-permission-*}, a refusal with the grant and location in
     * place is {@code wifi-scan-throttled}, and an empty cache with device
     * location off is {@code wifi-scan-unavailable:enable device location}
     * rather than a fake empty list.
     */
    @SuppressLint("MissingPermission")
    private AndroidCapabilityProtocol.Response scanInfo(
            AndroidCapabilityProtocol.Request request) {
        WifiManager wifi = wifiManager();
        if (wifi == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable");
        }
        String grantError = wifiGrantError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), grantError);
        }
        boolean wifiEnabled;
        try {
            wifiEnabled = wifi.isWifiEnabled();
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.scaninfo state check failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable");
        }
        if (!wifiEnabled) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable:wifi is off");
        }
        List<ScanResult> scans;
        try {
            scans = wifi.getScanResults();
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.scaninfo refused", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), scanRefusalError());
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.scaninfo failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable");
        }
        if (scans == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-unavailable");
        }
        if (scans.isEmpty() && !locationEnabled()) {
            // Platform quirk (also documented upstream): with location off the
            // scan cache silently reports empty, so empty is not evidence of
            // no access points.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wifi-scan-unavailable:enable device location");
        }
        int count = Math.min(scans.size(), MAX_SCAN_ROWS);
        StringBuilder json = new StringBuilder(count * 256);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(scanRowJson(scans.get(i)));
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scan_json", json.toString());
        fields.put("count", (long) count);
        if (scans.size() > count) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code wifi.set} — params {@code enabled} (required bool). Android 10+
     * removed the third-party wifi toggle, so after params validate the method
     * always answers the typed {@code wifi-toggle-unsupported} error and never
     * claims a state change.
     */
    private AndroidCapabilityProtocol.Response set(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("enabled"));
        if (!params.has("enabled")) {
            throw new CapabilityParams.Invalid("missing boolean parameter: enabled");
        }
        params.optionalBoolean("enabled", false);
        return AndroidCapabilityProtocol.Response.error(request.getId(),
                "wifi-toggle-unsupported:Android 10+ only allows the system Settings panel");
    }

    /**
     * The grant needed for SSID/BSSID and scan reads: fine location, or
     * {@code NEARBY_WIFI_DEVICES} on API 33+. {@code null} when a usable grant
     * exists, else the typed {@code wifi-permission-*} error.
     */
    private String wifiGrantError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && permissions.check(Manifest.permission.NEARBY_WIFI_DEVICES)
                        == CapabilityPermission.GRANTED) {
            return null;
        }
        CapabilityPermission fine =
                permissions.check(Manifest.permission.ACCESS_FINE_LOCATION);
        if (fine == CapabilityPermission.GRANTED) {
            return null;
        }
        return fine == CapabilityPermission.DENIED
                ? "wifi-permission-denied:grant the location permission in app settings"
                : "wifi-permission-required:grant ACCESS_FINE_LOCATION"
                        + " via permission.request";
    }

    /** A {@link SecurityException} on connection info after the grant gate passed. */
    private String wifiRefusalError() {
        String grantError = wifiGrantError();
        if (grantError != null) {
            return grantError;
        }
        return locationEnabled()
                ? "wifi-unavailable" : "wifi-unavailable:enable device location";
    }

    /**
     * A {@link SecurityException} on the scan read: a grant problem is its
     * typed error, location-off is the documented empty-cache quirk, and a
     * refusal with both in place is the throttled typed error.
     */
    private String scanRefusalError() {
        String grantError = wifiGrantError();
        if (grantError != null) {
            return grantError;
        }
        return locationEnabled()
                ? "wifi-scan-throttled"
                : "wifi-scan-unavailable:enable device location";
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

    private WifiManager wifiManager() {
        try {
            return (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One scan row in upstream's field order and spelling. */
    @SuppressLint("Deprecation") // ScanResult.SSID is still the public field for the name
    private static String scanRowJson(ScanResult scan) {
        StringBuilder row = new StringBuilder(256);
        row.append("{\"bssid\":").append(encodeNullable(scan.BSSID))
                .append(",\"frequency_mhz\":").append(scan.frequency)
                .append(",\"rssi\":").append(scan.level)
                .append(",\"ssid\":").append(encodeNullable(scan.SSID))
                .append(",\"timestamp\":").append(scan.timestamp)
                .append(",\"channel_bandwidth_mhz\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(
                        channelBandwidth(scan.channelWidth)));
        if (scan.channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ) {
            // centerFreq0 is documented as unused at 20 MHz.
            row.append(",\"center_frequency_mhz\":").append(scan.centerFreq0);
        }
        appendTextField(row, "capabilities", scan.capabilities);
        appendTextField(row, "operator_name", scan.operatorFriendlyName);
        appendTextField(row, "venue_name", scan.venueName);
        return row.append('}').toString();
    }

    private static void appendTextField(StringBuilder row, String key,
                                        CharSequence value) {
        if (value == null || value.length() == 0) {
            return;
        }
        String text = value.toString();
        if (text.length() > MAX_SCAN_FIELD_CHARS) {
            text = text.substring(0, MAX_SCAN_FIELD_CHARS);
        }
        row.append(",\"").append(key).append("\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(text));
    }

    /** Upstream's {@code channel_bandwidth_mhz} spelling. */
    private static String channelBandwidth(int channelWidth) {
        switch (channelWidth) {
            case ScanResult.CHANNEL_WIDTH_20MHZ: return "20";
            case ScanResult.CHANNEL_WIDTH_40MHZ: return "40";
            case ScanResult.CHANNEL_WIDTH_80MHZ: return "80";
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ: return "80+80";
            case ScanResult.CHANNEL_WIDTH_160MHZ: return "160";
            default: return "???";
        }
    }

    /** Dotted-quad form of {@link WifiInfo#getIpAddress()}'s little-endian int. */
    private static String ipv4(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    private static String encodeNullable(String value) {
        return value == null
                ? "null" : AndroidCapabilityProtocol.encodeStringValue(value);
    }
}
