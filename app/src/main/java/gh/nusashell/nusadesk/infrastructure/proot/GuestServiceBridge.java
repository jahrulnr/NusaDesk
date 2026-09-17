package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.VendoredFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Guest service bridge (ADR-0024): the curated {@code systemctl} replacement
 * and its Python runtime, delivered as the {@code guest-service-bridge} add-on
 * overlay bound at {@link CuratedRuntimeCatalog#SERVICES_OVERLAY_GUEST_DIR}.
 *
 * <p>The overlay keeps every payload file under its own guest dir — nothing is
 * installed into the rootfs. What the guest sees at the conventional paths is
 * a set of symlinks this class wires into the rootfs before the session
 * starts:</p>
 * <ul>
 *   <li>{@code /usr/bin/systemctl} and {@code /usr/bin/service} → the vendored
 *       scripts in the overlay, executable.</li>
 *   <li>{@code /usr/bin/python3}/{@code python3.12} → the overlay interpreter,
 *       so the script's {@code #!/usr/bin/env python3} shebang resolves.</li>
 *   <li>{@code /usr/lib/python3.12}, {@code /etc/python3.12}, and each payload
 *       library under {@code usr/lib/aarch64-linux-gnu} → the overlay, so the
 *       interpreter finds its stdlib and shared libraries through the normal
 *       guest paths.</li>
 *   <li>{@code etc/services}, {@code etc/protocols}, {@code etc/rpc},
 *       {@code etc/ethertypes}, {@code etc/mime.types}, and
 *       {@code usr/share/zoneinfo} → the overlay copies, since the base rootfs
 *       ships none of them.</li>
 *   <li>The compose payload (Phase 3B): {@code /usr/local/bin/udocker} and
 *       {@code /usr/local/bin/lw-compose-supervisor} → the product-owned
 *       launchers, {@code /usr/local/lib/nusadesk/compose} → the overlay's
 *       runtime directory (one directory symlink covering the runner and the
 *       pinned udocker/PyYAML source tarballs), and the global
 *       {@code lw-compose-supervisor.service} unit at the conventional
 *       {@code etc/systemd/system} path.</li>
 *   <li>{@code /run/systemd/system} is created as a real directory: it is both
 *       a unit search path and the marker that makes the replacement report
 *       {@code is-system-running} → {@code running}.</li>
 * </ul>
 *
 * <p>Wiring rules: a missing overlay member is skipped rather than linked
 * dangling; an existing real file or directory in the rootfs always wins over
 * the overlay (the guest's own content is never shadowed); only symlinks are
 * replaced, so rewiring can never delete guest-owned content. The step is
 * idempotent and re-runs on every session start.</p>
 *
 * <p>That preserve-first wiring means an apt-installed real {@code systemctl}
 * or {@code python3} would win over the overlay's entrypoints, so
 * {@link #requiredBinds()} additionally adds strict file binds
 * ({@link ProotBindMount#ofStrict}) for {@code /usr/bin/systemctl},
 * {@code /usr/bin/service}, {@code /usr/bin/python3}, and
 * {@code /usr/bin/python3.12} onto the overlay members. PRoot binds them at
 * the literal guest path without dereferencing a rootfs symlink, so the
 * product entrypoints stay effective for every session process while the
 * guest content underneath is shadowed, never modified or deleted.</p>
 *
 * <p>When the compose supervisor unit is present the same rules pre-create
 * its {@code multi-user.target.wants} enablement link (a relative symlink,
 * exactly what {@code systemctl enable} would write). This is product-owned
 * bootstrap, not a per-project unit: the vendored systemctl3 snapshots the
 * enabled-unit set at {@code systemctl init} and never re-reads it, so the
 * link must exist before session init for the global supervisor to be
 * restart-supervised at all.</p>
 *
 * <p>The supervised service manager is {@code systemctl init}: a blocking init
 * loop that starts the enabled services of the default target, reaps and
 * restarts them, and stops them on SIGTERM before exiting. It must share the
 * session's single PRoot tracer with the session daemon — PRoot mediates
 * {@code kill(2)}, so a tracee may only signal processes inside its own
 * tracer's tree, and a {@code systemctl stop} typed in a terminal can only
 * reach a manager-run service when the terminal, the manager, and the service
 * all live under the same tracer. {@link #sessionArgv(List)} therefore hands
 * the tracer the vendored {@code lw-session-supervisor} script as its initial
 * tracee: the script backgrounds the manager, records its real tracee pid in
 * {@link #INIT_PID_GUEST_PATH} and its exit code in
 * {@link #INIT_EXIT_GUEST_PATH}, then runs the session daemon — the host gets
 * the same pid-file handle sshd supervision relies on (killing the PRoot
 * tracer does not kill its tracee), and the daemon's exit ends the session.</p>
 *
 * <p>Pure JVM file/argv logic; unit-testable with temp directories.</p>
 */
public final class GuestServiceBridge {
    /** Guest path of the vendored {@code systemctl} replacement. */
    public static final String SYSTEMCTL_GUEST_PATH =
            CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR + "/usr/bin/systemctl";
    /** Guest path of the AArch64 Python interpreter the bridge provides. */
    public static final String PYTHON_GUEST_PATH =
            CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR + "/usr/bin/python3.12";
    /**
     * Guest path of the pid file the init wrapper writes. It lives under the
     * rootfs's real {@code /run} so the host can read it without resolving
     * overlay paths, and it is deleted before every supervised start.
     */
    public static final String INIT_PID_GUEST_PATH = "/run/lw-services-init.pid";
    /**
     * Guest path of the marker the supervisor writes when the manager exits;
     * its content is the manager's exit code. Presence means the manager is
     * gone even while the session daemon lives on.
     */
    public static final String INIT_EXIT_GUEST_PATH = "/run/lw-services-init.exit";
    /** Guest path of the vendored session supervisor script. */
    public static final String SUPERVISOR_GUEST_PATH =
            CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR
                    + "/usr/sbin/lw-session-supervisor";

    /** Guest-visible marker directory: present ⇒ the replacement is "running". */
    private static final String RUN_SYSTEMD_SYSTEM = "run/systemd/system";

    /**
     * Guest-relative {@code /run} where the replacement keeps its
     * {@code <unit>.status} marks. Session start is the guest's "boot", so
     * marks left by a previous session are stale and get wiped on wire-up.
     */
    private static final String RUN_DIR = "run";

    /**
     * Synthesized {@code /proc} substitutes (ADR-0024): SELinux denies the app
     * domains {@code /proc/uptime} and {@code /proc/stat}, and without them the
     * replacement's boot-time probe degenerates to "now", after which every
     * status file read truncates the file it just read. These vendored
     * stand-ins keep the probe honest; the launcher binds them over the real
     * paths for every session process.
     */
    private static final String PROC_UPTIME_OVERLAY = "lw-proc/uptime";
    private static final String PROC_STAT_OVERLAY = "lw-proc/stat";
    private static final String PROC_UPTIME_GUEST = "/proc/uptime";
    private static final String PROC_STAT_GUEST = "/proc/stat";

    /**
     * Guest-relative path of the product-owned global compose supervisor
     * unit, identical inside the overlay and the rootfs. The vendored
     * systemctl3 only restart-supervises units enabled at
     * {@code systemctl init}, so {@link #wireInto} pre-creates the
     * {@link #COMPOSE_SUPERVISOR_WANTS} link at wire-up — a mid-session
     * {@code enable} would run the service but never supervise it.
     */
    private static final String COMPOSE_SUPERVISOR_UNIT =
            "etc/systemd/system/lw-compose-supervisor.service";
    /**
     * Enablement link for {@link #COMPOSE_SUPERVISOR_UNIT}, with the same
     * relative target {@code systemctl enable} writes.
     */
    private static final String COMPOSE_SUPERVISOR_WANTS =
            "etc/systemd/system/multi-user.target.wants/lw-compose-supervisor.service";
    private static final String COMPOSE_SUPERVISOR_WANTS_TARGET =
            "../lw-compose-supervisor.service";

    /**
     * Guest-relative path of the product-owned user-service manager unit. The
     * session manager only starts *system* units, so without this unit a
     * {@code systemctl --user} unit — a web app enabled into
     * {@code default.target}, for example — never came up with the session.
     * Like the compose supervisor it is enabled at wire-up, because the
     * vendored systemctl3 snapshots the enabled-unit set at
     * {@code systemctl init}.
     */
    private static final String USER_MANAGER_UNIT =
            "etc/systemd/system/lw-user-manager.service";
    /** Enablement link for {@link #USER_MANAGER_UNIT}. */
    private static final String USER_MANAGER_WANTS =
            "etc/systemd/system/multi-user.target.wants/lw-user-manager.service";
    private static final String USER_MANAGER_WANTS_TARGET =
            "../lw-user-manager.service";

    /**
     * Guest-relative {@code /run} subtree that holds the replacement's
     * <em>user</em> status marks ({@code /run/user/<uid>/<unit>.status}).
     * These are wiped at session start for the same reason as the system
     * marks: a mark left by a dead session would otherwise make
     * {@code systemctl --user is-active} report a service that is not running.
     */
    private static final String RUN_USER_DIR = "run/user";

    /**
     * Overlay members re-exposed at the same guest path. Every entry is a
     * path relative to both the overlay root and the guest root; an entry is
     * wired only when the overlay actually carries it.
     */
    private static final List<String> WIRED_PATHS = Collections.unmodifiableList(Arrays.asList(
            "usr/bin/python3",
            "usr/bin/python3.12",
            "usr/bin/systemctl",
            "usr/bin/service",
            "usr/lib/python3.12",
            "etc/python3.12",
            "etc/mime.types",
            "etc/services",
            "etc/protocols",
            "etc/rpc",
            "etc/ethertypes",
            "usr/share/zoneinfo",
            // Compose payload (Phase 3B): the launchers land on PATH, the
            // runtime directory is exposed as one directory symlink, and
            // the global supervisor unit is wired then enabled below.
            "usr/local/bin/udocker",
            "usr/local/bin/lw-compose-supervisor",
            "usr/local/bin/lw-user-manager",
            "usr/local/lib/nusadesk/compose",
            COMPOSE_SUPERVISOR_UNIT,
            USER_MANAGER_UNIT));

    /**
     * Guest-relative directory whose flat {@code .so} members are each linked
     * into the rootfs (the payload's shared-library dir).
     */
    private static final String PAYLOAD_LIB_DIR = "usr/lib/aarch64-linux-gnu";

    /**
     * Guest-relative overlay members re-exposed as strict file binds at the
     * identical conventional path, in addition to the wiring symlinks. A
     * symlink only wins when the rootfs lacks real content, so these
     * entrypoints — the replacement executable, its {@code service} shim,
     * and the interpreter pair — are bound with {@link ProotBindMount#ofStrict}
     * to stay effective even over apt-installed guest files or foreign
     * symlinks. Paths are product constants, never caller input.
     */
    private static final List<String> STRICT_BIND_PATHS =
            Collections.unmodifiableList(Arrays.asList(
                    "usr/bin/systemctl",
                    "usr/bin/service",
                    "usr/bin/python3",
                    "usr/bin/python3.12"));

    private final String mountGuestDir;
    private final Path mountHostDir;

    private GuestServiceBridge(String mountGuestDir, Path mountHostDir) {
        this.mountGuestDir = mountGuestDir;
        this.mountHostDir = mountHostDir;
    }

    /**
     * Detect a usable service bridge: the activated add-on overlay carrying
     * the pinned interpreter entrypoint and every vendored member at its
     * pinned content digest.
     *
     * <p>This is the same-version update/reinstall guard: the overlay install
     * state does not record which packaged build wrote it, so an APK update
     * that changes a vendored asset (a wrapper path, a script fix) would
     * otherwise leave the old bytes "installed" forever. Each {@link
     * VendoredFile} is therefore re-hashed and compared to its catalog pin —
     * a missing member, unreadable file, or digest mismatch means "not
     * installed", never "usable but degraded", and the install pipeline
     * re-installs the overlay. The vendored set is bounded and every asset is
     * digest-pinned, so verification is cheap and total.</p>
     *
     * @param addonOverlayDir host path of the activated overlay (may be
     *                        null/absent)
     * @return the detected bridge, or {@code null} when none is installed or
     *         the installed overlay fails verification
     */
    public static GuestServiceBridge detect(Path addonOverlayDir) {
        GuestAddonPayloadProfile profile = CuratedRuntimeCatalog.guestServiceBridge();
        if (addonOverlayDir == null
                || !Files.isRegularFile(addonOverlayDir.resolve(profile.getEntrypoint()))
                || !Files.isRegularFile(addonOverlayDir.resolve("usr/bin/systemctl"))) {
            return null;
        }
        for (VendoredFile vendored : profile.getVendoredFiles()) {
            Path member = addonOverlayDir.resolve(vendored.getOverlayPath());
            if (!Files.isRegularFile(member)
                    || !matchesPinnedDigest(member, vendored.getSha256())) {
                return null;
            }
        }
        return new GuestServiceBridge(
                CuratedRuntimeCatalog.SERVICES_OVERLAY_GUEST_DIR, addonOverlayDir);
    }

    /**
     * Stream {@code member} through SHA-256 and compare against the pinned
     * hex digest. Fails closed: any I/O error or a missing digest
     * implementation is a mismatch, never an exception to the caller.
     */
    private static boolean matchesPinnedDigest(Path member, String expectedSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(member))) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return expectedSha256.equals(toHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            return false;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    /**
     * Wire the overlay's public guest paths into the active rootfs: symlinks
     * for the conventional paths plus the real {@code /run/systemd/system}
     * marker directory. Idempotent; see the class docs for the conflict rules.
     *
     * @return the guest-relative paths this call created or refreshed
     *         (existing real files and already-correct links are not listed)
     */
    public List<String> wireInto(Path rootfsDir) throws IOException {
        List<String> linked = new ArrayList<>();
        for (String guestRelative : WIRED_PATHS) {
            if (wireGuestPath(rootfsDir, guestRelative)) {
                linked.add(guestRelative);
            }
        }
        Path overlayLibs = mountHostDir.resolve(PAYLOAD_LIB_DIR);
        if (Files.isDirectory(overlayLibs)) {
            try (Stream<Path> entries = Files.list(overlayLibs)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    String name = entry.getFileName().toString();
                    if (!name.contains(".so") || Files.isDirectory(entry)) {
                        continue;
                    }
                    if (wireGuestPath(rootfsDir, PAYLOAD_LIB_DIR + "/" + name)) {
                        linked.add(PAYLOAD_LIB_DIR + "/" + name);
                    }
                }
            }
        }
        if (wireEnabled(rootfsDir, COMPOSE_SUPERVISOR_UNIT, COMPOSE_SUPERVISOR_WANTS,
                COMPOSE_SUPERVISOR_WANTS_TARGET)) {
            linked.add(COMPOSE_SUPERVISOR_WANTS);
        }
        if (wireEnabled(rootfsDir, USER_MANAGER_UNIT, USER_MANAGER_WANTS,
                USER_MANAGER_WANTS_TARGET)) {
            linked.add(USER_MANAGER_WANTS);
        }
        Files.createDirectories(rootfsDir.resolve(RUN_SYSTEMD_SYSTEM));
        wipeStaleStatusMarks(rootfsDir);
        return linked;
    }

    /**
     * Enable one product-owned unit before the next {@code systemctl init}:
     * once the unit exists in the rootfs — the freshly wired overlay link or
     * real guest content — create the conventional wants link with its
     * relative target. Same conflict rules as {@link #wireGuestPath}: only
     * symlinks are replaced, and a real file at the wants path is guest-owned
     * and never overwritten.
     */
    private boolean wireEnabled(Path rootfsDir, String unitPath, String wantsPath,
                                String wantsTarget) throws IOException {
        if (!Files.exists(rootfsDir.resolve(unitPath), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path wants = rootfsDir.resolve(wantsPath);
        if (Files.isSymbolicLink(wants)) {
            if (Files.readSymbolicLink(wants).toString().equals(wantsTarget)) {
                return false;
            }
            Files.delete(wants);
        } else if (Files.exists(wants)) {
            return false;
        }
        Files.createDirectories(wants.getParent());
        Files.createSymbolicLink(wants, Paths.get(wantsTarget));
        return true;
    }

    /**
     * A session start is the guest's "boot": {@code .status} marks left by a
     * previous session describe dead processes and are deleted so
     * {@code is-active}/{@code status} never read stale state. Both mark
     * locations are covered — the system dir ({@code /run/*.status}) and the
     * per-uid user dirs the {@code --user} instance writes
     * ({@code /run/user/<uid>/*.status}).
     */
    private void wipeStaleStatusMarks(Path rootfsDir) throws IOException {
        Path runDir = rootfsDir.resolve(RUN_DIR);
        if (!Files.isDirectory(runDir)) {
            return;
        }
        wipeStatusFiles(runDir);
        Path userRunDir = rootfsDir.resolve(RUN_USER_DIR);
        if (!Files.isDirectory(userRunDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(userRunDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                wipeStatusFiles(entry);
                // The replacement's PID dir for a non-root instance is its
                // runtime dir plus "run", so today's user marks live in
                // /run/user/<uid>/run; the flat form above covers other
                // layouts. Both are runtime marks only.
                Path compatDir = entry.resolve("run");
                if (Files.isDirectory(compatDir)) {
                    wipeStatusFiles(compatDir);
                }
            }
        }
    }

    /** Delete the {@code <unit>.status} marks directly inside one directory. */
    private static void wipeStatusFiles(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isDirectory(entry)
                        && entry.getFileName().toString().endsWith(".status")) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    /**
     * Link {@code guestRelative} to the same path inside the bound overlay.
     *
     * @return true when a link was created or updated; false when the overlay
     *         lacks the member or the rootfs already has real content there
     */
    private boolean wireGuestPath(Path rootfsDir, String guestRelative) throws IOException {
        Path member = mountHostDir.resolve(guestRelative);
        if (!Files.exists(member)) {
            return false;
        }
        String target = mountGuestDir + "/" + guestRelative;
        Path link = rootfsDir.resolve(guestRelative);
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).toString().equals(target)) {
                return false;
            }
            // A symlink is a pointer, not content: replacing it can never
            // delete anything the guest owns.
            Files.delete(link);
        } else if (Files.exists(link)) {
            // Real guest content wins over the overlay — never shadow it.
            return false;
        }
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, Paths.get(target));
        return true;
    }

    /**
     * Extra bind mounts the launcher must add for this bridge: the overlay
     * directory bind, then the strict entrypoint binds of
     * {@link #STRICT_BIND_PATHS} — emitted after the directory bind so the
     * file-level overrides are unambiguous — plus the synthesized
     * {@code /proc} substitutes (see {@link #PROC_UPTIME_OVERLAY}). A missing
     * overlay member is skipped.
     */
    public List<ProotBindMount> requiredBinds() {
        List<ProotBindMount> binds = new ArrayList<>();
        binds.add(ProotBindMount.of(mountHostDir.toString(), mountGuestDir));
        for (String guestRelative : STRICT_BIND_PATHS) {
            Path member = mountHostDir.resolve(guestRelative);
            if (Files.exists(member)) {
                binds.add(ProotBindMount.ofStrict(
                        member.toString(), "/" + guestRelative));
            }
        }
        addProcBindIfPresent(binds, PROC_UPTIME_OVERLAY, PROC_UPTIME_GUEST);
        addProcBindIfPresent(binds, PROC_STAT_OVERLAY, PROC_STAT_GUEST);
        return binds;
    }

    private void addProcBindIfPresent(
            List<ProotBindMount> binds, String overlayMember, String guestPath) {
        Path member = mountHostDir.resolve(overlayMember);
        if (Files.isRegularFile(member)) {
            binds.add(ProotBindMount.of(member.toString(), guestPath));
        }
    }

    /**
     * Session argv for the shared tracer: run the vendored supervisor as the
     * initial tracee with the init pid/exit paths, then hand it the session
     * daemon's argv after {@code --}. The supervisor backgrounds
     * {@code systemctl init}, records its real pid, and runs the daemon as a
     * sibling under the same tracer — the daemon's exit ends the session.
     */
    public List<String> sessionArgv(List<String> daemonArgv) {
        if (daemonArgv == null || daemonArgv.isEmpty()) {
            throw new IllegalArgumentException("daemon argv is required");
        }
        List<String> argv = new ArrayList<>(daemonArgv.size() + 6);
        argv.add("/bin/sh");
        argv.add(SUPERVISOR_GUEST_PATH);
        argv.add(INIT_PID_GUEST_PATH);
        argv.add(INIT_EXIT_GUEST_PATH);
        argv.add("--");
        argv.addAll(daemonArgv);
        return argv;
    }

    /**
     * Guest-visible path used to attribute a pid to this manager:
     * {@code /proc/<pid>/cmdline} of the init process contains the script
     * path, and {@link GuestSshdPidFile#matchesDaemon} is a containment test.
     */
    public String getBinaryPath() {
        return SYSTEMCTL_GUEST_PATH;
    }

    /** Resolve the init pid file to its host-side path under the rootfs. */
    public Path resolvePidFile(Path rootfsDir) {
        return rootfsDir.resolve(INIT_PID_GUEST_PATH.substring(1));
    }

    /** Resolve the init exit marker to its host-side path under the rootfs. */
    public Path resolveExitFile(Path rootfsDir) {
        return rootfsDir.resolve(INIT_EXIT_GUEST_PATH.substring(1));
    }

    /** The manager spawns and supervises services; fake root keeps uid 0. */
    public boolean requiresFakeRoot() {
        return true;
    }

    /** Non-secret human label for logs/UI. */
    public String label() {
        return "guest service manager (systemctl)";
    }
}
