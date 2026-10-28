package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes the product-owned, app-managed awareness bundle in the guest root.
 *
 * <p>The bundle is written when the guest session starts:
 * <ul>
 *   <li>{@code /root/README.md} — concise product overview with relative
 *       links into {@code docs/};</li>
 *   <li>{@code /root/docs/README.md} and the focused topic docs
 *       ({@code bridge.md}, {@code media.md}, {@code environment.md},
 *       {@code battery-sensors-location.md}, {@code messaging-telephony.md},
 *       {@code calendar.md}, {@code desktop.md}) documenting the native
 *       GUI/GPU launch path and the live
 *       loopback services, including the camera + microphone contract (live
 *       RTSP stream plus the separate file-capture methods) and the bounded
 *       calendar read/write contract; the optional
 *       {@code termux-compat.md} topic doc ships with the Termux toolkit;</li>
 *   <li>{@code /usr/local/bin/nusadesk-android} — an executable Python 3 CLI
 *       that controls {@code media.start} / {@code media.status} /
 *       {@code media.stop}, the bounded calendar commands
 *       ({@code calendar list|add|update|delete}), and {@code bridge.info}
 *       over the authenticated loopback JSONL bridge;</li>
 *   <li>{@code /usr/local/bin/android-cli} — the native Android tool
 *       dispatcher ({@link GuestAndroidCliWriter}, ADR-0045): {@code sh},
 *       {@code getprop}, {@code toolbox} and {@code toybox} applets,
 *       {@code su}, and a scoped {@code exec} over the device binaries the
 *       host binds under {@code /system}, reporting its tier on every
 *       call;</li>
 *   <li>{@code /usr/local/lib/nusadesk/termux_compat.py} — the shared bridge
 *       transport module imported by {@code android-cli} and every
 *       {@code nusadesk-*} client, so it stays installed even when the
 *       optional {@code termux-*} command set is not
 *       ({@link GuestTermuxCompatWriter#ensureModule});</li>
 *   <li>{@code /root/.agents/} — the seeded agent bundle
 *       ({@link GuestAgentSeedWriter}): a durable {@code AGENTS.md} plus
 *       starter skills, headed by the Termux-API-vs-{@code android-cli}
 *       decision. Unlike the managed files above, seeded files are
 *       user-owned: each is written only while absent-or-unmodified, and a
 *       file the user deleted is never recreated.</li>
 * </ul>
 * Two tool bundles are opt-in and owned by {@link GuestOptionalTools}
 * instead: the USB pass-through set ({@code /usr/local/bin/nusadesk-usb},
 * {@code nusadesk-usbd}, the {@code adb} wrapper and
 * {@code /opt/nusadesk/libusb-shim.c}) and the {@code termux-*} command
 * clients with {@code docs/termux-compat.md}. Each is written only when the
 * user chose it — at setup, later from System, or by adoption of an
 * install predating the markers — and this ensure runs
 * {@link GuestOptionalTools#reconcile} on every session start so a chosen
 * set is refreshed in place and a pre-existing marked install is adopted
 * rather than deleted.
 *
 * <p>Doc bodies ship as bundled assets under {@code assets/guest-docs/} —
 * each asset path is its {@code root/}-relative guest path — and are copied
 * in on every session start with the running APK version substituted for
 * {@link #APP_VERSION_TOKEN}, so an app update replaces stale text. The
 * targets stay normal writable guest files; this class does not bind them
 * read-only or make them immutable. The {@code .agents} tree is the
 * deliberate exception to the managed-file rule: seeded once and left to
 * the user (ADR-0056).</p>
 *
 * <p>Only fixed, app-managed paths are ever written and the parents must be
 * real directories: a symlink in {@code root}, {@code root/docs}, or
 * {@code usr/local/bin} fails closed with an {@link IOException} and nothing
 * is written outside the rootfs. A user-supplied destination path is never
 * accepted.</p>
 */
public final class GuestAwarenessReadmeWriter {

    /** Guest-relative path owned by NusaDesk. */
    public static final String GUEST_RELATIVE_PATH = "root/README.md";
    /** Guest-relative docs directory owned by NusaDesk. */
    public static final String GUEST_DOCS_RELATIVE_PATH = "root/docs";
    /** Guest-relative bin directory that receives the CLI. */
    public static final String GUEST_BIN_RELATIVE_PATH = "usr/local/bin";
    /** Guest-relative CLI path, executable on PATH. */
    public static final String GUEST_CLI_RELATIVE_PATH = "usr/local/bin/nusadesk-android";
    /** CLI file name inside the fixed bin parent. */
    public static final String CLI_FILE_NAME = "nusadesk-android";
    /** Guest session configuration pinned by the bridge. */
    public static final String GUEST_ENV_FILE_PATH = "/run/nusadesk/android-bridge.env";

    private static final String TEMP_PREFIX = ".nusadesk-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String README_PERMISSIONS = "rw-r--r--";
    private static final String CLI_PERMISSIONS = "rwxr-xr-x";

    /**
     * Asset root holding the bundled guest files: every path below it is the
     * file's {@code root/}-relative guest path.
     */
    public static final String GUEST_DOCS_ASSET_ROOT = "guest-docs";
    /** Placeholder in bundled docs replaced with the running app version. */
    public static final String APP_VERSION_TOKEN = "%APP_VERSION%";

    /** Bundled doc assets this writer installs, relative to the asset root. */
    static final String[] DOC_ASSETS = {
            "README.md",
            "docs/README.md",
            "docs/bridge.md",
            "docs/media.md",
            "docs/battery-sensors-location.md",
            "docs/messaging-telephony.md",
            "docs/calendar.md",
            "docs/desktop.md",
            "docs/environment.md",
    };

    /**
     * Source of bundled guest file bodies — the app's {@code AssetManager}
     * in production, a plain directory in unit tests.
     */
    public interface GuestDocSource {
        /**
         * Read one bundled file.
         *
         * @param assetPath path relative to {@link #GUEST_DOCS_ASSET_ROOT}
         * @return the file's bytes, never {@code null}
         * @throws IOException when the file cannot be read
         */
        byte[] read(String assetPath) throws IOException;
    }

    /**
     * A {@link GuestDocSource} backed by the app's assets.
     *
     * @param assets the app's {@code AssetManager}
     * @return a source that opens {@code guest-docs/<path>}
     */
    public static GuestDocSource assetSource(
            final android.content.res.AssetManager assets) {
        if (assets == null) {
            throw new IllegalArgumentException("assets must not be null");
        }
        return new GuestDocSource() {
            @Override
            public byte[] read(String assetPath) throws IOException {
                try (InputStream in =
                        assets.open(GUEST_DOCS_ASSET_ROOT + "/" + assetPath)) {
                    return readAll(in);
                }
            }
        };
    }

    /**
     * A {@link GuestDocSource} backed by a plain directory — for unit tests.
     *
     * @param dir directory containing the bundled tree (e.g.
     *        {@code src/main/assets/guest-docs})
     * @return a source that reads {@code dir/<path>}
     */
    public static GuestDocSource docsFrom(final Path dir) {
        if (dir == null) {
            throw new IllegalArgumentException("dir must not be null");
        }
        return new GuestDocSource() {
            @Override
            public byte[] read(String assetPath) throws IOException {
                return Files.readAllBytes(dir.resolve(assetPath));
            }
        };
    }

    /**
     * Load one bundled doc body with the version token substituted.
     *
     * @param source bundled doc source
     * @param assetPath path relative to {@link #GUEST_DOCS_ASSET_ROOT}
     * @param version app version, validated here like every writer entry point
     * @return the file's bytes after {@link #APP_VERSION_TOKEN} substitution
     * @throws IOException when the asset cannot be read
     */
    public static byte[] loadDoc(GuestDocSource source, String assetPath, String version)
            throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        String safeVersion = requireVersionForWriter(version);
        return new String(source.read(assetPath), StandardCharsets.UTF_8)
                .replace(APP_VERSION_TOKEN, safeVersion)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Outcome of an idempotent ensure operation. */
    public enum Result {
        UNCHANGED,
        UPDATED
    }

    private GuestAwarenessReadmeWriter() {
    }

    /**
     * Ensure the whole awareness bundle (README, docs index and topic docs,
     * and the executable CLI) matches the current app version.
     *
     * <p>Doc bodies ship as bundled assets under {@code guest-docs/} (each
     * asset path is its {@code root/}-relative guest path) and are copied in
     * on every session start, substituting {@link #APP_VERSION_TOKEN} with the
     * running version.</p>
     *
     * @param activeRootfs validated active rootfs directory
     * @param appVersion APK version name, not user input
     * @param docSource source of the bundled guest docs
     * @return whether any file was updated or everything was already current
     * @throws IOException when a fixed path is unsafe or cannot be written
     */
    public static Result ensure(Path activeRootfs, String appVersion,
            GuestDocSource docSource) throws IOException {
        if (activeRootfs == null || docSource == null) {
            throw new IllegalArgumentException("activeRootfs/docSource must not be null");
        }
        String version = requireVersion(appVersion);
        Path root = prepareDirectory(activeRootfs, "root", "guest /root");
        prepareDirectory(root, "docs", "guest /root/docs");
        Path bin = prepareDirectory(activeRootfs, "usr/local/bin", "guest /usr/local/bin");

        boolean updated = false;
        for (String asset : DOC_ASSETS) {
            updated |= ensureFile(root.resolve(asset),
                    loadDoc(docSource, asset, version), README_PERMISSIONS);
        }
        updated |= ensureFile(bin.resolve(CLI_FILE_NAME),
                cliContent(version).getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestAndroidCliWriter.CLI_FILE_NAME),
                GuestAndroidCliWriter.scriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestBtCliWriter.CLI_FILE_NAME),
                GuestBtCliWriter.scriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestServeCliWriter.SERVE_CLI_FILE_NAME),
                GuestServeCliWriter.serveScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestServeCliWriter.NET_CLI_FILE_NAME),
                GuestServeCliWriter.netScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestNativeCliWriter.WIFI_CLI_FILE_NAME),
                GuestNativeCliWriter.wifiScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestNativeCliWriter.PKG_CLI_FILE_NAME),
                GuestNativeCliWriter.pkgScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestNativeCliWriter.USAGE_CLI_FILE_NAME),
                GuestNativeCliWriter.usageScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestNativeCliWriter.OVERLAY_CLI_FILE_NAME),
                GuestNativeCliWriter.overlayScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= ensureFile(bin.resolve(GuestNativeCliWriter.LOC_CLI_FILE_NAME),
                GuestNativeCliWriter.locScriptContent(version)
                        .getBytes(StandardCharsets.UTF_8), CLI_PERMISSIONS);
        updated |= GuestTermuxCompatWriter.ensureModule(activeRootfs, version)
                == Result.UPDATED;
        updated |= GuestAgentSeedWriter.ensure(activeRootfs, version, docSource)
                == Result.UPDATED;
        updated |= GuestOptionalTools.reconcile(activeRootfs, version, docSource)
                == Result.UPDATED;
        return updated ? Result.UPDATED : Result.UNCHANGED;
    }

    /**
     * Canonical executable Python 3 CLI content. The script ships with
     * executable bits set by the writer; it is deliberately small and
     * bounded: fixed allowlist commands only, no shell execution, no user
     * method passthrough, and the session token is never printed.
     */
    public static String cliContent(String appVersion) {
        String version = requireVersion(appVersion);
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# NusaDesk Android bridge CLI - generated by the NusaDesk Android app.",
                "# App version: " + version,
                "# Fixed session env: " + GUEST_ENV_FILE_PATH,
                "# This tool issues only the fixed allowlisted methods below. It never",
                "# runs a shell, never takes a user-supplied method name, and never",
                "# prints the session token. Calendar writes build their bounded",
                "# params object from typed flags, never from a raw JSON argument.",
                "",
                "import json",
                "import secrets",
                "import socket",
                "import sys",
                "",
                "ENV_FILE = '" + GUEST_ENV_FILE_PATH + "'",
                "SOCKET_TIMEOUT_SECONDS = 15.0",
                "MAX_CONFIG_LINE_BYTES = 1024",
                "MAX_RESPONSE_BYTES = 16384",
                "PROTOCOL_VERSION = 1",
                "ENV_ADDRESS = 'NUSADESK_ANDROID_BRIDGE_ADDRESS'",
                "ENV_PORT = 'NUSADESK_ANDROID_BRIDGE_PORT'",
                "ENV_TOKEN = 'NUSADESK_ANDROID_BRIDGE_TOKEN'",
                "ENV_PROTOCOL = 'NUSADESK_ANDROID_BRIDGE_PROTOCOL'",
                "TOKEN_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-'",
                "USAGE = 'usage: nusadesk-android media start [--camera|--microphone]"
                        + " | media status | media stop | media outputs"
                        + " | media route DEVICE_ID | media route clear | bridge info"
                        + " | calendar list"
                        + " | calendar add --title TITLE --begin-ms EPOCH_MS"
                        + " --end-ms EPOCH_MS [--all-day] [--location LOCATION]"
                        + " | calendar update EVENT_ID [--title TITLE]"
                        + " [--begin-ms EPOCH_MS --end-ms EPOCH_MS]"
                        + " [--all-day|--timed] [--location LOCATION]"
                        + " | calendar delete EVENT_ID'",
                "",
                "# Fixed allowlist only; there is deliberately no generic call",
                "# passthrough and no arbitrary method command.",
                "COMMANDS = {",
                "    'media start': 'media.start',",
                "    'media start --camera': 'media.camera.start',",
                "    'media start --microphone': 'media.microphone.start',",
                "    'media status': 'media.status',",
                "    'media stop': 'media.stop',",
                "    'media outputs': 'mediaplayer.outputs',",
                "    'media route clear': 'mediaplayer.route.clear',",
                "    'bridge info': 'bridge.info',",
                "    'calendar list': 'calendar.list',",
                "}",
                "",
                "# Calendar writes take typed flags only: the bounded params object is",
                "# built here, so a raw JSON payload or an unknown key cannot be sent.",
                "CALENDAR_VALUE_FLAGS = {",
                "    '--title': 'title',",
                "    '--location': 'location',",
                "}",
                "CALENDAR_EPOCH_FLAGS = {",
                "    '--begin-ms': 'begin_ms',",
                "    '--end-ms': 'end_ms',",
                "}",
                "MAX_TITLE_CHARS = 200",
                "MAX_EPOCH_CHARS = 19",
                "",
                "",
                "class CliError(Exception):",
                "    pass",
                "",
                "",
                "def parse_args(argv):",
                "    if not argv:",
                "        raise CliError(USAGE)",
                "    if argv[0] == 'calendar':",
                "        return parse_calendar(argv)",
                "    if argv[0] == 'media' and len(argv) >= 2 and argv[1] == 'route':",
                "        return parse_media_route(argv)",
                "    if len(argv) == 2:",
                "        key = argv[0] + ' ' + argv[1]",
                "    elif len(argv) == 3:",
                "        key = argv[0] + ' ' + argv[1] + ' ' + argv[2]",
                "    else:",
                "        raise CliError(USAGE)",
                "    method = COMMANDS.get(key)",
                "    if method is None:",
                "        raise CliError(USAGE)",
                "    return method, None",
                "",
                "",
                "def parse_media_route(argv):",
                "    if len(argv) == 3 and argv[2] == 'clear':",
                "        return 'mediaplayer.route.clear', None",
                "    if len(argv) != 3:",
                "        raise CliError(USAGE)",
                "    text = argv[2]",
                "    if (not text or len(text) > 10 or not text.isascii()",
                "            or not text.isdigit()):",
                "        raise CliError(USAGE)",
                "    device_id = int(text, 10)",
                "    if device_id > 2147483647:",
                "        raise CliError(USAGE)",
                "    return 'mediaplayer.route', {'device_id': device_id}",
                "",
                "",
                "def parse_calendar(argv):",
                "    if len(argv) < 2:",
                "        raise CliError(USAGE)",
                "    command = argv[0] + ' ' + argv[1]",
                "    rest = argv[2:]",
                "    if command == 'calendar list':",
                "        if rest:",
                "            raise CliError(USAGE)",
                "        return 'calendar.list', None",
                "    if command == 'calendar delete':",
                "        if len(rest) != 1:",
                "            raise CliError(USAGE)",
                "        return 'calendar.delete', {'event_id': parse_epoch(rest[0])}",
                "    if command == 'calendar add':",
                "        params = parse_calendar_changes(rest)",
                "        for required in ('title', 'begin_ms', 'end_ms'):",
                "            if required not in params:",
                "                raise CliError(USAGE)",
                "        return 'calendar.insert', params",
                "    if command == 'calendar update':",
                "        if not rest:",
                "            raise CliError(USAGE)",
                "        params = parse_calendar_changes(rest[1:])",
                "        if not params:",
                "            raise CliError(USAGE)",
                "        params['event_id'] = parse_epoch(rest[0])",
                "        return 'calendar.update', params",
                "    raise CliError(USAGE)",
                "",
                "",
                "def parse_calendar_changes(tokens):",
                "    params = {}",
                "    while tokens:",
                "        flag = tokens[0]",
                "        if flag in ('--all-day', '--timed'):",
                "            if 'all_day' in params:",
                "                raise CliError(USAGE)",
                "            params['all_day'] = flag == '--all-day'",
                "            tokens = tokens[1:]",
                "            continue",
                "        if flag in CALENDAR_VALUE_FLAGS or flag in CALENDAR_EPOCH_FLAGS:",
                "            if len(tokens) < 2:",
                "                raise CliError(USAGE)",
                "            if flag in CALENDAR_EPOCH_FLAGS:",
                "                key = CALENDAR_EPOCH_FLAGS[flag]",
                "                value = parse_epoch(tokens[1])",
                "            else:",
                "                key = CALENDAR_VALUE_FLAGS[flag]",
                "                value = parse_title(tokens[1])",
                "            if key in params:",
                "                raise CliError(USAGE)",
                "            params[key] = value",
                "            tokens = tokens[2:]",
                "            continue",
                "        raise CliError(USAGE)",
                "    if ('begin_ms' in params) != ('end_ms' in params):",
                "        raise CliError(USAGE)",
                "    return params",
                "",
                "",
                "def parse_epoch(text):",
                "    if not text.isascii() or not text.isdigit():",
                "        raise CliError(USAGE)",
                "    if len(text) > MAX_EPOCH_CHARS:",
                "        raise CliError(USAGE)",
                "    value = int(text, 10)",
                "    if value < 1:",
                "        raise CliError(USAGE)",
                "    return value",
                "",
                "",
                "def parse_title(text):",
                "    if not text or len(text) > MAX_TITLE_CHARS:",
                "        raise CliError(USAGE)",
                "    if any(ord(ch) < 32 or ord(ch) == 127 for ch in text):",
                "        raise CliError(USAGE)",
                "    return text",
                "",
                "",
                "def load_config(path):",
                "    values = {}",
                "    try:",
                "        with open(path, 'r', encoding='ascii') as handle:",
                "            for raw in handle:",
                "                if len(raw) > MAX_CONFIG_LINE_BYTES:",
                "                    raise CliError('session env line too long')",
                "                line = raw.strip()",
                "                if not line or line.startswith('#'):",
                "                    continue",
                "                if '=' not in line:",
                "                    raise CliError('malformed session env line')",
                "                key, _, value = line.partition('=')",
                "                key = key.strip()",
                "                value = value.strip()",
                "                if key not in (ENV_ADDRESS, ENV_PORT, ENV_TOKEN,",
                "                                ENV_PROTOCOL):",
                "                    raise CliError('unexpected session env key')",
                "                if key in values:",
                "                    raise CliError('duplicate session env key')",
                "                values[key] = value",
                "    except (IOError, UnicodeDecodeError, CliError) as error:",
                "        if isinstance(error, CliError):",
                "            raise",
                "        raise CliError('cannot read session env')",
                "    address = values.get(ENV_ADDRESS)",
                "    port_text = values.get(ENV_PORT)",
                "    token = values.get(ENV_TOKEN)",
                "    protocol = values.get(ENV_PROTOCOL)",
                "    if address != '127.0.0.1':",
                "        raise CliError('bridge address must be loopback only')",
                "    if protocol != str(PROTOCOL_VERSION):",
                "        raise CliError('unsupported bridge protocol version')",
                "    try:",
                "        port = int(port_text, 10)",
                "    except (TypeError, ValueError):",
                "        raise CliError('invalid session env port')",
                "    if port < 1 or port > 65535:",
                "        raise CliError('invalid session env port')",
                "    if token is None or len(token) < 16 or len(token) > 256:",
                "        raise CliError('invalid session env token')",
                "    if any(ch not in TOKEN_ALPHABET for ch in token):",
                "        raise CliError('invalid session env token')",
                "    return address, port, token",
                "",
                "",
                "def build_request(method, token, params):",
                "    request = {",
                "        'v': PROTOCOL_VERSION,",
                "        'id': secrets.token_urlsafe(8),",
                "        'token': token,",
                "        'method': method,",
                "    }",
                "    if params is not None:",
                "        request['params'] = params",
                "    return json.dumps(request, separators=(',', ':'),",
                "                      ensure_ascii=True)",
                "",
                "",
                "def call(address, port, token, method, params):",
                "    payload = build_request(method, token, params)",
                "    with socket.create_connection((address, port),",
                "                                  timeout=SOCKET_TIMEOUT_SECONDS) as sock:",
                "        sock.settimeout(SOCKET_TIMEOUT_SECONDS)",
                "        sock.sendall(payload.encode('ascii') + bytes([10]))",
                "        outgoing = bytearray()",
                "        while True:",
                "            chunk = sock.recv(4096)",
                "            if not chunk:",
                "                break",
                "            outgoing.extend(chunk)",
                "            if 10 in outgoing:",
                "                break",
                "            if len(outgoing) > MAX_RESPONSE_BYTES:",
                "                raise CliError('response too large')",
                "    if not outgoing:",
                "        raise CliError('empty bridge response')",
                "    line = outgoing.split(bytes([10]), 1)[0]",
                "    if len(line) > MAX_RESPONSE_BYTES:",
                "        raise CliError('response too large')",
                "    text = line.decode('utf-8')",
                "    try:",
                "        parsed = json.loads(text)",
                "    except ValueError:",
                "        raise CliError('bridge response is not JSON')",
                "    if not isinstance(parsed, dict):",
                "        raise CliError('bridge response is not a JSON object')",
                "    if parsed.get('v') != PROTOCOL_VERSION:",
                "        raise CliError('unsupported bridge response version')",
                "    return text, parsed",
                "",
                "",
                "def main(argv):",
                "    if len(argv) == 1 and argv[0] in ('-h', '--help'):",
                "        print(USAGE)",
                "        return 0",
                "    try:",
                "        method, params = parse_args(argv)",
                "        address, port, token = load_config(ENV_FILE)",
                "        text, parsed = call(address, port, token, method, params)",
                "    except CliError as error:",
                "        print('nusadesk-android: ' + str(error), file=sys.stderr)",
                "        return 2",
                "    except OSError:",
                "        print('nusadesk-android: bridge socket connection failed',",
                "              file=sys.stderr)",
                "        return 2",
                "    print(text)",
                "    if not parsed.get('ok'):",
                "        return 1",
                "    return 0",
                "",
                "",
                "if __name__ == '__main__':",
                "    sys.exit(main(sys.argv[1:]))",
                "") + "\n";
    }

    private static String requireVersion(String appVersion) {
        return requireVersionForWriter(appVersion);
    }

    /**
     * Shared version guard for every generated guest file: a single safe line,
     * no control characters, non-blank.
     */
    static String requireVersionForWriter(String appVersion) {
        if (appVersion == null || appVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("appVersion must be a single safe line");
        }
        for (int i = 0; i < appVersion.length(); i++) {
            char c = appVersion.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException(
                        "appVersion must be a single safe line");
            }
        }
        return appVersion.trim();
    }

    /**
     * Resolve one fixed relative parent, creating missing segments and
     * failing closed the moment any segment is a symlink or not a real
     * directory.
     */
    static Path prepareDirectory(Path base, String relative, String description)
            throws IOException {
        Path cursor = base;
        for (String segment : relative.split("/")) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)
                        || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(description + " is not a real directory: " + cursor);
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
        return cursor;
    }

    /** @return true when {@code target} was (re)written; false when it was already current */
    static boolean ensureFile(Path target, byte[] expected, String permissions)
            throws IOException {
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && sameBytes(target, expected)) {
            setPermissions(target, permissions);
            return false;
        }
        Path temporary = Files.createTempFile(target.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
        try {
            Files.write(temporary, expected, StandardOpenOption.TRUNCATE_EXISTING);
            setPermissions(temporary, permissions);
            moveAtomically(temporary, target);
            setPermissions(target, permissions);
            return true;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw failure;
        }
    }

    private static boolean sameBytes(Path file, byte[] expected) throws IOException {
        if (Files.size(file) != expected.length) {
            return false;
        }
        byte[] actual = Files.readAllBytes(file);
        if (actual.length != expected.length) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < expected.length; i++) {
            difference |= actual[i] ^ expected[i];
        }
        return difference == 0;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Set<StandardCopyOption> options = new HashSet<>();
        options.add(StandardCopyOption.ATOMIC_MOVE);
        options.add(StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(source, target, options.toArray(new StandardCopyOption[0]));
        } catch (IOException atomicFailed) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailed) {
                fallbackFailed.addSuppressed(atomicFailed);
                throw fallbackFailed;
            }
        }
    }

    private static void setPermissions(Path file, String permissions) {
        try {
            Files.setPosixFilePermissions(file,
                    PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException ignored) {
            // The app owns the extracted rootfs; providers without POSIX mode
            // support still leave the regular file writable by the owner.
        }
    }
}
