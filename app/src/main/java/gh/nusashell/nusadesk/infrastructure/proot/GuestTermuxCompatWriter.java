package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.infrastructure.proot.termux.TermuxCommand;
import gh.nusashell.nusadesk.infrastructure.proot.termux.TermuxCommandCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Guest-side Termux CLI compatibility layer.
 *
 * <p>The guest is a Linux environment, and the widely used way to drive
 * Android from a Linux environment is the <em>client</em> half of Termux:API:
 * the MIT-licensed {@code termux-*} shell commands. Those commands talk to
 * the Termux:API Android app, which only accepts callers that share the
 * Termux signing key and UID, so the app half can never serve this product.
 * The client half, however, is just an interface contract — command name,
 * flags, and a JSON result on stdout — and NusaDesk already owns an
 * authenticated capability bridge that can answer the same questions.</p>
 *
 * <p>This writer installs a small set of {@code termux-*} commands into
 * {@code /usr/local/bin} of the guest. Each one is a generated Python 3
 * script that translates the Termux flags into the fixed allowlist methods of
 * the NusaDesk bridge and prints the answer in the Termux JSON shape. Scripts
 * and tools written against Termux:API keep working; nothing here talks to the
 * Termux app, and the bridge contract (fixed methods, bounded params, typed
 * errors) is unchanged.</p>
 *
 * <p>The transport runtime is no longer duplicated per script: every command
 * is a thin flag-to-method mapping that imports the shared
 * {@code termux_compat} module installed at
 * {@code /usr/local/lib/nusadesk/termux_compat.py}. The command set itself is
 * a catalog ({@link TermuxCommandCatalog}) of per-domain lists
 * ({@code TermuxCoreCommands} and, as later waves land, sibling
 * {@code Termux<Domain>Commands} classes), so adding a command means adding a
 * catalog entry rather than editing this writer.</p>
 *
 * <p>Deliberate limits, recorded in the generated docs: only the methods the
 * bridge actually answers are installed, so {@code termux-sms-send},
 * {@code termux-call-log} and friends are absent rather than fake. Installed
 * commands are bounded like the bridge: fixed row caps and no side effects.
 * Unsupported Termux flags fail with usage instead of being silently ignored.
 * Exit codes follow the Termux contract: {@code 0} on a result, {@code 1} on a
 * typed bridge error, {@code 2} on usage or transport failure.</p>
 */
public final class GuestTermuxCompatWriter {

    /** Guest-relative bin directory, shared with the NusaDesk CLI. */
    public static final String GUEST_BIN_RELATIVE_PATH =
            GuestAwarenessReadmeWriter.GUEST_BIN_RELATIVE_PATH;
    /** Guest-relative docs directory, shared with the NusaDesk docs. */
    public static final String GUEST_DOCS_RELATIVE_PATH =
            GuestAwarenessReadmeWriter.GUEST_DOCS_RELATIVE_PATH;
    /** Guest-relative documentation page for this compatibility layer. */
    public static final String GUEST_DOC_RELATIVE_PATH = "root/docs/termux-compat.md";
    /** Guest-relative directory that receives the shared runtime module. */
    public static final String GUEST_MODULE_DIR_RELATIVE_PATH =
            "usr/local/lib/nusadesk";
    /** Guest-relative path of the shared runtime module. */
    public static final String GUEST_MODULE_RELATIVE_PATH =
            GUEST_MODULE_DIR_RELATIVE_PATH + "/termux_compat.py";
    /** Absolute module directory inside the guest, used for sys.path. */
    public static final String GUEST_MODULE_DIR = "/usr/local/lib/nusadesk";

    /** Marker prefix of every file this writer owns in the guest bin dir. */
    public static final String MARKER = "NusaDesk Termux-compat";

    /**
     * Installed command names, in the order they are documented. These are the
     * Termux command names the bridge can actually answer; the catalog is the
     * source of truth and this array mirrors it for callers that enumerate.
     */
    public static final String[] COMMANDS =
            TermuxCommandCatalog.names().toArray(new String[0]);

    private static final String TEMP_PREFIX = ".nusadesk-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String MODULE_PERMISSIONS = "rw-r--r--";
    private static final String DOC_PERMISSIONS = "rw-r--r--";
    private static final String SCRIPT_PERMISSIONS = "rwxr-xr-x";

    private GuestTermuxCompatWriter() {
    }

    /** Guest-relative path of one installed command. */
    public static String guestRelativePath(String command) {
        return GUEST_BIN_RELATIVE_PATH + "/" + command;
    }

