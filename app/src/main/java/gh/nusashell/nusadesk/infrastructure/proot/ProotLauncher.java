package gh.nusashell.nusadesk.infrastructure.proot;

import android.content.Context;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.infrastructure.network.GuestDnsResolver;
import gh.nusashell.nusadesk.infrastructure.runtimehost.JdkProcessHandle;
import gh.nusashell.nusadesk.infrastructure.runtimehost.ProcessHandle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Android adapter that launches the packaged PRoot bridge against the curated
 * active rootfs.
 *
 * <p>This is the only class in the {@code proot} package that touches Android.
 * It resolves the packaged {@code libproot.so} from
 * {@link android.content.Context#getApplicationInfo() applicationInfo.nativeLibraryDir},
 * resolves the curated active rootfs from {@link Context#getFilesDir()}, validates
 * the rootfs, builds a {@link ProotLaunchSpec} with a fixed argv and explicit env,
 * and starts the process with {@link java.lang.ProcessBuilder} (working dir and
 * explicit env, never a shell string).</p>
 *
 * <p>Security contract (AGENTS.md): only the curated {@code ubuntu-base-arm64}
 * app id is accepted; the active rootfs must contain {@code etc/os-release} and
 * {@code usr/bin/sh}; the guest argv is fixed by the caller and never
 * interpolated; bind mounts are the fixed system set plus optional app-private
 * host paths (host keys, app-private storage) — never arbitrary host paths.
 * The environment is cleared and set explicitly so no host secrets leak into
 * the guest. The returned {@link ProcessHandle} is the same port the
 * {@code RuntimeSupervisor} uses, so a future supervisor integration can adopt
 * this launcher without a new abstraction.</p>
 *
 * <p>This launcher does <strong>not</strong> prove the guest runs. Per ADR-004 and
 * ADR-007, on-device execution requires an {@code adb} spike on Android 10+ with
 * the curated rootfs installed. No {@code STARTING}/{@code RUNNING} state is
 * honest until that spike passes.</p>
 */
public final class ProotLauncher {
    /** Curated app id accepted by this launcher. */
    public static final String SUPPORTED_APP_ID =
            CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId();

    /** Fixed guest probe argv for the on-device proof: {@code /bin/sh -c 'uname -a'}. */
    public static final List<String> GUEST_PROBE_ARGV = Collections.unmodifiableList(
            Arrays.asList("/bin/sh", "-c", "uname -a"));

    /** Default guest-facing environment injected through PRoot. */
    public static final String ENV_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    public static final String ENV_HOME = "/root";
    public static final String ENV_USER = "root";
    public static final String ENV_TERM = "linux";
    public static final String ENV_LANG = "C.UTF-8";
    public static final String ENV_PROOT_TMP_DIR = "PROOT_TMP_DIR";
    /**
     * Path to the packaged PRoot ELF loader in {@code nativeLibraryDir}
     * ({@code apk_data_file}, exec-allowed under {@code untrusted_app}).
     * Without it PRoot extracts its embedded loader into {@code PROOT_TMP_DIR}
     * ({@code app_data_file}) and the re-exec is denied by SELinux on
     * targetSdk 29+.
     */
    public static final String ENV_PROOT_LOADER = "PROOT_LOADER";

    private final Context context;
    private final ProotCommandFactory commandFactory;
    private final ProotRootfsValidator rootfsValidator;
    private final GuestDnsResolver dnsResolver;

    public ProotLauncher(Context context) {
        this(context, new ProotCommandFactory(), new ProotRootfsValidator(),
                new GuestDnsResolver(context, null));
    }

    ProotLauncher(Context context, ProotCommandFactory commandFactory, ProotRootfsValidator rootfsValidator) {
        this(context, commandFactory, rootfsValidator, new GuestDnsResolver(context, null));
    }

    /**
     * Full constructor. The {@link GuestDnsResolver} writes the guest
     * {@code /etc/resolv.conf} from Android's active-network DNS and returns
     * the bind added to every launch spec (see {@link #buildSpec}). Pass a
     * test double to make the bind deterministic without Android.
     */
    ProotLauncher(Context context, ProotCommandFactory commandFactory,
                  ProotRootfsValidator rootfsValidator, GuestDnsResolver dnsResolver) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (commandFactory == null) {
            throw new IllegalArgumentException("commandFactory must not be null");
        }
        if (rootfsValidator == null) {
            throw new IllegalArgumentException("rootfsValidator must not be null");
        }
        if (dnsResolver == null) {
            throw new IllegalArgumentException("dnsResolver must not be null");
        }
        this.context = context.getApplicationContext();
        this.commandFactory = commandFactory;
        this.rootfsValidator = rootfsValidator;
        this.dnsResolver = dnsResolver;
    }

    /**
     * Start rewriting the guest resolver when the active network changes.
     * Called by the long-lived workload while the supervised runtime runs;
     * idempotent. Transient callers (the deb extractor, the on-device probe)
     * do not call this.
     */
    public void startResolverRefresh() {
        dnsResolver.startRefresh();
    }

    /** Stop the resolver refresh. Idempotent; safe to call from any teardown path. */
    public void stopResolverRefresh() {
        dnsResolver.stopRefresh();
    }

    /**
     * Build a validated {@link ProotLaunchSpec} for the curated runtime and a
     * fixed guest argv, without starting a process. Exposed so callers and tests
     * can inspect the resolved argv before any process is started.
     *
     * @param appId      must equal {@link #SUPPORTED_APP_ID}
     * @param guestArgv  fixed argv executed inside the guest after PRoot's {@code --}
     * @param extraBinds optional additional bind mounts; non-system host paths
     *                   must be app-private or they are rejected
     * @param extraEnv   optional environment overrides merged over the default env
     */
    public ProotLaunchSpec buildSpec(
            String appId,
            List<String> guestArgv,
            List<ProotBindMount> extraBinds,
            Map<String, String> extraEnv) throws ProotLaunchException {
        return buildSpec(appId, guestArgv, extraBinds, extraEnv, false);
    }

    /**
     * Like {@link #buildSpec(String, List, List, Map)} with an explicit fake-root
     * flag for guests that must see uid 0 (e.g. OpenSSH {@code sshd}).
     */
    public ProotLaunchSpec buildSpec(
            String appId,
            List<String> guestArgv,
            List<ProotBindMount> extraBinds,
            Map<String, String> extraEnv,
            boolean fakeRoot) throws ProotLaunchException {
        RuntimeCatalogEntry entry = CuratedRuntimeCatalog.ubuntuBaseArm64();
        if (!entry.getAppId().equals(appId)) {
            throw new ProotLaunchException(
                    "unsupported runtime app id: " + appId + " (expected " + entry.getAppId() + ")");
        }

        Path filesDir = context.getFilesDir().toPath();
        Path activeRootfs = ProotPaths.activeRootfsPath(filesDir, appId);
        rootfsValidator.validate(activeRootfs, appId);

        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        Path prootBinary = ProotPaths.prootBinaryPath(nativeLibraryDir);
        if (!Files.isRegularFile(prootBinary)) {
            throw new ProotLaunchException("packaged PRoot binary not found at " + prootBinary);
        }
        if (!Files.isExecutable(prootBinary)) {
            throw new ProotLaunchException("packaged PRoot binary is not executable: " + prootBinary);
        }

        List<ProotBindMount> binds = new ArrayList<>(ProotCommandFactory.DEFAULT_SYSTEM_BINDS);
        // Bind the guest /etc/resolv.conf from Android's active-network DNS so
        // every PRoot path (setup, daemon, deb extraction, probe) resolves
        // consistently. When Android has no valid DNS, no bind is added and the
        // guest keeps its own resolver (graceful). The host file is app-private
        // (cache dir); it is never a broad host filesystem bind and the active
        // rootfs is never mutated.
        Optional<ProotBindMount> resolver = dnsResolver.resolverBind();
        resolver.ifPresent(binds::add);
        if (extraBinds != null) {
            List<String> appRoots = appPrivateRoots();
            for (ProotBindMount bind : extraBinds) {
                if (isSystemBind(bind)) {
                    // System binds are already provided as defaults; reject duplicates to keep the set fixed.
                    throw new ProotLaunchException("duplicate system bind mount: " + bind);
                }
                if (!ProotPaths.isAppPrivatePath(bind.getHostPath(), appRoots)) {
                    throw new ProotLaunchException(
                            "non-system bind host path must be app-private: " + bind.getHostPath());
                }
                binds.add(bind);
            }
        }

        Map<String, String> env = defaultEnv();
        Path prootLoader = ProotPaths.prootLoaderPath(nativeLibraryDir);
        if (Files.isRegularFile(prootLoader)) {
            env.put(ENV_PROOT_LOADER, prootLoader.toString());
        }
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }

        return ProotLaunchSpec.builder()
                .prootBinary(prootBinary.toString())
                .rootfs(activeRootfs.toString())
                .guestWorkdir(ENV_HOME)
                .guestArgv(guestArgv)
                .bindMounts(binds)
                .env(env)
                .hostWorkingDir(filesDir.toString())
                .killOnExit(true)
                .fakeRoot(fakeRoot)
                .build();
    }

    /**
     * Build and start the PRoot process for the curated runtime.
     *
     * @return a handle over the started PRoot process
     * @throws ProotLaunchException if the spec is invalid or the process cannot start
     */
    public ProcessHandle launch(
            String appId,
            List<String> guestArgv,
            List<ProotBindMount> extraBinds,
            Map<String, String> extraEnv) throws ProotLaunchException {
        ProotLaunchSpec spec = buildSpec(appId, guestArgv, extraBinds, extraEnv);
        return launchSpec(spec);
    }

    /**
     * Start a PRoot process from an already-built spec.
     *
     * <p>Uses {@link java.lang.ProcessBuilder} with an explicit working directory
     * and a cleared-then-set explicit environment. The argv comes from
     * {@link ProotCommandFactory} and is never a shell string.</p>
     */
    public ProcessHandle launchSpec(ProotLaunchSpec spec) throws ProotLaunchException {
        return new JdkProcessHandle(launchProcess(spec));
    }

    /**
     * Start a PRoot process and return the raw {@link Process} so callers that
     * need bidirectional stream access (the SSH bridge's guest shell) can own
     * stdin/stdout/stderr directly. {@link ProcessHandle} deliberately exposes
     * no stdin, so the interactive shell path uses this method instead.
     *
     * @return the live process; the caller owns its streams and teardown
     * @throws ProotLaunchException if the process cannot be started
     */
    public Process launchProcess(ProotLaunchSpec spec) throws ProotLaunchException {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        List<String> argv = commandFactory.buildArgv(spec);
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(new File(spec.getHostWorkingDir()));
        builder.environment().clear();
        builder.environment().putAll(spec.getEnv());
        builder.redirectErrorStream(false);
        try {
            return builder.start();
        } catch (IOException e) {
            throw new ProotLaunchException("failed to start PRoot process", e);
        }
    }

    private Map<String, String> defaultEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", ENV_PATH);
        env.put("HOME", ENV_HOME);
        env.put("USER", ENV_USER);
        env.put("LOGNAME", ENV_USER);
        env.put("TERM", ENV_TERM);
        env.put("LANG", ENV_LANG);
        env.put(ENV_PROOT_TMP_DIR, context.getCacheDir().getAbsolutePath());
        return env;
    }

    private List<String> appPrivateRoots() {
        List<String> roots = new ArrayList<>();
        addIfExists(roots, context.getFilesDir());
        addIfExists(roots, context.getCacheDir());
        addIfExists(roots, context.getDataDir());
        return roots;
    }

    private static void addIfExists(List<String> roots, File dir) {
        if (dir != null) {
            String path = dir.getAbsolutePath();
            if (!path.isEmpty()) {
                roots.add(path);
            }
        }
    }

    private static boolean isSystemBind(ProotBindMount bind) {
        for (ProotBindMount system : ProotCommandFactory.DEFAULT_SYSTEM_BINDS) {
            if (system.equals(bind)) {
                return true;
            }
        }
        return false;
    }
}
