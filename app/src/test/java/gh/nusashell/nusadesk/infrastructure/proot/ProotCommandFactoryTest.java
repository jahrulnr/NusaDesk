package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link ProotCommandFactory}: argv construction, ordering, and
 * that the guest argv is never interpolated. No Android, no filesystem.
 */
public class ProotCommandFactoryTest {

    private static final String PROOT = "/data/app/lib/arm64/libproot.so";
    private static final String ROOTFS = "/data/data/gh.nusashell.nusadesk/files/linux-wrapper/runtimes/ubuntu-base-arm64/active";

    private ProotLaunchSpec probeSpec() {
        return ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestWorkdir("/root")
                .guestArgv(ProotLauncher.GUEST_PROBE_ARGV)
                .bindMounts(ProotCommandFactory.DEFAULT_SYSTEM_BINDS)
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .build();
    }

    @Test
    public void buildArgvProducesExpectedOrder() {
        List<String> argv = new ProotCommandFactory().buildArgv(probeSpec());
        // [proot, -r, rootfs, -b, /proc:/proc, -b, /dev:/dev, -w, /root, --kill-on-exit, --, /bin/sh, -c, uname -a]
        assertEquals(PROOT, argv.get(0));
        assertEquals(ProotCommandFactory.OPT_ROOTFS, argv.get(1));
        assertEquals(ROOTFS, argv.get(2));
        assertEquals(ProotCommandFactory.OPT_BIND, argv.get(3));
        assertEquals("/proc:/proc", argv.get(4));
        assertEquals(ProotCommandFactory.OPT_BIND, argv.get(5));
        assertEquals("/dev:/dev", argv.get(6));
        assertEquals(ProotCommandFactory.OPT_WORKDIR, argv.get(7));
        assertEquals("/root", argv.get(8));
        assertEquals(ProotCommandFactory.OPT_KILL_ON_EXIT, argv.get(9));
        assertEquals("/bin/sh", argv.get(10));
        assertEquals("-c", argv.get(11));
        assertEquals("uname -a", argv.get(12));
        assertEquals(13, argv.size());
    }

