package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
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
 * Focused tests for the generated {@code nusadesk-serve} and
 * {@code nusadesk-net} CLIs (ADR-0051): the locked verb/flag contract,
 * {@code py_compile} on both scripts, the upload path-traversal guard run
 * through the real interpreter, a live HTTP round-trip against a real
 * daemonized server, and the {@code nusadesk-net bridge} round-trip over
 * the real generated {@code termux_compat} module against a fake loopback
 * bridge (interpreter-backed tests are skipped when python3 is not on
 * PATH).
 */
public class GuestServeCliWriterTest {

    private static final Pattern METHOD_PATTERN =
            Pattern.compile("\"method\":\"([^\"]+)\"");
    private static final Pattern PORT_PATTERN =
            Pattern.compile("\"port\":(\\d+)");
    private static final Pattern PID_PATTERN =
            Pattern.compile("\"pid\":(\\d+)");
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    private static final String TOKEN = "test_token_0123456789ABCDEF_net";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void serveScriptPinsTheContract() {
        String content = GuestServeCliWriter.serveScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));
        assertTrue(content.contains(
                "'/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("'/usr/local/lib/nusadesk'"));
        assertTrue(content.contains("'/tmp/nusadesk-serve.pid'"));
        assertTrue(content.contains("NUSADESK_SERVE_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_SERVE_MODULE_DIR"));
        assertTrue(content.contains("NUSADESK_SERVE_PID_FILE"));

        // The HTTP-not-FTP rationale is recorded in the header.
        assertTrue(content.contains("no FTP server"));
        assertTrue(content.contains("stdlib only"));

        // Usage pins the verbs and flags.
        assertTrue(content.contains(
                "usage: nusadesk-serve start [--port N] [--dir PATH]"));
        assertTrue(content.contains(
                " [--bind ADDR] [--no-upload] | stop | status'"));

        // The fixed bridge assist methods, and only those.
        for (String needle : new String[] {
                "'wifi.lock.acquire'", "'wifi.lock.release'",
                "'notification.post'", "'notification.remove'",
                "'tag': PROG", "'id': 'nusadesk-serve'",
                "'title': 'NusaDesk file server'",
                "'content': content", "'ongoing': True",
                "'priority': 'low'",
        }) {
            assertTrue("missing bridge fragment: " + needle,
                    content.contains(needle));
        }

        // Server construction, upload guard, and daemon plumbing.
        for (String needle : new String[] {
                "class Handler(http.server.SimpleHTTPRequestHandler)",
                "class FileServer(http.server.ThreadingHTTPServer)",
                "functools.partial(Handler, directory=root)",
                "def do_PUT(self)", "def do_POST(self)",
                "def resolve_upload(root, url_path)",
                "segment == '..'", "MAX_UPLOAD_BYTES = 512 * 1024 * 1024",
                "'Content-Length header required'",
                "os.fork()", "os.setsid()", "os.pipe()",
                "signal.SIGTERM", "signal.SIGKILL",
                "SIOCGIFADDR = 0x8915", "socket.if_nameindex()",
                "socket.getaddrinfo(socket.gethostname()",
                "socketserver.TCPServer.server_bind(self)",
                "DEFAULT_PORT = 8080", "DEFAULT_BIND = '0.0.0.0'",
        }) {
            assertTrue("missing serve fragment: " + needle,
                    content.contains(needle));
        }

        // The LAN warning and the exit-code contract.
        assertTrue(content.contains("WARNING: no authentication"));
        assertTrue(content.contains("stop it with: nusadesk-serve stop"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));

        // Transport lives in the shared termux_compat module, lazily.
        assertTrue(content.contains("sys.path.insert(0, MODULE_DIR)"));
        assertTrue(content.contains("import termux_compat"));
        assertTrue(content.contains("termux_compat.ENV_FILE = ENV_FILE"));

        // Stdlib only: the pinned import set and nothing else.
        String[] imports = content
                .replaceAll("(?m)^#.*$", "")
                .split("\\n");
        List<String> seen = new ArrayList<>();
        for (String line : imports) {
            if (line.startsWith("import ")) {
                seen.add(line.trim());
            }
        }
        assertEquals("[import fcntl, import functools, import http.server,"
                        + " import ipaddress, import json, import os,"
                        + " import select, import signal, import socket,"
                        + " import socketserver, import struct, import sys,"
                        + " import time, import urllib.parse]",
                seen.toString());

        String lower = content.toLowerCase(Locale.ROOT);
        assertFalse("no subprocess", lower.contains("import subprocess"));
        assertFalse("no ctypes", lower.contains("import ctypes"));
        assertFalse("no urllib import (only urllib.parse)",
                lower.contains("import urllib\n"));
        assertFalse("no os.system", lower.contains("os.system"));
        assertFalse("no os.popen", lower.contains("os.popen"));
        assertFalse("no eval(", lower.contains("eval("));
        assertFalse("no exec(", lower.contains("exec("));
        assertFalse("no shell interpolation", lower.contains("shell="));
        // The token never leaves the shared runtime module: no code path
        // in this script may read or print it (comments stripped first).
        String codeOnly = lower.replaceAll("(?m)#.*$", "");
        assertFalse("no token handling at all", codeOnly.contains("token"));
    }

    @Test
    public void netScriptPinsTheContract() {
        String content = GuestServeCliWriter.netScriptContent("0.1.0");

        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));
        assertTrue(content.contains(
                "'/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("'/usr/local/lib/nusadesk'"));
        assertTrue(content.contains("NUSADESK_NET_ENV_FILE"));
        assertTrue(content.contains("NUSADESK_NET_MODULE_DIR"));

        // Usage pins the verbs.
        assertTrue(content.contains(
                "USAGE = 'usage: nusadesk-net lan-ip [--json] | bridge'"));

        // The one bridge method and the no-subprocess address discovery.
        for (String needle : new String[] {
                "'bridge.info'",
                "SIOCGIFADDR = 0x8915", "socket.if_nameindex()",
                "socket.getaddrinfo(socket.gethostname()",
                "socket.inet_ntoa(packed[20:24])",
                "'addresses'", "status: reachable",
                "status: unreachable", "status: typed-error",
                "is not installed", "is not reachable",
                "bridge state unknown",
        }) {
            assertTrue("missing net fragment: " + needle,
                    content.contains(needle));
        }

        // Exit codes.
        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("return 2"));
        assertTrue(content.contains("return 1"));

        // Transport lives in the shared termux_compat module, lazily.
        assertTrue(content.contains("sys.path.insert(0, MODULE_DIR)"));
        assertTrue(content.contains("import termux_compat"));
        assertTrue(content.contains("termux_compat.ENV_FILE = ENV_FILE"));
        assertTrue(content.contains("tc.load_config(ENV_FILE)"));
        assertTrue(content.contains("tc.bridge_call('bridge.info'"));

        // Stdlib only: the pinned import set and nothing else.
        String[] imports = content
                .replaceAll("(?m)^#.*$", "")
                .split("\\n");
        List<String> seen = new ArrayList<>();
        for (String line : imports) {
            if (line.startsWith("import ")) {
                seen.add(line.trim());
            }
        }
        assertEquals("[import fcntl, import json, import os,"
                        + " import socket, import struct, import sys]",
                seen.toString());

        String lower = content.toLowerCase(Locale.ROOT);
        assertFalse("no subprocess", lower.contains("import subprocess"));
        assertFalse("no ctypes", lower.contains("import ctypes"));
        assertFalse("no urllib", lower.contains("import urllib"));
        assertFalse("no os.system", lower.contains("os.system"));
        assertFalse("no os.popen", lower.contains("os.popen"));
        assertFalse("no eval(", lower.contains("eval("));
        assertFalse("no exec(", lower.contains("exec("));
        assertFalse("no shell interpolation", lower.contains("shell="));
        // The token never leaves the shared runtime module: the script
        // reads the env through tc.load_config but never names the token
        // field, so no code path can print it (comments stripped first).
        String codeOnly = lower.replaceAll("(?m)#.*$", "");
        assertFalse("no token handling at all", codeOnly.contains("token"));
    }

    @Test
    public void bothScriptsCompileWithPython3() throws Exception {
        assumeTrue("python3 must be available for py_compile",
                interpreterAvailable());
        Path dir = temporary.newFolder("compile").toPath();
        Path serve = dir.resolve("nusadesk-serve");
        Path net = dir.resolve("nusadesk-net");
        Files.write(serve, GuestServeCliWriter.serveScriptContent("0.1.0")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(net, GuestServeCliWriter.netScriptContent("0.1.0")
                .getBytes(StandardCharsets.UTF_8));
        for (Path cli : new Path[] {serve, net}) {
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
            assertEquals("py_compile failed for " + cli + ":\n" + output,
                    0, process.exitValue());
        }
    }

    @Test
    public void serveUploadValidationRejectsTraversal() throws Exception {
        assumeTrue("python3 must be available for the traversal check",
                interpreterAvailable());
        Path dir = temporary.newFolder("traverse").toPath();
        Path cli = dir.resolve("nusadesk-serve");
        Files.write(cli, GuestServeCliWriter.serveScriptContent("0.1.0")
                .getBytes(StandardCharsets.UTF_8));
        Path root = dir.resolve("www");
        Files.createDirectories(root);

        // Load the generated script as a module and exercise
        // resolve_upload() directly: legal names resolve under the real
        // root; every traversal, embedded separator, or NUL is rejected.
        String check = String.join("\n",
                "import sys, types",
                "m = types.ModuleType('cli')",
                "m.__file__ = sys.argv[1]",
                "exec(compile(open(sys.argv[1]).read(), sys.argv[1],",
                "             'exec'), m.__dict__)",
                "root = sys.argv[2]",
                "real = m.os.path.realpath(root)",
                "r = m.resolve_upload",
                "good = [r(root, '/a/b.txt') == real + '/a/b.txt',",
                "        r(root, '/hello.txt') == real + '/hello.txt',",
                "        r(root, '/a file.txt') == real + '/a file.txt']",
                "bad = [r(root, '/../escape.txt'),",
                "       r(root, '/..'),",
                "       r(root, '/a/../../b.txt'),",
                "       r(root, '/%2e%2e/escape.txt'),",
                "       r(root, '/%2e%2e%2fescape.txt'),",
                "       r(root, '/a%2fb.txt'),",
                "       r(root, '/a%00b.txt'),",
                "       r(root, '/x\\\\..\\\\y'),",
                "       r(root, '/')]",
                "for value in bad:",
                "    if value is not None:",
                "        print('accepted: ' + value)",
                "sys.exit(0 if all(good) and all(v is None for v in bad)",
                "         else 1)",
                "");
        Process process = new ProcessBuilder("python3", "-c", check,
                cli.toString(), root.toString())
                .redirectErrorStream(true).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("traversal check timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("traversal check failed:\n" + output,
                0, process.exitValue());
    }

    @Test
    public void serveCliRoundTripsOverRealHttp() throws Exception {
        assumeTrue("python3 must be available for the live serve round-trip",
                interpreterAvailable());
        Path dir = temporary.newFolder("serve").toPath();
        Path cli = dir.resolve("nusadesk-serve");
        Files.write(cli, GuestServeCliWriter.serveScriptContent("0.1.0")
                .getBytes(StandardCharsets.UTF_8));
        Path root = dir.resolve("www");
        Files.createDirectories(root);
        Files.write(root.resolve("hello.txt"),
                "hello lan".getBytes(StandardCharsets.UTF_8));
        Path pidFile = dir.resolve("serve.pid");
        // No termux_compat module: the bridge assists are skipped, which is
        // exactly the best-effort contract.
        Path emptyLib = dir.resolve("emptylib");
        Files.createDirectories(emptyLib);

        int pid = -1;
        try {
            // start: daemonizes, writes the pid file, prints the URL.
            Result result = runServe(cli, pidFile, emptyLib,
                    "start", "--bind", "127.0.0.1", "--port", "0",
                    "--dir", root.toString());
            assertEquals("start rc: " + result.err, 0, result.code);
            assertTrue(result.out.contains("serving " + root));
            assertTrue(result.out.contains("http://127.0.0.1:"));
            assertTrue(result.out.contains("pid: "));
            assertTrue("loopback note on stderr",
                    result.err.contains("bound to loopback"));

            String state = new String(Files.readAllBytes(pidFile),
                    StandardCharsets.UTF_8);
            Matcher pidMatcher = PID_PATTERN.matcher(state);
            Matcher portMatcher = PORT_PATTERN.matcher(state);
            assertTrue("pid in state file", pidMatcher.find());
            assertTrue("port in state file", portMatcher.find());
            pid = Integer.parseInt(pidMatcher.group(1));
            int port = Integer.parseInt(portMatcher.group(1));
            String base = "http://127.0.0.1:" + port;

            // GET serves the directory file.
            HttpURLConnection get = http(base + "/hello.txt", "GET");
            assertEquals(200, get.getResponseCode());
            assertEquals("hello lan", readBody(get));

            // PUT uploads one file under the root.
            HttpURLConnection put = http(base + "/uploaded.bin", "PUT");
            put.getOutputStream().write(
                    "payload".getBytes(StandardCharsets.UTF_8));
            put.getOutputStream().close();
            assertEquals(201, put.getResponseCode());
            assertEquals("payload", new String(Files.readAllBytes(
                    root.resolve("uploaded.bin")), StandardCharsets.UTF_8));

            // Encoded traversal is refused and writes nothing outside.
            HttpURLConnection evil = http(base + "/%2e%2e/evil.txt", "PUT");
            evil.getOutputStream().write(
                    "x".getBytes(StandardCharsets.UTF_8));
            evil.getOutputStream().close();
            assertEquals(403, evil.getResponseCode());
            assertFalse(Files.exists(dir.resolve("evil.txt")));

            // A literal .. segment is refused the same way (or normalized
            // client-side into a harmless in-root name - either way no
            // file may appear outside the root).
            HttpURLConnection dotdot = http(base + "/../evil2.txt", "PUT");
            dotdot.getOutputStream().write(
                    "x".getBytes(StandardCharsets.UTF_8));
            dotdot.getOutputStream().close();
            int dotdotCode = dotdot.getResponseCode();
            assertTrue("literal .. not a 2xx escape, got " + dotdotCode,
                    dotdotCode == 403 || dotdotCode == 201);
            assertFalse(Files.exists(dir.resolve("evil2.txt")));

            // A second start while running is a typed refusal.
            result = runServe(cli, pidFile, emptyLib,
                    "start", "--bind", "127.0.0.1", "--port", "0");
            assertEquals("second start rc", 1, result.code);
            assertTrue(result.err.contains("already running"));

            // status reports the live server.
            result = runServe(cli, pidFile, emptyLib, "status");
            assertEquals("status rc", 0, result.code);
            assertTrue(result.out.contains("state: running"));
            assertTrue(result.out.contains("pid: " + pid));
            assertTrue(result.out.contains("port: " + port));
            assertTrue(result.out.contains("url: http://127.0.0.1:"
                    + port + "/"));

            // stop: SIGTERM via the pid file.
            result = runServe(cli, pidFile, emptyLib, "stop");
            assertEquals("stop rc: " + result.err, 0, result.code);
            assertTrue(result.out.contains("stopped"));
            assertFalse("pid file removed", Files.exists(pidFile));
            waitForPidExit(pid);

            // status after stop is the honest stopped state.
            result = runServe(cli, pidFile, emptyLib, "status");
            assertEquals(0, result.code);
            assertTrue(result.out.contains("state: stopped"));

            // Usage contract: unknown verb and bad flag value -> 2,
            // --help -> 0 on stdout.
            result = runServe(cli, pidFile, emptyLib, "frobnicate");
            assertEquals(2, result.code);
            assertTrue(result.err.contains("usage: nusadesk-serve"));
            result = runServe(cli, pidFile, emptyLib,
                    "start", "--port", "abc");
            assertEquals(2, result.code);
            result = runServe(cli, pidFile, emptyLib, "--help");
            assertEquals(0, result.code);
            assertTrue(result.out.startsWith("usage: nusadesk-serve"));
        } finally {
            if (pid > 0) {
                new ProcessBuilder("python3", "-c",
                        "import os,signal,sys;"
                                + "\ntry: os.kill(int(sys.argv[1]),"
                                + " signal.SIGKILL)"
                                + "\nexcept OSError: pass",
                        String.valueOf(pid))
                        .redirectErrorStream(true).start()
                        .waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void netCliRoundTripsAgainstFakeBridge() throws Exception {
        assumeTrue("python3 must be available for the live net round-trip",
                interpreterAvailable());
        Path dir = temporary.newFolder("net").toPath();
        Path cli = dir.resolve("nusadesk-net");
        Files.write(cli, GuestServeCliWriter.netScriptContent("0.1.0")
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
            ServerSocket closed = new ServerSocket(0, 50,
                    InetAddress.getByName("127.0.0.1"));
            int deadPort = closed.getLocalPort();
            closed.close();
            writeEnv(deadEnv, deadPort);

            // bridge: address + port printed, reachable status, the
            // bridge.info response fields - and never the token.
            Result result = runNet(cli, moduleDir, env, "bridge");
            assertEquals("bridge rc: " + result.err, 0, result.code);
            assertTrue(result.out.contains("address: 127.0.0.1"));
            assertTrue(result.out.contains("port: " + bridge.port()));
            assertTrue(result.out.contains("status: reachable"));
            assertTrue(result.out.contains("transport: tcp-loopback"));
            assertTrue(result.out.contains("capabilities: bridge.info"));
            assertEquals("bridge.info", bridge.lastMethod());
            assertFalse("token must not leak",
                    (result.out + result.err).contains(TOKEN));

            // Dead bridge: honest unreachable, exit 5.
            result = runNet(cli, moduleDir, deadEnv, "bridge");
            assertEquals("dead bridge rc", 5, result.code);
            assertTrue(result.out.contains("status: unreachable"));
            assertTrue(result.err.contains("not reachable"));
            assertFalse("token must not leak",
                    (result.out + result.err).contains(TOKEN));

            // Missing runtime module: typed absent, exit 5.
            Path emptyLib = dir.resolve("emptylib");
            Files.createDirectories(emptyLib);
            result = runNet(cli, emptyLib, env, "bridge");
            assertEquals("absent module rc", 5, result.code);
            assertTrue(result.err.contains("not installed"));

            // lan-ip needs no bridge at all; --json is the stable shape.
            result = runNet(cli, emptyLib, deadEnv, "lan-ip", "--json");
            assertEquals("lan-ip --json rc: " + result.err, 0, result.code);
            String json = result.out.trim();
            assertTrue(json.startsWith("{\"addresses\":["));
            assertTrue(json.endsWith("]}"));

            // Plain mode prints one IPv4 literal per line (or nothing when
            // the machine has no non-loopback address, with a stderr note).
            result = runNet(cli, emptyLib, deadEnv, "lan-ip");
            assertEquals("lan-ip rc: " + result.err, 0, result.code);
            for (String line : result.out.trim().split("\\n")) {
                if (!line.isEmpty()) {
                    assertTrue("not an IPv4 line: " + line,
                            IPV4_PATTERN.matcher(line).matches());
                    assertFalse(line.startsWith("127."));
                }
            }

            // --help prints usage on stdout and never touches the bridge.
            int before = bridge.requestCount();
            result = runNet(cli, moduleDir, env, "--help");
            assertEquals("help rc", 0, result.code);
            assertTrue(result.out.startsWith("usage: nusadesk-net"));
            assertEquals(before, bridge.requestCount());

            // Unknown verb and bad flag: usage exit 2.
            result = runNet(cli, moduleDir, env, "frobnicate");
            assertEquals("unknown verb rc", 2, result.code);
            assertTrue(result.err.contains("usage: nusadesk-net"));
            result = runNet(cli, moduleDir, env, "lan-ip", "--bogus");
            assertEquals("bad flag rc", 2, result.code);
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

    private static HttpURLConnection http(String url, String method)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod(method);
        if (method.equals("PUT")) {
            connection.setDoOutput(true);
        }
        return connection;
    }

    private static String readBody(HttpURLConnection connection)
            throws IOException {
        return new String(connection.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private static void waitForPidExit(int pid) throws InterruptedException {
        Path proc = Path.of("/proc/" + pid);
        for (int i = 0; i < 40 && Files.exists(proc); i++) {
            Thread.sleep(50);
        }
        assertFalse("daemon pid still alive: " + pid, Files.exists(proc));
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

    private static Result runServe(Path cli, Path pidFile, Path moduleDir,
                                   String... args)
            throws IOException, InterruptedException {
        return run(cli, args, env -> {
            env.put("NUSADESK_SERVE_PID_FILE", pidFile.toString());
            env.put("NUSADESK_SERVE_MODULE_DIR", moduleDir.toString());
            env.put("NUSADESK_SERVE_ENV_FILE",
                    pidFile.resolveSibling("absent.env").toString());
        });
    }

    private static Result runNet(Path cli, Path moduleDir, Path env,
                                 String... args)
            throws IOException, InterruptedException {
        return run(cli, args, environment -> {
            environment.put("NUSADESK_NET_MODULE_DIR", moduleDir.toString());
            environment.put("NUSADESK_NET_ENV_FILE", env.toString());
        });
    }

    private interface Env {
        void apply(java.util.Map<String, String> environment);
    }

    private static Result run(Path cli, String[] args, Env env)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(cli.toString());
        Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command);
        env.apply(builder.environment());
        Process process = builder.start();
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("CLI timed out: " + command);
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
     * answers {@code bridge.info} with the fields the net CLI prints.
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
            thread = new Thread(this::serve, "fake-net-bridge");
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
            OutputStream out = client.getOutputStream();
            out.write(("{\"v\":1,\"id\":\"s\",\"ok\":true,"
                            + "\"transport\":\"tcp-loopback\","
                            + "\"capabilities\":\"bridge.info,"
                            + "wifi.lock.acquire,notification.post\"}\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
        }
    }
}
