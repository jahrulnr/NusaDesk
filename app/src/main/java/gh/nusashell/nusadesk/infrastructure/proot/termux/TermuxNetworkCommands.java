package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The wifi domain's Termux-compat commands: {@code termux-wifi-connectioninfo},
 * {@code termux-wifi-scaninfo}, and {@code termux-wifi-enable} over the
 * {@code wifi.*} bridge methods.
 *
 * <p>{@code termux-wifi-enable} can never succeed on Android 10+: the bridge
 * always answers {@code wifi-toggle-unsupported} and the command reports it
 * with exit 1 — the upstream toggle no longer exists for third-party apps.</p>
 */
public final class TermuxNetworkCommands {

    private TermuxNetworkCommands() {
    }

    /** The wifi commands in documentation order. */
    public static List<TermuxCommand> commands() {
        return Collections.unmodifiableList(Arrays.asList(
                wifiConnectioninfo(),
                wifiScaninfo(),
                wifiEnable()));
    }

    private static TermuxCommand wifiConnectioninfo() {
        return new TermuxCommand(
                "termux-wifi-connectioninfo",
                String.join("\n",
                        "FIELDS = ('bssid', 'frequency_mhz', 'ip', 'link_speed_mbps',",
                        "          'mac_address', 'network_id', 'rssi', 'ssid',",
                        "          'ssid_hidden', 'supplicant_state', 'connected')",
                        "",
                        "",
                        "def main(argv):",
                        "    if argv:",
                        "        raise tc.CliError('usage: termux-wifi-connectioninfo')",
                        "    reading = tc.bridge_call('wifi.connectioninfo')",
                        "    return tc.emit({key: reading[key] for key in FIELDS",
                        "                    if key in reading})",
                        ""),
                Arrays.asList(
                        "- `termux-wifi-connectioninfo` - current wifi link in the",
                        "  upstream shape: `bssid`, `frequency_mhz`, `ip`,",
                        "  `link_speed_mbps`, `mac_address`, `network_id`, `rssi`,",
                        "  `ssid`, `ssid_hidden`, `supplicant_state`, plus a",
                        "  `connected` flag. No current link is a typed",
                        "  `wifi-unavailable` error, not an `API_ERROR` document."));
    }

    private static TermuxCommand wifiScaninfo() {
        return new TermuxCommand(
                "termux-wifi-scaninfo",
                String.join("\n",
                        "import json",
                        "",
                        "",
                        "def main(argv):",
                        "    if argv:",
                        "        raise tc.CliError('usage: termux-wifi-scaninfo')",
                        "    reading = tc.bridge_call('wifi.scaninfo')",
                        "    tc.warn_truncated('termux-wifi-scaninfo', reading)",
                        "    return tc.emit(json.loads(reading.get('scan_json', '[]')))",
                        ""),
                Arrays.asList(
                        "- `termux-wifi-scaninfo` - latest platform scan cache; rows",
                        "  `bssid`, `frequency_mhz`, `rssi`, `ssid`, `timestamp`,",
                        "  `channel_bandwidth_mhz`, plus `center_frequency_mhz`,",
                        "  `capabilities`, `operator_name`, `venue_name` when the",
                        "  platform reports them. A platform refusal is the typed",
                        "  `wifi-scan-throttled` error, not a fake empty list; wifi",
                        "  off or device location off are typed errors too."));
    }

    private static TermuxCommand wifiEnable() {
        return new TermuxCommand(
                "termux-wifi-enable",
                String.join("\n",
                        "def main(argv):",
                        "    if len(argv) != 1 or argv[0] not in ('true', 'false'):",
                        "        raise tc.CliError('usage: termux-wifi-enable [true | false]')",
                        "    enabled = argv[0] == 'true'",
                        "    tc.bridge_call('wifi.set', {'enabled': enabled})",
                        "    return tc.emit({'enabled': enabled})",
                        ""),
                Arrays.asList(
                        "- `termux-wifi-enable true|false` - always fails with",
                        "  `wifi-toggle-unsupported:Android 10+ only allows the",
                        "  system Settings panel` (exit 1): third-party apps lost",
                        "  the wifi toggle in Android 10, so the command never",
                        "  claims a state change."));
    }
}
