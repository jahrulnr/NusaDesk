package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Focused tests for the generated {@code nusadesk-usb} CLI: the locked
 * {@code usb.list} / {@code usb.open} protocol markers, executable
 * installation through the shared ensure path, and a live round-trip over
 * a fake loopback bridge plus a fake abstract-socket {@code SCM_RIGHTS}
 * sender (skipped when python3 is not on PATH).
 */
public class GuestUsbCliWriterTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsExecutableCliThroughSharedEnsurePath() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));

        Path cli = rootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH);
        assertTrue("CLI must exist", Files.isRegularFile(cli));
        assertTrue("CLI must be executable", Files.isExecutable(cli));
        String content = new String(Files.readAllBytes(cli), StandardCharsets.UTF_8);
        assertEquals("same canonical text as the writer advertises",
                GuestUsbCliWriter.scriptContent("0.1.0"), content);
        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));

        // Idempotent like the rest of the bundle.
        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));
        // A stale copy is replaced on version change.
        Files.write(cli, "stale".getBytes(StandardCharsets.UTF_8));
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.2.0"));
        assertTrue(new String(Files.readAllBytes(cli), StandardCharsets.UTF_8)
                .contains("App version: 0.2.0"));
    }

    @Test
    public void scriptPinsTheLockedUsbProtocol() {
        String content = GuestUsbCliWriter.scriptContent("0.1.0");

        // Same session transport as nusadesk-android: same env file, same
        // envelope, same bounds.
        assertTrue(content.contains(
                "ENV_FILE = '/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("PROTOCOL_VERSION = 1"));
        assertTrue(content.contains("MAX_RESPONSE_BYTES = 16384"));
        assertTrue(content.contains("secrets.token_urlsafe(8)"));

        // The three subcommands and only the two bridge methods.
        assertTrue(content.contains("usage: nusadesk-usb list"));
        assertTrue(content.contains("probe <vendorId>:<productId>"));
        assertTrue(content.contains(
                "exec <vendorId>:<productId> -- <command> [args...]"));
        assertTrue(content.contains("call('usb.list'"));
        assertTrue(content.contains("call('usb.open'"));
        assertTrue(content.contains("'vendorId': vendor_id"));
        assertTrue(content.contains("'productId': product_id"));
        assertTrue(content.contains("'socket': name"));

        // Abstract unix listener and the SCM_RIGHTS fd receipt.
        assertTrue(content.contains("SOCKET_PREFIX = 'nu-usb-'"));
        assertTrue(content.contains("listener.bind('\\0' + name)"));
        assertTrue(content.contains("LISTEN_TIMEOUT_SECONDS = 60.0"));
        assertTrue(content.contains("recvmsg"));
        assertTrue(content.contains("socket.CMSG_SPACE("));
        assertTrue(content.contains("SCM_RIGHTS"));
        assertTrue(content.contains("array.array('i')"));

        // The proof ioctl and the descriptor parse.
        assertTrue(content.contains("USBDEVFS_CONTROL = 0xC0185500"));
        assertTrue(content.contains("ctypes.CDLL(None, use_errno=True)"));
        assertTrue(content.contains("ctypes.byref(transfer)"));
        assertTrue(content.contains("create_string_buffer("));
        assertTrue(content.contains("probe ok: idVendor="));

        // exec hands the fd to the child, never through a shell.
        assertTrue(content.contains("NUSADESK_USB_FD"));
        assertTrue(content.contains("pass_fds=(fd,)"));
        assertTrue(content.contains("{**os.environ, FD_ENV: str(fd)}"));

        String lower = content.toLowerCase(java.util.Locale.ROOT);
        assertFalse("no shell interpolation", lower.contains("shell=true"));
        assertFalse("no os.system", lower.contains("os.system"));
        assertFalse("no os.popen", lower.contains("os.popen"));
        assertFalse("no eval(", lower.contains("eval("));
        assertFalse("no sh -c", lower.contains("sh -c"));
        assertFalse("token is never printed", content.contains("print(token"));
    }

    @Test
    public void cliRoundTripsAgainstFakeBridgeAndScmRightsSender() throws Exception {
        assumeTrue("python3 must be available for the live USB CLI round-trip",
                interpreterAvailable());
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
        Path cli = rootfs.resolve(GuestUsbCliWriter.GUEST_CLI_RELATIVE_PATH);
        Path env = temporary.newFolder("env").toPath().resolve("android-bridge.env");
        Files.write(env, "placeholder".getBytes(StandardCharsets.UTF_8));
        Path harness = temporary.newFolder("harness").toPath().resolve("run_usb_cli.py");
        Files.write(harness, harnessScript().getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(
                "python3", harness.toString(), cli.toString(), env.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("USB CLI harness timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("USB CLI harness failed:\n" + output,
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

    /**
     * A bounded Python harness that loads the generated CLI module, runs it
     * against a fake loopback bridge listener, and acts as the app half of
     * {@code usb.open}: it connects back to the guest's abstract unix
     * listener and passes a real fd with {@code SCM_RIGHTS}. Validates the
     * list table, the fd hand-off into {@code NUSADESK_USB_FD}, the clean
     * {@code USBDEVFS_CONTROL} failure on a non-usbfs fd, and typed-error
     * surfacing.
     */
    private static String harnessScript() {
        return String.join("\n",
                "import array",
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
                "ADDRESS = '127.0.0.1'",
                "TOKEN = 'test_token_0123456789ABCDEF_usb'",
                "failures = []",
                "",
                "",
                "def load_cli():",
                "    # The generated CLI is a shebang script without a .py",
                "    # suffix; exec the source into a plain module instead.",
                "    module = types.ModuleType('nusadesk_usb_cli')",
                "    module.__file__ = CLI_PATH",
                "    with open(CLI_PATH, 'r', encoding='utf-8') as handle:",
                "        source = handle.read()",
                "    exec(compile(source, CLI_PATH, 'exec'), module.__dict__)",
                "    module.ENV_FILE = ENV_PATH",
                "    return module",
                "",
                "",
                "def write_env(port):",
                "    with open(ENV_PATH, 'w', encoding='ascii') as handle:",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS='",
                "                     + ADDRESS + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_PORT='",
                "                     + str(port) + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_TOKEN='",
                "                     + TOKEN + '\\n')",
                "        handle.write('NUSADESK_ANDROID_BRIDGE_PROTOCOL=1\\n')",
                "",
                "",
                "def capture(module, argv):",
                "    out, err = io.StringIO(), io.StringIO()",
                "    old_out, old_err = sys.stdout, sys.stderr",
                "    sys.stdout, sys.stderr = out, err",
                "    try:",
                "        rc = module.main(argv)",
                "    finally:",
                "        sys.stdout, sys.stderr = old_out, old_err",
                "    return rc, out.getvalue(), err.getvalue()",
                "",
                "",
                "def read_request(client):",
                "    raw = bytearray()",
                "    while True:",
                "        chunk = client.recv(4096)",
                "        if not chunk:",
                "            break",
                "        raw.extend(chunk)",
                "        if 10 in raw:",
                "            break",
                "    return json.loads(raw.split(bytes([10]), 1)[0].decode('ascii'))",
                "",
                "",
                "def serve(module, argv, answer):",
                "    received = {}",
                "    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)",
                "    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)",
                "    server.bind((ADDRESS, 0))",
                "    server.listen(2)",
                "    server.settimeout(20)",
                "    write_env(server.getsockname()[1])",
                "",
                "    def handler():",
                "        try:",
                "            client, _ = server.accept()",
                "            try:",
                "                received.update(read_request(client))",
                "                payload = answer(received) + '\\n'",
                "                client.sendall(payload.encode('ascii'))",
                "            finally:",
                "                client.close()",
                "        except OSError:",
                "            pass",
                "        finally:",
                "            server.close()",
                "",
                "    thread = threading.Thread(target=handler)",
                "    thread.daemon = True",
                "    thread.start()",
                "    rc, out, err = capture(module, argv)",
                "    thread.join(10)",
                "    return rc, out, err, received",
                "",
                "",
                "def expect(condition, message):",
                "    if not condition:",
                "        failures.append(message)",
                "",
                "",
                "def open_answer(send_fd, payload):",
                "    # The fake app half of usb.open: connect back to the guest's",
                "    # abstract listener and pass a real fd with SCM_RIGHTS",
                "    # before answering the bridge request.",
                "    def answer(request):",
                "        if send_fd:",
                "            params = request.get('params') or {}",
                "            device_fd = os.open('/dev/null', os.O_RDONLY)",
                "            back = socket.socket(socket.AF_UNIX,",
                "                                 socket.SOCK_STREAM)",
                "            try:",
                "                back.connect('\\0' + params['socket'])",
                "                back.sendmsg(",
                "                    [b'f'],",
                "                    [(socket.SOL_SOCKET, socket.SCM_RIGHTS,",
                "                      array.array('i', [device_fd]))])",
                "            finally:",
                "                back.close()",
                "                os.close(device_fd)",
                "        return json.dumps(payload(request),",
                "                          separators=(',', ':'))",
                "    return answer",
                "",
                "",
                "def ok_open(request):",
                "    params = request.get('params') or {}",
                "    return {'v': 1, 'id': 's', 'ok': True, 'opened': True,",
                "            'vendorId': params.get('vendorId'),",
                "            'productId': params.get('productId')}",
                "",
                "",
                "def expect_open_envelope(received, name):",
                "    params = received.get('params') or {}",
                "    expect(received.get('method') == 'usb.open',",
                "           name + ': method ' + str(received.get('method')))",
                "    expect(params.get('vendorId') == 1",
                "           and params.get('productId') == 2,",
                "           name + ': params ' + repr(params))",
                "    socket_name = str(params.get('socket'))",
                "    expect(socket_name.startswith('nu-usb-')",
                "           and len(socket_name) == 19,",
                "           name + ': socket ' + socket_name)",
                "    expect(received.get('v') == 1",
                "           and received.get('token') == TOKEN,",
                "           name + ': bad envelope')",
                "",
                "",
                "module = load_cli()",
                "",
                "# list: fixed method, one table row per device, count line.",
                "DEVICES = json.dumps([",
                "    {'vendorId': 1256, 'productId': 26720, 'name': 'Phone',",
                "     'manufacturer': 'ACME', 'product': 'Gadget'},",
                "    {'vendorId': 7531, 'productId': 2, 'name': 'Hub',",
                "     'manufacturer': None, 'product': None}],",
                "    separators=(',', ':'))",
                "rc, out, err, received = serve(",
                "    module, ['list'],",
                "    lambda r: json.dumps({'v': 1, 'id': 's', 'ok': True,",
                "                          'count': 2, 'devices': DEVICES},",
                "                         separators=(',', ':')))",
                "expect(rc == 0, 'list: rc ' + str(rc) + ' stderr=' + err)",
                "expect(received.get('method') == 'usb.list',",
                "       'list: method ' + str(received.get('method')))",
                "expect('params' not in received, 'list: params sent')",
                "expect(out.strip().splitlines()",
                "       == ['04e8:6860  Phone  ACME  Gadget',",
                "           '1d6b:0002  Hub', 'count: 2'],",
                "       'list: output ' + repr(out))",
                "expect(TOKEN not in out + err, 'list: token leaked')",
                "",
                "# exec: the received fd reaches the child through",
                "# NUSADESK_USB_FD (fstat of it must succeed).",
                "rc, out, err, received = serve(",
                "    module,",
                "    ['exec', '0001:0002', '--', 'python3', '-c',",
                "     'import os; os.fstat(int(os.environ[\"NUSADESK_USB_FD\"]))'],",
                "    open_answer(True, ok_open))",
                "expect(rc == 0, 'exec: rc ' + str(rc) + ' stderr=' + err)",
                "expect_open_envelope(received, 'exec')",
                "",
                "# probe: the fd is real but not a usbfs device, so the locked",
                "# USBDEVFS_CONTROL ioctl fails cleanly on stderr with rc 1.",
                "rc, out, err, received = serve(",
                "    module, ['probe', '0001:0002'], open_answer(True, ok_open))",
                "expect(rc == 1, 'probe: rc ' + str(rc))",
                "expect('USBDEVFS_CONTROL failed' in err,",
                "       'probe: stderr ' + repr(err))",
                "expect_open_envelope(received, 'probe')",
                "",
                "# typed error: surfaced verbatim on stderr with rc 1.",
                "rc, out, err, received = serve(",
                "    module, ['probe', '0001:0002'],",
                "    open_answer(False,",
                "                lambda r: {'v': 1, 'id': 's', 'ok': False,",
                "                           'error': 'usb-permission-denied'}))",
                "expect(rc == 1, 'denied: rc ' + str(rc))",
                "expect('usb-permission-denied' in err,",
                "       'denied: stderr ' + repr(err))",
                "",
                "for failure in failures:",
                "    print(failure)",
                "sys.exit(1 if failures else 0)",
                "");
    }
}
