package gh.nusashell.nusadesk.infrastructure.proot;

/**
 * Guest-side CLIs for the NusaDesk-native capability domains that have no
 * upstream Termux:API counterpart (ADR-0051, ADR-0052).
 *
 * <p>This writer installs five scripts into {@code /usr/local/bin}, each a
 * thin flag-to-method mapping over the authenticated loopback bridge, in the
 * shape of {@link GuestBtCliWriter}:</p>
 *
 * <ul>
 *   <li>{@code nusadesk-wifi} — {@code hotspot start|stop|status},
 *       {@code suggest add|remove|list} and {@code lock acquire|release}
 *       onto {@code wifi.hotspot.*}, {@code wifi.suggest.*} and
 *       {@code wifi.lock.*};</li>
 *   <li>{@code nusadesk-pkg} — {@code list|info|launch} onto
 *       {@code packages.*};</li>
 *   <li>{@code nusadesk-usage} — {@code query|events|standby} onto
 *       {@code usage.*};</li>
 *   <li>{@code nusadesk-overlay} — {@code show|update|status|hide} onto
 *       {@code overlay.*};</li>
 *   <li>{@code nusadesk-loc} — {@code start|poll|stop} onto
 *       {@code location.background.*}.</li>
 * </ul>
 *
 * <p>Every script speaks the bridge protocol only through the shared
 * {@code termux_compat} runtime module (lazy import): none reads the
 * session token itself, none runs a shell or a subprocess, and none guesses
 * a state — a missing module, a dead socket, or a malformed answer is an
 * honest typed exit 5, not a fabricated result. Exit codes: {@code 0}
 * result printed, {@code 1} typed bridge error, {@code 2} usage,
 * {@code 5} bridge runtime absent, unreachable, or malformed. Per-script
 * {@code NUSADESK_<DOMAIN>_ENV_FILE} and {@code NUSADESK_<DOMAIN>_MODULE_DIR}
 * overrides relocate the session paths so hermetic tests can run the real
 * transport against a fake loopback bridge. Like every generated guest
 * file the scripts are rewritten by
 * {@link GuestAwarenessReadmeWriter#ensure} on session start, so an app
 * update replaces a stale copy.</p>
 */
public final class GuestNativeCliWriter {

    /** Guest-relative path of the wifi CLI, executable on PATH. */
    public static final String GUEST_WIFI_CLI_RELATIVE_PATH =
            "usr/local/bin/nusadesk-wifi";
    /** Wifi CLI file name inside the fixed bin parent. */
    public static final String WIFI_CLI_FILE_NAME = "nusadesk-wifi";
    /** Guest-relative path of the packages CLI, executable on PATH. */
    public static final String GUEST_PKG_CLI_RELATIVE_PATH =
            "usr/local/bin/nusadesk-pkg";
    /** Packages CLI file name inside the fixed bin parent. */
    public static final String PKG_CLI_FILE_NAME = "nusadesk-pkg";
    /** Guest-relative path of the usage-stats CLI, executable on PATH. */
    public static final String GUEST_USAGE_CLI_RELATIVE_PATH =
            "usr/local/bin/nusadesk-usage";
    /** Usage-stats CLI file name inside the fixed bin parent. */
    public static final String USAGE_CLI_FILE_NAME = "nusadesk-usage";
    /** Guest-relative path of the overlay CLI, executable on PATH. */
    public static final String GUEST_OVERLAY_CLI_RELATIVE_PATH =
            "usr/local/bin/nusadesk-overlay";
    /** Overlay CLI file name inside the fixed bin parent. */
    public static final String OVERLAY_CLI_FILE_NAME = "nusadesk-overlay";
    /** Guest-relative path of the background-location CLI, executable on PATH. */
    public static final String GUEST_LOC_CLI_RELATIVE_PATH =
            "usr/local/bin/nusadesk-loc";
    /** Background-location CLI file name inside the fixed bin parent. */
    public static final String LOC_CLI_FILE_NAME = "nusadesk-loc";