    @Test
    public void buildArgvIsImmutable() {
        List<String> argv = new ProotCommandFactory().buildArgv(probeSpec());
        try {
            argv.add("evil");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void guestArgvIsAppendedVerbatimAtTail() {
        // A guest arg that looks like shell metacharacters must not be interpreted.
        List<String> guestArgv = Arrays.asList("/bin/sh", "-c", "echo $HOME; rm -rf /; $(touch /tmp/x)");
        ProotLaunchSpec spec = ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestArgv(guestArgv)
                .bindMounts(ProotCommandFactory.DEFAULT_SYSTEM_BINDS)
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .build();
        List<String> argv = new ProotCommandFactory().buildArgv(spec);
        // No "--" separator is emitted; the guest argv is the verbatim tail.
        List<String> tail = argv.subList(argv.size() - guestArgv.size(), argv.size());
        assertEquals(guestArgv, tail);
        // The entrypoint is the first non-option after the PRoot flags.
        assertEquals("/bin/sh", tail.get(0));
    }

    @Test
    public void fakeRootEmitsDashZeroAfterRootfs() {
        ProotLaunchSpec spec = ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestArgv(ProotLauncher.GUEST_PROBE_ARGV)
                .bindMounts(ProotCommandFactory.DEFAULT_SYSTEM_BINDS)
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .fakeRoot(true)
                .build();
        List<String> argv = new ProotCommandFactory().buildArgv(spec);
        assertEquals(ProotCommandFactory.OPT_FAKE_ROOT, argv.get(3));
    }

    @Test
    public void fakeRootDefaultsToFalse() {
        List<String> argv = new ProotCommandFactory().buildArgv(probeSpec());
        assertFalse(argv.contains(ProotCommandFactory.OPT_FAKE_ROOT));
        assertFalse(probeSpec().isFakeRoot());
    }

    @Test
    public void killOnExitFalseOmitsFlag() {
        ProotLaunchSpec spec = ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestArgv(ProotLauncher.GUEST_PROBE_ARGV)
                .bindMounts(ProotCommandFactory.DEFAULT_SYSTEM_BINDS)
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .killOnExit(false)
                .build();
        List<String> argv = new ProotCommandFactory().buildArgv(spec);
        assertFalse(argv.contains(ProotCommandFactory.OPT_KILL_ON_EXIT));
    }

    @Test
    public void emptyWorkdirOmitsWorkdirFlag() {
        ProotLaunchSpec spec = ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestWorkdir("")
                .guestArgv(ProotLauncher.GUEST_PROBE_ARGV)
                .bindMounts(ProotCommandFactory.DEFAULT_SYSTEM_BINDS)
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .build();
        List<String> argv = new ProotCommandFactory().buildArgv(spec);
        assertFalse(argv.contains(ProotCommandFactory.OPT_WORKDIR));
    }

    @Test
    public void extraAppPrivateBindAppearsAfterSystemBinds() {
        ProotBindMount hostKeys = ProotBindMount.of(
                "/data/data/gh.nusashell.nusadesk/files/linux-wrapper/hostkeys",
                "/root/.ssh");
        ProotLaunchSpec spec = ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestArgv(ProotLauncher.GUEST_PROBE_ARGV)
                .bindMounts(Arrays.asList(
                        ProotBindMount.of("/proc", "/proc"),
                        ProotBindMount.of("/dev", "/dev"),
                        hostKeys))
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .build();
        List<String> argv = new ProotCommandFactory().buildArgv(spec);
        int firstBind = argv.indexOf(ProotCommandFactory.OPT_BIND);
        int lastBind = argv.lastIndexOf(ProotCommandFactory.OPT_BIND);
        assertEquals("/proc:/proc", argv.get(firstBind + 1));
        assertEquals(hostKeys.toBindArgument(), argv.get(lastBind + 1));
    }

    @Test
    public void buildArgvRejectsNullSpec() {
        try {
            new ProotCommandFactory().buildArgv(null);
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void defaultSystemBindsAreProcAndDev() {
        assertEquals(2, ProotCommandFactory.DEFAULT_SYSTEM_BINDS.size());
        assertEquals(ProotBindMount.of("/proc", "/proc"), ProotCommandFactory.DEFAULT_SYSTEM_BINDS.get(0));
        assertEquals(ProotBindMount.of("/dev", "/dev"), ProotCommandFactory.DEFAULT_SYSTEM_BINDS.get(1));
    }

    @Test
    public void noShellStringOrUrlAnywhere() {
        List<String> argv = new ProotCommandFactory().buildArgv(probeSpec());
        // The PRoot binary is invoked directly as argv[0], never via a host shell.
        assertEquals(PROOT, argv.get(0));
        assertFalse("argv[0] is not a shell", "sh".equals(argv.get(0)) || "/bin/sh".equals(argv.get(0)));
        for (String arg : argv) {
            assertFalse("no http url: " + arg, arg.startsWith("http://") || arg.startsWith("https://"));
        }
        // The guest "-c" is the /bin/sh option, not a host shell invocation; it
        // appears only after the guest entrypoint "/bin/sh" (no "--" separator).
        int sh = argv.indexOf("/bin/sh");
        assertTrue("/bin/sh present", sh >= 0);
        assertTrue("'-c' appears after the guest entrypoint", sh < argv.indexOf("-c"));
        // No bare "--" separator is emitted: this PRoot build rejects it.
        assertFalse("no bare -- separator", argv.contains("--"));
    }

    @Test
    public void emptyBindListProducesNoBindFlags() {
        ProotLaunchSpec spec = ProotLaunchSpec.builder()
                .prootBinary(PROOT)
                .rootfs(ROOTFS)
                .guestArgv(ProotLauncher.GUEST_PROBE_ARGV)
                .bindMounts(Collections.<ProotBindMount>emptyList())
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files")
                .build();
        List<String> argv = new ProotCommandFactory().buildArgv(spec);
        assertFalse(argv.contains(ProotCommandFactory.OPT_BIND));
    }
}
