package gh.nusashell.nusadesk.infrastructure.proot;

import android.content.Context;

import gh.nusashell.nusadesk.application.workspace.WorkspaceStore;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.RuntimeCatalogEntry;
import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;
import gh.nusashell.nusadesk.infrastructure.network.GuestDnsResolver;
import gh.nusashell.nusadesk.infrastructure.runtimehost.JdkProcessHandle;
import gh.nusashell.nusadesk.infrastructure.runtimehost.ProcessHandle;
import gh.nusashell.nusadesk.infrastructure.workspace.SharedPreferencesWorkspaceStore;
import gh.nusashell.nusadesk.infrastructure.workspace.WorkspaceFolderAccess;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * interpolated; bind mounts are the fixed system set, plus the fixed
 * inner-runtime set below, plus optional app-private host paths (host keys,
 * app-private storage), plus the user's workspace folder when one is chosen
 * and still usable — never an arbitrary host path.
 * The environment is cleared and set explicitly so no host secrets leak into
 * the guest. The returned {@link ProcessHandle} is the same port the
 * {@code RuntimeSupervisor} uses, so a future supervisor integration can adopt
 * this launcher without a new abstraction.</p>
 *
 * <p><strong>Nested (udocker) inner runtime.</strong> The udocker Compose
 * adapter reuses the packaged NusaDesk PRoot as the inner PRoot for
 * {@code udocker run}: the upstream udocker-englib helper binaries are
 * Android-incompatible and are never shipped or downloaded (device-verified
 * on S10e; {@code assets/compose/lw_compose_runtime.py} pins
 * {@code UDOCKER_USE_PROOT_EXECUTABLE}/{@code PROOT_LOADER} to the guest
 * paths below). Every launch spec therefore adds a closed, product-owned set
 * of file binds: {@code nativeLibraryDir/libproot.so} →
 * {@link #INNER_PROOT_GUEST_PATH}, {@code nativeLibraryDir/libproot-loader.so}
 * → {@link #INNER_PROOT_LOADER_GUEST_PATH}, and the Android ELF interpreter
 * and Bionic libraries under {@link #ANDROID_SYSTEM_ROOT} bound file-by-file
 * at their identical guest paths — the packaged bridge is an Android PIE and
 * cannot exec inside a glibc guest without its host linker and libraries.
 * Every host source and guest target is a constant: never caller input, never
 * a user path, and never a {@code /system} directory-wide bind. A bind is
 * conditional on its host source being a regular file, so a missing optional
 * file is skipped rather than failing the launch. Guest content always wins:
 * when the rootfs already carries a file, directory, or symlink at a target,
 * the product bind is skipped instead of shadowing it (the same rule
 * {@link GuestServiceBridge} wiring uses), and the target's parent
 * directories are created under the active rootfs only when the bind is
 * actually added — never through a symlink or over non-directory content, so
 * no write can escape the rootfs. The outer {@code PROOT_LOADER} env is
 * unchanged: it still points at the host-side loader.</p>
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

    /**
     * Guest path where the packaged PRoot binary is bound for the nested
     * (udocker) inner runtime. The guest-side wrapper pins udocker to exactly
     * this path via {@code UDOCKER_USE_PROOT_EXECUTABLE}
     * ({@code assets/compose/lw_compose_runtime.py}).
     */
    public static final String INNER_PROOT_GUEST_PATH = "/usr/local/bin/proot";

    /**
     * Guest path where the packaged PRoot ELF loader is bound for the nested
     * inner runtime; the wrapper pins it via {@code PROOT_LOADER}. Required
     * for the same SELinux reason as the outer loader: the inner PRoot must
     * not fall back to extracting a loader into a guest {@code PROOT_TMP_DIR}
     * it cannot exec under {@code untrusted_app}.
     */
    public static final String INNER_PROOT_LOADER_GUEST_PATH = "/usr/local/bin/proot-loader";

    /**
     * Host root of the Android system tree the inner runtime binds from.
     * This is a fixed product-owned constant, never caller input.
     */
    public static final String ANDROID_SYSTEM_ROOT = "/system";

    /**
     * Android ELF interpreter and Bionic libraries the packaged inner PRoot
     * needs inside the glibc guest, relative to {@link #ANDROID_SYSTEM_ROOT}.
     * The packaged bridge is an Android PIE: it cannot exec inside the guest
     * without the host linker and libc/libdl/libm. Each file is bound at its
     * identical absolute guest path — a file bind, never a {@code /system}
     * directory-wide bind.
     */
    private static final List<String> INNER_ANDROID_SYSTEM_FILES =
            Collections.unmodifiableList(Arrays.asList(
                    "bin/linker64", "lib64/libc.so", "lib64/libdl.so", "lib64/libm.so"));

    private final Context context;
    private final Path androidSystemRoot;
    private final ProotCommandFactory commandFactory;
    private final ProotRootfsValidator rootfsValidator;
    private final GuestDnsResolver dnsResolver;
    private final WorkspaceStore workspaceStore;

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
        this(context, commandFactory, rootfsValidator, dnsResolver,
                Paths.get(ANDROID_SYSTEM_ROOT));
    }

    /**
     * Test seam: {@code androidSystemRoot} stands in for the device
     * {@code /system} tree so JVM tests can stage fake linker/Bionic sources
     * under a temp dir. Production callers always get
     * {@link #ANDROID_SYSTEM_ROOT}; device code never passes this parameter.
     */
    ProotLauncher(Context context, Path androidSystemRoot) {
        this(context, new ProotCommandFactory(), new ProotRootfsValidator(),
                new GuestDnsResolver(context, null), androidSystemRoot);
    }

    ProotLauncher(Context context, ProotCommandFactory commandFactory,
                  ProotRootfsValidator rootfsValidator, GuestDnsResolver dnsResolver,
                  Path androidSystemRoot) {
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
        if (androidSystemRoot == null) {
            throw new IllegalArgumentException("androidSystemRoot must not be null");
        }
        this.context = context.getApplicationContext();
        this.commandFactory = commandFactory;
        this.rootfsValidator = rootfsValidator;
        this.dnsResolver = dnsResolver;
        this.androidSystemRoot = androidSystemRoot;
        this.workspaceStore = new SharedPreferencesWorkspaceStore(context);
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
        Path prootLoader = ProotPaths.prootLoaderPath(nativeLibraryDir);

        List<ProotBindMount> binds = new ArrayList<>(ProotCommandFactory.DEFAULT_SYSTEM_BINDS);
        // Bind the guest /etc/resolv.conf from Android's active-network DNS so
        // every PRoot path (setup, daemon, deb extraction, probe) resolves
        // consistently. When Android has no valid DNS, no bind is added and the
        // guest keeps its own resolver (graceful). The host file is app-private
        // (cache dir); it is never a broad host filesystem bind and the active
        // rootfs is never mutated.
        Optional<ProotBindMount> resolver = dnsResolver.resolverBind();
        resolver.ifPresent(binds::add);
        // Keep Ubuntu's original identity fields but expose a product-owned
        // namespaced contributor/source overlay at the effective /etc path.
        // The source is regenerated from the current rootfs os-release, so an
        // apt update of base-files is reflected without editing package-owned
        // /usr/lib/os-release.
        try {
            GuestOsReleaseWriter.ensure(filesDir, activeRootfs);
            Path managedOsRelease = filesDir.resolve(GuestOsReleaseWriter.STATE_RELATIVE_PATH);
            binds.add(ProotBindMount.ofStrict(
                    managedOsRelease.toString(), GuestOsReleaseWriter.GUEST_PATH));
        } catch (IOException | RuntimeException e) {
            throw new ProotLaunchException("could not prepare managed guest os-release", e);
        }
        // Fixed, product-owned binds for the nested (udocker) inner PRoot
        // runtime: the packaged bridge pair plus the Android linker/Bionic
        // libraries it is linked against. Fixed host paths and fixed guest
        // targets only — see the class Javadoc for the contract. Each bind is
        // skipped when its host source is absent or the guest already carries
        // content at the target, so this never fails a normal guest launch.
        binds.addAll(innerRuntimeBinds(prootBinary, prootLoader, activeRootfs));
        // Every activated add-on overlay is bound at its catalog-declared guest
        // dir for every guest launch (daemon, service manager, guest setup,
        // deb decoding): the guest always sees the installed add-ons without a
        // caller having to name them.
        for (ProotBindMount addonBind : activatedAddonBinds(filesDir)) {
            binds.add(addonBind);
        }
        // User-chosen workspace folder. This is the single deliberate exception
        // to the app-private rule enforced for caller-supplied binds below; see
        // workspaceBind() for what it validates and why.
        workspaceBind().ifPresent(binds::add);
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
                if (!binds.contains(bind)) {
                    binds.add(bind);
                }
            }
        }

        Map<String, String> env = defaultEnv();
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

    /**
     * Binds for every curated add-on whose activated overlay currently
     * provides its entrypoint. Host paths stay app-private (they live under
     * {@code filesDir}); guest paths come from the catalog, never a caller.
     */
    private List<ProotBindMount> activatedAddonBinds(Path filesDir) {
        List<ProotBindMount> binds = new ArrayList<>();
        for (GuestAddonPayloadProfile profile : CuratedRuntimeCatalog.guestAddons()) {
            Path active = ProotPaths.activeAddonPath(filesDir, profile.getAddonId());
            if (active == null
                    || !Files.isRegularFile(active.resolve(profile.getEntrypoint()))) {
                continue;
            }
            // The service bridge's requiredBinds() adds its synthesized
            // /proc substitutes on top of the overlay bind (ADR-0024).
            GuestServiceBridge bridge = GuestServiceBridge.detect(active);
            if (bridge != null) {
                binds.addAll(bridge.requiredBinds());
            } else {
                binds.add(ProotBindMount.of(active.toString(), profile.getGuestDir()));
            }
        }
        return binds;
    }

    /**
     * The user's workspace folder as a bind mount, when one is chosen and still
     * usable.
     *
     * <p>This is the one deliberate exception to the rule that a non-system bind
     * host path must be app-private. It is safe to make because every part of
     * the decision is fixed by the product rather than by a caller: the host
     * path comes from the folder the user picked in the system picker and stored
     * (ADR-0023), or — below API 30, where a shared folder cannot be bound at
     * all — from the app's own external folder. The guest path is the fixed
     * {@link WorkspaceFolder#GUEST_MOUNT_PATH}, a picked folder needs the
     * all-files grant to still be in place, and a probe write into the folder
     * must succeed. Anything else yields no bind at all, so the guest keeps
     * working without a workspace instead of failing to start.</p>
     */
    private Optional<ProotBindMount> workspaceBind() {
        WorkspaceFolderAccess access = new WorkspaceFolderAccess(context);
        WorkspaceFolder workspace = workspaceStore.load();
        if (workspace == null && !access.isSupportedPlatform()) {
            // Android 10 cannot grant raw access to a shared folder, so the
            // workspace there is the app's own external folder: no grant is
            // involved and the user can still reach it from a file manager.
            workspace = access.appFolderWorkspace();
        }
        if (!access.isUsable(workspace)) {
            return Optional.empty();
        }
        return Optional.of(ProotBindMount.of(
                workspace.getHostPath(), WorkspaceFolder.GUEST_MOUNT_PATH));
    }

    /**
     * The fixed inner-runtime bind set for the nested udocker PRoot (see the
     * class Javadoc): the packaged bridge pair bound at
     * {@link #INNER_PROOT_GUEST_PATH}/{@link #INNER_PROOT_LOADER_GUEST_PATH},
     * plus each file of {@link #INNER_ANDROID_SYSTEM_FILES} bound from
     * {@link #ANDROID_SYSTEM_ROOT} at its identical guest path. Best-effort
     * and never fatal: an absent host source or existing guest content simply
     * skips that one bind.
     */
    private List<ProotBindMount> innerRuntimeBinds(
            Path prootBinary, Path prootLoader, Path activeRootfs) {
        List<ProotBindMount> binds = new ArrayList<>();
        addFixedInnerBind(binds, activeRootfs, prootBinary, INNER_PROOT_GUEST_PATH);
        addFixedInnerBind(binds, activeRootfs, prootLoader, INNER_PROOT_LOADER_GUEST_PATH);
        for (String relative : INNER_ANDROID_SYSTEM_FILES) {
            addFixedInnerBind(binds, activeRootfs,
                    androidSystemRoot.resolve(relative),
                    ANDROID_SYSTEM_ROOT + "/" + relative);
        }
        return binds;
    }

    /**
     * Add one fixed inner-runtime bind when its host source is a regular file
     * and the guest target carries no content.
     *
     * <p>Real guest content always wins — the same rule
     * {@link GuestServiceBridge} wiring uses: a product-owned bind provides
     * what the rootfs lacks and never shadows what the guest already carries,
     * and a dangling symlink counts as carried content. The target's parent
     * directories are created only when the bind is actually added.</p>
     */
    private void addFixedInnerBind(List<ProotBindMount> binds, Path activeRootfs,
                                   Path hostSource, String guestTarget) {
        if (!Files.isRegularFile(hostSource)) {
            return;
        }
        Path target = activeRootfs.resolve(guestTarget.substring(1));
        if (Files.exists(target) || Files.isSymbolicLink(target)) {
            return;
        }
        if (!createGuestParentDirs(activeRootfs, target.getParent())) {
            return;
        }
        binds.add(ProotBindMount.of(hostSource.toString(), guestTarget));
    }

    /**
     * Create a guest target's parent chain under the rootfs. Refuses to
     * descend through a symlink (it could resolve outside the rootfs) or
     * through a component that already exists as non-directory content.
     *
     * @return {@code true} when the parent chain exists or was created
     */
    private static boolean createGuestParentDirs(Path rootfs, Path guestParent) {
        Path cursor = rootfs;
        for (Path segment : rootfs.relativize(guestParent)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)
                    || (Files.exists(cursor) && !Files.isDirectory(cursor))) {
                return false;
            }
        }
        try {
            Files.createDirectories(guestParent);
            return true;
        } catch (IOException e) {
            return false;
        }
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
