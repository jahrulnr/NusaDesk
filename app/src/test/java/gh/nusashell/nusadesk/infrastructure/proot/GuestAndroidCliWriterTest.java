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
 * Focused tests for the generated {@code android-cli} native dispatcher
 * (ADR-0045): the verb/markers contract, executable installation through
 * the shared ensure path, and a live round-trip against a fake
 * {@code <tmp>/system} tree of stub tools driven through
 * {@code ANDROID_CLI_SYSTEM_ROOT} (skipped when python3 is not on PATH).
 */
public class GuestAndroidCliWriterTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsExecutableCliThroughSharedEnsurePath() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));

        Path cli = rootfs.resolve(GuestAndroidCliWriter.GUEST_CLI_RELATIVE_PATH);
        assertTrue("CLI must exist", Files.isRegularFile(cli));
        assertTrue("CLI must be executable", Files.isExecutable(cli));
        String content = new String(Files.readAllBytes(cli), StandardCharsets.UTF_8);
        assertEquals("same canonical text as the writer advertises",
                GuestAndroidCliWriter.scriptContent("0.1.0"), content);
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
    public void scriptPinsTheNativeContract() {
        String content = GuestAndroidCliWriter.scriptContent("0.1.0");

        // Native tree env knobs; no adb anywhere.
        assertTrue(content.contains("ANDROID_CLI_SYSTEM_ROOT"));
        assertTrue(content.contains("'/system'"));
        assertTrue(content.contains("ANDROID_CLI_QUIET"));
        assertTrue(content.contains("'/run/nusadesk/android-bridge.env'"));

        // The verbs and only those verbs.
        assertTrue(content.contains("usage: android-cli [-q] doctor"));
        assertTrue(content.contains(" | status"));
        assertTrue(content.contains(" | permissions"));
        assertTrue(content.contains(" | sh [args...]"));
        assertTrue(content.contains(" | getprop [name]"));
        assertTrue(content.contains(" | toolbox <applet> [args...]"));
        assertTrue(content.contains(" | toybox <applet> [args...]"));
        assertTrue(content.contains(" | su [args...]"));
        assertTrue(content.contains(" | exec <absolute-path> [args...]"));

        // Tier reporting, the informational stderr line, and the exit codes.
        assertTrue(content.contains("'android-cli: tier=' + tier"));
        assertTrue(content.contains("source=native"));
        assertTrue(content.contains("device="));
        assertTrue(content.contains("return 5"));
        assertTrue(content.contains("no such tool under"));
        assertTrue(content.contains("device su absent"));
        assertTrue(content.contains("android-cli: hint:"));
        assertTrue(content.contains("tier=' + tier_now() + ' source=native"));

        // exec stays under the bound trees; argv is always list-form.
        assertTrue(content.contains("normalized.startswith(prefix)"));
        assertTrue(content.contains(
                "subprocess.run([path] + list(args), env=child_env())"));
        // Children get Android's own search paths (PATH + APEX libraries);
        // the session's own PATH is never touched.
        assertTrue(content.contains("def child_env():"));
        assertTrue(content.contains("'/system/bin', '/system/xbin', '/product/bin'"));
        assertTrue(content.contains("'/apex/com.android.runtime/lib64/bionic'"));

        // doctor honesty: probes, the su present/absent line, the boundary
        // note, and the Magisk grant wording.
        assertTrue(content.contains("present, exec ok"));
        assertTrue(content.contains("device su"));
        assertTrue(content.contains("Magisk"));

        // The bridge-backed permissions verb: the shared termux_compat
        // module does the transport, states come only from
        // bridge.permissions, hints print the Settings action for every
        // non-granted entry, and every failure mode is an honest typed
        // exit (1 typed bridge error, 5 absent/unreachable/malformed).
        assertTrue(content.contains("bridge.permissions"));
        assertTrue(content.contains("termux_compat"));
        assertTrue(content.contains("'/usr/local/lib/nusadesk'"));
        assertTrue(content.contains("sys.path.insert(0, MODULE_DIR)"));
        assertTrue(content.contains("ANDROID_CLI_MODULE_DIR"));
        assertTrue(content.contains("ANDROID_CLI_ENV_FILE"));
        assertTrue(content.contains("PERMISSION_STATES"));
        assertTrue(content.contains("  settings: ' + action"));
        assertTrue(content.contains("'permissions (bridge): ' + count_text(counts)"));
        assertTrue(content.contains("permission states unknown"));
        assertTrue(content.contains("grant"));
        assertTrue(content.contains("states unknown"));
        assertTrue(content.contains("permission.request"));
        assertTrue(content.contains("def info(tier, quiet, source='native'):"));
        assertTrue(content.contains("info(tier_now(), quiet, 'bridge')"));

        String lower = content.toLowerCase(java.util.Locale.ROOT);
        assertFalse("no adb client invocation", lower.contains("adb connect")
                || lower.contains("adb devices") || lower.contains("adb shell"));
        assertFalse("no adb argv literal", content.contains("'adb'"));
        assertFalse("no tcpip step", lower.contains("tcpip 5555"));
        assertFalse("no shell interpolation", lower.contains("shell="));
        assertFalse("no os.system", lower.contains("os.system"));
        assertFalse("no os.exec", lower.contains("os.exec"));
        assertFalse("no eval(", lower.contains("eval("));
        assertFalse("no os.popen", lower.contains("os.popen"));
        // The token stays out of this script entirely: the shared
        // termux_compat module is the only component that reads it. Comment
        // text is stripped first — a comment may name what the module owns,
        // but no code path may read or print a token.
        String codeOnly = lower.replaceAll("(?m)#.*$", "");
        assertFalse("no token handling at all", codeOnly.contains("token"));
    }

    @Test
    public void cliRunsNativelyAgainstFakeSystemTree() throws Exception {
        assumeTrue("python3 must be available for the live android-cli round-trip",
                interpreterAvailable());
        Path rootfs = temporary.newFolder("rootfs").toPath();
        GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0");
        Path cli = rootfs.resolve(GuestAndroidCliWriter.GUEST_CLI_RELATIVE_PATH);
        Path full = temporary.newFolder("system-full").toPath();
        Path bare = temporary.newFolder("system-bare").toPath();
        Path empty = temporary.newFolder("system-empty").toPath();
        Path work = temporary.newFolder("work").toPath();
        Path harness = temporary.newFolder("harness").toPath().resolve("run_android_cli.py");
        Files.write(harness, harnessScript().getBytes(StandardCharsets.UTF_8));

        ProcessBuilder builder = new ProcessBuilder(
                "python3", harness.toString(), cli.toString(),
                full.toString(), bare.toString(), empty.toString(),
                work.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            fail("android-cli harness timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertEquals("android-cli harness failed:\n" + output,
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
     * A bounded Python harness that builds fake {@code <root>/bin} trees of
     * stub tools (each logs its argv and prints FAKE markers), then runs the
     * real generated {@code android-cli} with
     * {@code ANDROID_CLI_SYSTEM_ROOT} pointed at each tree. Validates
     * delegation and argv passthrough for every verb, child exit-code
     * propagation with the child's stderr kept, the missing-tool and su
     * typed exits, quiet mode, the doctor report, and usage handling.
     * A fake {@code termux_compat} module behind
     * {@code ANDROID_CLI_MODULE_DIR}/{@code ANDROID_CLI_ENV_FILE} covers
     * the bridge-backed {@code permissions} verb and the doctor summary:
     * report, typed error, dead bridge, malformed answer, and an absent
     * module.
     */
    private static String harnessScript() {
        return String.join("\n",
                "import os",
                "import subprocess",
                "import sys",
                "",
                "CLI = sys.argv[1]",
                "FULL = sys.argv[2]",
                "BARE = sys.argv[3]",
                "EMPTY = sys.argv[4]",
                "WORK = sys.argv[5]",
                "LOG = os.path.join(WORK, 'argv.log')",
                "TAB = chr(9)",
                "failures = []",
                "",
                "FAKE = (\"#!/usr/bin/env python3\\n\"",
                "        \"import os, sys\\n\"",
                "        \"name = os.path.basename(sys.argv[0])\\n\"",
                "        \"log = os.environ.get('FAKE_ARGV_LOG')\\n\"",
                "        \"if log:\\n\"",
                "        \"    with open(log, 'a', encoding='utf-8') as h:\\n\"",
                "        \"        h.write(name + chr(9)\\n\"",
                "        \"                + chr(9).join(sys.argv[1:]) + chr(10))\\n\"",
                "        \"sys.stdout.write('FAKE-' + name.upper() + '-OUT '\\n\"",
                "        \"                 + ' '.join(sys.argv[1:]) + chr(10))\\n\"",
                "        \"sys.stderr.write('FAKE-' + name.upper() + '-ERR' + chr(10))\\n\"",
                "        \"sys.exit(int(os.environ.get('FAKE_' + name.upper()\\n\"",
                "        \"                                  + '_RC', '0')))\\n\")",
                "",
                "",
                "def make_tree(root, names):",
                "    bin_dir = os.path.join(root, 'bin')",
                "    os.makedirs(bin_dir, exist_ok=True)",
                "    for name in names:",
                "        path = os.path.join(bin_dir, name)",
                "        with open(path, 'w', encoding='utf-8') as h:",
                "            h.write(FAKE)",
                "        os.chmod(path, 0o755)",
                "    os.symlink('toolbox', os.path.join(bin_dir, 'getprop'))",
                "",
                "",
                "def run(args, root, extra_env=None):",
                "    env = dict(os.environ)",
                "    env['ANDROID_CLI_SYSTEM_ROOT'] = root",
                "    env['FAKE_ARGV_LOG'] = LOG",
                "    env.pop('ANDROID_CLI_QUIET', None)",
                "    if extra_env:",
                "        env.update(extra_env)",
                "    completed = subprocess.run([CLI] + args, env=env,",
                "                               stdout=subprocess.PIPE,",
                "                               stderr=subprocess.PIPE)",
                "    return (completed.returncode,",
                "            completed.stdout.decode('utf-8', 'replace'),",
                "            completed.stderr.decode('utf-8', 'replace'))",
                "",
                "",
                "def expect(condition, message):",
                "    if not condition:",
                "        failures.append(message)",
                "",
                "",
                "def clear_log():",
                "    if os.path.exists(LOG):",
                "        os.unlink(LOG)",
                "",
                "",
                "def read_log():",
                "    if not os.path.isfile(LOG):",
                "        return ''",
                "    with open(LOG, 'r', encoding='utf-8') as h:",
                "        return h.read()",
                "",
                "",
                "make_tree(FULL, ['sh', 'toolbox', 'toybox', 'su', 'faketool'])",
                "make_tree(BARE, ['sh', 'toolbox', 'toybox'])",
                "os.makedirs(os.path.join(EMPTY, 'bin'), exist_ok=True)",
                "",
                "# A fake termux_compat module stands in for the shared bridge",
                "# runtime: bridge_call replays canned answers or failures so",
                "# the permissions verb and doctor summary get a live",
                "# round-trip without a real bridge socket.",
                "FAKE_TC_DIR = os.path.join(WORK, 'fakelib')",
                "os.makedirs(FAKE_TC_DIR, exist_ok=True)",
                "ENVFILE = os.path.join(WORK, 'bridge.env')",
                "FAKE_TC = (\"import os\\n\"",
                "           \"class CliError(Exception):\\n\"",
                "           \"    pass\\n\"",
                "           \"class BridgeError(Exception):\\n\"",
                "           \"    pass\\n\"",
                "           \"def bridge_call(method, params=None, timeout=None):\\n\"",
                "           \"    if method != 'bridge.permissions':\\n\"",
                "           \"        raise CliError('unsupported-method')\\n\"",
                "           \"    mode = os.environ.get('FAKE_TC_MODE', 'ok')\\n\"",
                "           \"    if mode == 'typed':\\n\"",
                "           \"        raise BridgeError('fake-typed-error:hint text')\\n\"",
                "           \"    if mode == 'dead':\\n\"",
                "           \"        raise CliError('cannot read session env')\\n\"",
                "           \"    if mode == 'refused':\\n\"",
                "           \"        raise OSError(111, 'Connection refused')\\n\"",
                "           \"    if mode == 'malformed':\\n\"",
                "           \"        return {'permissions': 'bogus-state-string'}\\n\"",
                "           \"    return {\\n\"",
                "           \"        'v': 1, 'ok': True,\\n\"",
                "           \"        'permissions': (\\n\"",
                "           \"            'android.permission.CAMERA:granted,'\\n\"",
                "           \"            'android.permission.RECORD_AUDIO:required,'\\n\"",
                "           \"            'android.permission.SYSTEM_ALERT_WINDOW:denied,'\\n\"",
                "           \"            'android.permission.FUTURE_PERM:unsupported'),\\n\"",
                "           \"        'hints': (\\n\"",
                "           \"            'android.permission.RECORD_AUDIO='\\n\"",
                "           \"            'android.settings.APPLICATION_DETAILS_SETTINGS,'\\n\"",
                "           \"            'android.permission.SYSTEM_ALERT_WINDOW='\\n\"",
                "           \"            'android.settings.action.MANAGE_OVERLAY_PERMISSION,'\\n\"",
                "           \"            'android.permission.FUTURE_PERM='\\n\"",
                "           \"            'android.settings.APPLICATION_DETAILS_SETTINGS')}\\n\")",
                "with open(os.path.join(FAKE_TC_DIR, 'termux_compat.py'), 'w',",
                "          encoding='utf-8') as handle:",
                "    handle.write(FAKE_TC)",
                "with open(ENVFILE, 'w', encoding='utf-8') as handle:",
                "    handle.write('NUSADESK_ANDROID_BRIDGE_ADDRESS=127.0.0.1\\n')",
                "PERM_ENV = {'ANDROID_CLI_MODULE_DIR': FAKE_TC_DIR,",
                "            'ANDROID_CLI_ENV_FILE': ENVFILE}",
                "",
                "# status: one machine line on stdout, the info line on stderr.",
                "rc, out, err = run(['status'], FULL)",
                "expect(rc == 0, 'status: rc ' + str(rc))",
                "expect(out.strip() == 'tier=app source=native',",
                "       'status: out ' + repr(out))",
                "expect('tier=app source=native device=' in err,",
                "       'status: info ' + repr(err))",
                "",
                "# quiet: -q and ANDROID_CLI_QUIET=1 both suppress the info line.",
                "rc, out, err = run(['-q', 'status'], FULL)",
                "expect(rc == 0, 'quiet -q: rc ' + str(rc))",
                "expect('android-cli:' not in err, 'quiet -q: stderr ' + repr(err))",
                "rc, out, err = run(['status'], FULL, {'ANDROID_CLI_QUIET': '1'})",
                "expect(rc == 0, 'quiet env: rc ' + str(rc))",
                "expect('android-cli:' not in err, 'quiet env: stderr ' + repr(err))",
                "",
                "# doctor on the full tree: every probe ok, su present, tier app.",
                "rc, out, err = run(['doctor'], FULL)",
                "expect(rc == 0, 'doctor: rc ' + str(rc) + ' stderr=' + repr(err))",
                "for needle in ('sh: present, exec ok', 'toolbox: present, exec ok',",
                "               'toybox: present, exec ok', 'getprop: present',",
                "               'device su: present', 'tier: app source=native'):",
                "    expect(needle in out, 'doctor: missing ' + needle",
                "           + ' in ' + repr(out))",
                "",
                "# doctor on the empty tree: tier none and a typed exit.",
                "rc, out, err = run(['doctor'], EMPTY)",
                "expect(rc == 5, 'doctor-empty: rc ' + str(rc))",
                "expect('tier: none source=native' in out,",
                "       'doctor-empty: tier ' + repr(out))",
                "expect('device su: absent' in out, 'doctor-empty: su ' + repr(out))",
                "",
                "# status on the empty tree reports tier=none.",
                "rc, out, err = run(['status'], EMPTY)",
                "expect(out.strip() == 'tier=none source=native',",
                "       'status-empty: ' + repr(out))",
                "",
                "# sh delegates with the caller's argv.",
                "clear_log()",
                "rc, out, err = run(['sh', '-c', 'echo hi'], FULL)",
                "expect(rc == 0, 'sh: rc ' + str(rc) + ' stderr=' + repr(err))",
                "expect('sh' + TAB + '-c' + TAB + 'echo hi' in read_log(),",
                "       'sh: argv ' + repr(read_log()))",
                "expect('FAKE-SH-OUT -c echo hi' in out, 'sh: out ' + repr(out))",
                "",
                "# getprop runs through the toolbox symlink.",
                "clear_log()",
                "rc, out, err = run(['getprop', 'ro.example'], FULL)",
                "expect(rc == 0, 'getprop: rc ' + str(rc))",
                "expect('getprop' + TAB + 'ro.example' in read_log(),",
                "       'getprop: argv ' + repr(read_log()))",
                "",
                "# toolbox and toybox applets.",
                "clear_log()",
                "rc, out, err = run(['toolbox', 'ls', '/sdcard'], FULL)",
                "expect(rc == 0, 'toolbox: rc ' + str(rc))",
                "expect('toolbox' + TAB + 'ls' + TAB + '/sdcard' in read_log(),",
                "       'toolbox: argv ' + repr(read_log()))",
                "clear_log()",
                "rc, out, err = run(['toybox', 'echo', 'a b'], FULL)",
                "expect(rc == 0, 'toybox: rc ' + str(rc))",
                "expect('toybox' + TAB + 'echo' + TAB + 'a b' in read_log(),",
                "       'toybox: argv ' + repr(read_log()))",
                "",
                "# exec passes argv through as a list, spaces intact.",
                "clear_log()",
                "tool = os.path.join(FULL, 'bin', 'faketool')",
                "rc, out, err = run(['exec', tool, 'a', 'b c'], FULL)",
                "expect(rc == 0, 'exec: rc ' + str(rc))",
                "expect('faketool' + TAB + 'a' + TAB + 'b c' in read_log(),",
                "       'exec: argv ' + repr(read_log()))",
                "",
                "# exec boundaries: relative path and outside the tree are usage;",
                "# a missing path under the tree is the typed 5.",
                "rc, out, err = run(['exec', 'faketool'], FULL)",
                "expect(rc == 2, 'exec-relative: rc ' + str(rc))",
                "rc, out, err = run(['exec', '/bin/true'], FULL)",
                "expect(rc == 2 and 'not under' in err,",
                "       'exec-outside: rc ' + str(rc) + ' ' + repr(err))",
                "rc, out, err = run(['exec', os.path.join(FULL, 'bin', 'nothere')],",
                "                 FULL)",
                "expect(rc == 5 and 'nothere' in err,",
                "       'exec-missing: rc ' + str(rc) + ' ' + repr(err))",
                "",
                "# A denied child keeps its own stderr and exit code, then the",
                "# one hint line.",
                "rc, out, err = run(['toybox', 'echo'], FULL, {'FAKE_TOYBOX_RC': '7'})",
                "expect(rc == 7, 'rc-propagation: rc ' + str(rc))",
                "expect('FAKE-TOYBOX-ERR' in err,",
                "       'rc-propagation: child stderr kept ' + repr(err))",
                "expect('android-cli: hint:' in err,",
                "       'rc-propagation: hint ' + repr(err))",
                "",
                "# su: delegates when the device has one; a typed 5 when not.",
                "clear_log()",
                "rc, out, err = run(['su', '-c', 'id'], FULL)",
                "expect(rc == 0, 'su: rc ' + str(rc))",
                "expect('su' + TAB + '-c' + TAB + 'id' in read_log(),",
                "       'su: argv ' + repr(read_log()))",
                "rc, out, err = run(['su'], BARE)",
                "expect(rc == 5 and 'device su absent' in err,",
                "       'su-absent: rc ' + str(rc) + ' ' + repr(err))",
                "rc, out, err = run(['doctor'], BARE)",
                "expect(rc == 0, 'doctor-bare: rc ' + str(rc))",
                "expect('device su: absent' in out, 'doctor-bare: ' + repr(out))",
                "",
                "# permissions: a live bridge answers the declared grant report.",
                "rc, out, err = run(['permissions'], FULL, PERM_ENV)",
                "expect(rc == 0, 'permissions: rc ' + str(rc) + ' ' + repr(err))",
                "expect('tier=app source=bridge device=' in err,",
                "       'permissions: info ' + repr(err))",
                "for needle in ('android-cli permissions',",
                "               'android.permission.CAMERA: granted',",
                "               'android.permission.RECORD_AUDIO: required'",
                "               + '  settings: '",
                "               + 'android.settings.APPLICATION_DETAILS_SETTINGS',",
                "               'android.permission.SYSTEM_ALERT_WINDOW: denied'",
                "               + '  settings: '",
                "               + 'android.settings.action.MANAGE_OVERLAY_PERMISSION',",
                "               'android.permission.FUTURE_PERM: unsupported',",
                "               'summary: 1 granted, 1 required, 1 denied,'",
                "               + ' 1 unsupported',",
                "               'permission.request'):",
                "    expect(needle in out, 'permissions: missing ' + needle",
                "           + ' in ' + repr(out))",
                "",
                "# A typed bridge error propagates verbatim at exit 1.",
                "env = dict(PERM_ENV)",
                "env['FAKE_TC_MODE'] = 'typed'",
                "rc, out, err = run(['permissions'], FULL, env)",
                "expect(rc == 1 and 'fake-typed-error:hint text' in err,",
                "       'permissions-typed: rc ' + str(rc) + ' ' + repr(err))",
                "",
                "# Unreachable and malformed answers are the honest typed 5 -",
                "# the states are reported unknown, never guessed.",
                "env = dict(PERM_ENV)",
                "env['FAKE_TC_MODE'] = 'dead'",
                "rc, out, err = run(['permissions'], FULL, env)",
                "expect(rc == 5 and 'permission states unknown' in err",
                "       and 'cannot read session env' in err,",
                "       'permissions-dead: rc ' + str(rc) + ' ' + repr(err))",
                "env = dict(PERM_ENV)",
                "env['FAKE_TC_MODE'] = 'refused'",
                "rc, out, err = run(['permissions'], FULL, env)",
                "expect(rc == 5 and 'permission states unknown' in err,",
                "       'permissions-refused: rc ' + str(rc) + ' ' + repr(err))",
                "env = dict(PERM_ENV)",
                "env['FAKE_TC_MODE'] = 'malformed'",
                "rc, out, err = run(['permissions'], FULL, env)",
                "expect(rc == 5 and 'malformed bridge response' in err,",
                "       'permissions-malformed: rc ' + str(rc) + ' ' + repr(err))",
                "",
                "# An absent runtime module is the same honest typed 5.",
                "rc, out, err = run(['permissions'], FULL,",
                "                   {'ANDROID_CLI_MODULE_DIR': EMPTY})",
                "expect(rc == 5 and 'not installed' in err,",
                "       'permissions-module: rc ' + str(rc) + ' ' + repr(err))",
                "",
                "# permissions takes no arguments.",
                "rc, out, err = run(['permissions', 'extra'], FULL, PERM_ENV)",
                "expect(rc == 2, 'permissions-args: rc ' + str(rc))",
                "",
                "# doctor prints the bridge permission summary after the tier",
                "# line, with the grant-opening guidance.",
                "rc, out, err = run(['doctor'], FULL, PERM_ENV)",
                "expect(rc == 0, 'doctor-perms: rc ' + str(rc) + ' ' + repr(err))",
                "expect('permissions (bridge): 1 granted, 1 required, 1 denied,'",
                "       + ' 1 unsupported' in out,",
                "       'doctor-perms: ' + repr(out))",
                "expect('permission.request' in out,",
                "       'doctor-perms: ' + repr(out))",
                "expect(out.index('tier: app') < out.index('permissions (bridge):'),",
                "       'doctor-perms: order ' + repr(out))",
                "",
                "# No session env: the summary is honestly unknown.",
                "env = dict(PERM_ENV)",
                "env['ANDROID_CLI_ENV_FILE'] = os.path.join(WORK, 'absent.env')",
                "rc, out, err = run(['doctor'], FULL, env)",
                "expect(rc == 0, 'doctor-noenv: rc ' + str(rc))",
                "expect('permissions (bridge): no session env - grant states'",
                "       + ' unknown' in out, 'doctor-noenv: ' + repr(out))",
                "",
                "# A missing requested tool is the typed 5 and names the path.",
                "rc, out, err = run(['toolbox', 'ls'], EMPTY)",
                "expect(rc == 5, 'missing: rc ' + str(rc))",
                "expect(os.path.join(EMPTY, 'bin', 'toolbox') in err,",
                "       'missing: path ' + repr(err))",
                "",
                "# usage: no args and unknown verbs are 2; --help prints usage.",
                "rc, out, err = run([], FULL)",
                "expect(rc == 2, 'usage-empty: rc ' + str(rc))",
                "rc, out, err = run(['--help'], FULL)",
                "expect(rc == 0 and 'usage: android-cli' in out,",
                "       'help: rc ' + str(rc) + ' ' + repr(out))",
                "rc, out, err = run(['nope'], FULL)",
                "expect(rc == 2, 'usage-verb: rc ' + str(rc))",
                "rc, out, err = run(['toybox'], FULL)",
                "expect(rc == 2, 'usage-applet: rc ' + str(rc))",
                "",
                "for failure in failures:",
                "    print(failure)",
                "sys.exit(1 if failures else 0)",
                "");
    }
}
