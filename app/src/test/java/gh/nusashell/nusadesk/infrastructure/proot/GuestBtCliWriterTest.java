package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Focused tests for the generated {@code nusadesk-bt} CLI (ADR-0050): the
 * locked {@code bt.*} method/verb contract, a {@code py_compile} syntax
 * check, and a live round-trip over the real generated
 * {@code termux_compat} runtime module against a fake loopback bridge
 * (skipped when python3 is not on PATH).
 */
public class GuestBtCliWriterTest {

    private static final Pattern METHOD_PATTERN =
            Pattern.compile("\"method\":\"([^\"]+)\"");
    private static final String TOKEN = "test_token_0123456789ABCDEF_bt";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void scriptPinsTheBluetoothContract() {
        String content = GuestBtCliWriter.scriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));
        assertTrue(content.contains(
                "'/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("'/usr/local/lib/nusadesk'"));
        assertTrue(content.contains("NUSADESK_BT_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_BT_MODULE_DIR"));

        // Every documented verb appears in the usage line.
        for (String needle : new String[] {
                "usage: nusadesk-bt status | devices | enable",
                "pair <address> | unpair <address>",
                "discover [--seconds N]",
                "discoverable [--seconds N]",
                "le-scan [--seconds N] [--uuid U] [--name-prefix P]",
                "advertise start [--uuid U] [--name NAME]",
                " | advertise stop'",
                "gatt connect <address> | gatt services",
                "gatt read <service-uuid> <char-uuid>",
                "gatt write <service-uuid> <char-uuid>",
                "gatt notify start|stop <service-uuid> <char-uuid>",
                "gatt poll | gatt disconnect",
                "server start [--name NAME] | server stop",
                "server notify (--hex H|--text T)",
                "rfcomm listen [--uuid U] [--name N] [--seconds N]",
                "rfcomm accept",
                "rfcomm connect <address> [--uuid U] [--seconds N]",
                "rfcomm read [--seconds N]",
                "rfcomm write (--hex H|--text T) | rfcomm close",
        }) {
            assertTrue("missing usage fragment: " + needle,
                    content.contains(needle));
        }

        // The fixed bt.* method literals, and only those.
        for (String needle : new String[] {
                "'bt.status'", "'bt.devices'", "'bt.enable.request'",
                "'bt.' + verb", "'bt.discoverable.request'",
                "'bt.discover'", "'bt.le.scan'",
                "'bt.le.advertise.start'", "'bt.le.advertise.stop'",
                "'bt.gatt.connect'", "'bt.gatt.services'",
                "'bt.gatt.read'", "'bt.gatt.write'",
                "'bt.gatt.notify.' + pos[1]", "'bt.gatt.notify.poll'",
                "'bt.gatt.disconnect'",
                "'bt.gatt.server.start'", "'bt.gatt.server.stop'",
                "'bt.gatt.server.notify'",
                "'bt.rfcomm.listen'", "'bt.rfcomm.accept'",
                "'bt.rfcomm.connect'", "'bt.rfcomm.read'",
                "'bt.rfcomm.write'", "'bt.rfcomm.close'",
                "prefix + '.start'", "prefix + '.poll'", "prefix + '.stop'",
        }) {
            assertTrue("missing method fragment: " + needle,
                    content.contains(needle));
        }

        // The bridge param spellings pinned by the module contract.
        for (String needle : new String[] {
                "'service_uuid'", "'char_uuid'", "'name_prefix'",
                "'timeout_ms'", "'value_hex'", "'value_utf8'",
                "'seconds'", "'address'", "'name'",
        }) {
            assertTrue("missing param: " + needle, content.contains(needle));
        }

        // Exit codes and honest failure wording.
        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));
        assertTrue(content.contains("is not reachable"));
        assertTrue(content.contains("is not installed"));
        assertTrue(content.contains("malformed bridge response"));
        assertTrue(content.contains("bluetooth state unknown"));

        // Transport lives in the shared termux_compat module, lazily.
        assertTrue(content.contains("sys.path.insert(0, MODULE_DIR)"));
        assertTrue(content.contains("import termux_compat"));
        assertTrue(content.contains("termux_compat.ENV_FILE = ENV_FILE"));

        // Stdlib only: the four imports and nothing else.
        String[] imports = content
                .replaceAll("(?m)^#.*$", "")
                .split("\\n");
        List<String> seen = new ArrayList<>();
        for (String line : imports) {
            if (line.startsWith("import ")) {
                seen.add(line.trim());
            }
        }
        assertEquals("[import json, import os, import sys, import time]",
                seen.toString());

        String lower = content.toLowerCase(Locale.ROOT);
        assertFalse("no socket code of its own", lower.contains("import socket"));
        assertFalse("no subprocess", lower.contains("import subprocess"));
        assertFalse("no ctypes", lower.contains("import ctypes"));
        assertFalse("no urllib", lower.contains("import urllib"));
        assertFalse("no os.system", lower.contains("os.system"));
        assertFalse("no os.popen", lower.contains("os.popen"));
        assertFalse("no eval(", lower.contains("eval("));
        assertFalse("no shell interpolation", lower.contains("shell="));
        // The token never leaves the shared runtime module: no code path
        // in this script may read or print it (comments stripped first).
        String codeOnly = lower.replaceAll("(?m)#.*$", "");
        assertFalse("no token handling at all", codeOnly.contains("token"));
    }

    @Test
    public void scriptCompilesWithPython3() throws Exception {
        assumeTrue("python3 must be available for py_compile",
                interpreterAvailable());
        Path dir = temporary.newFolder("compile").toPath();
        Path cli = dir.resolve("nusadesk-bt");
        Files.write(cli, GuestBtCliWriter.scriptContent("0.1.0")
                .getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder(
                "python3", "-m", "py_compile", cli.toString())
                .redirectErrorStream(true).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("py_compile timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("py_compile failed:\n" + output, 0,
                process.exitValue());
    }

    @Test
    public void cliRoundTripsAgainstRealBridgeTransport() throws Exception {
        assumeTrue("python3 must be available for the live bt CLI round-trip",
                interpreterAvailable());
        Path dir = temporary.newFolder("bt").toPath();
        Path cli = dir.resolve("nusadesk-bt");
        Files.write(cli, GuestBtCliWriter.scriptContent("0.1.0")
                .getBytes(StandardCharsets.UTF_8));
        Path moduleDir = dir.resolve("lib");
        Files.createDirectories(moduleDir);
        Files.write(moduleDir.resolve("termux_compat.py"),
                GuestTermuxCompatWriter.moduleContent("0.1.0")
                        .getBytes(StandardCharsets.UTF_8));
        Path env = dir.resolve("bridge.env");
        Path deadEnv = dir.resolve("dead.env");

        try (FakeBridge bridge = new FakeBridge()) {
            writeEnv(env, bridge.port());
            // A port nobody listens on for the unreachable case.
            ServerSocket closed = new ServerSocket(0, 50,
                    InetAddress.getByName("127.0.0.1"));
            int deadPort = closed.getLocalPort();
            closed.close();
            writeEnv(deadEnv, deadPort);

            // status: fields-only JSON on stdout, envelope stripped.
            Result result = runCli(cli, moduleDir, env, "status");
            assertEquals("status rc: " + result.err, 0, result.code);
            assertEquals("{\"state\":\"on\",\"name\":\"S10e\","
                            + "\"scan_mode\":\"connectable\","
                            + "\"bonded_count\":2}",
                    result.out.trim());
            assertEquals("bt.status", bridge.lastMethod());
            assertFalse("token must not leak",
                    (result.out + result.err).contains(TOKEN));

            // devices: devices_json stays a verbatim string field.
            result = runCli(cli, moduleDir, env, "devices");
            assertEquals("devices rc: " + result.err, 0, result.code);
            assertEquals("{\"devices_json\":\"[]\",\"count\":0}",
                    result.out.trim());

            // discover: start + poll + stop ordering, prints the final
            // devices array parsed from devices_json.
            int before = bridge.requestCount();
            result = runCli(cli, moduleDir, env,
                    "discover", "--seconds", "2");
            assertEquals("discover rc: " + result.err, 0, result.code);
            assertEquals("[{\"address\":\"AA:BB:CC:DD:EE:FF\","
                            + "\"name\":\"Dev\"}]",
                    result.out.trim());
            assertEquals("[bt.discover.start, bt.discover.poll,"
                            + " bt.discover.stop]",
                    bridge.methodsSince(before).toString());

            // pair: typed bridge error -> stderr text, exit 1.
            result = runCli(cli, moduleDir, env,
                    "pair", "aa:bb:cc:dd:ee:ff");
            assertEquals("pair rc: " + result.err, 1, result.code);
            assertTrue(result.err.contains("bt-pair-timeout"));
            assertTrue(bridge.lastRequest().contains(
                    "\"address\":\"AA:BB:CC:DD:EE:FF\""));

            // pair with a malformed address: usage exit 2, and no bridge
            // request was ever sent.
            before = bridge.requestCount();
            result = runCli(cli, moduleDir, env, "pair", "not-an-address");
            assertEquals("bad pair rc", 2, result.code);
            assertTrue(result.err.contains("bad bluetooth address"));
            assertEquals(before, bridge.requestCount());

            // gatt write: service_uuid + char_uuid + value_hex params.
            result = runCli(cli, moduleDir, env,
                    "gatt", "write", "180f", "2a19", "--hex", "0A0b");
            assertEquals("gatt write rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"bt.gatt.write\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"service_uuid\":\"180f\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"char_uuid\":\"2a19\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"value_hex\":\"0a0b\""));

            // gatt notify start: notify.start with both uuids.
            result = runCli(cli, moduleDir, env,
                    "gatt", "notify", "start", "180f", "2a19");
            assertEquals("gatt notify rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"bt.gatt.notify.start\""));

            // gatt poll: the notify.poll method.
            result = runCli(cli, moduleDir, env, "gatt", "poll");
            assertEquals("gatt poll rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"bt.gatt.notify.poll\""));

            // rfcomm connect: uppercased address + seconds -> timeout_ms.
            result = runCli(cli, moduleDir, env,
                    "rfcomm", "connect", "aa:bb:cc:dd:ee:ff",
                    "--seconds", "1");
            assertEquals("rfcomm connect rc: " + result.err, 0,
                    result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"bt.rfcomm.connect\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"address\":\"AA:BB:CC:DD:EE:FF\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"timeout_ms\":1000"));

            // server notify: --text maps to value_utf8.
            result = runCli(cli, moduleDir, env,
                    "server", "notify", "--text", "hi");
            assertEquals("server notify rc: " + result.err, 0,
                    result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"bt.gatt.server.notify\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"value_utf8\":\"hi\""));

            // Dead bridge: honest unreachable message, exit 5.
            result = runCli(cli, moduleDir, deadEnv, "status");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.err.contains("not reachable"));
            assertTrue(result.err.contains("bluetooth state unknown"));

            // Missing runtime module: typed absent, exit 5.
            Path emptyLib = dir.resolve("emptylib");
            Files.createDirectories(emptyLib);
            result = runCli(cli, emptyLib, env, "status");
            assertEquals("absent module rc", 5, result.code);
            assertTrue(result.err.contains("not installed"));

            // --help prints usage on stdout and never touches the bridge.
            before = bridge.requestCount();
            result = runCli(cli, moduleDir, env, "--help");
            assertEquals("help rc", 0, result.code);
            assertTrue(result.out.startsWith("usage: nusadesk-bt"));
            assertEquals(before, bridge.requestCount());

            // Unknown verb: usage exit 2.
            result = runCli(cli, moduleDir, env, "frobnicate");
            assertEquals("unknown verb rc", 2, result.code);
            assertTrue(result.err.contains("usage: nusadesk-bt"));
        }
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

    private static void writeEnv(Path env, int port) throws IOException {
        Files.write(env, String.join("\n",
                "NUSADESK_ANDROID_BRIDGE_ADDRESS=127.0.0.1",
                "NUSADESK_ANDROID_BRIDGE_PORT=" + port,
                "NUSADESK_ANDROID_BRIDGE_TOKEN=" + TOKEN,
                "NUSADESK_ANDROID_BRIDGE_PROTOCOL=1",
                "").getBytes(StandardCharsets.US_ASCII));
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private static Result runCli(Path cli, Path moduleDir, Path env,
                                 String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(cli.toString());
        Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("NUSADESK_BT_MODULE_DIR",
                moduleDir.toString());
        builder.environment().put("NUSADESK_BT_ENV_FILE", env.toString());
        Process process = builder.start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("nusadesk-bt timed out: " + command);
        }
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return new Result(process.exitValue(), out, err);
    }

    /**
     * A bounded fake capability bridge: accepts sequential loopback
     * connections, records each newline-terminated request line, and
     * answers from a fixed per-method table (a typed error for
     * {@code bt.pair} so the exit-1 path is exercised).
     */
    private static final class FakeBridge implements AutoCloseable {
        private final ServerSocket server;
        private final List<String> requests =
                Collections.synchronizedList(new ArrayList<>());
        private final Thread thread;
        private volatile boolean closed;

        FakeBridge() throws IOException {
            server = new ServerSocket(0, 50,
                    InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "fake-bt-bridge");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        int requestCount() {
            return requests.size();
        }

        String lastRequest() {
            synchronized (requests) {
                return requests.isEmpty() ? ""
                        : requests.get(requests.size() - 1);
            }
        }

        String lastMethod() {
            Matcher matcher = METHOD_PATTERN.matcher(lastRequest());
            return matcher.find() ? matcher.group(1) : "";
        }

        List<String> methodsSince(int index) {
            List<String> methods = new ArrayList<>();
            synchronized (requests) {
                for (int i = index; i < requests.size(); i++) {
                    Matcher matcher =
                            METHOD_PATTERN.matcher(requests.get(i));
                    methods.add(matcher.find() ? matcher.group(1) : "?");
                }
            }
            return methods;
        }

        private void serve() {
            while (!closed) {
                try {
                    Socket client = server.accept();
                    try {
                        handle(client);
                    } finally {
                        client.close();
                    }
                } catch (IOException e) {
                    if (!closed) {
                        // Keep serving; a single bad connection must not
                        // kill the fake.
                    }
                }
            }
        }

        private void handle(Socket client) throws IOException {
            client.setSoTimeout(15000);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (line.size() < 65536) {
                int ch = client.getInputStream().read();
                if (ch < 0 || ch == '\n') {
                    break;
                }
                line.write(ch);
            }
            String request = line.toString(StandardCharsets.US_ASCII);
            requests.add(request);
            String method = lastMethodOf(request);
            OutputStream out = client.getOutputStream();
            out.write((answer(method) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private static String lastMethodOf(String request) {
            Matcher matcher = METHOD_PATTERN.matcher(request);
            return matcher.find() ? matcher.group(1) : "";
        }

        private static String answer(String method) {
            String ok = "{\"v\":1,\"id\":\"s\",\"ok\":true";
            switch (method) {
                case "bt.status":
                    return ok + ",\"state\":\"on\",\"name\":\"S10e\","
                            + "\"scan_mode\":\"connectable\","
                            + "\"bonded_count\":2}";
                case "bt.devices":
                    return ok + ",\"devices_json\":\"[]\",\"count\":0}";
                case "bt.discover.start":
                    return ok + ",\"started\":true}";
                case "bt.discover.poll":
                    return ok + ",\"running\":false,"
                            + "\"devices_json\":\"[]\",\"elapsed_ms\":42}";
                case "bt.discover.stop":
                    return ok + ",\"stopped\":true,\"devices_json\":"
                            + "\"[{\\\"address\\\":\\\"AA:BB:CC:DD:EE:FF"
                            + "\\\",\\\"name\\\":\\\"Dev\\\"}]\"}";
                case "bt.pair":
                    return "{\"v\":1,\"id\":\"s\",\"ok\":false,"
                            + "\"error\":\"bt-pair-timeout\"}";
                case "bt.gatt.write":
                    return ok + ",\"written\":true,\"length\":2}";
                case "bt.gatt.notify.start":
                    return ok + ",\"subscribed\":true}";
                case "bt.gatt.notify.poll":
                    return ok + ",\"notifications_json\":\"[]\"}";
                case "bt.rfcomm.connect":
                    return ok + ",\"connected\":true,"
                            + "\"address\":\"AA:BB:CC:DD:EE:FF\"}";
                case "bt.gatt.server.notify":
                    return ok + ",\"delivered\":1,\"subscribers\":1}";
                default:
                    return ok + "}";
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
        }
    }
}
