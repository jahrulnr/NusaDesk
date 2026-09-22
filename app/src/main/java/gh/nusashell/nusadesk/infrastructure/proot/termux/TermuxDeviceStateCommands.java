package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Guest commands for the device-state module ({@code vibrate},
 * {@code torch.set}, {@code volume.get}, {@code volume.set},
 * {@code audio.info}, {@code brightness.set}) plus the lifecycle no-ops and
 * the deprecated {@code termux-sms-inbox} alias. Bodies only parse the Termux
 * flags and map the answer onto the upstream JSON shape; anything the bridge
 * did not answer is omitted rather than fabricated, and Termux flags the
 * bridge cannot express (like brightness {@code auto}) fail with a typed
 * error instead of pretending.
 */
public final class TermuxDeviceStateCommands {

    private TermuxDeviceStateCommands() {
    }

    /** The device-state commands in documentation order. */
    public static List<TermuxCommand> commands() {
        return Collections.unmodifiableList(Arrays.asList(
                vibrate(),
                torch(),
                volume(),
                brightness(),
                audioInfo(),
                apiStart(),
                apiStop(),
                smsInbox()));
    }

    private static TermuxCommand vibrate() {
        return new TermuxCommand(
                "termux-vibrate",
                String.join("\n",
                        "def parse(argv):",
                        "    duration_ms = None",
                        "    force = False",
                        "    index = 0",
                        "    while index < len(argv):",
                        "        flag = argv[index]",
                        "        if flag == '-f':",
                        "            force = True",
                        "            index += 1",
                        "            continue",
                        "        if flag != '-d' or index + 1 >= len(argv):",
                        "            raise tc.CliError(",
                        "                'usage: termux-vibrate [-d duration] [-f]')",
                        "        value = argv[index + 1]",
                        "        if not (value.isascii() and value.isdigit()):",
                        "            raise tc.CliError(",
                        "                'duration must be a number of milliseconds')",
                        "        duration_ms = int(value, 10)",
                        "        index += 2",
                        "    return duration_ms, force",
                        "",
                        "",
                        "def main(argv):",
                        "    duration_ms, force = parse(argv)",
                        "    params = {}",
                        "    if duration_ms is not None:",
                        "        params['duration_ms'] = duration_ms",
                        "    if force:",
                        "        params['force'] = True",
                        "    tc.bridge_call('vibrate', params)",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-vibrate [-d duration] [-f]` - vibrate the",
                        "  device; `-d` is milliseconds (default 1000), `-f`",
                        "  vibrates even in silent mode. Prints nothing on",
                        "  success, like upstream."));
    }

    private static TermuxCommand torch() {
        return new TermuxCommand(
                "termux-torch",
                String.join("\n",
                        "def main(argv):",
                        "    if len(argv) != 1 or argv[0] not in ('on', 'off'):",
                        "        raise tc.CliError('usage: termux-torch on|off')",
                        "    tc.bridge_call('torch.set', {'enabled': argv[0] == 'on'})",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-torch on|off` - toggle the LED torch on the",
                        "  first flash-capable camera. Prints nothing on",
                        "  success, like upstream."));
    }

