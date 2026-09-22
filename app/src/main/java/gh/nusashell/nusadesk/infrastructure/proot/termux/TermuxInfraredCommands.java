package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The infrared domain's Termux-compat commands: {@code
 * termux-infrared-frequencies} and {@code termux-infrared-transmit} over the
 * {@code infrared.*} bridge methods.
 *
 * <p>A device without an IR emitter answers the typed
 * {@code infrared-unavailable:this device has no IR emitter} (exit 1) rather
 * than upstream's empty array — an honest absence the guest can act on.</p>
 */
public final class TermuxInfraredCommands {

    private TermuxInfraredCommands() {
    }

    /** The infrared commands in documentation order. */
    public static List<TermuxCommand> commands() {
        return Collections.unmodifiableList(Arrays.asList(
                infraredFrequencies(),
                infraredTransmit()));
    }

    private static TermuxCommand infraredFrequencies() {
        return new TermuxCommand(
                "termux-infrared-frequencies",
                String.join("\n",
                        "import json",
                        "",
                        "",
                        "def main(argv):",
                        "    if argv:",
                        "        raise tc.CliError('usage: termux-infrared-frequencies')",
                        "    reading = tc.bridge_call('infrared.frequencies')",
                        "    return tc.emit(json.loads(reading.get('ranges_json', '[]')))",
                        ""),
                Arrays.asList(
                        "- `termux-infrared-frequencies` - emitter carrier ranges as",
                        "  `[{\"min\":hz,\"max\":hz}]`. Devices without an IR emitter",
                        "  answer `infrared-unavailable:this device has no IR",
                        "  emitter` (exit 1) instead of upstream's empty array."));
    }

    private static TermuxCommand infraredTransmit() {
        return new TermuxCommand(
                "termux-infrared-transmit",
                String.join("\n",
                        "USAGE = 'usage: termux-infrared-transmit -f frequency pattern'",
                        "",
                        "",
                        "def parse(argv):",
                        "    if '-h' in argv or '--help' in argv:",
                        "        return None",
                        "    frequency = None",
                        "    positional = []",
                        "    index = 0",
                        "    while index < len(argv):",
                        "        flag = argv[index]",
                        "        if flag == '-f' and index + 1 < len(argv):",
                        "            frequency = argv[index + 1]",
                        "            index += 2",
                        "            continue",
                        "        if flag.startswith('-'):",
                        "            raise tc.CliError(USAGE)",
                        "        positional.append(flag)",
                        "        index += 1",
                        "    if frequency is None:",
                        "        raise tc.CliError('no frequency specified')",
                        "    if not frequency.isdigit():",
                        "        raise tc.CliError('frequency must be a number')",
                        "    if len(positional) != 1:",
                        "        raise tc.CliError('expected exactly one pattern argument')",
                        "    return int(frequency, 10), positional[0]",
                        "",
                        "",
                        "def main(argv):",
                        "    spec = parse(argv)",
                        "    if spec is None:",
                        "        print(USAGE)",
                        "        return 0",
                        "    frequency, pattern = spec",
                        "    result = tc.bridge_call('infrared.transmit',",
                        "                            {'frequency': frequency,",
                        "                             'pattern': pattern})",
                        "    return tc.emit({'transmitted': result.get('transmitted') is True,",
                        "                    'frequency': frequency,",
                        "                    'periods': result.get('periods')})",
                        ""),
                Arrays.asList(
                        "- `termux-infrared-transmit -f HZ pattern` - transmit one",
                        "  comma-separated on/off microsecond pattern (bounded to",
                        "  256 periods and two seconds total, like upstream); emits",
                        "  `transmitted`, `frequency`, `periods` on success.",
                        "  Without an emitter the typed `infrared-unavailable`",
                        "  error applies."));
    }
}