    /**
     * The exact script content of one command. Public so tests and
     * documentation tooling can pin the generated text.
     *
     * @param command one of {@link #COMMANDS}
     * @param appVersion app version stamped into the script header
     */
    public static String scriptContent(String command, String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        TermuxCommand entry = TermuxCommandCatalog.require(command);
        return header(command, version) + entry.body() + trailer(command);
    }

    /**
     * The exact shared runtime module content: transport, session-env
     * validation, error types, and the emit/warn/run helpers every generated
     * command imports as {@code termux_compat}.
     */
    public static String moduleContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        return String.join("\n",
                "# " + MARKER + " runtime module: termux_compat",
                "# Generated by the NusaDesk Android app; do not edit.",
                "# App version: " + version,
                "# Shared by every generated termux-* command in /usr/local/bin.",
                "# It speaks only the fixed NusaDesk bridge allowlist: it never",
                "# contacts the Termux app, never runs a shell, and never prints",
                "# the token.",
                "",
                "import json",
                "import secrets",
                "import socket",
                "import sys",
                "",
                "ENV_FILE = '/run/nusadesk/android-bridge.env'",
                "SOCKET_TIMEOUT_SECONDS = 15.0",
                "MAX_CONFIG_LINE_BYTES = 1024",
                "MAX_RESPONSE_BYTES = 16384",
                "PROTOCOL_VERSION = 1",
                "ENV_ADDRESS = 'NUSADESK_ANDROID_BRIDGE_ADDRESS'",
                "ENV_PORT = 'NUSADESK_ANDROID_BRIDGE_PORT'",
                "ENV_TOKEN = 'NUSADESK_ANDROID_BRIDGE_TOKEN'",
                "ENV_PROTOCOL = 'NUSADESK_ANDROID_BRIDGE_PROTOCOL'",
                "TOKEN_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-'",
                "",
                "",
                "class CliError(Exception):",
                "    pass",
                "",
                "",
                "class BridgeError(Exception):",
                "    pass",
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
                "def bridge_call(method, params=None, timeout=SOCKET_TIMEOUT_SECONDS):",
                "    address, port, token = load_config(ENV_FILE)",
                "    request = {",
                "        'v': PROTOCOL_VERSION,",
                "        'id': secrets.token_urlsafe(8),",
                "        'token': token,",
                "        'method': method,",
                "    }",
                "    if params is not None:",
                "        request['params'] = params",
                "    payload = json.dumps(request, separators=(',', ':'),",
                "                          ensure_ascii=True)",
                "    with socket.create_connection((address, port),",
                "                                  timeout=timeout) as sock:",
                "        sock.settimeout(timeout)",
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
                "    try:",
                "        parsed = json.loads(line.decode('utf-8'))",
                "    except ValueError:",
                "        raise CliError('bridge response is not JSON')",
                "    if not isinstance(parsed, dict):",
                "        raise CliError('bridge response is not a JSON object')",
                "    if parsed.get('v') != PROTOCOL_VERSION:",
                "        raise CliError('unsupported bridge response version')",
                "    if not parsed.get('ok'):",
                "        raise BridgeError(str(parsed.get('error', 'bridge error')))",
                "    return parsed",
                "",
                "",
                "def call(method):",
                "    return bridge_call(method)",
                "",
                "",
                "def print_json(value):",
                "    print(json.dumps(value, separators=(',', ':'), ensure_ascii=True))",
                "",
                "",
                "def emit(value):",
                "    print_json(value)",
                "    return 0",
                "",
                "",
                "def warn(prog, message):",
                "    print(prog + ': ' + str(message), file=sys.stderr)",
                "",
                "",
                "def warn_truncated(prog, reading):",
                "    if reading.get('truncated') is True:",
                "        warn(prog, 'bridge row cap reached; results are truncated')",
                "",
                "",
                "# Print a diagnostic and exit with the given code; usable from",
                "# inside parse helpers where returning a status is awkward.",
                "def fail(prog, message, code):",
                "    warn(prog, message)",
                "    raise SystemExit(code)",
                "",
                "",
                "def run(prog, main):",
                "    try:",
                "        return main(sys.argv[1:])",
                "    except CliError as error:",
                "        warn(prog, error)",
                "        return 2",
                "    except BridgeError as error:",
                "        warn(prog, error)",
                "        return 1",
                "    except (ValueError, TypeError, KeyError):",
                "        # A malformed bridge payload is a protocol fault, not a typed",
                "        # capability error: fail with the usage/transport exit code.",
                "        warn(prog, 'malformed bridge response')",
                "        return 2",
                "    except OSError:",
                "        warn(prog, 'bridge socket connection failed')",
                "        return 2",
                "",
                "") + "\n";
    }

    /** The exact documentation page content for this layer. */
    public static String docContent(String appVersion) {
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        StringBuilder text = new StringBuilder();
        text.append(String.join("\n",
                "# Termux command compatibility",
                "",
                "NusaDesk installs a small set of Termux:API command-line clients into",
                "`/usr/local/bin`. They exist because the Termux:API *app* cannot serve",
                "this product: it only accepts callers that share the Termux signing",
                "key and Android UID, and NusaDesk is a different app. The commands",
                "themselves are a plain interface contract, so NusaDesk answers the",
                "same questions from its own authenticated Android capability bridge.",
                "",
                "Scripts written against these Termux commands keep working without",
                "changes. Nothing here contacts the Termux app, and no Termux app is",
                "required or used.",
                "",
                "## Installed commands",
                "",
                ""));
        for (TermuxCommand command : TermuxCommandCatalog.all()) {
            for (String line : command.docLines()) {
                text.append(line).append('\n');
            }
        }
        text.append(String.join("\n",
                "",
                "## Output shape",
                "",
                "Output is the Termux JSON shape on stdout: object keys as Termux:API",
                "emits them (for example `percentage`, `latitude`, `values`), enum",
                "values in the Termux spelling (for example `CHARGING`, `PLUGGED_USB`),",
                "and, where Termux has one, a `received` timestamp in the Termux",
                "`yyyy-MM-dd HH:mm:ss` format (`termux-sms-list`). Values are computed",
                "from the bounded bridge reading, so some fields are necessarily",
                "best-effort: `termux-location` reports one foreground fix and omits",
                "fields the bridge did not return; `termux-sensor` is a sequence of",
                "one-shot reads rather than a continuous stream; `termux-sms-list`",
                "carries the bridge's bounded message preview as `body`.",
                "",
                "Row caps are lower than Termux's defaults: `termux-sms-list` and",
                "`termux-telephony-cellinfo` see at most 50 and 10 rows respectively",
                "from the bounded bridge reading, so `-o` pages only within that",
                "window. `termux-contact-list` expands one bridge row per number, so",
                "it can print up to five entries for each of the bridge's 50 contact",
                "rows. When the bridge itself hit its cap the row commands say so on",
                "stderr; the exit code stays `0` because the rows that *were*",
                "returned are valid.",
                "",
                "Every command is a thin script: it imports the shared runtime at",
                "`" + GUEST_MODULE_DIR + "/termux_compat.py` and only translates",
                "its flags into the fixed bridge call.",
                "",
                "## Exit codes",
                "",
                "- `0` - a result was printed",
                "- `1` - the bridge returned a typed error (printed to stderr)",
                "- `2` - usage error, an unparseable bridge payload, or the bridge",
                "  was unreachable",
                "",
                "## Not installed",
                "",
                "Only methods this bridge answers are installed. Commands that need",
                "another Termux app, a side effect the bridge does not expose, or an",
                "Android capability outside the current allowlist are deliberately",
                "absent:",
                ""));
        for (String line : absentLines()) {
            text.append(line).append('\n');
        }
        text.append(String.join("\n",
                "",
                "(`termux-sms-inbox` is upstream-deprecated: Termux replaced it with",
                "`termux-sms-list`.) Camera and microphone are served by the live",
                "media session instead: see [media.md](media.md).",
                "",
                "## Bridge session",
                "",
                "The commands read the same session file as `nusadesk-android`",
                "(`/run/nusadesk/android-bridge.env`) and speak the same loopback",
                "protocol, so they are only usable inside a live NusaDesk session.",
                "",
                generatedFooter(version),
                "",
                ""));
        return text.append('\n').toString();
    }

    /** The deliberately-absent command names, wrapped to readable doc lines. */
    private static List<String> absentLines() {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String absent : TermuxCommandCatalog.deliberatelyAbsent()) {
            String name = "`" + absent + "`";
            if (line.length() > 0 && line.length() + 2 + name.length() > 72) {
                lines.add(line + ",");
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(name);
        }
        if (line.length() > 0) {
            lines.add(line + ".");
        }
        // Indent the whole list under the "absent:" lead-in.
        List<String> wrapped = new ArrayList<>();
        for (String item : lines) {
            wrapped.add("  " + item);
        }
        return wrapped;
    }

    private static String generatedFooter(String version) {
        return "> Docs generated by the NusaDesk Android app (version " + version
                + "). Manual edits may be overwritten on the next session start.";
    }

    private static String header(String command, String version) {
        return String.join("\n",
                "#!/usr/bin/env python3",
                "# " + MARKER + " client: " + command,
                "# Generated by the NusaDesk Android app; do not edit.",
                "# App version: " + version,
                "# Speaks only the fixed NusaDesk bridge allowlist through the",
                "# shared termux_compat module. It never contacts the Termux app,",
                "# never runs a shell, and never prints the token.",
                "",
                "import sys",
                "",
                "sys.path.insert(0, '" + GUEST_MODULE_DIR + "')",
                "import termux_compat as tc",
                "",
                "");
    }

    private static String trailer(String command) {
        return "\n\nif __name__ == '__main__':\n"
                + "    sys.exit(tc.run('" + command + "', main))\n";
    }

    /**
     * Ensure the whole Termux-compat layer (shared runtime module, one
     * executable script per cataloged command, the documentation page, and a
     * sweep of stale generated commands) matches the current app version.
     *
     * <p>Writes are atomic and idempotent like every other generated guest
     * file: content is staged beside the target and moved into place, and a
     * file whose bytes already match is left untouched. A script this writer
     * once installed but that is no longer declared by the catalog is swept,
     * so a shrinking command set cannot leave a stale executable behind; the
     * shared module is never a sweep candidate.</p>
     *
     * @param activeRootfs validated active rootfs directory
     * @param appVersion APK version name, not user input
     * @return whether any file was updated or everything was already current
     * @throws IOException when a fixed path is unsafe or cannot be written
     */
    public static GuestAwarenessReadmeWriter.Result ensure(Path activeRootfs,
            String appVersion) throws IOException {
        if (activeRootfs == null) {
            throw new IllegalArgumentException("activeRootfs must not be null");
        }
        String version = GuestAwarenessReadmeWriter.requireVersionForWriter(appVersion);
        Path docs = prepareDirectory(activeRootfs, GUEST_DOCS_RELATIVE_PATH,
                "guest /root/docs");
        Path bin = prepareDirectory(activeRootfs, GUEST_BIN_RELATIVE_PATH,
                "guest /usr/local/bin");
        Path lib = prepareDirectory(activeRootfs, GUEST_MODULE_DIR_RELATIVE_PATH,
                "guest /usr/local/lib/nusadesk");

        boolean updated = false;
        updated |= ensureFile(lib.resolve("termux_compat.py"),
                moduleContent(version).getBytes(StandardCharsets.UTF_8),
                MODULE_PERMISSIONS);
        for (TermuxCommand command : TermuxCommandCatalog.all()) {
            updated |= ensureFile(bin.resolve(command.name()),
                    scriptContent(command.name(), version)
                            .getBytes(StandardCharsets.UTF_8),
                    SCRIPT_PERMISSIONS);
        }
        updated |= ensureFile(docs.resolve("termux-compat.md"),
                docContent(version).getBytes(StandardCharsets.UTF_8),
                DOC_PERMISSIONS);
        updated |= sweepStale(bin);
        return updated ? GuestAwarenessReadmeWriter.Result.UPDATED
                : GuestAwarenessReadmeWriter.Result.UNCHANGED;
    }

    /**
     * Delete a previously generated Termux-compat script whose command is no
     * longer declared. Only a regular (never a symlink) file whose name starts
     * with {@code termux-} and whose content carries this writer's marker is
     * eligible, so a user file or another tool's file is never touched. The
     * shared module lives outside the bin directory and is never a candidate.
     */
    private static boolean sweepStale(Path bin) throws IOException {
        Set<String> declared = TermuxCommandCatalog.names();
        boolean removed = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(bin)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith("termux-") || declared.contains(name)) {
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!carriesMarker(entry)) {
                    continue;
                }
                Files.delete(entry);
                removed = true;
            }
        }
        return removed;
    }

    private static boolean carriesMarker(Path file) throws IOException {
        // Open with NOFOLLOW so a symlink swapped in after the check above is
        // never read through; the marker is only ever in our own regular files.
        Set<java.nio.file.OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.READ);
        options.add(LinkOption.NOFOLLOW_LINKS);
        try (java.nio.channels.SeekableByteChannel channel =
                     Files.newByteChannel(file, options);
             BufferedReader reader = new BufferedReader(
                     new java.io.InputStreamReader(
                             java.nio.channels.Channels.newInputStream(channel),
                             StandardCharsets.UTF_8))) {
            for (int line = 0; line < 3; line++) {
                String text = reader.readLine();
                if (text == null) {
                    return false;
                }
                if (text.contains(MARKER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolve one fixed relative parent, creating missing segments and
     * failing closed the moment any segment is a symlink or not a real
     * directory.
     */
    private static Path prepareDirectory(Path base, String relative, String description)
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
    private static boolean ensureFile(Path target, byte[] expected, String permissions)
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
