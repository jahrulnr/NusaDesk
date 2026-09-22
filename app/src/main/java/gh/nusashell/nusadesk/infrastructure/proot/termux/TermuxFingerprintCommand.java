package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The biometric domain Termux-compat command (wave 2, last upstream
 * command): {@code termux-fingerprint}.
 *
 * <p>The body is a thin flag-to-method mapping onto
 * {@code FingerprintModule} through the shared {@code termux_compat}
 * runtime. It speaks the upstream flag set ({@code -t -d -s -c -h}) plus
 * one NusaDesk extension, {@code --timeout ms}, for the bridge's bounded
 * {@code timeout_ms} (upstream uses a fixed 10 s sensor timeout). The
 * upstream {@code {"errors","failed_attempts","auth_result"}} JSON shape
 * is reassembled from the flat bridge fields, with {@code auth_result}
 * rendered as a plain boolean.</p>
 */
public final class TermuxFingerprintCommand {

    private TermuxFingerprintCommand() {
    }

    /** The biometric domain commands in documentation order. */
    public static List<TermuxCommand> commands() {
        return Collections.unmodifiableList(Arrays.asList(fingerprint()));
    }

    private static TermuxCommand fingerprint() {
        return new TermuxCommand(
                "termux-fingerprint",
                String.join("\n",
                        "import json",
                        "",
                        "USAGE = ('usage: termux-fingerprint [-t title] [-d description]'",
                        "         ' [-s subtitle] [-c cancel] [--timeout ms]')",
                        "MIN_TIMEOUT_MS = 1000",
                        "MAX_TIMEOUT_MS = 300000",
                        "",
                        "",
                        "def main(argv):",
                        "    if '-h' in argv:",
                        "        print(USAGE)",
                        "        return 0",
                        "    params = {}",
                        "    index = 0",
                        "    while index < len(argv):",
                        "        flag = argv[index]",
                        "        if flag in ('-t', '-d', '-s', '-c', '--timeout'):",
                        "            if index + 1 >= len(argv):",
                        "                raise tc.CliError(USAGE)",
                        "            value = argv[index + 1]",
                        "            if flag == '-t':",
                        "                params['title'] = value",
                        "            elif flag == '-d':",
                        "                params['description'] = value",
                        "            elif flag == '-s':",
                        "                params['subtitle'] = value",
                        "            elif flag == '-c':",
                        "                params['cancel'] = value",
                        "            else:",
                        "                try:",
                        "                    params['timeout_ms'] = int(value)",
                        "                except ValueError:",
                        "                    raise tc.CliError(",
                        "                        '--timeout expects milliseconds: ' + value)",
                        "                if (params['timeout_ms'] < MIN_TIMEOUT_MS",
                        "                        or params['timeout_ms'] > MAX_TIMEOUT_MS):",
                        "                    raise tc.CliError(",
                        "                        '--timeout must be between '",
                        "                        + str(MIN_TIMEOUT_MS) + ' and '",
                        "                        + str(MAX_TIMEOUT_MS))",
                        "            index += 2",
                        "        else:",
                        "            raise tc.CliError(USAGE)",
                        "    reading = tc.bridge_call('fingerprint.authenticate', params,",
                        "                             timeout=360.0)",
                        "    result = {'auth_result': bool(reading.get('auth_result', False)),",
                        "              'errors': json.loads(reading.get('errors_json', '[]')),",
                        "              'failed_attempts': int(reading.get('failed_attempts', 0))}",
                        "    tc.print_json(result)",
                        "    return 0",
                        ""),
                Arrays.asList(
                        "- `termux-fingerprint [-t TITLE] [-d DESCRIPTION]`",
                        "  `[-s SUBTITLE] [-c CANCEL] [--timeout MS]` - shows the",
                        "  system biometric prompt on the phone and prints",
                        "  `{\"auth_result\":bool,\"errors\":[..],",
                        "  `\"failed_attempts\":n}`; `auth_result` is a plain",
                        "  boolean where upstream prints the `AUTH_RESULT_*`",
                        "  string. `errors` carries `ERROR_*` names",
                        "  (`ERROR_CANCELED`, `ERROR_LOCKOUT`, `ERROR_TIMEOUT`,",
                        "  ...). No fingerprint sensor answers the typed",
                        "  `fingerprint-unavailable` (exit 1); a sensor with no",
                        "  enrolled fingerprints answers `auth_result:false`",
                        "  with `ERROR_NO_ENROLLED_FINGERPRINTS`, like upstream.",
                        "  `--timeout` is a NusaDesk extension for the prompt's",
                        "  own bound (upstream is a fixed 10 s): default 60000",
                        "  ms, accepted range 1000-300000"));
    }
}
