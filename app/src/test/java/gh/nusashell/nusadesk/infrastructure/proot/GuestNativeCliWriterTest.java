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
 * Focused tests for the generated NusaDesk-native CLIs (ADR-0051,
 * ADR-0052): the locked verb/flag/method contract of each of the five
 * scripts, a {@code py_compile} syntax check, and a live round-trip per
 * script over the real generated {@code termux_compat} runtime module
 * against a fake loopback bridge (skipped when python3 is not on PATH).
 */
public class GuestNativeCliWriterTest {

    private static final Pattern METHOD_PATTERN =
            Pattern.compile("\"method\":\"([^\"]+)\"");
    private static final String TOKEN = "test_token_0123456789ABCDEF_nat";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void wifiScriptPinsTheContract() {
        String content = GuestNativeCliWriter.wifiScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));
        assertTrue(content.contains(
                "'/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("'/usr/local/lib/nusadesk'"));
        assertTrue(content.contains("NUSADESK_WIFI_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_WIFI_MODULE_DIR"));
        assertTrue(content.contains("PROG = 'nusadesk-wifi'"));

        for (String needle : new String[] {
                "usage: nusadesk-wifi hotspot start | hotspot stop",
                " | hotspot status",
                "suggest add --ssid S [--passphrase P]",
                "[--priority N] [--hidden]",
                "suggest remove --ssid S | suggest list",
                "lock acquire [--tag T] | lock release",
        }) {
            assertTrue("missing usage fragment: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'wifi.hotspot.start'", "'wifi.hotspot.stop'",
                "'wifi.hotspot.status'", "'wifi.suggest.add'",
                "'wifi.suggest.remove'", "'wifi.suggest.list'",
                "'wifi.lock.acquire'", "'wifi.lock.release'",
        }) {
            assertTrue("missing method: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'ssid'", "'passphrase'", "'priority'", "'is_hidden'",
                "'tag'", "bare=('--hidden',)",
        }) {
            assertTrue("missing param: " + needle, content.contains(needle));
        }

        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));
        assertTrue(content.contains("is not reachable"));
        assertTrue(content.contains("is not installed"));
        assertTrue(content.contains("malformed bridge response"));
        assertTrue(content.contains("wifi state unknown"));
        assertTrue(content.contains("LONG_TIMEOUT_SECONDS"));

        assertStdlibOnlyAndNoToken(content);
    }

    @Test
    public void pkgScriptPinsTheContract() {
        String content = GuestNativeCliWriter.pkgScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));
        assertTrue(content.contains("NUSADESK_PKG_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_PKG_MODULE_DIR"));
        assertTrue(content.contains("PROG = 'nusadesk-pkg'"));

        for (String needle : new String[] {
                "usage: nusadesk-pkg list [--filter F] [--limit N]",
                "[--no-system] | info <package>",
                "launch <package> [--activity A]",
        }) {
            assertTrue("missing usage fragment: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'packages.list'", "'packages.info'", "'packages.launch'",
        }) {
            assertTrue("missing method: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'filter'", "'limit'", "'include_system'", "'package'",
                "'activity'", "bare=('--no-system',)",
        }) {
            assertTrue("missing param: " + needle, content.contains(needle));
        }

        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));
        assertTrue(content.contains("package state unknown"));

        assertStdlibOnlyAndNoToken(content);
    }

    @Test
    public void usageScriptPinsTheContract() {
        String content = GuestNativeCliWriter.usageScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("NUSADESK_USAGE_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_USAGE_MODULE_DIR"));
        assertTrue(content.contains("PROG = 'nusadesk-usage'"));

        for (String needle : new String[] {
                "usage: nusadesk-usage query [--days N] [--limit N]",
                "events [--hours N] [--limit N]",
                "standby <package>",
        }) {
            assertTrue("missing usage fragment: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'usage.query'", "'usage.events'", "'usage.standby'",
        }) {
            assertTrue("missing method: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'days'", "'limit'", "'hours'", "'package'",
        }) {
            assertTrue("missing param: " + needle, content.contains(needle));
        }

        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));
        assertTrue(content.contains("usage state unknown"));

        assertStdlibOnlyAndNoToken(content);
    }

    @Test
    public void overlayScriptPinsTheContract() {
        String content = GuestNativeCliWriter.overlayScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("NUSADESK_OVERLAY_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_OVERLAY_MODULE_DIR"));
        assertTrue(content.contains("PROG = 'nusadesk-overlay'"));

        for (String needle : new String[] {
                "usage: nusadesk-overlay show --text T [--x N]",
                "[--y N] [--size N] [--color HEX]",
                "update [--text T] [--x N] [--y N] [--size N]",
                "[--color HEX] | status | hide",
        }) {
            assertTrue("missing usage fragment: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'overlay.show'", "'overlay.update'", "'overlay.status'",
                "'overlay.hide'",
        }) {
            assertTrue("missing method: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'text'", "'x'", "'y'", "'size'", "'color'",
        }) {
            assertTrue("missing param: " + needle, content.contains(needle));
        }

        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));
        assertTrue(content.contains("overlay state unknown"));

        assertStdlibOnlyAndNoToken(content);
    }

    @Test
    public void locScriptPinsTheContract() {
        String content = GuestNativeCliWriter.locScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("NUSADESK_LOC_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_LOC_MODULE_DIR"));
        assertTrue(content.contains("PROG = 'nusadesk-loc'"));

        for (String needle : new String[] {
                "usage: nusadesk-loc start",
                "[--provider gps|network|passive]",
                "[--interval-ms N] [--distance-m N] | poll | stop",
        }) {
            assertTrue("missing usage fragment: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'location.background.start'", "'location.background.poll'",
                "'location.background.stop'",
        }) {
            assertTrue("missing method: " + needle,
                    content.contains(needle));
        }

        for (String needle : new String[] {
                "'provider'", "'interval_ms'", "'distance_m'",
                "'gps', 'network', 'passive'",
        }) {
            assertTrue("missing param: " + needle, content.contains(needle));
        }

        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));
        assertTrue(content.contains("location state unknown"));

        assertStdlibOnlyAndNoToken(content);
    }

    @Test
    public void scriptsCompileWithPython3() throws Exception {
        assumeTrue("python3 must be available for py_compile",
                interpreterAvailable());
        Path dir = temporary.newFolder("compile").toPath();
        compileOne(dir, "nusadesk-wifi",
                GuestNativeCliWriter.wifiScriptContent("0.1.0"));
        compileOne(dir, "nusadesk-pkg",
                GuestNativeCliWriter.pkgScriptContent("0.1.0"));
        compileOne(dir, "nusadesk-usage",
                GuestNativeCliWriter.usageScriptContent("0.1.0"));
        compileOne(dir, "nusadesk-overlay",
                GuestNativeCliWriter.overlayScriptContent("0.1.0"));
        compileOne(dir, "nusadesk-loc",
                GuestNativeCliWriter.locScriptContent("0.1.0"));
    }

    @Test
    public void wifiCliRoundTripsAgainstRealBridgeTransport()
            throws Exception {
        assumeTrue("python3 must be available for the live round-trip",
                interpreterAvailable());
        Fixture fixture = newFixture("wifi",
                GuestNativeCliWriter.wifiScriptContent("0.1.0"));

        try (FakeBridge bridge = new FakeBridge()) {
            writeEnv(fixture.env, bridge.port());
            writeDeadEnv(fixture.deadEnv);

            // hotspot status: fields-only JSON, envelope stripped.
            Result result = runCli(fixture, "WIFI", "hotspot", "status");
            assertEquals("status rc: " + result.err, 0, result.code);
            assertEquals("{\"running\":false,\"ssid\":\"\","
                            + "\"passphrase\":\"\"}",
                    result.out.trim());
            assertEquals("wifi.hotspot.status", bridge.lastMethod());
            assertFalse("token must not leak",
                    (result.out + result.err).contains(TOKEN));

            // suggest add: ssid + priority + the bare --hidden flag.
            result = runCli(fixture, "WIFI", "suggest", "add",
                    "--ssid", "TestNet", "--priority", "5", "--hidden");
            assertEquals("suggest add rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"wifi.suggest.add\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"ssid\":\"TestNet\""));
            assertTrue(bridge.lastRequest().contains("\"priority\":5"));
            assertTrue(bridge.lastRequest().contains(
                    "\"is_hidden\":true"));

            // lock acquire: optional tag param.
            result = runCli(fixture, "WIFI", "lock", "acquire",
                    "--tag", "xfer");
            assertEquals("lock rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"wifi.lock.acquire\""));
            assertTrue(bridge.lastRequest().contains("\"tag\":\"xfer\""));

            // hotspot start: typed bridge error -> stderr, exit 1.
            result = runCli(fixture, "WIFI", "hotspot", "start");
            assertEquals("hotspot start rc: " + result.err, 1,
                    result.code);
            assertTrue(result.err.contains("wifi-hotspot-in-use"));

            // Missing required flag: usage exit 2, no bridge request.
            int before = bridge.requestCount();
            result = runCli(fixture, "WIFI", "suggest", "add",
                    "--priority", "1");
            assertEquals("missing ssid rc", 2, result.code);
            assertEquals(before, bridge.requestCount());

            // Out-of-range number: usage exit 2, no bridge request.
            result = runCli(fixture, "WIFI", "suggest", "add",
                    "--ssid", "X", "--priority", "1001");
            assertEquals("bad priority rc", 2, result.code);
            assertTrue(result.err.contains("priority"));
            assertEquals(before, bridge.requestCount());

            // Dead bridge: honest unreachable message, exit 5.
            result = runCli(fixture.withEnv(fixture.deadEnv), "WIFI",
                    "hotspot", "status");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.err.contains("not reachable"));
            assertTrue(result.err.contains("wifi state unknown"));

            // Missing runtime module: typed absent, exit 5.
            Path emptyLib = fixture.dir.resolve("emptylib");
            Files.createDirectories(emptyLib);
            result = runCli(fixture.withModuleDir(emptyLib), "WIFI",
                    "hotspot", "status");
            assertEquals("absent module rc", 5, result.code);
            assertTrue(result.err.contains("not installed"));

            // --help prints usage on stdout and never touches the bridge.
            before = bridge.requestCount();
            result = runCli(fixture, "WIFI", "--help");
            assertEquals("help rc", 0, result.code);
            assertTrue(result.out.startsWith("usage: nusadesk-wifi"));
            assertEquals(before, bridge.requestCount());

            // Unknown verb: usage exit 2.
            result = runCli(fixture, "WIFI", "frobnicate");
            assertEquals("unknown verb rc", 2, result.code);
            assertTrue(result.err.contains("usage: nusadesk-wifi"));
        }
    }

    @Test
    public void pkgCliRoundTripsAgainstRealBridgeTransport()
            throws Exception {
        assumeTrue("python3 must be available for the live round-trip",
                interpreterAvailable());
        Fixture fixture = newFixture("pkg",
                GuestNativeCliWriter.pkgScriptContent("0.1.0"));

        try (FakeBridge bridge = new FakeBridge()) {
            writeEnv(fixture.env, bridge.port());
            writeDeadEnv(fixture.deadEnv);

            // list: filter + limit + the bare --no-system flag.
            Result result = runCli(fixture, "PKG", "list",
                    "--filter", "foo", "--limit", "5", "--no-system");
            assertEquals("list rc: " + result.err, 0, result.code);
            assertEquals("{\"packages_json\":\"[]\",\"count\":0,"
                            + "\"truncated\":false}", result.out.trim());
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"packages.list\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"filter\":\"foo\""));
            assertTrue(bridge.lastRequest().contains("\"limit\":5"));
            assertTrue(bridge.lastRequest().contains(
                    "\"include_system\":false"));

            // info: positional package param.
            result = runCli(fixture, "PKG", "info", "com.example.app");
            assertEquals("info rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"packages.info\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"package\":\"com.example.app\""));

            // launch: package + optional activity.
            result = runCli(fixture, "PKG", "launch", "com.example.app",
                    "--activity", ".Main");
            assertEquals("launch rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"packages.launch\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"activity\":\".Main\""));

            // Bad limit: usage exit 2, no request.
            int before = bridge.requestCount();
            result = runCli(fixture, "PKG", "list", "--limit", "0");
            assertEquals("bad limit rc", 2, result.code);
            assertEquals(before, bridge.requestCount());

            // Dead bridge: exit 5.
            result = runCli(fixture.withEnv(fixture.deadEnv), "PKG",
                    "list");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.err.contains("package state unknown"));

            // --help: usage on stdout, no request.
            result = runCli(fixture, "PKG", "--help");
            assertEquals("help rc", 0, result.code);
            assertTrue(result.out.startsWith("usage: nusadesk-pkg"));
        }
    }

    @Test
    public void usageCliRoundTripsAgainstRealBridgeTransport()
            throws Exception {
        assumeTrue("python3 must be available for the live round-trip",
                interpreterAvailable());
        Fixture fixture = newFixture("usage",
                GuestNativeCliWriter.usageScriptContent("0.1.0"));

        try (FakeBridge bridge = new FakeBridge()) {
            writeEnv(fixture.env, bridge.port());
            writeDeadEnv(fixture.deadEnv);

            // query: days + limit params.
            Result result = runCli(fixture, "USAGE", "query",
                    "--days", "3", "--limit", "10");
            assertEquals("query rc: " + result.err, 0, result.code);
            assertEquals("{\"apps_json\":\"[]\",\"count\":0,"
                            + "\"window_days\":3}", result.out.trim());
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"usage.query\""));
            assertTrue(bridge.lastRequest().contains("\"days\":3"));
            assertTrue(bridge.lastRequest().contains("\"limit\":10"));

            // events: hours param.
            result = runCli(fixture, "USAGE", "events", "--hours", "12");
            assertEquals("events rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"usage.events\""));
            assertTrue(bridge.lastRequest().contains("\"hours\":12"));

            // standby: positional package.
            result = runCli(fixture, "USAGE", "standby",
                    "com.example.app");
            assertEquals("standby rc: " + result.err, 0, result.code);
            assertEquals("{\"package\":\"com.example.app\","
                            + "\"bucket\":\"active\",\"inactive\":false}",
                    result.out.trim());
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"usage.standby\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"package\":\"com.example.app\""));

            // Out-of-range days: usage exit 2, no request.
            int before = bridge.requestCount();
            result = runCli(fixture, "USAGE", "query", "--days", "8");
            assertEquals("bad days rc", 2, result.code);
            assertEquals(before, bridge.requestCount());

            // Dead bridge: exit 5.
            result = runCli(fixture.withEnv(fixture.deadEnv), "USAGE",
                    "query");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.err.contains("usage state unknown"));

            result = runCli(fixture, "USAGE", "-h");
            assertEquals("help rc", 0, result.code);
            assertTrue(result.out.startsWith("usage: nusadesk-usage"));
        }
    }

    @Test
    public void overlayCliRoundTripsAgainstRealBridgeTransport()
            throws Exception {
        assumeTrue("python3 must be available for the live round-trip",
                interpreterAvailable());
        Fixture fixture = newFixture("overlay",
                GuestNativeCliWriter.overlayScriptContent("0.1.0"));

        try (FakeBridge bridge = new FakeBridge()) {
            writeEnv(fixture.env, bridge.port());
            writeDeadEnv(fixture.deadEnv);

            // show: every flag, including a negative coord and a color.
            Result result = runCli(fixture, "OVERLAY", "show",
                    "--text", "hi", "--x", "10", "--y", "-20",
                    "--size", "20", "--color", "#FF0000");
            assertEquals("show rc: " + result.err, 0, result.code);
            assertEquals("{\"shown\":true,\"x\":10,\"y\":-20}",
                    result.out.trim());
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"overlay.show\""));
            assertTrue(bridge.lastRequest().contains("\"text\":\"hi\""));
            assertTrue(bridge.lastRequest().contains("\"x\":10"));
            assertTrue(bridge.lastRequest().contains("\"y\":-20"));
            assertTrue(bridge.lastRequest().contains("\"size\":20"));
            assertTrue(bridge.lastRequest().contains(
                    "\"color\":\"#FF0000\""));

            // update: partial params.
            result = runCli(fixture, "OVERLAY", "update",
                    "--text", "later");
            assertEquals("update rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"overlay.update\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"text\":\"later\""));

            // status: no params.
            result = runCli(fixture, "OVERLAY", "status");
            assertEquals("status rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"overlay.status\""));

            // update with no flags: usage exit 2, no request.
            int before = bridge.requestCount();
            result = runCli(fixture, "OVERLAY", "update");
            assertEquals("empty update rc", 2, result.code);
            assertEquals(before, bridge.requestCount());

            // show without --text: usage exit 2.
            result = runCli(fixture, "OVERLAY", "show", "--x", "10");
            assertEquals("missing text rc", 2, result.code);

            // Malformed color: usage exit 2.
            result = runCli(fixture, "OVERLAY", "show", "--text", "x",
                    "--color", "FF0000");
            assertEquals("bad color rc", 2, result.code);
            assertTrue(result.err.contains("bad color"));
            assertEquals(before, bridge.requestCount());

            // Dead bridge: exit 5.
            result = runCli(fixture.withEnv(fixture.deadEnv), "OVERLAY",
                    "status");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.err.contains("overlay state unknown"));
        }
    }

    @Test
    public void locCliRoundTripsAgainstRealBridgeTransport()
            throws Exception {
        assumeTrue("python3 must be available for the live round-trip",
                interpreterAvailable());
        Fixture fixture = newFixture("loc",
                GuestNativeCliWriter.locScriptContent("0.1.0"));

        try (FakeBridge bridge = new FakeBridge()) {
            writeEnv(fixture.env, bridge.port());
            writeDeadEnv(fixture.deadEnv);

            // start: provider + interval + distance params.
            Result result = runCli(fixture, "LOC", "start",
                    "--provider", "gps", "--interval-ms", "5000",
                    "--distance-m", "10");
            assertEquals("start rc: " + result.err, 0, result.code);
            assertEquals("{\"started\":true,\"provider\":\"gps\","
                            + "\"interval_ms\":5000,\"distance_m\":10}",
                    result.out.trim());
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"location.background.start\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"provider\":\"gps\""));
            assertTrue(bridge.lastRequest().contains(
                    "\"interval_ms\":5000"));
            assertTrue(bridge.lastRequest().contains("\"distance_m\":10"));

            // poll and stop: no params.
            result = runCli(fixture, "LOC", "poll");
            assertEquals("poll rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"location.background.poll\""));
            result = runCli(fixture, "LOC", "stop");
            assertEquals("stop rc: " + result.err, 0, result.code);
            assertTrue(bridge.lastRequest().contains(
                    "\"method\":\"location.background.stop\""));

            // Bad provider and out-of-range interval: usage exit 2,
            // no request.
            int before = bridge.requestCount();
            result = runCli(fixture, "LOC", "start",
                    "--provider", "bogus");
            assertEquals("bad provider rc", 2, result.code);
            result = runCli(fixture, "LOC", "start",
                    "--interval-ms", "500");
            assertEquals("bad interval rc", 2, result.code);
            assertEquals(before, bridge.requestCount());

            // Dead bridge: exit 5.
            result = runCli(fixture.withEnv(fixture.deadEnv), "LOC",
                    "poll");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.err.contains("location state unknown"));
        }
    }

    /** Stdlib-only imports, no subprocess/eval, token never in code. */
    private static void assertStdlibOnlyAndNoToken(String content) {
        String[] imports = content
                .replaceAll("(?m)^#.*$", "")
                .split("\\n");
        List<String> seen = new ArrayList<>();
        for (String line : imports) {
            if (line.startsWith("import ")) {
                seen.add(line.trim());
            }
        }
        assertEquals("[import json, import os, import sys]",
                seen.toString());

        String lower = content.toLowerCase(Locale.ROOT);
        assertFalse("no socket code of its own",
                lower.contains("import socket"));
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

        // The shared transport import is lazy, like nusadesk-bt.
        assertTrue(content.contains("sys.path.insert(0, MODULE_DIR)"));
        assertTrue(content.contains("import termux_compat"));
        assertTrue(content.contains("termux_compat.ENV_FILE = ENV_FILE"));
    }

    private static void compileOne(Path dir, String name, String content)
            throws IOException, InterruptedException {
        Path cli = dir.resolve(name);
        Files.write(cli, content.getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder(
                "python3", "-m", "py_compile", cli.toString())
                .redirectErrorStream(true).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("py_compile timed out for " + name);
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("py_compile failed for " + name + ":\n" + output, 0,
                process.exitValue());
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

    private Fixture newFixture(String name, String script)
            throws IOException {
        Fixture fixture = new Fixture();
        fixture.dir = temporary.newFolder(name).toPath();
        fixture.cli = fixture.dir.resolve("nusadesk-" + name);
        Files.write(fixture.cli, script.getBytes(StandardCharsets.UTF_8));
        fixture.moduleDir = fixture.dir.resolve("lib");
        Files.createDirectories(fixture.moduleDir);
        Files.write(fixture.moduleDir.resolve("termux_compat.py"),
                GuestTermuxCompatWriter.moduleContent("0.1.0")
                        .getBytes(StandardCharsets.UTF_8));
        fixture.env = fixture.dir.resolve("bridge.env");
        fixture.deadEnv = fixture.dir.resolve("dead.env");
        return fixture;
    }

    private static void writeEnv(Path env, int port) throws IOException {
        Files.write(env, String.join("\n",
                "NUSADESK_ANDROID_BRIDGE_ADDRESS=127.0.0.1",
                "NUSADESK_ANDROID_BRIDGE_PORT=" + port,
                "NUSADESK_ANDROID_BRIDGE_TOKEN=" + TOKEN,
                "NUSADESK_ANDROID_BRIDGE_PROTOCOL=1",
                "").getBytes(StandardCharsets.US_ASCII));
    }

    private void writeDeadEnv(Path deadEnv) throws IOException {
        // A port nobody listens on for the unreachable case.
        ServerSocket closed = new ServerSocket(0, 50,
                InetAddress.getByName("127.0.0.1"));
        int deadPort = closed.getLocalPort();
        closed.close();
        writeEnv(deadEnv, deadPort);
    }

    /** Per-script paths the round-trip tests run against. */
    private static final class Fixture {
        Path dir;
        Path cli;
        Path moduleDir;
        Path env;
        Path deadEnv;

        Fixture withEnv(Path newEnv) {
            Fixture copy = new Fixture();
            copy.dir = dir;
            copy.cli = cli;
            copy.moduleDir = moduleDir;
            copy.env = newEnv;
            copy.deadEnv = deadEnv;
            return copy;
        }

        Fixture withModuleDir(Path newModuleDir) {
            Fixture copy = new Fixture();
            copy.dir = dir;
            copy.cli = cli;
            copy.moduleDir = newModuleDir;
            copy.env = env;
            copy.deadEnv = deadEnv;
            return copy;
        }
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

    private static Result runCli(Fixture fixture, String envPrefix,
                                 String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(fixture.cli.toString());
        Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("NUSADESK_" + envPrefix + "_MODULE_DIR",
                fixture.moduleDir.toString());
        builder.environment().put("NUSADESK_" + envPrefix + "_ENV_FILE",
                fixture.env.toString());
        Process process = builder.start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("nusadesk CLI timed out: " + command);
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
     * {@code wifi.hotspot.start} so the exit-1 path is exercised).
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
            thread = new Thread(this::serve, "fake-native-bridge");
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
                case "wifi.hotspot.start":
                    return "{\"v\":1,\"id\":\"s\",\"ok\":false,"
                            + "\"error\":\"wifi-hotspot-in-use\"}";
                case "wifi.hotspot.status":
                    return ok + ",\"running\":false,\"ssid\":\"\","
                            + "\"passphrase\":\"\"}";
                case "wifi.suggest.add":
                    return ok + ",\"added\":true,\"ssid\":\"TestNet\"}";
                case "wifi.lock.acquire":
                    return ok + ",\"held\":true,\"tag\":\"xfer\"}";
                case "packages.list":
                    return ok + ",\"packages_json\":\"[]\",\"count\":0,"
                            + "\"truncated\":false}";
                case "packages.info":
                    return ok + ",\"package\":\"com.example.app\","
                            + "\"label\":\"Example\",\"version_code\":1}";
                case "packages.launch":
                    return ok + ",\"launched\":true,"
                            + "\"component\":\"com.example.app/.Main\"}";
                case "usage.query":
                    return ok + ",\"apps_json\":\"[]\",\"count\":0,"
                            + "\"window_days\":3}";
                case "usage.standby":
                    return ok + ",\"package\":\"com.example.app\","
                            + "\"bucket\":\"active\",\"inactive\":false}";
                case "overlay.show":
                    return ok + ",\"shown\":true,\"x\":10,\"y\":-20}";
                case "location.background.start":
                    return ok + ",\"started\":true,\"provider\":\"gps\","
                            + "\"interval_ms\":5000,\"distance_m\":10}";
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