    /**
     * The shared Python helpers identical in all five scripts: argument
     * splitting (with the {@code bare} set for valueless flags like
     * {@code --hidden}), flag validation, bounded integer and printable-text
     * parsing, the envelope-stripping JSON emit, the single-call action
     * factory, and the lazy {@code termux_compat} runtime import.
     */
    private static final String HELPERS_BLOCK = String.join("\n",
            "",
            "",
            "class UsageError(Exception):",
            "    pass",
            "",
            "",
            "class ProtocolError(Exception):",
            "    pass",
            "",
            "",
            "def split_args(rest, bare=()):",
            "    positionals = []",
            "    flags = {}",
            "    index = 0",
            "    while index < len(rest):",
            "        arg = rest[index]",
            "        if arg.startswith('--'):",
            "            if arg in flags:",
            "                raise UsageError(USAGE)",
            "            if arg in bare:",
            "                flags[arg] = True",
            "                index += 1",
            "            else:",
            "                if index + 1 >= len(rest):",
            "                    raise UsageError(USAGE)",
            "                flags[arg] = rest[index + 1]",
            "                index += 2",
            "        else:",
            "            positionals.append(arg)",
            "            index += 1",
            "    return positionals, flags",
            "",
            "",
            "def require_flags(flags, allowed):",
            "    if any(flag not in allowed for flag in flags):",
            "        raise UsageError(USAGE)",
            "",
            "",
            "def empty(pos, flags):",
            "    if pos or flags:",
            "        raise UsageError(USAGE)",
            "",
            "",
            "def parse_int(text, minimum, maximum, name):",
            "    digits = text[1:] if text.startswith('-') else text",
            "    if not text.isascii() or not digits.isdigit():",
            "        raise UsageError('bad ' + name + ': ' + text)",
            "    value = int(text, 10)",
            "    if value < minimum or value > maximum:",
            "        raise UsageError(name + ' must be ' + str(minimum)",
            "                         + '..' + str(maximum))",
            "    return value",
            "",
            "",
            "def parse_text(text, maximum, name):",
            "    if not text or len(text) > maximum:",
            "        raise UsageError('bad ' + name + ' length')",
            "    if any(ord(ch) < 32 or ord(ch) == 127 for ch in text):",
            "        raise UsageError('bad ' + name + ' characters')",
            "    return text",
            "",
            "",
            "def emit_fields(response):",
            "    payload = {key: response[key] for key in response",
            "               if key not in ('v', 'id', 'ok')}",
            "    print(json.dumps(payload, separators=(',', ':'),",
            "                     ensure_ascii=True))",
            "    return 0",
            "",
            "",
            "def call_action(method, params=None, timeout=CALL_TIMEOUT_SECONDS):",
            "    def action(tc):",
            "        return emit_fields(tc.bridge_call(method, params, timeout))",
            "    return action",
            "",
            "",
            "def runtime():",
            "    # The shared Termux-compat module owns the bridge transport",
            "    # (session env, token, the socket protocol). The import is lazy",
            "    # so --help and usage errors still work where the layer is",
            "    # absent; the bridge verbs fail typed when it is missing.",
            "    if MODULE_DIR not in sys.path:",
            "        sys.path.insert(0, MODULE_DIR)",
            "    try:",
            "        import termux_compat",
            "    except ImportError:",
            "        return None",
            "    termux_compat.ENV_FILE = ENV_FILE",
            "    return termux_compat");

    /**
     * The shared entry point identical in all five scripts: {@code --help}
     * and the usage-error path never touch the bridge, the missing-module
     * and unreachable paths are honest typed exit 5s that name the domain
     * through {@code STATE_UNKNOWN}, a typed bridge error is exit 1, and a
     * malformed response is exit 5.
     */
    private static final String MAIN_BLOCK = String.join("\n",
            "",
            "",
            "def main(argv):",
            "    if len(argv) == 1 and argv[0] in ('-h', '--help'):",
            "        print(USAGE)",
            "        return 0",
            "    try:",
            "        if not argv:",
            "            raise UsageError(USAGE)",
            "        action = parse(argv)",
            "    except UsageError as error:",
            "        print(PROG + ': ' + str(error), file=sys.stderr)",
            "        return 2",
            "    tc = runtime()",
            "    if tc is None:",
            "        print(PROG + ': ' + MODULE_DIR + '/termux_compat.py: the'",
            "              + ' shared bridge runtime is not installed - '",
            "              + STATE_UNKNOWN, file=sys.stderr)",
            "        return 5",
            "    try:",
            "        return action(tc)",
            "    except tc.BridgeError as error:",
            "        print(PROG + ': ' + str(error), file=sys.stderr)",
            "        return 1",
            "    except (tc.CliError, OSError) as error:",
            "        print(PROG + ': the capability bridge is not reachable'",
            "              + ' (' + str(error) + ') - ' + STATE_UNKNOWN + ';'",
            "              + ' the bridge lives with the NusaDesk session',",
            "              file=sys.stderr)",
            "        return 5",
            "    except (ProtocolError, ValueError, TypeError, KeyError):",
            "        print(PROG + ': malformed bridge response',",
            "              file=sys.stderr)",
            "        return 5",
            "",
            "",
            "if __name__ == '__main__':",
            "    sys.exit(main(sys.argv[1:]))",
            "");

