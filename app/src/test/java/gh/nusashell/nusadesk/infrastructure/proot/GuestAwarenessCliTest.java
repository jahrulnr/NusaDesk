package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Focused tests for the generated {@code nusadesk-android} CLI: command
 * mapping, safety bounds, executable bits, and a live round-trip over a fake
 * loopback JSONL listener (skipped when python3 is not on PATH).
 */
public class GuestAwarenessCliTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void cliIsExecutableFixedAllowlistWithNoGenericCallPassthrough() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
        Path cli = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_CLI_RELATIVE_PATH);
        String content = new String(Files.readAllBytes(cli), StandardCharsets.UTF_8);

        assertTrue(Files.isExecutable(cli));
        // Same canonical text as the writer advertises.
        assertEquals(GuestAwarenessReadmeWriter.cliContent("0.1.0"), content);

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));
        assertTrue(content.contains("ENV_FILE = '/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("SOCKET_TIMEOUT_SECONDS"));
        assertTrue(content.contains("MAX_RESPONSE_BYTES = 16384"));
        assertTrue(content.contains("MAX_CONFIG_LINE_BYTES = 1024"));
        assertTrue(content.contains("secrets.token_urlsafe(8)"));
        assertTrue(content.contains(
                "usage: nusadesk-android media start [--camera|--microphone]"
                        + " | media status | media stop | bridge info"
                        + " | calendar list"
                        + " | calendar add --title TITLE --begin-ms EPOCH_MS"
                        + " --end-ms EPOCH_MS [--all-day] [--location LOCATION]"
                        + " | calendar update EVENT_ID [--title TITLE]"
                        + " [--begin-ms EPOCH_MS --end-ms EPOCH_MS]"
                        + " [--all-day|--timed] [--location LOCATION]"
                        + " | calendar delete EVENT_ID"));

        // The fixed allowlist is exactly the supported fixed commands; the
        // calendar writes are parsed from typed flags instead of a table key.
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("media start", "media.start");
        expected.put("media start --camera", "media.camera.start");
        expected.put("media start --microphone", "media.microphone.start");
        expected.put("media status", "media.status");
        expected.put("media stop", "media.stop");
        expected.put("bridge info", "bridge.info");
        expected.put("calendar list", "calendar.list");
        assertEquals(expected, commandMapping(content));

        // Calendar writes build the bounded params object from typed flags, so
        // a raw JSON payload or an unknown key can never be forwarded.
        assertTrue(content.contains("def parse_calendar_changes"));
        assertTrue(content.contains("request['params'] = params"));
        assertFalse("no raw JSON argument is forwarded",
                lower(content).contains("json.loads(sys.argv")
                        || lower(content).contains("json.loads(tokens)"));
        assertFalse("no attendee flag is expressible", content.contains("--attendee"));

        // No shell, no generic call passthrough, no arbitrary method command.
        String lower = content.toLowerCase();
        assertFalse("no subprocess import", lower.contains("subprocess"));
        assertFalse("no os.system", lower.contains("os.system"));
        assertFalse("no os.popen", lower.contains("os.popen"));
        assertFalse("no eval(", lower.contains("eval("));
        assertFalse("no exec(", lower.contains("exec("));
        assertFalse("no 'call ' command key", content.contains("'call '"));
        assertFalse("no argument-forwarded method",
                content.contains("sys.argv[2]") || content.contains("sys.argv[3]"));
    }

    @Test
    public void cliRoundTripsAgainstLoopbackBridgeListener() throws Exception {
        assumeTrue("python3 must be available for the live CLI round-trip",
                interpreterAvailable());
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
        Path cli = rootfs.resolve(GuestAwarenessReadmeWriter.GUEST_CLI_RELATIVE_PATH);
        Path env = temporary.newFolder("env").toPath().resolve("android-bridge.env");
        Files.write(env, "placeholder".getBytes(StandardCharsets.UTF_8));
        Path orchestrator = temporary.newFolder("harness").toPath().resolve("run_cli.py");
        Files.write(orchestrator,
                orchestratorScript().getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(
                "python3", orchestrator.toString(), cli.toString(), env.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("CLI round-trip harness timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("CLI round-trip harness failed:\n" + output,
                0, process.exitValue());
    }

    private static boolean interpreterAvailable() {
        try {
            Process process = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** The fixed command table only; the calendar flags are not commands. */
    private static Map<String, String> commandMapping(String content) {
        int tableStart = content.indexOf("COMMANDS = {");
        int tableEnd = content.indexOf("}", tableStart);
        Pattern pattern = Pattern.compile(
                "^    '(.+)': '(.+)',$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content.substring(tableStart, tableEnd));
        Map<String, String> mapping = new LinkedHashMap<>();
        while (matcher.find()) {
            mapping.put(matcher.group(1), matcher.group(2));
        }
        return mapping;
    }

    private static String lower(String text) {
        return text.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * A bounded Python harness that loads the generated CLI module, runs it
     * against a fake loopback listener, and validates the wire contract:
     * fixed envelope, bounded unique id, full-duplex one-line JSON report,
     * exit codes, and no token disclosure. Fails with a traceback-free
     * report on stderr path when any case fails.
     */
    private static String orchestratorScript() {
        return String.join("\n",
                "import io",
                "import json",
                "import os",
                "import socket",
                "import sys",
                "import threading",
                "import types",
                "",
                "CLI_PATH = sys.argv[1]",
                "ENV_PATH = sys.argv[2]",
                "",
                "ADDRESS = '127.0.0.1'",
                "TOKEN = 'test_token_0123456789ABCDEF_inv'",
                "PROTOCOL = '1'",
                "USAGE = ('usage: nusadesk-android media start [--camera|--microphone]'",
                "         + ' | media status | media stop | bridge info | calendar list'",
                "         + ' | calendar add --title TITLE --begin-ms EPOCH_MS'",
                "         + ' --end-ms EPOCH_MS [--all-day] [--location LOCATION]'",
                "         + ' | calendar update EVENT_ID [--title TITLE]'",
                "         + ' [--begin-ms EPOCH_MS --end-ms EPOCH_MS]'",
                "         + ' [--all-day|--timed] [--location LOCATION]'",
                "         + ' | calendar delete EVENT_ID')",
                "BEGIN_MS = 1760003600000",
                "END_MS = 1760007200000",
                "failures = []",
                "",
                "",
                "def load_cli():",
                "    # The generated CLI is a shebang script without a .py suffix,",
                "    # so spec_from_file_location cannot derive a source loader.",
                "    # Build a plain module and exec the source into it instead.",
                "    module = types.ModuleType('nusadesk_android_cli')",
                "    module.__file__ = CLI_PATH",
                "    with open(CLI_PATH, 'r', encoding='utf-8') as handle:",
                "        source = handle.read()",
                "    exec(compile(source, CLI_PATH, 'exec'), module.__dict__)",
                "    return module",
                "",
                "",
                "def write_env(port):",
                "    with open(ENV_PATH, 'w', encoding='ascii') as handle:",
                "        handle.write('# NusaDesk Android capability bridge; session-scoped\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS=' + ADDRESS + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_PORT=' + str(port) + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_TOKEN=' + TOKEN + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_PROTOCOL=' + PROTOCOL + '\\n')",
                "",
                "",
                "def capture(module, argv):",
                "    out = io.StringIO()",
                "    err = io.StringIO()",
                "    old_out, old_err = sys.stdout, sys.stderr",
                "    sys.stdout, sys.stderr = out, err",
                "    try:",
                "        rc = module.main(argv)",
                "    finally:",
                "        sys.stdout, sys.stderr = old_out, old_err",
                "    return rc, out.getvalue(), err.getvalue()",
                "",
                "",
                "def run_case(module, name, argv, response_fields, expect_rc,",
                "             expected_method, expected_params=None):",
                "    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "    server.bind((ADDRESS, 0))",
                "    server.listen(2)",
                "    write_env(server.getsockname()[1])",
                "    response = json.dumps(response_fields, separators=(',', ':'))",
                "    received = {}",
                "",
                "    def handler():",
                "        client, _ = server.accept()",
                "        try:",
                "            raw = bytearray()",
                "            while True:",
                "                chunk = client.recv(4096)",
                "                if not chunk:",
                "                    break",
                "                raw.extend(chunk)",
                "                if 10 in raw:",
                "                    break",
                "            text = raw.split(bytes([10]), 1)[0].decode('ascii')",
                "            received.update(json.loads(text))",
                "            client.sendall((response + '\\n').encode('ascii'))",
                "        finally:",
                "            client.close()",
                "            server.close()",
                "",
                "    thread = threading.Thread(target=handler)",
                "    thread.start()",
                "    rc, captured, _ = capture(module, argv)",
                "    thread.join(10)",
                "    if rc != expect_rc:",
                "        failures.append(name + ': rc ' + str(rc)",
                "                        + ' expected ' + str(expect_rc))",
                "    if captured.strip() != response:",
                "        failures.append(name + ': stdout not the raw JSON response '",
                "                        + repr(captured))",
                "    if TOKEN in captured:",
                "        failures.append(name + ': token leaked into stdout')",
                "    if received.get('method') != expected_method:",
                "        failures.append(name + ': method ' + str(received.get('method')))",
                "    if received.get('params') != expected_params:",
                "        failures.append(name + ': params ' + repr(received.get('params')))",
                "    if received.get('v') != 1:",
                "        failures.append(name + ': bad request version')",
                "    if received.get('token') != TOKEN:",
                "        failures.append(name + ': bad request token')",
                "    request_id = received.get('id')",
                "    allowed = ('ABCDEFGHIJKLMNOPQRSTUVWXYZ'",
                "               + 'abcdefghijklmnopqrstuvwxyz0123456789_-')",
                "    if not request_id or len(request_id) > 64:",
                "        failures.append(name + ': id not bounded')",
                "    elif any(ch not in allowed for ch in request_id):",
                "        failures.append(name + ': id not ascii-safe')",
                "    return request_id",
                "",
                "",
                "def usage_expects(module, name, argv):",
                "    rc, captured, err = capture(module, argv)",
                "    if rc != 2:",
                "        failures.append(name + ': rc ' + str(rc))",
                "    if captured != '':",
                "        failures.append(name + ': unexpected stdout')",
                "    if USAGE not in err:",
                "        failures.append(name + ': missing usage on stderr')",
                "",
                "",
                "module = load_cli()",
                "module.ENV_FILE = ENV_PATH",
                "",
                "run_case(module, 'status-stopped', ['media', 'status'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'state': 'stopped'}, 0,",
                "         'media.status')",
                "run_case(module, 'start-running', ['media', 'start'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'state': 'running',",
                "          'mode': 'both',",
                "          'rtsp_url': 'rtsp://127.0.0.1:1234/', 'video_codec': 'h264',",
                "          'audio_codec': 'aac', 'video_width': 1280,",
                "          'video_height': 720, 'video_fps': 30, 'client_limit': 2}, 0,",
                "         'media.start')",
                "run_case(module, 'camera-start', ['media', 'start', '--camera'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'state': 'running',",
                "          'mode': 'camera',",
                "          'rtsp_url': 'rtsp://127.0.0.1:1234/', 'video_codec': 'h264',",
                "          'video_width': 1280, 'video_height': 720, 'video_fps': 30,",
                "          'client_limit': 2}, 0, 'media.camera.start')",
                "run_case(module, 'microphone-start', ['media', 'start', '--microphone'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'state': 'running',",
                "          'mode': 'microphone',",
                "          'rtsp_url': 'rtsp://127.0.0.1:1234/', 'audio_codec': 'aac',",
                "          'client_limit': 2}, 0, 'media.microphone.start')",
                "run_case(module, 'mode-conflict', ['media', 'start', '--camera'],",
                "         {'v': 1, 'id': 'srv', 'ok': False,",
                "          'error': 'media-mode-conflict'}, 1, 'media.camera.start')",
                "run_case(module, 'stop-stopped', ['media', 'stop'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'state': 'stopped'}, 0,",
                "         'media.stop')",
                "run_case(module, 'bridge-info', ['bridge', 'info'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'transport': 'tcp-loopback',",
                "          'capabilities': 'media.start,media.status,media.stop',",
                "          'best_effort': True}, 0, 'bridge.info')",
                "run_case(module, 'typed-error', ['media', 'start'],",
                "         {'v': 1, 'id': 'srv', 'ok': False,",
                "          'error': 'media-permission-required'}, 1, 'media.start')",
                "run_case(module, 'calendar-list', ['calendar', 'list'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'available': True,",
                "          'count': 1, 'truncated': False,",
                "          'rows': '[{\"event_id\":11,\"title\":\"Rapat\"}]'}, 0,",
                "         'calendar.list')",
                "run_case(module, 'calendar-add',",
                "         ['calendar', 'add', '--title', 'Rapat',",
                "          '--begin-ms', str(BEGIN_MS), '--end-ms', str(END_MS)],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'written': True,",
                "          'event_id': 500}, 0, 'calendar.insert',",
                "         {'title': 'Rapat', 'begin_ms': BEGIN_MS, 'end_ms': END_MS})",
                "run_case(module, 'calendar-add-all-day',",
                "         ['calendar', 'add', '--title', 'Cuti', '--all-day',",
                "          '--location', 'Ruang 1',",
                "          '--begin-ms', str(BEGIN_MS), '--end-ms', str(END_MS)],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'written': True,",
                "          'event_id': 501}, 0, 'calendar.insert',",
                "         {'title': 'Cuti', 'all_day': True, 'location': 'Ruang 1',",
                "          'begin_ms': BEGIN_MS, 'end_ms': END_MS})",
                "run_case(module, 'calendar-update-title',",
                "         ['calendar', 'update', '500', '--title', 'Rapat baru'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'written': True,",
                "          'event_id': 500}, 0, 'calendar.update',",
                "         {'title': 'Rapat baru', 'event_id': 500})",
                "run_case(module, 'calendar-update-timed-range',",
                "         ['calendar', 'update', '500', '--begin-ms', str(BEGIN_MS),",
                "          '--end-ms', str(END_MS), '--timed'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'written': True,",
                "          'event_id': 500}, 0, 'calendar.update',",
                "         {'begin_ms': BEGIN_MS, 'end_ms': END_MS, 'all_day': False,",
                "          'event_id': 500})",
                "run_case(module, 'calendar-delete', ['calendar', 'delete', '500'],",
                "         {'v': 1, 'id': 'srv', 'ok': True, 'written': True,",
                "          'event_id': 500}, 0, 'calendar.delete',",
                "         {'event_id': 500})",
                "run_case(module, 'calendar-typed-error',",
                "         ['calendar', 'delete', '500'],",
                "         {'v': 1, 'id': 'srv', 'ok': False,",
                "          'error': 'calendar-read-only'}, 1, 'calendar.delete',",
                "         {'event_id': 500})",
                "",
                "id_a = run_case(module, 'unique-id-a', ['media', 'status'],",
                "                {'v': 1, 'id': 'srv', 'ok': True, 'state': 'stopped'},",
                "                0, 'media.status')",
                "id_b = run_case(module, 'unique-id-b', ['media', 'status'],",
                "                {'v': 1, 'id': 'srv', 'ok': True, 'state': 'stopped'},",
                "                0, 'media.status')",
                "if id_a == id_b or not id_a or not id_b:",
                "    failures.append('ids are not unique per invocation')",
                "",
                "usage_expects(module, 'call-passthrough', ['call', 'media.start'])",
                "usage_expects(module, 'unknown-subcommand', ['media', 'bogus'])",
                "usage_expects(module, 'missing-subcommand', ['media'])",
                "usage_expects(module, 'no-args', [])",
                "usage_expects(module, 'unknown-mode-flag', ['media', 'start', '--bogus'])",
                "usage_expects(module, 'mode-flag-on-status',",
                "              ['media', 'status', '--camera'])",
                "usage_expects(module, 'too-many-tokens',",
                "              ['media', 'start', '--camera', 'extra'])",
                "usage_expects(module, 'calendar-without-subcommand', ['calendar'])",
                "usage_expects(module, 'calendar-unknown-subcommand',",
                "              ['calendar', 'bogus'])",
                "usage_expects(module, 'calendar-list-with-extra-token',",
                "              ['calendar', 'list', '--camera'])",
                "usage_expects(module, 'calendar-add-missing-range',",
                "              ['calendar', 'add', '--title', 'X'])",
                "usage_expects(module, 'calendar-add-half-range',",
                "              ['calendar', 'add', '--title', 'X', '--begin-ms', '1'])",
                "usage_expects(module, 'calendar-add-non-numeric-epoch',",
                "              ['calendar', 'add', '--title', 'X', '--begin-ms', 'soon',",
                "               '--end-ms', '1'])",
                "usage_expects(module, 'calendar-add-empty-title',",
                "              ['calendar', 'add', '--title', '', '--begin-ms', '1',",
                "               '--end-ms', '2'])",
                "usage_expects(module, 'calendar-add-unknown-parameter',",
                "              ['calendar', 'add', '--title', 'X', '--begin-ms', '1',",
                "               '--end-ms', '2', '--attendees', 'a@b.c'])",
                "usage_expects(module, 'calendar-add-raw-json-parameter',",
                "              ['calendar', 'add', '--params', '{\"title\":\"X\"}'])",
                "usage_expects(module, 'calendar-update-without-id',",
                "              ['calendar', 'update'])",
                "usage_expects(module, 'calendar-update-without-change',",
                "              ['calendar', 'update', '500'])",
                "usage_expects(module, 'calendar-update-negative-id',",
                "              ['calendar', 'update', '-1', '--title', 'X'])",
                "usage_expects(module, 'calendar-delete-without-id',",
                "              ['calendar', 'delete'])",
                "usage_expects(module, 'calendar-delete-extra-token',",
                "              ['calendar', 'delete', '500', 'extra'])",
                "usage_expects(module, 'calendar-delete-non-numeric-id',",
                "              ['calendar', 'delete', 'event'])",
                "",
                "rc, captured, err = capture(module, ['-h'])",
                "if rc != 0 or USAGE not in captured:",
                "    failures.append('help: rc ' + str(rc))",
                "",
                "os.remove(ENV_PATH)",
                "rc, captured, err = capture(module, ['media', 'status'])",
                "if rc != 2 or captured != '' or 'session env' not in err:",
                "    failures.append('missing-env: rc ' + str(rc))",
                "",
                "with open(ENV_PATH, 'w', encoding='ascii') as handle:",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS=192.168.1.10\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_PORT=1\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_TOKEN=' + TOKEN + '\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_PROTOCOL=' + PROTOCOL + '\\n')",
                "rc, _, err = capture(module, ['media', 'status'])",
                "if rc != 2 or 'loopback' not in err:",
                "    failures.append('non-loopback-address: rc ' + str(rc))",
                "",
                "if failures:",
                "    print('FAILURES:')",
                "    for failure in failures:",
                "        print('  ' + failure)",
                "    sys.exit(1)",
                "print('cli-roundtrip-ok')",
                "sys.exit(0)",
                "") + "\n";
    }
}