package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@link ProcessLauncher} backed by {@link java.lang.ProcessBuilder}.
 *
 * <p>This adapter starts a process from an argv array (never a shell string) and
 * merges {@code extraEnv} over the inherited environment. It does not validate
 * that the entrypoint is an allowlisted, verified runtime binary; that is the
 * responsibility of the caller that assembles the argv from the curated catalog
 * and the future verified execution bridge. The adapter itself is a generic
 * JDK process starter, not the PRoot/QEMU/Linux execution bridge.</p>
 */
public final class JdkProcessLauncher implements ProcessLauncher {

    @Override
    public ProcessHandle launch(List<String> argv, Map<String, String> extraEnv) throws ProcessLaunchException {
        if (argv == null || argv.isEmpty()) {
            throw new ProcessLaunchException("argv must not be null or empty");
        }
        for (String arg : argv) {
            if (arg == null) {
                throw new ProcessLaunchException("argv must not contain null elements");
            }
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            if (extraEnv != null && !extraEnv.isEmpty()) {
                builder.environment().putAll(extraEnv);
            }
            builder.redirectErrorStream(false);
            return new JdkProcessHandle(builder.start());
        } catch (IOException e) {
            throw new ProcessLaunchException("failed to start process", e);
        }
    }
}
