package gh.nusashell.nusadesk.domain.runtime;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Executes the product-owned {@code lw-systemctl} wrapper asset with real
 * {@code sh} against a stub standing in for the vendored systemctl3.py, so
 * the contract no prose check can see is pinned: only the manager call is
 * exec'd (the process that answers is the stub itself, so the pid the
 * session supervisor records stays the manager's own). Every other call is
 * proxied so the exit code is observable and stderr controllable — stderr
 * is captured and dropped on success or forwarded verbatim on failure —
 * while a {@code --value} call additionally captures stdout so the
 * {@code Property=} prefixes can be stripped to bare values. A requested
 * {@code UnitPath} is answered from the vendored {@code unit-paths} list the
 * D-Bus provider also reads, not from the script's empty result. On a plain
 * call stdout is never captured: it stays the caller's own stream, which
 * {@link #nonValueCallsKeepStdoutAttachedNotCaptured} proves in flight
 * rather than assumes.
 *
 * The wrapper names guest-absolute paths, so the staged copy swaps the
 * {@link CuratedRuntimeCatalog#SERVICES_OVERLAY_GUEST_DIR} prefix for a temp
 * dir — the same relative layout the guest binds at that prefix — and the
 * pinned-path test below asserts that shipped prefix is still composed from
 * the catalog constant.
 */
public class LwSystemctlWrapperAssetTest {

    private static final String INTERP_REL = "usr/bin/python3.12";
    private static final String SCRIPT_REL = "usr/lib/nusadesk/systemctl3.py";
    private static final String UNIT_PATHS_REL = "usr/share/lw-services/unit-paths";

    /** Stands in for the overlay interpreter: runs the script operand with
     * sh, the way python3.12 would run systemctl3.py. exec keeps the pid. */
    private static final String STUB_INTERPRETER =
            "#!/bin/sh\n"
            + "exec /bin/sh \"$@\"\n";

    /** Stand-in for the vendored systemctl3.py: records its pid and argv,
     * emits the measured stray stderr diagnostics on every run, answers
     * -p/--property requests with Property=value lines (UnitPath included —
     * LW_STUB_OMIT_UNITPATH silences it to model the real script's
     * answer-nothing shape), prints a removal line for `disable`, blocks
     * mid-run for `stream-live`, and has a failing command and the exit-0
     * usage-error shape. */
    private static final String STUB_SYSTEMCTL =
            "#!/bin/sh\n"
            + "printf '%s\\n' \"$$\" > \"${LW_STUB_PID_FILE:?}\"\n"
            + ": > \"${LW_STUB_ARGV_FILE:?}\"\n"
            + "for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$LW_STUB_ARGV_FILE\"; done\n"
            + "echo 'WARNING:systemctl:could not access /proc/net/stat >> [Errno 13] Permission denied' >&2\n"
            + "for a in \"$@\"; do\n"
            + "    case \"$a\" in\n"
            + "        --value) echo 'stub saw --value' >&2; exit 9 ;;\n"
            + "        fail.service) echo 'ERROR:systemctl:Unit fail.service not found.' >&2; exit 3 ;;\n"
            + "        usage-error) echo 'Usage: systemctl3.py [options] command [name...]' >&2; exit 0 ;;\n"
            + "        disable) echo 'Removed /etc/systemd/system/multi-user.target.wants/foo.service.' ;;\n"
            + "        stream-live) echo 'LIVE-STDOUT-MARKER'; read lw_stub_gate ;;\n"
            + "        echo-stdin) read lw_stub_gate; echo \"STDIN-GOT:$lw_stub_gate\" ;;\n"
            + "    esac\n"
            + "done\n"
            + "emit() {\n"
            + "    case \"$1\" in\n"
            + "        LoadState) echo \"LoadState=not-found\" ;;\n"
            + "        ActiveState) echo \"ActiveState=inactive\" ;;\n"
            + "        UnitPath) [ -n \"${LW_STUB_OMIT_UNITPATH:-}\" ] || echo \"UnitPath=/run/systemd/system /etc/systemd/system\" ;;\n"
            + "        ExecStart) echo \"ExecStart=/bin/x --opt=a\" ;;\n"
            + "        *) echo \"$1=stubbed\" ;;\n"
            + "    esac\n"
            + "}\n"
            + "while [ $# -gt 0 ]; do\n"
            + "    case \"$1\" in\n"
            + "        --property=*) emit \"${1#*=}\" ;;\n"
            + "        --property|-p) shift; [ $# -gt 0 ] && emit \"$1\" ;;\n"
            + "        -p?*) emit \"${1#-p}\" ;;\n"
            + "    esac\n"
            + "    shift\n"
            + "done\n"
            + "exit 0\n";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path stageRoot;
    private Path wrapper;
    private Path pidFile;
    private Path argvFile;
    private Path tmpDir;

    @Before
    public void stage() throws Exception {
        assumeTrue("sh must be available to execute the wrapper asset",
                shAvailable());
        stageRoot = folder.newFolder("overlay").toPath();
        tmpDir = folder.newFolder("tmp").toPath();
        pidFile = stageRoot.resolve("stub.pid");
        argvFile = stageRoot.resolve("stub.argv");

        Path interpreter = stageRoot.resolve(INTERP_REL);
        Files.createDirectories(interpreter.getParent());
        Files.write(interpreter, STUB_INTERPRETER.getBytes(StandardCharsets.UTF_8));
        assertTrue("stub interpreter must be executable",
                interpreter.toFile().setExecutable(true));

        Path script = stageRoot.resolve(SCRIPT_REL);
        Files.createDirectories(script.getParent());
        Files.write(script, STUB_SYSTEMCTL.getBytes(StandardCharsets.UTF_8));

        // The real vendored data file, staged at its overlay path — the
        // UnitPath tests then pin the shipped list's parse, not a fixture's.
        Path unitPaths = stageRoot.resolve(UNIT_PATHS_REL);
        Files.createDirectories(unitPaths.getParent());
        Files.copy(assetsDir().resolve("services/lw-unit-paths"), unitPaths,
                StandardCopyOption.REPLACE_EXISTING);

        // Execute the real asset bytes with only the overlay prefix moved —
        // the same re-rooting the guest bind performs.
        Path asset = wrapperAsset();
        String content = new String(Files.readAllBytes(asset), StandardCharsets.UTF_8);
        String overlayDir = CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR;
        assertTrue("wrapper must name the services overlay dir: " + asset,
                content.contains(overlayDir));
        wrapper = folder.newFolder("bin").toPath().resolve("lw-systemctl");
        Files.write(wrapper, content.replace(overlayDir, stageRoot.toString())
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void nonValueCallsAreProxiedWithSilentStderrOnSuccess() throws Exception {
        // The openclaw `disable --now` idempotence case: a successful plain
        // call must put nothing on stderr, while the child's own stdout —
        // never captured — still arrives intact.
        Result result = run("disable", "--now", "foo.service");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals("a successful non---value run is quiet on stderr",
                "", result.err);
        assertEquals("stdout still arrives intact",
                "Removed /etc/systemd/system/multi-user.target.wants/foo.service.\n",
                result.out);
        assertNotEquals("plain calls are proxied, not exec'd",
                String.valueOf(result.pid), read(pidFile).trim());
        // Only --value is stripped; every other flag reaches the parser.
        assertEquals("disable\n--now\nfoo.service", read(argvFile).trim());
    }

    @Test
    public void nonValueFailuresForwardStderrVerbatimAndTheRealExitCode() throws Exception {
        Result result = run("is-enabled", "fail.service");

        assertEquals("the vendored exit code is propagated", 3, result.code);
        // Captured stderr comes back whole on failure — vendor strays and
        // the real diagnosis alike, in order.
        assertEquals("WARNING:systemctl:could not access /proc/net/stat"
                + " >> [Errno 13] Permission denied\n"
                + "ERROR:systemctl:Unit fail.service not found.\n",
                result.err);
    }

    @Test
    public void nonValueCallsKeepStdoutAttachedNotCaptured() throws Exception {
        // If fd 1 were redirected into a capture file, its bytes could only
        // be replayed after the child exited. The stub prints the marker
        // and then blocks on stdin, so a marker that arrives while the run
        // is still in flight proves the caller's stdout is the child's own
        // stream — the property a terminal `systemctl status` relies on.
        Process process = startWrapper("stream-live");
        try {
            String marker = pollStdoutLine(process, 15);
            assertNotNull("child stdout must arrive while the run is in flight",
                    marker);
            assertEquals("LIVE-STDOUT-MARKER", marker);
            assertTrue("the run is still in flight — the marker was not replayed",
                    process.isAlive());
        } finally {
            process.getOutputStream().close(); // EOF releases the stub's read
        }
        assertTrue("lw-systemctl timed out",
                process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    @Test
    public void valueCallsPrintBareValuesWithQuietStderr() throws Exception {
        Result result = run("show", "--property=LoadState", "--value",
                "missing.service");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals("real `show --value` prints the bare value",
                "not-found\n", result.out);
        assertFalse("no Property= prefix survives",
                result.out.contains("="));
        // The stub did emit its warning — a successful adapted run drops it.
        assertEquals("a successful --value run is quiet on stderr",
                "", result.err);
        assertNotEquals("--value takes the subprocess path, not exec",
                String.valueOf(result.pid), read(pidFile).trim());
        // The unsupported flag must never reach the vendored parser.
        assertEquals("show\n--property=LoadState\nmissing.service",
                read(argvFile).trim());
    }

    @Test
    public void valueCallsStripEveryRequestedProperty() throws Exception {
        Result result = run("show",
                "-p", "LoadState",
                "--property=ActiveState",
                "--property", "ExecStart",
                "--value", "x.service");

        assertEquals("rc: " + result.err, 0, result.code);
        // One bare value per line, in request order; values keep their own
        // '=' and spaces — only the first prefix is stripped.
        assertEquals("not-found\ninactive\n/bin/x --opt=a\n", result.out);
        assertEquals("", result.err);
    }

    @Test
    public void unitPathValueAnswersFromTheSharedDataFile() throws Exception {
        // The gap this exists for: `show --property=UnitPath --value` must
        // print the vendored search dirs one per line — the property's `as`
        // shape — not the script's silent empty answer, and not the script's
        // own UnitPath= line either (the stub emits one; the wrapper must
        // substitute the shared list at that line, never echo it).
        Result result = run("show", "--property=UnitPath", "--value",
                "x.service");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals(
                "/etc/systemd/system\n"
                + "/run/systemd/system\n"
                + "/var/run/systemd/system\n"
                + "/usr/local/lib/systemd/system\n"
                + "/usr/lib/systemd/system\n"
                + "/lib/systemd/system\n",
                result.out);
        assertFalse("the stub's own UnitPath= line must not survive",
                result.out.contains("UnitPath="));
        assertFalse("one absolute path per line, never space-joined",
                result.out.contains(" "));
        assertEquals("", result.err);
    }

    @Test
    public void unitPathValueAnswersTheUserScopeList() throws Exception {
        // --user searches user_folders() then system_folders() — both groups
        // in file order — with the {XDG_*} placeholders expanded from the
        // caller's environment (startWrapper pins HOME and XDG_RUNTIME_DIR,
        // so the HOME-derived defaults are exercised too). This is also the
        // list the D-Bus provider answers for Manager.UnitPath.
        Result result = run("--user", "show", "-p", "UnitPath", "--value",
                "x.service");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals(
                "/home/lw-test/.config/systemd/user\n"
                + "/etc/systemd/user\n"
                + "/run/lw-runtime/systemd/user\n"
                + "/run/systemd/user\n"
                + "/var/run/systemd/user\n"
                + "/home/lw-test/.local/share/systemd/user\n"
                + "/usr/local/lib/systemd/user\n"
                + "/usr/lib/systemd/user\n"
                + "/lib/systemd/user\n"
                + "/etc/systemd/system\n"
                + "/run/systemd/system\n"
                + "/var/run/systemd/system\n"
                + "/usr/local/lib/systemd/system\n"
                + "/usr/lib/systemd/system\n"
                + "/lib/systemd/system\n",
                result.out);
        assertEquals("", result.err);
    }

    @Test
    public void systemScopeFlagOverridesUserRegardlessOfOrder() throws Exception {
        // The vendored parser lets --system win whenever both flags appear;
        // the wrapper's scope detection must match it, not last-flag-wins.
        Result result = run("--system", "--user", "show", "-p", "UnitPath",
                "--value", "x.service");

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals(
                "/etc/systemd/system\n"
                + "/run/systemd/system\n"
                + "/var/run/systemd/system\n"
                + "/usr/local/lib/systemd/system\n"
                + "/usr/lib/systemd/system\n"
                + "/lib/systemd/system\n",
                result.out);
    }

    @Test
    public void unitPathListSkipsCommentsBlankLinesAndUnknownTags() throws Exception {
        // The staged file is the real asset; overwrite it with a fixture
        // carrying every skippable shape so the filter is pinned
        // independently of the shipped list's contents.
        Path staged = stageRoot.resolve(UNIT_PATHS_REL);
        Files.write(staged, (
                "# a comment\n"
                + "\n"
                + "a line with no scope tag\n"
                + "unknown:/not/a/scope\n"
                + "user:/only/user\n"
                + "system:/only/system\n"
                + "# trailing comment\n").getBytes(StandardCharsets.UTF_8));

        Result system = run("show", "-p", "UnitPath", "--value", "x.service");
        assertEquals("/only/system\n", system.out);
        Result user = run("--user", "show", "-p", "UnitPath", "--value",
                "x.service");
        assertEquals("/only/user\n/only/system\n", user.out);
    }

    @Test
    public void unitPathIsAppendedWhenTheScriptAnswersNothing() throws Exception {
        // The real vendored script has no UnitPath item at all — the actual
        // production shape: the property is appended after the script's own
        // values rather than substituted at a line that never exists.
        Result result = awaitResult(startWrapper(
                Collections.singletonMap("LW_STUB_OMIT_UNITPATH", "1"),
                "show", "-p", "LoadState", "-p", "UnitPath", "--value",
                "x.service"));

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals("not-found\n"
                + "/etc/systemd/system\n"
                + "/run/systemd/system\n"
                + "/var/run/systemd/system\n"
                + "/usr/local/lib/systemd/system\n"
                + "/usr/lib/systemd/system\n"
                + "/lib/systemd/system\n", result.out);
    }

    @Test
    public void missingUnitPathDataFileFailsLoudlyInsteadOfAnsweringNothing()
            throws Exception {
        // Silence is the bug being fixed: a missing file is a damaged
        // overlay, so the call must exit non-zero with a diagnostic naming
        // the file rather than print nothing.
        Files.delete(stageRoot.resolve(UNIT_PATHS_REL));

        Result result = run("show", "--property=UnitPath", "--value",
                "x.service");

        assertNotEquals("a missing data file must fail, not answer empty",
                0, result.code);
        assertTrue("the diagnostic must name the missing file: " + result.err,
                result.err.contains(UNIT_PATHS_REL));
        assertEquals("no half-answer on stdout", "", result.out);
    }

    @Test
    public void unitPathSharesOneVendoredFileWithTheDbusProvider() throws Exception {
        // The one-source rule: both faces read the same vendored file at the
        // same overlay path — the wrapper for the CLI spelling, the provider
        // for Manager.UnitPath. Pin the path in both assets, that the catalog
        // actually ships the file, and that the provider carries no leftover
        // hardcoded directory list — deleting the file's role in either
        // consumer fails here.
        String wrapperContent = read(wrapperAsset());
        assertTrue("the wrapper must read the vendored file under the "
                        + "services overlay dir",
                wrapperContent.contains(
                        CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR
                                + "/" + UNIT_PATHS_REL));
        String provider = new String(Files.readAllBytes(
                assetsDir().resolve("services/lw-systemd-dbus-provider")),
                StandardCharsets.UTF_8);
        assertTrue("the provider must read the same vendored file",
                provider.contains(UNIT_PATHS_REL));
        assertFalse("no hardcoded search dir list may be left in the provider",
                provider.contains("\"/etc/systemd/system\""));
        boolean vendored = false;
        for (VendoredFile file
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            if (UNIT_PATHS_REL.equals(file.getOverlayPath())) {
                vendored = true;
                assertFalse("a data file is not executable",
                        file.isExecutable());
            }
        }
        assertTrue("the shared unit-path list must be vendored into the "
                + "service-bridge overlay", vendored);
    }

    @Test
    public void failingCallsForwardStderrAndTheRealExitCode() throws Exception {
        Result result = run("is-enabled", "--value", "fail.service");

        assertEquals("the vendored exit code is propagated", 3, result.code);
        // On a non-zero exit stderr is the real diagnosis — forwarded whole.
        assertTrue(result.err.contains(
                "WARNING:systemctl:could not access /proc/net/stat"));
        assertTrue(result.err.contains(
                "ERROR:systemctl:Unit fail.service not found."));
    }

    @Test
    public void usageBannerOnExitZeroBecomesAFailure() throws Exception {
        // The vendored parser's usage error can exit 0; that shape must read
        // as the failure it is, not a successful empty answer.
        Result result = run("frob", "--value", "usage-error");

        assertNotEquals("a usage error is not a success", 0, result.code);
        assertTrue(result.err.contains(
                "Usage: systemctl3.py [options] command [name...]"));
    }

    @Test
    public void usageBannerOnStderrIsCoercedOnPlainCallsToo() throws Exception {
        // The coercion does not depend on --value: a banner captured on
        // stderr with a clean exit still reads as the failure it is. (A
        // banner on stdout would pass through unseen — stdout is live.)
        Result result = run("usage-error");

        assertNotEquals("a usage error is not a success", 0, result.code);
        assertTrue(result.err.contains(
                "Usage: systemctl3.py [options] command [name...]"));
    }

    @Test
    public void theManagerPathIsNeverProxied() throws Exception {
        // Every manager spelling must keep exec — the supervisor records the
        // spawned pid as the manager's, and a wrapper shell in between would
        // orphan the real manager on teardown. `--value` on the same call is
        // still stripped before the parser sees argv.
        for (String spelling : new String[]{"init", "--init", "-1"}) {
            Result result = run("--value", spelling);

            assertEquals("rc: " + result.err, 0, result.code);
            assertEquals(spelling + " must inherit the wrapper's own pid (exec)",
                    String.valueOf(result.pid), read(pidFile).trim());
            assertEquals("--value is stripped before exec",
                    spelling, read(argvFile).trim());
        }
    }

    @Test
    public void adaptedRunsCleanUpTheirTempFiles() throws Exception {
        Result value = run("show", "--property=LoadState", "--value", "x");
        assertEquals("rc: " + value.err, 0, value.code);
        Result plain = run("is-active", "x.service");
        assertEquals("rc: " + plain.err, 0, plain.code);

        try (java.util.stream.Stream<Path> leftovers = Files.list(tmpDir)) {
            assertEquals("no capture files may linger",
                    0, leftovers.count());
        }
    }

    @Test
    public void stdinStillReachesTheChildThroughTheProxy() throws Exception {
        // The `& wait` supervisor shape would hand the child /dev/null on
        // stdin without the fd-9 save in the wrapper — pin that a caller's
        // piped input still arrives.
        Process process = startWrapper("echo-stdin");
        process.getOutputStream().write("caller-line\n"
                .getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        Result result = awaitResult(process);

        assertEquals("rc: " + result.err, 0, result.code);
        assertEquals("the child read the caller's stdin, not /dev/null",
                "STDIN-GOT:caller-line\n", result.out);
    }

    @Test
    public void aKilledAdaptedRunStillCleansUpItsTempFiles() throws Exception {
        // The signal traps routing a kill into the EXIT trap are part of the
        // contract: the capture files must go even when the run dies
        // mid-flight, not only on a clean exit. The marker proves the files
        // were already created before the kill. (dash runs no EXIT trap on
        // an untrapped fatal signal, and defers a trapped one while it waits
        // on a foreground child — the wrapper's `& wait` shape is what makes
        // this prompt.)
        Process process = startWrapper("stream-live");
        try {
            String marker = pollStdoutLine(process, 15);
            assertEquals("the run must be in flight before the kill",
                    "LIVE-STDOUT-MARKER", marker);
        } finally {
            process.destroy(); // SIGTERM — the EXIT trap must still run
            process.getOutputStream().close(); // release the stub's read
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("lw-systemctl did not exit after destroy");
        }
        try (java.util.stream.Stream<Path> leftovers = Files.list(tmpDir)) {
            assertEquals("the EXIT trap must remove the capture files",
                    0, leftovers.count());
        }
    }

    @Test
    public void wrapperNamesBothPathsUnderTheServicesOverlayDir() throws Exception {
        // Composing the expected paths from the catalog constant is what
        // keeps a future overlay move honest: the digest pin would still
        // match and only a test reading the asset notices the bridge was
        // left pointing at a dir the launcher no longer binds.
        String interpreter = CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR
                + "/" + INTERP_REL;
        String script = CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR
                + "/" + SCRIPT_REL;
        String content = new String(Files.readAllBytes(wrapperAsset()),
                StandardCharsets.UTF_8);

        String invocation = interpreter + " " + script + " \"$@\"";
        assertTrue("the manager path must exec the pinned pair",
                content.contains("exec " + invocation));
        assertTrue("the --value path must capture both streams",
                content.contains(invocation
                        + " <&9 9>&- > \"$lw_out\" 2> \"$lw_err\" &"));
        assertTrue("the plain-call path must capture stderr only — "
                        + "no fd-1 redirect on that invocation",
                content.contains(invocation + " <&9 9>&- 2> \"$lw_err\" &"));
    }

    @Test
    public void wrapperStaysPosixShWithNoNewInterpreterDependency() throws Exception {
        List<String> lines = Files.readAllLines(wrapperAsset(),
                StandardCharsets.UTF_8);
        assertEquals("#!/bin/sh", lines.get(0));
        // The pinned python3.12 path is the payload; embedded Python code
        // (an interpreter fed -c/-) would be a new dependency and is not.
        for (String line : lines) {
            if (line.trim().startsWith("#")) {
                continue;
            }
            assertFalse("no inline Python in the wrapper: " + line.trim(),
                    line.contains("python3.12 -") || line.contains("python3 -"));
        }
    }

    /** Run the staged wrapper under {@code sh} and capture streams + the
     * spawned pid (which only the exec'd manager call keeps for the stub). */
    private Result run(String... args) throws Exception {
        return awaitResult(startWrapper(args));
    }

    private Result awaitResult(Process process) throws Exception {
        long pid = pidOf(process);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("lw-systemctl timed out");
        }
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        return new Result(process.exitValue(), out, err, pid);
    }

    /**
     * The spawned process's pid, read reflectively: unit tests compile against
     * the Android {@code java.lang.Process} stub, which has no {@code pid()},
     * while the JVM that actually runs them does. Comparing this pid against
     * the stub script's own {@code $$} is what distinguishes the exec'd
     * manager path from the proxied paths.
     */
    private static long pidOf(Process process) {
        try {
            return (Long) Process.class.getMethod("pid").invoke(process);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the test JVM's Process has no pid()", e);
        }
    }

    private Process startWrapper(String... args) throws IOException {
        return startWrapper(Collections.<String, String>emptyMap(), args);
    }

    private Process startWrapper(Map<String, String> extraEnv, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add(wrapper.toString());
        Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("LW_STUB_PID_FILE", pidFile.toString());
        builder.environment().put("LW_STUB_ARGV_FILE", argvFile.toString());
        builder.environment().put("TMPDIR", tmpDir.toString());
        // Deterministic expansion of the data file's {XDG_*} placeholders:
        // XDG_CONFIG_HOME and XDG_DATA_HOME are removed, not merely left
        // alone, so their $HOME-derived defaults are what this exercises
        // wherever the suite runs — a CI runner that exports them would
        // otherwise change the answer under the test.
        builder.environment().put("HOME", "/home/lw-test");
        builder.environment().put("XDG_RUNTIME_DIR", "/run/lw-runtime");
        builder.environment().remove("XDG_CONFIG_HOME");
        builder.environment().remove("XDG_DATA_HOME");
        builder.environment().putAll(extraEnv);
        return builder.start();
    }

    /** Reads one stdout line within a deadline: a regression that captured
     * fd 1 could only replay it after the child exits, so a missing line
     * inside the deadline is a failed pin, not a hung test. */
    private static String pollStdoutLine(Process process, long timeoutSeconds)
            throws Exception {
        BlockingQueue<String> line = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                String value = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))
                        .readLine();
                if (value != null) {
                    line.offer(value);
                }
            } catch (IOException ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();
        return line.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    private static final class Result {
        final int code;
        final String out;
        final String err;
        final long pid;

        Result(int code, String out, String err, long pid) {
            this.code = code;
            this.out = out;
            this.err = err;
            this.pid = pid;
        }
    }

    private static boolean shAvailable() {
        try {
            Process process = new ProcessBuilder("sh", "-c", "true")
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

    /** The packaged wrapper asset — its overlay path is the guest's
     * {@code usr/bin/systemctl} entrypoint. */
    private static Path wrapperAsset() {
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            if ("usr/bin/systemctl".equals(vendored.getOverlayPath())) {
                return assetsDir().resolve(vendored.getAssetPath());
            }
        }
        throw new AssertionError("no vendored member at usr/bin/systemctl");
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Path assetsDir() {
        // Anchor on the process working directory, not the JVM-global
        // user.dir property: Robolectric rewrites user.dir to the app data
        // dir while running ANY Robolectric test in the same test JVM, which
        // made this helper order-dependent on other test classes. The worker
        // process working directory is untouched and stays at the module dir.
        Path workingDir = Paths.get("").toAbsolutePath();
        Path moduleAssets = workingDir.resolve("src/main/assets");
        return Files.isDirectory(moduleAssets)
                ? moduleAssets
                : workingDir.resolve("app/src/main/assets");
    }
}
