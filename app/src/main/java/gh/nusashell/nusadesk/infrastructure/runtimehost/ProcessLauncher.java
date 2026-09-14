package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.util.List;
import java.util.Map;

/**
 * Port that starts the guest runtime process and returns a {@link ProcessHandle}.
 *
 * <p>Kept local to the runtimehost package so the {@link RuntimeSupervisor} can
 * be unit-tested with a fake launcher instead of fake Linux execution. The
 * concrete {@link JdkProcessLauncher} uses {@link java.lang.ProcessBuilder}.</p>
 *
 * <p>Implementations take an <em>argv array</em>, never a shell string, to avoid
 * shell interpolation. The entrypoint is supplied by the caller (the future
 * verified execution bridge) and must come from an allowlisted, versioned
 * catalog; this port does not itself validate the executable path or ABI.</p>
 */
public interface ProcessLauncher {
    /**
     * Start the guest process.
     *
     * @param argv     argv array; non-null, non-empty, no null elements
     * @param extraEnv additional environment mappings merged over the inherited
     *                 environment; may be null or empty
     * @return a handle to the started process
     * @throws ProcessLaunchException if the process could not be started
     */
    ProcessHandle launch(List<String> argv, Map<String, String> extraEnv) throws ProcessLaunchException;
}