    private static TermuxCommand volume() {
        return new TermuxCommand(
                "termux-volume",
                String.join("\n",
                        "import json",
                        "",
                        "",
                        "def all_streams():",
                        "    rows = json.loads(tc.call('volume.get')['streams_json'])",
                        "    result = []",
                        "    for row in rows:",
                        "        if not isinstance(row, dict):",
                        "            continue",
                        "        # Termux's own shape is stream/volume/max_volume only:",
                        "        # the bridge's min_volume and muted stay unprinted.",
                        "        result.append({",
                        "            'stream': row.get('stream'),",
                        "            'volume': int(row.get('volume')),",
                        "            'max_volume': int(row.get('max_volume')),",
                        "        })",
                        "    return result",
                        "",
                        "",
                        "def main(argv):",
                        "    if not argv:",
                        "        return tc.emit(all_streams())",
                        "    if len(argv) != 2:",
                        "        raise tc.CliError('usage: termux-volume <stream> <volume>')",
                        "    volume_text = argv[1]",
                        "    if not (volume_text.isascii() and volume_text.isdigit()):",
                        "        raise tc.CliError('volume must be a non-negative number')",
                        "    tc.bridge_call('volume.set',",
                        "                   {'stream': argv[0], 'volume': int(volume_text, 10)})",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-volume` - JSON array of the six audio",
                        "  streams; fields `stream`, `volume`, `max_volume`.",
                        "- `termux-volume <stream> <volume>` - set one stream",
                        "  (`alarm`, `call`, `music`, `notification`, `ring`,",
                        "  `system`); prints nothing on success. An unknown",
                        "  stream or out-of-range volume is the bridge's typed",
                        "  `volume-invalid-stream` error."));
    }

    private static TermuxCommand brightness() {
        return new TermuxCommand(
                "termux-brightness",
                String.join("\n",
                        "def main(argv):",
                        "    if len(argv) != 1:",
                        "        raise tc.CliError('usage: termux-brightness <0-255|auto>')",
                        "    value = argv[0]",
                        "    if value == 'auto':",
                        "        raise tc.BridgeError(",
                        "            'action-unsupported:the bridge sets a fixed'",
                        "            ' brightness value only; auto mode is not exposed')",
                        "    if not (value.isascii() and value.isdigit()):",
                        "        raise tc.CliError(",
                        "            'brightness must be a number between 0 and 255 or auto')",
                        "    # Upstream clamps a number above 255 down to 255.",
                        "    tc.bridge_call('brightness.set',",
                        "                   {'value': min(int(value, 10), 255)})",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-brightness <0-255>` - set screen brightness;",
                        "  needs the `WRITE_SETTINGS` special access, and the",
                        "  typed `brightness-permission-required` error names",
                        "  the grant path. Values above 255 clamp like",
                        "  upstream. Upstream's `auto` mode is not exposed by",
                        "  the bridge and fails with `action-unsupported`."));
    }

    private static TermuxCommand audioInfo() {
        return new TermuxCommand(
                "termux-audio-info",
                String.join("\n",
                        "def to_termux(reading):",
                        "    result = {}",
                        "    sample_rate = reading.get('sample_rate')",
                        "    frames = reading.get('frames_per_buffer')",
                        "    if sample_rate is not None:",
                        "        result['PROPERTY_OUTPUT_SAMPLE_RATE'] = str(int(sample_rate))",
                        "    if frames is not None:",
                        "        result['PROPERTY_OUTPUT_FRAMES_PER_BUFFER'] = str(int(frames))",
                        "    if sample_rate is not None:",
                        "        result['AUDIOTRACK_SAMPLE_RATE'] = int(sample_rate)",
                        "    if frames is not None:",
                        "        result['AUDIOTRACK_BUFFER_SIZE_IN_FRAMES'] = int(frames)",
                        "    a2dp = reading.get('bluetooth_a2dp_on')",
                        "    if a2dp is not None:",
                        "        result['BLUETOOTH_A2DP_IS_ON'] = a2dp is True",
                        "    headset = reading.get('wired_headset_on')",
                        "    if headset is not None:",
                        "        result['WIREDHEADSET_IS_CONNECTED'] = headset is True",
                        "    return result",
                        "",
                        "",
                        "def main(argv):",
                        "    if argv:",
                        "        raise tc.CliError('usage: termux-audio-info')",
                        "    return tc.emit(to_termux(tc.call('audio.info')))",
                        ""),
                Arrays.asList(
                        "- `termux-audio-info` - audio properties; fields",
                        "  `PROPERTY_OUTPUT_SAMPLE_RATE`,",
                        "  `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`,",
                        "  `AUDIOTRACK_SAMPLE_RATE`,",
                        "  `AUDIOTRACK_BUFFER_SIZE_IN_FRAMES`,",
                        "  `BLUETOOTH_A2DP_IS_ON`, `WIREDHEADSET_IS_CONNECTED`.",
                        "  Termux's low-latency/power-saving AudioTrack fields",
                        "  are not exposed by the bridge and are omitted."));
    }

