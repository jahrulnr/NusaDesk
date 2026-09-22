package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.infrastructure.proot.termux.TermuxCommand;
import gh.nusashell.nusadesk.infrastructure.proot.termux.TermuxCommandCatalog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Focused tests for the generated Termux command compatibility layer
 * (ADR-0036): the installed command set, the shared runtime module, the
 * safety properties of the generated scripts, and a live round-trip that
 * proves the scripts translate Termux flags into the fixed bridge methods and
 * print the Termux JSON shape.
 */
public class GuestTermuxCompatWriterTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsExecutableScriptsModuleAndDocsForEveryDeclaredCommand()
            throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestTermuxCompatWriter.ensure(rootfs, "0.2.1"));

        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            Path script = rootfs.resolve(GuestTermuxCompatWriter.guestRelativePath(command));
            assertTrue(command + " must be installed", Files.isRegularFile(script));
            assertTrue(command + " must be executable", Files.isExecutable(script));
            String content = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
            assertEquals("same canonical text as the writer advertises",
                    GuestTermuxCompatWriter.scriptContent(command, "0.2.1"), content);
            assertTrue(content.startsWith("#!/usr/bin/env python3"));
            assertTrue(content.contains(GuestTermuxCompatWriter.MARKER));
            assertTrue(content.contains("App version: 0.2.1"));
            // Thin client: import the shared runtime, translate flags, exit
            // through the shared runner. No transport code lives here.
            assertTrue(content.contains(
                    "sys.path.insert(0, '/usr/local/lib/nusadesk')"));
            assertTrue(content.contains("import termux_compat as tc"));
            assertTrue(content.contains(
                    "sys.exit(tc.run('" + command + "', main))"));
            assertFalse(command + " must not carry the transport",
                    content.contains("def load_config"));
            assertFalse(command + " must not carry the transport",
                    content.contains("def bridge_call"));
            assertFalse(command + " must not carry the transport",
                    content.contains("socket.create_connection"));
            assertFalse(command + " must not carry a second entry point",
                    content.contains("sys.argv"));
            // No shell, no generic passthrough, no Termux app contact.
            // termux-usb is the one documented exception: its `-e CMD`
            // contract IS running the user's callback with the delivered fd
            // (upstream behaviour), through an argv list and never a shell.
            String lower = content.toLowerCase(java.util.Locale.ROOT);
            if (command.equals("termux-usb")) {
                assertTrue("the usb callback runs an argv list",
                        lower.contains("subprocess.run("));
                assertTrue("the usb callback passes the delivered fd",
                        lower.contains("pass_fds"));
                assertFalse("the usb callback never runs a shell",
                        lower.contains("shell=true"));
            } else {
                assertFalse("no subprocess", lower.contains("subprocess"));
            }
            assertFalse("no os.system", lower.contains("os.system"));
            assertFalse("no eval(", lower.contains("eval("));
            assertFalse("no exec(", lower.contains("exec("));
            assertFalse("never talks to the Termux app", lower.contains("com.termux"));
            assertFalse("no argv-derived method",
                    lower.contains("call(argv") || lower.contains("call(sys.argv"));
        }

        Path module = rootfs.resolve(GuestTermuxCompatWriter.GUEST_MODULE_RELATIVE_PATH);
        assertTrue("shared runtime must be installed", Files.isRegularFile(module));
        assertFalse("shared runtime is a library, not a command",
                Files.isExecutable(module));
        String moduleText = new String(Files.readAllBytes(module),
                StandardCharsets.UTF_8);
        assertEquals("same canonical text as the writer advertises",
                GuestTermuxCompatWriter.moduleContent("0.2.1"), moduleText);
        assertTrue(moduleText.contains(GuestTermuxCompatWriter.MARKER));
        assertTrue(moduleText.contains("App version: 0.2.1"));
        // The bridge contract is untouched: same envelope, same bounds.
        assertTrue(moduleText.contains(
                "ENV_FILE = '/run/nusadesk/android-bridge.env'"));
        assertTrue(moduleText.contains("PROTOCOL_VERSION = 1"));
        assertTrue(moduleText.contains("MAX_RESPONSE_BYTES = 16384"));
        assertTrue(moduleText.contains("secrets.token_urlsafe(8)"));
        assertTrue(moduleText.contains("def bridge_call(method, params=None,"));
        assertTrue(moduleText.contains("request['params'] = params"));
        assertTrue(moduleText.contains("def call(method):"));
        assertTrue(moduleText.contains("def run(prog, main):"));
        assertTrue(moduleText.contains("return main(sys.argv[1:])"));
        assertEquals("the shared runner is the only argv reader", 1,
                moduleText.split("sys\\.argv", -1).length - 1);
        String moduleLower = moduleText.toLowerCase(java.util.Locale.ROOT);
        assertFalse("no subprocess", moduleLower.contains("subprocess"));
        assertFalse("no os.system", moduleLower.contains("os.system"));
        assertFalse("no eval(", moduleLower.contains("eval("));
        assertFalse("no exec(", moduleLower.contains("exec("));
        assertFalse("never talks to the Termux app", moduleLower.contains("com.termux"));

        Path doc = rootfs.resolve(GuestTermuxCompatWriter.GUEST_DOC_RELATIVE_PATH);
        assertTrue(Files.isRegularFile(doc));
        String docText = new String(Files.readAllBytes(doc), StandardCharsets.UTF_8);
        assertEquals(GuestTermuxCompatWriter.docContent("0.2.1"), docText);
        assertTrue(docText.contains("signing"));
        assertTrue(docText.contains("key"));
        assertTrue(docText.contains("termux-sms-send"));
        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            assertTrue("doc must list " + command, docText.contains(command));
        }
    }

    @Test
    public void eachCommandSendsItsFixedBridgeMethodOnly() {
        assertEquals("battery.status", methodOf("termux-battery-status"));
        assertEquals("location.get", methodOf("termux-location"));
        assertEquals("contacts.list", methodOf("termux-contact-list"));
        assertEquals("sms.inbox", methodOf("termux-sms-list"));
        assertEquals("telephony.info", methodOf("termux-telephony-deviceinfo"));
        assertEquals("telephony.cellinfo", methodOf("termux-telephony-cellinfo"));

        String sensor = GuestTermuxCompatWriter.scriptContent("termux-sensor", "0.2.1");
        // The full-flag sensor client: a bounded read for -n, the pull
        // stream without it, the catalogue for -l, -c for cleanup.
        assertTrue(sensor.contains("'sensor.read'"));
        assertTrue(sensor.contains("'sensor.list'"));
        assertTrue(sensor.contains("'sensor.stream.start'"));
        assertTrue(sensor.contains("'sensor.stream.poll'"));
        assertTrue(sensor.contains("'sensor.stream.stop'"));
        assertTrue(sensor.contains("MAX_READ_SAMPLES = 10"));

        String sms = GuestTermuxCompatWriter.scriptContent("termux-sms-list", "0.2.1");
        assertTrue(sms.contains("MAX_ROWS = 50"));
        assertTrue(sms.contains("'type': 'inbox'"));

        // Every bridge call site must be anchored on a literal method name
        // or a literal method-family prefix ('mediaplayer.' + a bounded
        // sub-command) — never an argv-derived or computed name.
        java.util.regex.Pattern callSite = java.util.regex.Pattern.compile(
                "(?:bridge_call|tc\\.call)\\(\\s*");
        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            String content = GuestTermuxCompatWriter.scriptContent(command, "0.2.1");
            java.util.regex.Matcher matcher = callSite.matcher(content);
            while (matcher.find()) {
                int arg = matcher.end();
                assertTrue(command + " must call a literal bridge method",
                        arg < content.length() && content.charAt(arg) == '\'');
            }
            assertFalse(command + " must not build the envelope itself",
                    content.contains("'method':"));
            assertFalse(command + " must not build the envelope itself",
                    content.contains("\"method\":"));
        }
    }

    @Test
    public void catalogCoversExactlyTheDeclaredCommands() {
        assertEquals(Arrays.asList(GuestTermuxCompatWriter.COMMANDS),
                Arrays.asList(TermuxCommandCatalog.names().toArray(new String[0])));
        for (TermuxCommand command : TermuxCommandCatalog.all()) {
            assertTrue(TermuxCommandCatalog.names().contains(command.name()));
            assertFalse(command.docLines().isEmpty());
        }
        try {
            TermuxCommandCatalog.require("termux-not-a-command");
            fail("unknown names must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("termux-not-a-command"));
        }
        try {
            GuestTermuxCompatWriter.scriptContent("termux-not-a-command", "0.2.1");
            fail("unknown commands must be rejected");
        } catch (IllegalArgumentException expected) {
            // Rejected by the catalog lookup.
        }
    }

    @Test
    public void commandNamesAreValidated() {
        for (String bad : new String[]{"battery", "Termux-battery-status",
                "termux-Battery", "termux-../x", "termux-a.b", "termux-a b",
                "termux-", "termux--x", "termux-x-", "nusadesk-usb"}) {
            try {
                new TermuxCommand(bad, "def main(argv):\n    return 0\n",
                        Collections.singletonList("- `" + bad + "`"));
                fail("unsafe name must be rejected: " + bad);
            } catch (IllegalArgumentException expected) {
                // Rejected by the name pattern.
            }
        }
        try {
            new TermuxCommand("termux-battery-status",
                    "if __name__ == '__main__':\n    pass\n",
                    Collections.singletonList("- x"));
            fail("bodies must not carry their own entry point");
        } catch (IllegalArgumentException expected) {
            // Rejected by the body guard.
        }
    }

    @Test
    public void generatedContentIsVersionGuarded() {
        for (String bad : new String[]{null, "", "  ", "0.2.1\nrm -rf /"}) {
            try {
                GuestTermuxCompatWriter.moduleContent(bad);
                fail("module must reject version " + bad);
            } catch (IllegalArgumentException expected) {
                // Version guard.
            }
            try {
                GuestTermuxCompatWriter.docContent(bad);
                fail("doc must reject version " + bad);
            } catch (IllegalArgumentException expected) {
                // Version guard.
            }
            try {
                GuestTermuxCompatWriter.scriptContent("termux-battery-status", bad);
                fail("script must reject version " + bad);
            } catch (IllegalArgumentException expected) {
                // Version guard.
            }
        }
    }

    @Test
    public void roundTripsTermuxCommandsAgainstLoopbackBridgeListener() throws Exception {
        assumeTrue("python3 must be available for the live round-trip",
                interpreterAvailable());
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestTermuxCompatWriter.ensure(rootfs, "0.2.1");
        Path env = temporary.newFolder("env").toPath().resolve("android-bridge.env");
        Files.write(env, "placeholder".getBytes(StandardCharsets.UTF_8));
        Path harness = temporary.newFolder("harness").toPath().resolve("run_termux_compat.py");
        Files.write(harness, harnessScript().getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(
                "python3", harness.toString(),
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_BIN_RELATIVE_PATH).toString(),
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_MODULE_DIR_RELATIVE_PATH).toString(),
                env.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("Termux compatibility harness timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("Termux compatibility harness failed:\n" + output,
                0, process.exitValue());
    }

    private static String methodOf(String command) {
        String content = GuestTermuxCompatWriter.scriptContent(command, "0.2.1");
        int index = content.indexOf("call('");
        assertTrue(command + " must call a bridge method", index >= 0);
        // Exactly one literally-named call site; a second one would be a
        // second capability the script should not have.
        int second = content.indexOf("call('", index + 1);
        assertTrue(command + " must call exactly one bridge method",
                second < 0);
        int start = index + "call('".length();
        int end = content.indexOf('\'', start);
        return content.substring(start, end);
    }

    @Test
    public void sweepRemovesOnlyStaleGeneratedCommands() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestTermuxCompatWriter.ensure(rootfs, "0.2.1");
        Path bin = rootfs.resolve(GuestTermuxCompatWriter.GUEST_BIN_RELATIVE_PATH);
        Path module = rootfs.resolve(GuestTermuxCompatWriter.GUEST_MODULE_RELATIVE_PATH);

        // A file this writer once owned but no longer declares (the name
        // must not collide with a cataloged command: termux-camera-photo is
        // a real command now, so the fixture uses a retired one).
        Path stale = bin.resolve("termux-legacy-widget");
        Files.write(stale, String.join("\n",
                "#!/usr/bin/env python3",
                "# " + GuestTermuxCompatWriter.MARKER + " client: termux-legacy-widget",
                "# Generated by the NusaDesk Android app; do not edit.",
                "").getBytes(StandardCharsets.UTF_8));
        // A user file with a termux- name that this writer never owned.
        Path userFile = bin.resolve("termux-mine");
        Files.write(userFile, "#!/bin/sh\necho mine\n".getBytes(StandardCharsets.UTF_8));
        // A symlink must never be followed or deleted.
        Path linked = bin.resolve("termux-link");
        try {
            Files.createSymbolicLink(linked, userFile);
        } catch (UnsupportedOperationException | IOException e) {
            linked = null;
        }

        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestTermuxCompatWriter.ensure(rootfs, "0.2.1"));

        assertFalse("stale generated script must be swept", Files.exists(stale));
        assertTrue("user file must survive", Files.exists(userFile));
        assertTrue("shared runtime is never a sweep candidate",
                Files.isRegularFile(module));
        if (linked != null) {
            assertTrue("symlink must survive", Files.isSymbolicLink(linked));
        }
        // A second ensure with nothing stale is idempotent.
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestTermuxCompatWriter.ensure(rootfs, "0.2.1"));
    }

    @Test
    public void legacyAwarenessEnsureStillInstallsScriptsAndDoc() throws Exception {
        // Until the awareness writer delegates to GuestTermuxCompatWriter.ensure,
        // its own ensureTermuxCompat path keeps installing the cataloged
        // scripts and the doc page through the same canonical content.
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.2.1");

        for (String command : GuestTermuxCompatWriter.COMMANDS) {
            Path script = rootfs.resolve(GuestTermuxCompatWriter.guestRelativePath(command));
            assertTrue(command + " must be installed", Files.isRegularFile(script));
            assertTrue(command + " must be executable", Files.isExecutable(script));
        }
        assertTrue(Files.isRegularFile(
                rootfs.resolve(GuestTermuxCompatWriter.GUEST_DOC_RELATIVE_PATH)));
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

    /**
     * Harness that imports the shared runtime module the way the generated
     * commands do (a {@code sys.path} entry plus {@code import termux_compat}),
     * points it at a fake loopback listener, and asserts the wire method plus
     * the printed Termux JSON shape for representative responses.
     */
    private static String harnessScript() {
        return String.join("\n",
                "import datetime",
                "import io",
                "import json",
                "import os",
                "import socket",
                "import sys",
                "import threading",
                "import types",
                "",
                "BIN_DIR = sys.argv[1]",
                "LIB_DIR = sys.argv[2]",
                "ENV_PATH = sys.argv[3]",
                "ADDRESS = '127.0.0.1'",
                "TOKEN = 'test_token_0123456789ABCDEF_inv'",
                "failures = []",
                "",
                "sys.path.insert(0, LIB_DIR)",
                "import termux_compat as tc",
                "tc.ENV_FILE = ENV_PATH",
                "",
                "",
                "def load(command):",
                "    module = types.ModuleType(command.replace('-', '_'))",
                "    path = os.path.join(BIN_DIR, command)",
                "    with open(path, 'r', encoding='utf-8') as handle:",
                "        source = handle.read()",
                "    exec(compile(source, path, 'exec'), module.__dict__)",
                "    return module",
                "",
                "",
                "def write_env(port):",
                "    with open(ENV_PATH, 'w', encoding='ascii') as handle:",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS=' + ADDRESS + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_PORT=' + str(port) + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_TOKEN=' + TOKEN + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_PROTOCOL=1\\n')",
                "",
                "",
                "def capture(module, command, argv):",
                "    out, err = io.StringIO(), io.StringIO()",
                "    old_out, old_err = sys.stdout, sys.stderr",
                "    sys.stdout, sys.stderr = out, err",
                "    try:",
                "        rc = tc.run(command, lambda a: module.main(argv))",
                "    finally:",
                "        sys.stdout, sys.stderr = old_out, old_err",
                "    return rc, out.getvalue().strip(), err.getvalue()",
                "",
                "",
                "def run_case(command, argv, response, expected_method, check,",
                "             expected_calls=1, allow_multiline=False, expect_rc=0,",
                "             expected_params=None):",
                "    module = load(command)",
                "    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "    server.bind((ADDRESS, 0))",
                "    server.listen(4)",
                "    server.settimeout(20)",
                "    write_env(server.getsockname()[1])",
                "    payload = json.dumps(response, separators=(',', ':'))",
                "    received = {}",
                "    calls = []",
                "",
                "    def handler():",
                "        try:",
                "            for _ in range(expected_calls):",
                "                client, _ = server.accept()",
                "                try:",
                "                    raw = bytearray()",
                "                    while True:",
                "                        chunk = client.recv(4096)",
                "                        if not chunk:",
                "                            break",
                "                        raw.extend(chunk)",
                "                        if 10 in raw:",
                "                            break",
                "                    request = json.loads(raw.split(bytes([10]), 1)[0].decode('ascii'))",
                "                    received.update(request)",
                "                    calls.append(request)",
                "                    client.sendall((payload + '\\n').encode('ascii'))",
                "                finally:",
                "                    client.close()",
                "        except OSError:",
                "            pass",
                "        finally:",
                "            server.close()",
                "",
                "    thread = threading.Thread(target=handler)",
                "    thread.daemon = True",
                "    thread.start()",
                "    rc, captured, err = capture(module, command, argv)",
                "    thread.join(10)",
                "    name = command + ' ' + ' '.join(argv)",
                "    if rc != expect_rc:",
                "        failures.append(name + ': rc ' + str(rc) + ' expected '",
                "                        + str(expect_rc) + ' stderr=' + err)",
                "    if expect_rc != 0:",
                "        if captured.strip():",
                "            failures.append(name + ': expected no stdout'",
                "                            + ' but got ' + repr(captured))",
                "        return",
                "    if len(calls) != expected_calls:",
                "        failures.append(name + ': calls ' + str(len(calls))",
                "                        + ' expected ' + str(expected_calls))",
                "    if received.get('method') != expected_method:",
                "        failures.append(name + ': method ' + str(received.get('method')))",
                "    if received.get('v') != 1 or received.get('token') != TOKEN:",
                "        failures.append(name + ': bad envelope')",
                "    if expected_params is None:",
                "        for request in calls:",
                "            if 'params' in request:",
                "                failures.append(name",
                "                                + ': params sent for a param-free method')",
                "    else:",
                "        for request in calls:",
                "            if request.get('params') != expected_params:",
                "                failures.append(name + ': params '",
                "                                + repr(request.get('params'))",
                "                                + ' expected ' + repr(expected_params))",
                "    documents = []",
                "    for line in captured.splitlines():",
                "        if not line.strip():",
                "            continue",
                "        try:",
                "            documents.append(json.loads(line))",
                "        except ValueError:",
                "            failures.append(name + ': stdout line is not JSON: ' + repr(line))",
                "            return",
                "    if allow_multiline:",
                "        check(name, documents)",
                "    else:",
                "        if len(documents) != 1:",
                "            failures.append(name + ': expected one document, got '",
                "                            + str(len(documents)))",
                "            return",
                "        check(name, documents[0])",
                "",
                "",
                "def expect(condition, message):",
                "    if not condition:",
                "        failures.append(message)",
                "",
                "",
                "run_case('termux-battery-status', [],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True,",
                "          'capacity_percent': 87, 'status': 'charging', 'health': 'good',",
                "          'plugged': 'usb', 'temperature_deci_celsius': 312,",
                "          'voltage_microvolts': 4200000, 'current_microamps': 1234000},",
                "         'battery.status',",
                "         lambda n, r: (expect(r.get('percentage') == 87, n + ': percentage'),",
                "                       expect(r.get('status') == 'CHARGING', n + ': status'),",
                "                       expect(r.get('health') == 'GOOD', n + ': health'),",
                "                       expect(r.get('plugged') == 'PLUGGED_USB', n + ': plugged'),",
                "                       expect(abs(r.get('temperature', 0) - 31.2) < 0.01,",
                "                              n + ': temperature'),",
                "                       expect(r.get('voltage') == 4200, n + ': voltage'),",
                "                       expect(r.get('current') == 1234000, n + ': current')))",
                "",
                "run_case('termux-location', ['-p', 'network'],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True,",
                "          'latitude': -6.2, 'longitude': 106.8, 'accuracy_meters': 12.5,",
                "          'provider': 'network', 'timestamp_utc_ms': 1},",
                "         'location.get',",
                "         lambda n, r: (expect(r.get('provider') == 'network', n + ': provider'),",
                "                       expect(r.get('latitude') == -6.2, n + ': latitude'),",
                "                       expect(r.get('accuracy') == 12.5, n + ': accuracy')),",
                "         expected_params={'request': 'once', 'provider': 'network'})",
                "",
                "# -n takes the bounded read: one sensor.read call whose samples_json",
                "# array becomes one Termux document per sample.",
                "run_case('termux-sensor', ['-s', 'gyroscope', '-n', '2'],",
                "         {'v': 1, 'id': 's', 'ok': True,",
                "          'samples_json': '[{\"GYROSCOPE\": {\"values\": [0.1, 0.2, 0.3]}},'",
                "                          + ' {\"GYROSCOPE\": {\"values\": [0.1, 0.2, 0.3]}}]'},",
                "         'sensor.read',",
                "         lambda n, docs: expect(",
                "             docs == [{'GYROSCOPE': {'values': [0.1, 0.2, 0.3]}},",
                "                      {'GYROSCOPE': {'values': [0.1, 0.2, 0.3]}}],",
                "             n + ': per-sample documents'),",
                "         expected_calls=1, allow_multiline=True,",
                "         expected_params={'delay_ms': 1000, 'sensor': 'gyroscope',",
                "                          'count': 2})",
                "",
                "# A typed bridge error on the bounded read exits 1.",
                "run_case('termux-sensor', ['-s', 'accelerometer', '-n', '1'],",
                "         {'v': 1, 'id': 's', 'ok': False, 'error': 'sensor-unavailable'},",
                "         'sensor.read',",
                "         lambda n, r: expect(False, n + ': unavailable must fail'),",
                "         expect_rc=1)",
                "",
                "# Without -n the command drives the pull stream:",
                "# start -> poll (sample) -> poll (stopped) -> stop.",
                "module = load('termux-sensor')",
                "server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "server.bind((ADDRESS, 0))",
                "server.listen(4)",
                "write_env(server.getsockname()[1])",
                "stream_requests = []",
                "",
                "",
                "def stream_handler():",
                "    try:",
                "        for _ in range(4):",
                "            client, _ = server.accept()",
                "            try:",
                "                raw = bytearray()",
                "                while True:",
                "                    chunk = client.recv(4096)",
                "                    if not chunk:",
                "                        break",
                "                    raw.extend(chunk)",
                "                    if 10 in raw:",
                "                        break",
                "                request = json.loads(",
                "                    raw.split(bytes([10]), 1)[0].decode('ascii'))",
                "                stream_requests.append(request)",
                "                method = request.get('method')",
                "                polls = sum(1 for r in stream_requests",
                "                            if r.get('method') == 'sensor.stream.poll')",
                "                if method == 'sensor.stream.start':",
                "                    body = {'v': 1, 'id': request.get('id'), 'ok': True,",
                "                            'stream_started': True}",
                "                elif method == 'sensor.stream.poll' and polls == 1:",
                "                    body = {'v': 1, 'id': request.get('id'), 'ok': True,",
                "                            'sample_json':",
                "                            '{\"ACCELEROMETER\":{\"values\":[0.1,0.2,0.3]}}'}",
                "                else:",
                "                    body = {'v': 1, 'id': request.get('id'), 'ok': True,",
                "                            'stream_stopped': True}",
                "                client.sendall(json.dumps(body).encode('ascii')",
                "                               + bytes([10]))",
                "            finally:",
                "                client.close()",
                "    except OSError:",
                "        pass",
                "    finally:",
                "        server.close()",
                "",
                "",
                "thread = threading.Thread(target=stream_handler)",
                "thread.daemon = True",
                "thread.start()",
                "rc, captured, err = capture(module, 'termux-sensor',",
                "                          ['-s', 'accelerometer'])",
                "thread.join(10)",
                "stream_methods = [r.get('method') for r in stream_requests]",
                "expect(rc == 0, 'stream: rc ' + str(rc) + ' stderr=' + err)",
                "expect(stream_methods == ['sensor.stream.start', 'sensor.stream.poll',",
                "                          'sensor.stream.poll', 'sensor.stream.stop'],",
                "       'stream: call order ' + repr(stream_methods))",
                "if stream_requests:",
                "    expect(stream_requests[0].get('params')",
                "           == {'delay_ms': 1000, 'sensor': 'accelerometer'},",
                "           'stream: start params '",
                "           + repr(stream_requests[0].get('params')))",
                "if len(stream_requests) > 1:",
                "    expect(stream_requests[1].get('params') == {'timeout_ms': 2000},",
                "           'stream: poll params '",
                "           + repr(stream_requests[1].get('params')))",
                "stream_docs = []",
                "for line in captured.splitlines():",
                "    if line.strip():",
                "        stream_docs.append(json.loads(line))",
                "expect(stream_docs == [{'ACCELEROMETER': {'values': [0.1, 0.2, 0.3]}}],",
                "       'stream: documents ' + repr(stream_docs))",
                "",
                "run_case('termux-contact-list', [],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True, 'count': 1,",
                "          'rows': '[{\"name\":\"Ana\",\"number_1\":\"111\",\"number_2\":\"222\"}]'},",
                "         'contacts.list',",
                "         lambda n, r: expect(r == [{'name': 'Ana', 'number': '111'},",
                "                                    {'name': 'Ana', 'number': '222'}],",
                "                              n + ': rows'))",
                "",
                "run_case('termux-contact-list', [],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True, 'count': 1,",
                "          'rows': '[{\"name\":\"\",\"number_1\":\"999\"}]'},",
                "         'contacts.list',",
                "         lambda n, r: expect(r == [{'name': '', 'number': '999'}],",
                "                              n + ': nameless contact keeps its number'))",
                "",
                "run_case('termux-sms-list', ['-l', '1'],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True, 'count': 1,",
                "          'rows': '[{\"address\":\"+62812\",\"timestamp_utc_ms\":1760003600000,'",
                "                   + '\"read\":true,\"snippet\":\"halo\"}]'},",
                "         'sms.inbox',",
                "         lambda n, r: expect(r == [{'type': 'inbox', 'read': True,",
                "                                    'address': '+62812', 'number': '+62812',",
                "                                    'body': 'halo',",
                "                                    'received': datetime.datetime.fromtimestamp(",
                "                                        1760003600).strftime('%Y-%m-%d %H:%M:%S')}],",
                "                              n + ': rows ' + repr(r)))",
                "",
                "run_case('termux-telephony-deviceinfo', [],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True,",
                "          'phone_type': 'GSM', 'sim_state': 'READY'},",
                "         'telephony.info',",
                "         lambda n, r: (expect(r.get('phone_type') == 'GSM', n + ': phone_type'),",
                "                       expect(r.get('sim_state') == 'READY', n + ': sim_state')))",
                "",
                "run_case('termux-telephony-cellinfo', [],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True, 'count': 1,",
                "          'rows': '[{\"technology\":\"LTE\",\"signal_dbm\":-95,\"signal_level\":3}]'},",
                "         'telephony.cellinfo',",
                "         lambda n, r: expect(r == [{'type': 'lte', 'dbm': -95, 'level': 3}],",
                "                              n + ': rows'))",
                "",
                "# bridge_call sends params only when they are provided.",
                "param_requests = []",
                "server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "server.bind((ADDRESS, 0))",
                "server.listen(2)",
                "write_env(server.getsockname()[1])",
                "",
                "",
                "def param_handler():",
                "    try:",
                "        for _ in range(2):",
                "            client, _ = server.accept()",
                "            try:",
                "                raw = bytearray()",
                "                while True:",
                "                    chunk = client.recv(4096)",
                "                    if not chunk:",
                "                        break",
                "                    raw.extend(chunk)",
                "                    if 10 in raw:",
                "                        break",
                "                param_requests.append(json.loads(",
                "                    raw.split(bytes([10]), 1)[0].decode('ascii')))",
                "                client.sendall(json.dumps(",
                "                    {'v': 1, 'id': 's', 'ok': True}).encode('ascii')",
                "                    + bytes([10]))",
                "            finally:",
                "                client.close()",
                "    finally:",
                "        server.close()",
                "",
                "",
                "thread = threading.Thread(target=param_handler)",
                "thread.daemon = True",
                "thread.start()",
                "tc.bridge_call('bridge.info')",
                "tc.bridge_call('calendar.insert', {'title': 'x', 'begin_ms': '1'})",
                "thread.join(10)",
                "expect(len(param_requests) == 2,",
                "       'params: requests ' + str(len(param_requests)))",
                "if len(param_requests) == 2:",
                "    expect('params' not in param_requests[0],",
                "           'params: param-free call must not send params')",
                "    expect(param_requests[1].get('params')",
                "           == {'title': 'x', 'begin_ms': '1'},",
                "           'params: params object not forwarded')",
                "",
                "# Typed bridge errors are exit code 1 with the error on stderr.",
                "module = load('termux-battery-status')",
                "server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "server.bind((ADDRESS, 0))",
                "server.listen(2)",
                "write_env(server.getsockname()[1])",
                "",
                "",
                "def error_handler():",
                "    client, _ = server.accept()",
                "    try:",
                "        client.recv(4096)",
                "        body = json.dumps({'v': 1, 'id': 's', 'ok': False,",
                "                           'error': 'capability-unavailable'})",
                "        client.sendall((body + '\\n').encode('ascii'))",
                "    finally:",
                "        client.close()",
                "        server.close()",
                "",
                "",
                "thread = threading.Thread(target=error_handler)",
                "thread.start()",
                "rc, captured, err = capture(module, 'termux-battery-status', [])",
                "thread.join(10)",
                "expect(rc == 1, 'typed-error: rc ' + str(rc))",
                "expect(captured == '', 'typed-error: unexpected stdout ' + repr(captured))",
                "expect('capability-unavailable' in err, 'typed-error: stderr ' + repr(err))",
                "",
                "# A malformed payload is a protocol fault: exit 2, no traceback.",
                "module = load('termux-sms-list')",
                "server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "server.bind((ADDRESS, 0))",
                "server.listen(2)",
                "write_env(server.getsockname()[1])",
                "",
                "",
                "def malformed_handler():",
                "    client, _ = server.accept()",
                "    try:",
                "        client.recv(4096)",
                "        body = json.dumps({'v': 1, 'id': 's', 'ok': True,",
                "                           'available': True, 'rows': 42})",
                "        client.sendall((body + '\\n').encode('ascii'))",
                "    finally:",
                "        client.close()",
                "        server.close()",
                "",
                "",
                "thread = threading.Thread(target=malformed_handler)",
                "thread.daemon = True",
                "thread.start()",
                "rc, captured, err = capture(module, 'termux-sms-list', [])",
                "thread.join(10)",
                "expect(rc == 2, 'malformed-payload: rc ' + str(rc))",
                "expect(captured == '', 'malformed-payload: stdout ' + repr(captured))",
                "expect('Traceback' not in err, 'malformed-payload: traceback leaked')",
                "expect('malformed bridge response' in err,",
                "       'malformed-payload: stderr ' + repr(err))",
                "",
                "# A truncated bridge reading warns on stderr but still returns rows.",
                "run_case('termux-sms-list', [],",
                "         {'v': 1, 'id': 's', 'ok': True, 'available': True, 'count': 2,",
                "          'truncated': True, 'rows': '[{\"address\":\"+62\",\"read\":true}'",
                "                                      + ',{\"address\":\"+63\",\"read\":true}]'},",
                "         'sms.inbox',",
                "         lambda n, r: expect(len(r) == 2, n + ': rows'),",
                "         expect_rc=0)",
                "",
                "# The truncated warning must reach stderr (run_case ignores stderr,",
                "# so assert it here explicitly).",
                "module = load('termux-telephony-cellinfo')",
                "server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "server.bind((ADDRESS, 0))",
                "server.listen(2)",
                "write_env(server.getsockname()[1])",
                "",
                "",
                "def truncated_handler():",
                "    client, _ = server.accept()",
                "    try:",
                "        client.recv(4096)",
                "        body = json.dumps({'v': 1, 'id': 's', 'ok': True, 'available': True,",
                "                           'truncated': True, 'rows': '[]'})",
                "        client.sendall((body + '\\n').encode('ascii'))",
                "    finally:",
                "        client.close()",
                "        server.close()",
                "",
                "",
                "thread = threading.Thread(target=truncated_handler)",
                "thread.daemon = True",
                "thread.start()",
                "rc, captured, err = capture(module, 'termux-telephony-cellinfo', [])",
                "thread.join(10)",
                "expect(rc == 0, 'truncated-warning: rc ' + str(rc))",
                "expect('truncated' in err, 'truncated-warning: stderr ' + repr(err))",
                "",
                "# Env-file contract: missing file, non-loopback address, short token.",
                "os.remove(ENV_PATH)",
                "module = load('termux-battery-status')",
                "rc, captured, err = capture(module, 'termux-battery-status', [])",
                "expect(rc == 2, 'missing-env: rc ' + str(rc))",
                "expect(captured == '', 'missing-env: stdout ' + repr(captured))",
                "expect('session env' in err, 'missing-env: stderr ' + repr(err))",
                "",
                "with open(ENV_PATH, 'w', encoding='ascii') as handle:",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS=10.0.0.1\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_PORT=1\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_TOKEN=' + TOKEN + '\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_PROTOCOL=1\\n')",
                "rc, captured, err = capture(module, 'termux-battery-status', [])",
                "expect(rc == 2, 'non-loopback: rc ' + str(rc))",
                "expect('loopback' in err, 'non-loopback: stderr ' + repr(err))",
                "",
                "with open(ENV_PATH, 'w', encoding='ascii') as handle:",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS=127.0.0.1\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_PORT=1\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_TOKEN=short\\n')",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_PROTOCOL=1\\n')",
                "rc, captured, err = capture(module, 'termux-battery-status', [])",
                "expect(rc == 2, 'short-token: rc ' + str(rc))",
                "expect('token' in err, 'short-token: stderr ' + repr(err))",
                "",
                "# Unsupported flags fail closed with usage (exit code 2).",
                "for command, argv in [('termux-battery-status', ['-x']),",
                "                      ('termux-location', ['-p', 'wifi']),",
                "                      ('termux-sensor', ['-z']),",
                "                      ('termux-sms-list', ['-l', 'abc']),",
                "                      ('termux-telephony-cellinfo', ['extra'])]:",
                "    module = load(command)",
                "    rc, captured, err = capture(module, command, argv)",
                "    expect(rc == 2, command + ': usage rc ' + str(rc))",
                "    expect(captured == '', command + ': usage stdout ' + repr(captured))",
                "    expect('usage' in err.lower() or 'unsupported' in err.lower()",
                "           or 'must be' in err.lower(),",
                "           command + ': usage stderr ' + repr(err))",
                "",
                "os.remove(ENV_PATH)",
                "",
                "if failures:",
                "    print('FAILURES:')",
                "    for failure in failures:",
                "        print('  ' + failure)",
                "    sys.exit(1)",
                "print('termux-compat-roundtrip-ok')",
                "sys.exit(0)",
                "") + "\n";
    }
}
