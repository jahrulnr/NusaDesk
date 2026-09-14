package gh.nusashell.nusadesk.infrastructure.proot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the fixed PRoot argv array from a {@link ProotLaunchSpec}.
 *
 * <p>Pure Java, no Android, no filesystem, no network: this is the testable
 * command builder. It produces an argv array (never a shell string) of the form:</p>
 *
 * <pre>
 *   libproot.so -r <rootfs>
 *              -b <host>:<guest> ...
 *              -w <guestWorkdir>
 *              --kill-on-exit
 *              <guestArgv...>
 * </pre>
 *
 * <p>The guest argv is appended verbatim after the PRoot options; no element is
 * interpreted, split, or interpolated. {@code --kill-on-exit} is included by
 * default so PRoot tears down the guest process tree when it exits, avoiding
 * orphaned children (AGENTS.md process rules).</p>
 *
 * <p><strong>No {@code --} separator is emitted.</strong> The packaged PRoot
 * build (termux fork v5.1.107.92) rejects the bare {@code --} option
 * ({@code proot error: unknown option '--'}, verified on an Android 10/API 29
 * arm64 device). PRoot's parser treats the first non-option argument as the
 * guest command and passes the remaining arguments through verbatim, so the
 * fixed guest argv is appended directly. {@link ProotLaunchSpec.Builder}
 * rejects guest entrypoints that begin with {@code -} to prevent PRoot from
 * misinterpreting them as options.</p>
 */
public final class ProotCommandFactory {

    /** PRoot option flags used by this bridge. */
    public static final String OPT_ROOTFS = "-r";
    public static final String OPT_BIND = "-b";
    public static final String OPT_WORKDIR = "-w";
    public static final String OPT_KILL_ON_EXIT = "--kill-on-exit";
    /** PRoot {@code -0}: fake uid/gid 0 inside the guest for root-requiring daemons. */
    public static final String OPT_FAKE_ROOT = "-0";

    /** Default fixed system bind mounts: {@code /proc} and {@code /dev}, required for guest operation. */
    public static final List<ProotBindMount> DEFAULT_SYSTEM_BINDS;
    static {
        List<ProotBindMount> binds = new ArrayList<>();
        binds.add(ProotBindMount.of("/proc", "/proc"));
        binds.add(ProotBindMount.of("/dev", "/dev"));
        DEFAULT_SYSTEM_BINDS = Collections.unmodifiableList(binds);
    }

    public ProotCommandFactory() {
    }

    /**
     * Build the immutable PRoot argv array for the given spec.
     *
     * @throws IllegalArgumentException if the spec is null
     */
    public List<String> buildArgv(ProotLaunchSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        List<String> argv = new ArrayList<>();
        argv.add(spec.getProotBinary());
        argv.add(OPT_ROOTFS);
        argv.add(spec.getRootfs());
        if (spec.isFakeRoot()) {
            argv.add(OPT_FAKE_ROOT);
        }
        for (ProotBindMount bind : spec.getBindMounts()) {
            argv.add(OPT_BIND);
            argv.add(bind.toBindArgument());
        }
        String workdir = spec.getGuestWorkdir();
        if (workdir != null && !workdir.isEmpty()) {
            argv.add(OPT_WORKDIR);
            argv.add(workdir);
        }
        if (spec.isKillOnExit()) {
            argv.add(OPT_KILL_ON_EXIT);
        }
        // No "--" separator (see class Javadoc): the packaged PRoot build rejects
        // "--". PRoot treats the first non-option argument as the guest command
        // and passes the rest through verbatim, so the guest argv is appended
        // directly. The spec builder guarantees the entrypoint does not begin
        // with '-' so it is not misparsed as a PRoot option.
        for (String arg : spec.getGuestArgv()) {
            argv.add(arg);
        }
        return Collections.unmodifiableList(argv);
    }
}