    private static TermuxCommand apiStart() {
        return new TermuxCommand(
                "termux-api-start",
                String.join("\n",
                        "def main(argv):",
                        "    tc.warn('termux-api-start',",
                        "            'no-op: the Android bridge lives with the NusaDesk'",
                        "            ' session; there is no Termux:API service to start')",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-api-start` - no-op success with a stderr",
                        "  note: upstream starts the Termux:API app, but the",
                        "  bridge lives with the NusaDesk session, so there is",
                        "  nothing to start."));
    }

    private static TermuxCommand apiStop() {
        return new TermuxCommand(
                "termux-api-stop",
                String.join("\n",
                        "def main(argv):",
                        "    tc.warn('termux-api-stop',",
                        "            'no-op: the Android bridge lives with the NusaDesk'",
                        "            ' session; there is no Termux:API service to stop')",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-api-stop` - no-op success with a stderr",
                        "  note: upstream stops the Termux:API app, but the",
                        "  bridge lives with the NusaDesk session, so there is",
                        "  nothing to stop."));
    }

    private static TermuxCommand smsInbox() {
        return new TermuxCommand(
                "termux-sms-inbox",
                String.join("\n",
                        "import datetime",
                        "import json",
                        "",
                        "",
                        "MAX_ROWS = 50",
                        "",
                        "",
                        "def parse(argv):",
                        "    limit = None",
                        "    offset = None",
                        "    index = 0",
                        "    while index < len(argv):",
                        "        flag = argv[index]",
                        "        if flag not in ('-l', '-o') or index + 1 >= len(argv):",
                        "            raise tc.CliError(",
                        "                'usage: termux-sms-inbox [-l LIMIT] [-o OFFSET]')",
                        "        value = argv[index + 1]",
                        "        if not value.isdigit():",
                        "            raise tc.CliError(",
                        "                'limit and offset must be non-negative numbers')",
                        "        if flag == '-l':",
                        "            limit = int(value, 10)",
                        "        else:",
                        "            offset = int(value, 10)",
                        "        index += 2",
                        "    return limit, offset",
                        "",
                        "",
                        "def to_termux(reading, limit, offset):",
                        "    tc.warn_truncated('termux-sms-inbox', reading)",
                        "    rows = json.loads(reading.get('rows', '[]'))",
                        "    if offset is not None:",
                        "        rows = rows[offset:]",
                        "    if limit is not None:",
                        "        rows = rows[:limit]",
                        "    else:",
                        "        rows = rows[:MAX_ROWS]",
                        "    result = []",
                        "    for row in rows:",
                        "        if not isinstance(row, dict):",
                        "            continue",
                        "        entry = {",
                        "            'type': 'inbox',",
                        "            'read': row.get('read') is True,",
                        "            'address': row.get('address', ''),",
                        "            'number': row.get('address', ''),",
                        "            'body': row.get('snippet', ''),",
                        "        }",
                        "        received_ms = row.get('timestamp_utc_ms')",
                        "        if isinstance(received_ms, int) and received_ms > 0:",
                        "            entry['received'] = datetime.datetime.fromtimestamp(",
                        "                received_ms / 1000).strftime('%Y-%m-%d %H:%M:%S')",
                        "        result.append(entry)",
                        "    return result",
                        "",
                        "",
                        "def main(argv):",
                        "    tc.warn('termux-sms-inbox',",
                        "            'deprecated alias of termux-sms-list; use'",
                        "            ' termux-sms-list directly')",
                        "    limit, offset = parse(argv)",
                        "    reading = tc.call('sms.inbox')",
                        "    return tc.emit(to_termux(reading, limit, offset))",
                        ""),
                Arrays.asList(
                        "- `termux-sms-inbox [-l LIMIT] [-o OFFSET]` -",
                        "  deprecated alias of `termux-sms-list`; prints a",
                        "  deprecation note on stderr and the same inbox rows",
                        "  on stdout."));
    }
}
