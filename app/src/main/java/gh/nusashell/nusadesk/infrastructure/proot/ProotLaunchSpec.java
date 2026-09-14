package gh.nusashell.nusadesk.infrastructure.proot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, fully-resolved specification for one PRoot launch.
 *
 * <p>This is the testable builder surface that does not require Android: tests
 * construct a spec directly with absolute paths and assert the resulting argv
 * via {@link ProotCommandFactory}. The Android adapter
 * {@link ProotLauncher} resolves the concrete paths from a {@code Context} and
 * then builds a spec through this builder.</p>
 *
 * <p>The spec carries the PRoot binary path, the curated active rootfs path, the
 * fixed guest argv (run after PRoot's {@code --}), the guest working directory,
 * the bind mounts, the explicit environment map, and the host working directory
 * for {@link java.lang.ProcessBuilder}. No field is a shell string or a URL.</p>
 */
public final class ProotLaunchSpec {
    private final String prootBinary;
    private final String rootfs;
    private final String guestWorkdir;
    private final List<String> guestArgv;
    private final List<ProotBindMount> bindMounts;
    private final Map<String, String> env;
    private final String hostWorkingDir;
    private final boolean killOnExit;
    private final boolean fakeRoot;

    private ProotLaunchSpec(Builder builder) {
        this.prootBinary = builder.prootBinary;
        this.rootfs = builder.rootfs;
        this.guestWorkdir = builder.guestWorkdir;
        this.guestArgv = Collections.unmodifiableList(new ArrayList<>(builder.guestArgv));
        this.bindMounts = Collections.unmodifiableList(new ArrayList<>(builder.bindMounts));
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(builder.env));
        this.hostWorkingDir = builder.hostWorkingDir;
        this.killOnExit = builder.killOnExit;
        this.fakeRoot = builder.fakeRoot;
    }

    public String getProotBinary() {
        return prootBinary;
    }

    public String getRootfs() {
        return rootfs;
    }

    public String getGuestWorkdir() {
        return guestWorkdir;
    }

    public List<String> getGuestArgv() {
        return guestArgv;
    }

    public List<ProotBindMount> getBindMounts() {
        return bindMounts;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public String getHostWorkingDir() {
        return hostWorkingDir;
    }

    public boolean isKillOnExit() {
        return killOnExit;
    }

    /**
     * Whether PRoot {@code -0} (fake root id) is requested. Required for guest
     * daemons such as {@code sshd} that refuse to run unless euid is 0; the
     * emulation is confined to the guest process tree.
     */
    public boolean isFakeRoot() {
        return fakeRoot;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link ProotLaunchSpec}. Validates every field on {@link #build()}. */
    public static final class Builder {
        private String prootBinary;
        private String rootfs;
        private String guestWorkdir = "/";
        private final List<String> guestArgv = new ArrayList<>();
        private final List<ProotBindMount> bindMounts = new ArrayList<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private String hostWorkingDir;
        private boolean killOnExit = true;
        private boolean fakeRoot;

        /** Absolute path to the packaged PRoot PIE executable ({@code libproot.so}). */
        public Builder prootBinary(String prootBinary) {
            this.prootBinary = prootBinary;
            return this;
        }

        /** Absolute path to the curated active rootfs directory. */
        public Builder rootfs(String rootfs) {
            this.rootfs = rootfs;
            return this;
        }

        /** Absolute guest working directory passed to PRoot {@code -w}. Defaults to {@code /}. */
        public Builder guestWorkdir(String guestWorkdir) {
            this.guestWorkdir = guestWorkdir;
            return this;
        }

        /** Fixed argv executed inside the guest after PRoot's {@code --}. Non-empty, no nulls. */
        public Builder guestArgv(List<String> guestArgv) {
            this.guestArgv.clear();
            if (guestArgv != null) {
                this.guestArgv.addAll(guestArgv);
            }
            return this;
        }

        /** Add a single guest argv element. */
        public Builder addGuestArg(String arg) {
            this.guestArgv.add(arg);
            return this;
        }

        /** Replace the bind-mount list. */
        public Builder bindMounts(List<ProotBindMount> bindMounts) {
            this.bindMounts.clear();
            if (bindMounts != null) {
                this.bindMounts.addAll(bindMounts);
            }
            return this;
        }

        /** Add a single bind mount. */
        public Builder addBindMount(ProotBindMount bindMount) {
            this.bindMounts.add(bindMount);
            return this;
        }

        /** Replace the explicit environment map. */
        public Builder env(Map<String, String> env) {
            this.env.clear();
            if (env != null) {
                this.env.putAll(env);
            }
            return this;
        }

        /** Add or override one environment variable. */
        public Builder putEnv(String key, String value) {
            this.env.put(key, value);
            return this;
        }

        /** Absolute host working directory for {@link java.lang.ProcessBuilder#directory}. */
        public Builder hostWorkingDir(String hostWorkingDir) {
            this.hostWorkingDir = hostWorkingDir;
            return this;
        }

        /** Whether to pass PRoot {@code --kill-on-exit} for guest-tree cleanup. Default {@code true}. */
        public Builder killOnExit(boolean killOnExit) {
            this.killOnExit = killOnExit;
            return this;
        }

        /**
         * Whether to pass PRoot {@code -0} so the guest sees uid/gid 0. Needed
         * by daemons that require root; the fake id never applies to the host
         * process. Default {@code false}.
         */
        public Builder fakeRoot(boolean fakeRoot) {
            this.fakeRoot = fakeRoot;
            return this;
        }

        /** Build and validate the immutable spec. */
        public ProotLaunchSpec build() {
            if (prootBinary == null) {
                throw new IllegalArgumentException("prootBinary must not be null");
            }
            if (rootfs == null) {
                throw new IllegalArgumentException("rootfs must not be null");
            }
            if (hostWorkingDir == null) {
                throw new IllegalArgumentException("hostWorkingDir must not be null");
            }
            ProotPaths.requireAbsolutePath(prootBinary, "prootBinary");
            ProotPaths.requireAbsolutePath(rootfs, "rootfs");
            ProotPaths.requireAbsolutePath(hostWorkingDir, "hostWorkingDir");
            if (guestWorkdir != null && !guestWorkdir.isEmpty()) {
                ProotPaths.requireAbsolutePath(guestWorkdir, "guestWorkdir");
            }
            if (guestArgv.isEmpty()) {
                throw new IllegalArgumentException("guestArgv must not be empty");
            }
            for (String arg : guestArgv) {
                if (arg == null) {
                    throw new IllegalArgumentException("guestArgv must not contain null elements");
                }
            }
            if (guestArgv.get(0) == null || guestArgv.get(0).trim().isEmpty()) {
                throw new IllegalArgumentException("guestArgv entrypoint must not be blank");
            }
            // The command factory emits no "--" separator (the packaged PRoot build
            // rejects it), so the entrypoint must not begin with '-' or PRoot would
            // misparse it as an option. Executable paths never begin with '-'.
            if (guestArgv.get(0).startsWith("-")) {
                throw new IllegalArgumentException(
                        "guestArgv entrypoint must not begin with '-' (no PRoot '--' separator): " + guestArgv.get(0));
            }
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    throw new IllegalArgumentException("env keys must not be null or empty");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("env value for " + entry.getKey() + " must not be null");
                }
            }
            return new ProotLaunchSpec(this);
        }
    }
}