    private GuestNativeCliWriter() {
    }

    /**
     * The exact executable Python 3 script installed at
     * {@link #GUEST_WIFI_CLI_RELATIVE_PATH}. Public so tests and
     * documentation tooling can pin the generated text.
     *
     * @param appVersion app version stamped into the script header
     */
    public static String wifiScriptContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# NusaDesk wifi CLI - generated by the NusaDesk Android app.",
                "# App version: " + version,
                "# Fixed session env: " + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH,
                "# Shared runtime module: " + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "/termux_compat.py",
                "#",
                "# Guest half of the NusaDesk wifi-extras capability",
                "# (ADR-0051): the local-only hotspot (no internet backhaul),",
                "# advisory network suggestions, and the high-performance",
                "# wifi lock. Every verb maps to one fixed wifi.* bridge",
                "# method answered by the Android host over the",
                "# authenticated loopback bridge - there is no method",
                "# passthrough, no shell, and the session token never leaves",
                "# the shared runtime module. The wifi radio toggle itself",
                "# stays absent by design (platform rule since Android 10).",
                "#",
                "# Verbs and methods:",
                "#   hotspot start                      -> wifi.hotspot.start",
                "#   hotspot stop                       -> wifi.hotspot.stop",
                "#   hotspot status                     -> wifi.hotspot.status",
                "#   suggest add --ssid S [--passphrase P] [--priority N]",
                "#     [--hidden]                     -> wifi.suggest.add",
                "#   suggest remove --ssid S            -> wifi.suggest.remove",
                "#   suggest list                       -> wifi.suggest.list",
                "#   lock acquire [--tag T]             -> wifi.lock.acquire",
                "#   lock release                       -> wifi.lock.release",
                "#",
                "# Output: one compact JSON object on stdout - the bridge",
                "# payload fields as answered. Exit codes: 0 ok, 1 typed",
                "# bridge error, 2 usage, 5 bridge runtime absent,",
                "# unreachable, or malformed.",
                "",
                "import json",
                "import os",
                "import sys",
                "",
                "PROG = 'nusadesk-wifi'",
                "# The env-file and module-dir overrides exist for hermetic",
                "# tests only; the device defaults are the real session paths.",
                "ENV_FILE = (os.environ.get('NUSADESK_WIFI_ENV_FILE')",
                "            or '" + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH + "')",
                "MODULE_DIR = (os.environ.get('NUSADESK_WIFI_MODULE_DIR')",
                "              or '" + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "')",
                "CALL_TIMEOUT_SECONDS = 15.0",
                "# wifi.hotspot.start blocks host-side up to its own 15 s",
                "# hotspot bound waiting on the platform callback, so that",
                "# one call needs a wider socket window than a plain query.",
                "LONG_TIMEOUT_SECONDS = 45.0",
                "STATE_UNKNOWN = 'wifi state unknown'",
                "MAX_SSID_CHARS = 64",
                "MAX_PASSPHRASE_CHARS = 128",
                "MAX_TAG_CHARS = 64",
                "MAX_PRIORITY = 1000",
                "USAGE = ('usage: nusadesk-wifi hotspot start | hotspot stop'",
                "         + ' | hotspot status'",
                "         + ' | suggest add --ssid S [--passphrase P]'",
                "         + ' [--priority N] [--hidden]'",
                "         + ' | suggest remove --ssid S | suggest list'",
                "         + ' | lock acquire [--tag T] | lock release')",
                "")
                + HELPERS_BLOCK
                + String.join("\n",
                "",
                "",
                "",
                "def parse_hotspot(pos, flags):",
                "    if len(pos) != 1 or flags:",
                "        raise UsageError(USAGE)",
                "    if pos[0] == 'start':",
                "        return call_action('wifi.hotspot.start', None,",
                "                           LONG_TIMEOUT_SECONDS)",
                "    if pos[0] == 'stop':",
                "        return call_action('wifi.hotspot.stop')",
                "    if pos[0] == 'status':",
                "        return call_action('wifi.hotspot.status')",
                "    raise UsageError(USAGE)",
                "",
                "",
                "def parse_suggest(pos, flags):",
                "    if not pos:",
                "        raise UsageError(USAGE)",
                "    sub = pos[0]",
                "    if sub == 'add':",
                "        if len(pos) != 1 or '--ssid' not in flags:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--ssid', '--passphrase',",
                "                              '--priority', '--hidden'))",
                "        params = {'ssid': parse_text(flags['--ssid'],",
                "                                     MAX_SSID_CHARS, 'ssid')}",
                "        if '--passphrase' in flags:",
                "            params['passphrase'] = parse_text(",
                "                flags['--passphrase'], MAX_PASSPHRASE_CHARS,",
                "                'passphrase')",
                "        if '--priority' in flags:",
                "            params['priority'] = parse_int(flags['--priority'],",
                "                                           0, MAX_PRIORITY, 'priority')",
                "        if '--hidden' in flags:",
                "            params['is_hidden'] = True",
                "        return call_action('wifi.suggest.add', params)",
                "    if sub == 'remove':",
                "        if len(pos) != 1 or '--ssid' not in flags:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--ssid',))",
                "        return call_action('wifi.suggest.remove',",
                "                           {'ssid': parse_text(flags['--ssid'],",
                "                                               MAX_SSID_CHARS,",
                "                                               'ssid')})",
                "    if sub == 'list':",
                "        if len(pos) != 1 or flags:",
                "            raise UsageError(USAGE)",
                "        return call_action('wifi.suggest.list')",
                "    raise UsageError(USAGE)",
                "",
                "",
                "def parse_lock(pos, flags):",
                "    if not pos:",
                "        raise UsageError(USAGE)",
                "    if pos[0] == 'acquire':",
                "        if len(pos) != 1:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--tag',))",
                "        params = None",
                "        if '--tag' in flags:",
                "            params = {'tag': parse_text(flags['--tag'],",
                "                                        MAX_TAG_CHARS, 'tag')}",
                "        return call_action('wifi.lock.acquire', params)",
                "    if pos[0] == 'release':",
                "        if len(pos) != 1 or flags:",
                "            raise UsageError(USAGE)",
                "        return call_action('wifi.lock.release')",
                "    raise UsageError(USAGE)",
                "",
                "",
                "def parse(argv):",
                "    pos, flags = split_args(argv[1:], bare=('--hidden',))",
                "    verb = argv[0]",
                "    if verb == 'hotspot':",
                "        return parse_hotspot(pos, flags)",
                "    if verb == 'suggest':",
                "        return parse_suggest(pos, flags)",
                "    if verb == 'lock':",
                "        return parse_lock(pos, flags)",
                "    raise UsageError(USAGE)",
                "")
                + MAIN_BLOCK;
    }

    /**
     * The exact executable Python 3 script installed at
     * {@link #GUEST_PKG_CLI_RELATIVE_PATH}: the guest half of the
     * {@code packages.*} capability (ADR-0052) backed by the manifest's
     * {@code QUERY_ALL_PACKAGES} declaration.
     *
     * @param appVersion app version stamped into the script header
     */
    public static String pkgScriptContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# NusaDesk packages CLI - generated by the NusaDesk Android",
                "# app. App version: " + version,
                "# Fixed session env: " + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH,
                "# Shared runtime module: " + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "/termux_compat.py",
                "#",
                "# Guest half of the NusaDesk packages capability (ADR-0052):",
                "# enumerate installed packages, inspect one, and launch one.",
                "# Every verb maps to one fixed packages.* bridge method",
                "# answered by the Android host over the authenticated",
                "# loopback bridge - there is no method passthrough, no",
                "# shell, and the session token never leaves the shared",
                "# runtime module. packages.launch can fail typed",
                "# (packages-launch-blocked) when Android 10+ refuses a",
                "# background activity start; that is the platform's answer,",
                "# not a guess.",
                "#",
                "# Verbs and methods:",
                "#   list [--filter F] [--limit N] [--no-system]",
                "#                                      -> packages.list",
                "#   info <package>                     -> packages.info",
                "#   launch <package> [--activity A]    -> packages.launch",
                "#",
                "# Output: one compact JSON object on stdout - the bridge",
                "# payload fields as answered. Exit codes: 0 ok, 1 typed",
                "# bridge error, 2 usage, 5 bridge runtime absent,",
                "# unreachable, or malformed.",
                "",
                "import json",
                "import os",
                "import sys",
                "",
                "PROG = 'nusadesk-pkg'",
                "# The env-file and module-dir overrides exist for hermetic",
                "# tests only; the device defaults are the real session paths.",
                "ENV_FILE = (os.environ.get('NUSADESK_PKG_ENV_FILE')",
                "            or '" + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH + "')",
                "MODULE_DIR = (os.environ.get('NUSADESK_PKG_MODULE_DIR')",
                "              or '" + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "')",
                "CALL_TIMEOUT_SECONDS = 15.0",
                "STATE_UNKNOWN = 'package state unknown'",
                "MAX_FILTER_CHARS = 64",
                "MAX_PACKAGE_CHARS = 128",
                "MAX_ACTIVITY_CHARS = 256",
                "MAX_LIMIT = 100",
                "USAGE = ('usage: nusadesk-pkg list [--filter F] [--limit N]'",
                "         + ' [--no-system] | info <package>'",
                "         + ' | launch <package> [--activity A]')",
                "")
                + HELPERS_BLOCK
                + String.join("\n",
                "",
                "",
                "",
                "def parse(argv):",
                "    pos, flags = split_args(argv[1:], bare=('--no-system',))",
                "    verb = argv[0]",
                "    if verb == 'list':",
                "        if pos:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--filter', '--limit',",
                "                              '--no-system'))",
                "        params = {}",
                "        if '--filter' in flags:",
                "            params['filter'] = parse_text(flags['--filter'],",
                "                                          MAX_FILTER_CHARS, 'filter')",
                "        if '--limit' in flags:",
                "            params['limit'] = parse_int(flags['--limit'], 1,",
                "                                        MAX_LIMIT, 'limit')",
                "        if '--no-system' in flags:",
                "            params['include_system'] = False",
                "        return call_action('packages.list', params or None)",
                "    if verb == 'info':",
                "        if len(pos) != 1 or flags:",
                "            raise UsageError(USAGE)",
                "        return call_action('packages.info',",
                "                           {'package': parse_text(",
                "                               pos[0], MAX_PACKAGE_CHARS,",
                "                               'package')})",
                "    if verb == 'launch':",
                "        if len(pos) != 1:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--activity',))",
                "        params = {'package': parse_text(pos[0],",
                "                                        MAX_PACKAGE_CHARS,",
                "                                        'package')}",
                "        if '--activity' in flags:",
                "            params['activity'] = parse_text(",
                "                flags['--activity'], MAX_ACTIVITY_CHARS,",
                "                'activity')",
                "        return call_action('packages.launch', params)",
                "    raise UsageError(USAGE)",
                "")
                + MAIN_BLOCK;
    }

    /**
     * The exact executable Python 3 script installed at
     * {@link #GUEST_USAGE_CLI_RELATIVE_PATH}: the guest half of the
     * {@code usage.*} capability (ADR-0052) behind the usage-access
     * special grant.
     *
     * @param appVersion app version stamped into the script header
     */
    public static String usageScriptContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# NusaDesk usage-stats CLI - generated by the NusaDesk",
                "# Android app. App version: " + version,
                "# Fixed session env: " + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH,
                "# Shared runtime module: " + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "/termux_compat.py",
                "#",
                "# Guest half of the NusaDesk usage-stats capability",
                "# (ADR-0052): bounded daily aggregates, a bounded event",
                "# window, and the app-standby bucket - all behind the",
                "# usage-access special grant, which a missing grant reports",
                "# as the typed usage-permission-required error. Every verb",
                "# maps to one fixed usage.* bridge method over the",
                "# authenticated loopback bridge - there is no method",
                "# passthrough, no shell, and the session token never leaves",
                "# the shared runtime module.",
                "#",
                "# Verbs and methods:",
                "#   query [--days N] [--limit N]       -> usage.query",
                "#   events [--hours N] [--limit N]     -> usage.events",
                "#   standby <package>                  -> usage.standby",
                "#",
                "# Output: one compact JSON object on stdout - the bridge",
                "# payload fields as answered. Exit codes: 0 ok, 1 typed",
                "# bridge error, 2 usage, 5 bridge runtime absent,",
                "# unreachable, or malformed.",
                "",
                "import json",
                "import os",
                "import sys",
                "",
                "PROG = 'nusadesk-usage'",
                "# The env-file and module-dir overrides exist for hermetic",
                "# tests only; the device defaults are the real session paths.",
                "ENV_FILE = (os.environ.get('NUSADESK_USAGE_ENV_FILE')",
                "            or '" + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH + "')",
                "MODULE_DIR = (os.environ.get('NUSADESK_USAGE_MODULE_DIR')",
                "              or '" + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "')",
                "CALL_TIMEOUT_SECONDS = 15.0",
                "STATE_UNKNOWN = 'usage state unknown'",
                "MAX_WINDOW_DAYS = 7",
                "MAX_QUERY_ROWS = 50",
                "MAX_EVENT_HOURS = 24",
                "MAX_EVENT_ROWS = 100",
                "MAX_PACKAGE_CHARS = 128",
                "USAGE = ('usage: nusadesk-usage query [--days N] [--limit N]'",
                "         + ' | events [--hours N] [--limit N]'",
                "         + ' | standby <package>')",
                "")
                + HELPERS_BLOCK
                + String.join("\n",
                "",
                "",
                "",
                "def parse(argv):",
                "    pos, flags = split_args(argv[1:])",
                "    verb = argv[0]",
                "    if verb == 'query':",
                "        if pos:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--days', '--limit'))",
                "        params = {}",
                "        if '--days' in flags:",
                "            params['days'] = parse_int(flags['--days'], 1,",
                "                                       MAX_WINDOW_DAYS, 'days')",
                "        if '--limit' in flags:",
                "            params['limit'] = parse_int(flags['--limit'], 1,",
                "                                        MAX_QUERY_ROWS, 'limit')",
                "        return call_action('usage.query', params or None)",
                "    if verb == 'events':",
                "        if pos:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--hours', '--limit'))",
                "        params = {}",
                "        if '--hours' in flags:",
                "            params['hours'] = parse_int(flags['--hours'], 1,",
                "                                        MAX_EVENT_HOURS, 'hours')",
                "        if '--limit' in flags:",
                "            params['limit'] = parse_int(flags['--limit'], 1,",
                "                                        MAX_EVENT_ROWS, 'limit')",
                "        return call_action('usage.events', params or None)",
                "    if verb == 'standby':",
                "        if len(pos) != 1 or flags:",
                "            raise UsageError(USAGE)",
                "        return call_action('usage.standby',",
                "                           {'package': parse_text(",
                "                               pos[0], MAX_PACKAGE_CHARS,",
                "                               'package')})",
                "    raise UsageError(USAGE)",
                "")
                + MAIN_BLOCK;
    }

    /**
     * The exact executable Python 3 script installed at
     * {@link #GUEST_OVERLAY_CLI_RELATIVE_PATH}: the guest half of the
     * {@code overlay.*} capability (ADR-0052) behind the
     * draw-over-apps special grant.
     *
     * @param appVersion app version stamped into the script header
     */
    public static String overlayScriptContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# NusaDesk overlay CLI - generated by the NusaDesk Android",
                "# app. App version: " + version,
                "# Fixed session env: " + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH,
                "# Shared runtime module: " + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "/termux_compat.py",
                "#",
                "# Guest half of the NusaDesk overlay capability (ADR-0052):",
                "# one small non-interactive text plate over other apps,",
                "# behind the draw-over-apps (SYSTEM_ALERT_WINDOW) special",
                "# grant. Every verb maps to one fixed overlay.* bridge",
                "# method over the authenticated loopback bridge - there is",
                "# no method passthrough, no shell, and the session token",
                "# never leaves the shared runtime module.",
                "#",
                "# Verbs and methods:",
                "#   show --text T [--x N] [--y N] [--size N] [--color HEX]",
                "#                                      -> overlay.show",
                "#   update [--text T] [--x N] [--y N] [--size N]",
                "#     [--color HEX]                    -> overlay.update",
                "#   status                             -> overlay.status",
                "#   hide                               -> overlay.hide",
                "#",
                "# --x/--y are raw pixel offsets (-5000..5000), --size is sp",
                "# (10..72), --color is a #RRGGBB or #AARRGGBB literal.",
                "# update requires at least one flag.",
                "#",
                "# Output: one compact JSON object on stdout - the bridge",
                "# payload fields as answered. Exit codes: 0 ok, 1 typed",
                "# bridge error, 2 usage, 5 bridge runtime absent,",
                "# unreachable, or malformed.",
                "",
                "import json",
                "import os",
                "import sys",
                "",
                "PROG = 'nusadesk-overlay'",
                "# The env-file and module-dir overrides exist for hermetic",
                "# tests only; the device defaults are the real session paths.",
                "ENV_FILE = (os.environ.get('NUSADESK_OVERLAY_ENV_FILE')",
                "            or '" + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH + "')",
                "MODULE_DIR = (os.environ.get('NUSADESK_OVERLAY_MODULE_DIR')",
                "              or '" + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "')",
                "CALL_TIMEOUT_SECONDS = 15.0",
                "STATE_UNKNOWN = 'overlay state unknown'",
                "MAX_TEXT_CHARS = 256",
                "COORD_MIN = -5000",
                "COORD_MAX = 5000",
                "SIZE_MIN = 10",
                "SIZE_MAX = 72",
                "HEXDIGITS = '0123456789abcdefABCDEF'",
                "USAGE = ('usage: nusadesk-overlay show --text T [--x N]'",
                "         + ' [--y N] [--size N] [--color HEX]'",
                "         + ' | update [--text T] [--x N] [--y N] [--size N]'",
                "         + ' [--color HEX] | status | hide')",
                "")
                + HELPERS_BLOCK
                + String.join("\n",
                "",
                "",
                "",
                "def is_hex(text):",
                "    return bool(text) and all(ch in HEXDIGITS for ch in text)",
                "",
                "",
                "def parse_color(text):",
                "    if (len(text) not in (7, 9) or not text.startswith('#')",
                "            or not is_hex(text[1:])):",
                "        raise UsageError('bad color: ' + text)",
                "    return text",
                "",
                "",
                "def overlay_params(flags, show):",
                "    require_flags(flags, ('--text', '--x', '--y', '--size',",
                "                          '--color'))",
                "    if show and '--text' not in flags:",
                "        raise UsageError(USAGE)",
                "    if not flags:",
                "        raise UsageError(USAGE)",
                "    params = {}",
                "    if '--text' in flags:",
                "        params['text'] = parse_text(flags['--text'],",
                "                                    MAX_TEXT_CHARS, 'text')",
                "    if '--x' in flags:",
                "        params['x'] = parse_int(flags['--x'], COORD_MIN,",
                "                                COORD_MAX, 'x')",
                "    if '--y' in flags:",
                "        params['y'] = parse_int(flags['--y'], COORD_MIN,",
                "                                COORD_MAX, 'y')",
                "    if '--size' in flags:",
                "        params['size'] = parse_int(flags['--size'], SIZE_MIN,",
                "                                   SIZE_MAX, 'size')",
                "    if '--color' in flags:",
                "        params['color'] = parse_color(flags['--color'])",
                "    return params",
                "",
                "",
                "def parse(argv):",
                "    pos, flags = split_args(argv[1:])",
                "    verb = argv[0]",
                "    if verb == 'show':",
                "        if pos:",
                "            raise UsageError(USAGE)",
                "        return call_action('overlay.show',",
                "                           overlay_params(flags, True))",
                "    if verb == 'update':",
                "        if pos:",
                "            raise UsageError(USAGE)",
                "        return call_action('overlay.update',",
                "                           overlay_params(flags, False))",
                "    if verb == 'status':",
                "        empty(pos, flags)",
                "        return call_action('overlay.status')",
                "    if verb == 'hide':",
                "        empty(pos, flags)",
                "        return call_action('overlay.hide')",
                "    raise UsageError(USAGE)",
                "")
                + MAIN_BLOCK;
    }

    /**
     * The exact executable Python 3 script installed at
     * {@link #GUEST_LOC_CLI_RELATIVE_PATH}: the guest half of the
     * {@code location.background.*} capability (ADR-0052), mirroring the
     * upstream {@code termux-location -r updates} contract behind the
     * "Allow all the time" location grant.
     *
     * @param appVersion app version stamped into the script header
     */
    public static String locScriptContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# NusaDesk background-location CLI - generated by the",
                "# NusaDesk Android app. App version: " + version,
                "# Fixed session env: " + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH,
                "# Shared runtime module: " + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "/termux_compat.py",
                "#",
                "# Guest half of the NusaDesk background-location capability",
                "# (ADR-0052): a continuous fix stream that only runs behind",
                "# the 'Allow all the time' location grant, owned host-side",
                "# by a foreground service with a visible notification.",
                "# start begins the stream, poll drains the bounded fix",
                "# buffer (it is a drain, not a watch), stop ends the",
                "# session. Every verb maps to one fixed",
                "# location.background.* bridge method over the",
                "# authenticated loopback bridge - there is no method",
                "# passthrough, no shell, and the session token never",
                "# leaves the shared runtime module.",
                "#",
                "# Verbs and methods:",
                "#   start [--provider gps|network|passive] [--interval-ms N]",
                "#     [--distance-m N]        -> location.background.start",
                "#   poll                    -> location.background.poll",
                "#   stop                    -> location.background.stop",
                "#",
                "# Output: one compact JSON object on stdout - the bridge",
                "# payload fields as answered (poll's fixes_json stays the",
                "# verbatim array string the bridge returned). Exit codes:",
                "# 0 ok, 1 typed bridge error, 2 usage, 5 bridge runtime",
                "# absent, unreachable, or malformed.",
                "",
                "import json",
                "import os",
                "import sys",
                "",
                "PROG = 'nusadesk-loc'",
                "# The env-file and module-dir overrides exist for hermetic",
                "# tests only; the device defaults are the real session paths.",
                "ENV_FILE = (os.environ.get('NUSADESK_LOC_ENV_FILE')",
                "            or '" + GuestAwarenessReadmeWriter.GUEST_ENV_FILE_PATH + "')",
                "MODULE_DIR = (os.environ.get('NUSADESK_LOC_MODULE_DIR')",
                "              or '" + GuestTermuxCompatWriter.GUEST_MODULE_DIR + "')",
                "CALL_TIMEOUT_SECONDS = 15.0",
                "STATE_UNKNOWN = 'location state unknown'",
                "INTERVAL_MIN_MS = 1000",
                "INTERVAL_MAX_MS = 60000",
                "DISTANCE_MIN_M = 0",
                "DISTANCE_MAX_M = 1000",
                "USAGE = ('usage: nusadesk-loc start'",
                "         + ' [--provider gps|network|passive]'",
                "         + ' [--interval-ms N] [--distance-m N] | poll | stop')",
                "")
                + HELPERS_BLOCK
                + String.join("\n",
                "",
                "",
                "",
                "def parse(argv):",
                "    pos, flags = split_args(argv[1:])",
                "    verb = argv[0]",
                "    if verb == 'start':",
                "        if pos:",
                "            raise UsageError(USAGE)",
                "        require_flags(flags, ('--provider', '--interval-ms',",
                "                              '--distance-m'))",
                "        params = {}",
                "        if '--provider' in flags:",
                "            provider = flags['--provider']",
                "            if provider not in ('gps', 'network', 'passive'):",
                "                raise UsageError('bad provider: ' + provider)",
                "            params['provider'] = provider",
                "        if '--interval-ms' in flags:",
                "            params['interval_ms'] = parse_int(",
                "                flags['--interval-ms'], INTERVAL_MIN_MS,",
                "                INTERVAL_MAX_MS, 'interval-ms')",
                "        if '--distance-m' in flags:",
                "            params['distance_m'] = parse_int(",
                "                flags['--distance-m'], DISTANCE_MIN_M,",
                "                DISTANCE_MAX_M, 'distance-m')",
                "        return call_action('location.background.start',",
                "                           params or None)",
                "    if verb == 'poll':",
                "        empty(pos, flags)",
                "        return call_action('location.background.poll')",
                "    if verb == 'stop':",
                "        empty(pos, flags)",
                "        return call_action('location.background.stop')",
                "    raise UsageError(USAGE)",
                "")
                + MAIN_BLOCK;
    }
}
