package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The NFC Termux-compat command (wave 2, W2g): {@code termux-nfc}.
 *
 * <p>A thin flag mapping onto {@code nfc.read} / {@code nfc.write}: reads
 * park a foreground reader-mode operation (the only legal NFC path on
 * Android) and print the upstream JSON shape; writes carry the {@code -t}
 * text as one UTF-8 NDEF text record. One extension over upstream: under
 * {@code -r}, {@code -t SECONDS} bounds the tag wait (upstream's
 * {@code -r -t} combination produced a "Wrong Params" answer, so nothing
 * valid is displaced). With neither {@code -r} nor {@code -w} the command
 * prints the upstream presence answer {@code {"nfcPresent":..,"nfcActive":..}}
 * without parking an operation.</p>
 */
public final class TermuxNfcCommands {

    private TermuxNfcCommands() {
    }

    /** The NFC domain commands in documentation order. */
    public static List<TermuxCommand> commands() {
        return Collections.unmodifiableList(Arrays.asList(
                nfc()));
    }

    private static TermuxCommand nfc() {
        return new TermuxCommand(
                "termux-nfc",
                String.join("\n",
                        "import json",
                        "",
                        "USAGE = ('Usage: termux-nfc [-r [short|full]] [-w] [-t TEXT|SECONDS]'",
                        "         + '\\n read/write data from/to NDEF tag'",
                        "         + '\\n   -r, read tag'",
                        "         + '\\n     short, read short information from tag'",
                        "         + '\\n     full,  read full information from tag'",
                        "         + '\\n   -w, write information on tag'",
                        "         + '\\n   -t, text for tag (with -w) or the read wait'",
                        "         + '\\n       in seconds (with -r)')",
                        "",
                        "DEFAULT_TIMEOUT_S = 60",
                        "",
                        "",
                        "def emit_presence(reading):",
                        "    # Upstream's mode-less answer; nfcActive only when present.",
                        "    result = {'nfcPresent': reading.get('nfc_present') is True}",
                        "    if 'nfc_enabled' in reading:",
                        "        result['nfcActive'] = reading.get('nfc_enabled') is True",
                        "    print(json.dumps(result, separators=(',', ':')))",
                        "    return 0",
                        "",
                        "",
                        "def unwrap(error):",
                        "    # The bridge's typed 'not an NDEF tag' answer is upstream's",
                        "    # Wrong-Technology result object, printed as data.",
                        "    if str(error).split(':', 1)[0].strip() == 'nfc-tag-not-ndef':",
                        "        print('{\"error\":\"Wrong Technology\",\"description\":'",
                        "              + '\"termux API support only NDEF Tag\"}')",
                        "        return 0",
                        "    raise error",
                        "",
                        "",
                        "def main(argv):",
                        "    if not argv or '-h' in argv:",
                        "        print(USAGE)",
                        "        return 0",
                        "    read = False",
                        "    write = False",
                        "    mode = 'short'",
                        "    t_value = None",
                        "    index = 0",
                        "    while index < len(argv):",
                        "        flag = argv[index]",
                        "        if flag == '-r':",
                        "            if (index + 1 >= len(argv)",
                        "                    or argv[index + 1] not in ('short', 'full')):",
                        "                raise tc.CliError(USAGE)",
                        "            read = True",
                        "            mode = argv[index + 1]",
                        "            index += 2",
                        "        elif flag == '-w':",
                        "            write = True",
                        "            index += 1",
                        "        elif flag == '-t':",
                        "            if index + 1 >= len(argv):",
                        "                raise tc.CliError(USAGE)",
                        "            t_value = argv[index + 1]",
                        "            index += 2",
                        "        else:",
                        "            raise tc.CliError(USAGE)",
                        "    if read and write:",
                        "        raise tc.CliError('Error: Incompatible parameters!'",
                        "                          + ' \"-r\" and \"-w\"')",
                        "    if read:",
                        "        timeout_s = DEFAULT_TIMEOUT_S",
                        "        if t_value is not None:",
                        "            try:",
                        "                timeout_s = int(t_value, 10)",
                        "            except ValueError:",
                        "                raise tc.CliError('termux-nfc: -t under -r must be'",
                        "                                  + ' a timeout in seconds')",
                        "            if timeout_s < 5 or timeout_s > 300:",
                        "                raise tc.CliError('termux-nfc: timeout must be 5..300 s')",
                        "        params = {'mode': mode, 'timeout_s': timeout_s}",
                        "        try:",
                        "            reading = tc.bridge_call('nfc.read', params,",
                        "                                     timeout=timeout_s + 30.0)",
                        "        except tc.BridgeError as error:",
                        "            return unwrap(error)",
                        "        tag_json = reading.get('tag_json')",
                        "        if tag_json:",
                        "            print(tag_json)",
                        "            return 0",
                        "        return emit_presence(reading)",
                        "    if write:",
                        "        if t_value is None:",
                        "            raise tc.CliError('termux-nfc: -w requires -t <text>')",
                        "        try:",
                        "            reading = tc.bridge_call('nfc.write',",
                        "                                     {'text': t_value},",
                        "                                     timeout=DEFAULT_TIMEOUT_S + 30.0)",
                        "        except tc.BridgeError as error:",
                        "            return unwrap(error)",
                        "        if reading.get('nfc_enabled') is False:",
                        "            return emit_presence(reading)",
                        "        # Upstream prints nothing on a successful write.",
                        "        return 0",
                        "    reading = tc.bridge_call('nfc.read', {'mode': 'probe'})",
                        "    return emit_presence(reading)",
                        ""),
                Arrays.asList(
                        "- `termux-nfc -r [short|full] [-t seconds]` - parks a",
                        "  foreground reader-mode wait (default 60 s, bounded",
                        "  5..300) and prints the tag JSON: `{\"Record\":..}`",
                        "  for `short`, `id`/`typeTag`/`maxSize`/`techList`/",
                        "  `record` for `full`. `nfc-timeout` means no tag",
                        "  arrived in the window.",
                        "- `termux-nfc -w -t TEXT` - writes one UTF-8 NDEF",
                        "  text record (`en`); silent on success like",
                        "  upstream.",
                        "- `termux-nfc` with no mode flag prints the",
                        "  presence answer `{\"nfcPresent\":..,\"nfcActive\":..}`",
                        "  without waiting for a tag. A device without NFC",
                        "  answers `nfc-unavailable` on -r/-w; a non-NDEF",
                        "  tag prints upstream's Wrong-Technology object."));
    }
}
