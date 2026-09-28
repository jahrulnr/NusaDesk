package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.domain.runtime.GuestAddonPayloadProfile;
import gh.nusashell.nusadesk.domain.runtime.VendoredFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Guest systemd D-Bus face (ADR-0024): the {@code org.freedesktop.systemd1}
 * session-bus surface D-Bus-gated consumers probe for, delivered as the
 * {@code guest-systemd-dbus-face} add-on overlay bound at
 * {@link CuratedRuntimeCatalog#DBUS_FACE_OVERLAY_GUEST_DIR}.
 *
 * <p>This is a compatibility <em>face</em>, not a second manager. The provider
 * ({@code lw-systemd-dbus-provider}) owns exactly one responsibility: it spawns
 * the vendored {@code dbus-daemon} bound at {@code $XDG_RUNTIME_DIR/bus},
 * claims the {@code org.freedesktop.systemd1} name on it, and translates the
 * audited D-Bus calls into the existing {@code systemctl} CLI — the same
 * vendored manager the service bridge installs — and the same unit files that
 * CLI reads. Nothing in the face tracks unit state, so the CLI and the bus can
 * never disagree. The companion {@code lw-busctl} shim answers the client side
 * ({@code busctl --user … get-property|call}, legacy and {@code --json=short}
 * output) without shipping real systemd's busctl.</p>
 *
 * <p>The companion {@code $XDG_RUNTIME_DIR/systemd/private} socket is
 * deliberately <em>absent</em>: under the packaged PRoot fake-root a peer's
 * kernel credentials never read guest uid 0, so a private socket would accept
 * connections its clients then fail credential checks on — strictly worse than
 * no socket at all. The face serves only the runtime-bus candidate.</p>
 *
 * <p>The overlay keeps every payload file under its own guest dir. What the
 * guest sees at conventional paths is wired into the rootfs by
 * {@link #wireInto}:</p>
 * <ul>
 *   <li>{@code /usr/bin/busctl} → the product shim (a Python client of the
 *       vendored {@code python3-dbus} binding).</li>
 *   <li>{@code /usr/sbin/lw-systemd-dbus-provider} → the provider.</li>
 *   <li>{@code /usr/share/dbus-1} and {@code /etc/dbus-1} → the payload's
 *       config tree, so the conventional config paths exist.</li>
 *   <li>{@code /usr/lib/python3/dist-packages} → the vendored
 *       {@code python3-dbus} tree, so any guest {@code python3.12} can
 *       {@code import dbus}, not just the shim (which inserts the overlay
 *       path itself anyway).</li>
 *   <li>Each {@code .so} member of {@code usr/lib/aarch64-linux-gnu} → the
 *       same flat rootfs dir, exactly the wiring {@link GuestServiceBridge}
 *       does for its payload libraries, so the dynamic linker resolves
 *       {@code libdbus-1.so.3}, {@code libapparmor.so.1}, and
 *       {@code libexpat.so.1} for {@code dbus-daemon} and the Python
 *       extension.</li>
 *   <li>{@code etc/systemd/user/lw-systemd-dbus-provider.service} → the
 *       provider's unit, enabled at wire-up into
 *       {@code etc/systemd/user/default.target.wants} — the same
 *       snapshot-at-init constraint the bridge documents applies to the
 *       user manager: a mid-session enable would run but never
 *       restart-supervise the provider.</li>
 *   <li>{@code run/user/0} is created as a real directory: it is the fake-root
 *       guest's runtime dir, the parent of the {@code bus} socket consumers
 *       stat.</li>
 *   <li>{@code etc/machine-id} is generated once when absent —
 *       {@code dbus-daemon} answers {@code GetId} from it and real
 *       {@code busctl} consumers pattern-match that value.</li>
 * </ul>
 *
 * <p>Wiring rules mirror the bridge: a missing overlay member is skipped
 * rather than linked dangling; an existing real file or directory in the
 * rootfs always wins over the overlay; only symlinks are replaced. For the
 * flat library dir the rule is tightened slightly: a rootfs member that
 * already resolves (real file <em>or</em> healthy symlink) is left alone —
 * the service bridge ships the same pinned {@code libexpat1} bytes, and
 * replacing an already-satisfied library link would only churn which overlay
 * owns the pointer, never which bytes run.</p>
 *
 * <p>Lifecycle: the unit runs under {@code lw-user-manager} (the
 * {@code systemctl --user init} instance), so the bus and the name live
 * exactly as long as the guest session, and {@code systemctl --user
 * stop|restart lw-systemd-dbus-provider} is the honest operator control. The
 * provider supervises its own {@code dbus-daemon} child and exits with it.</p>
 *
 * <p>Pure JVM file logic; unit-testable with temp directories.</p>
 */
public final class GuestSystemdBusFace {
    /**
     * Guest path of the product {@code busctl} entrypoint — the shim the
     * guest runs, and the path the launcher strict-binds so a foreign
     * busctl can never change the audited output contract.
     */
    public static final String BUSCTL_GUEST_PATH =
            CuratedRuntimeCatalog.DBUS_FACE_OVERLAY_GUEST_DIR + "/usr/bin/busctl";
    /**
     * Guest path of the provider executable the unit's ExecStart names and
     * the launcher strict-binds.
     */
    public static final String PROVIDER_GUEST_PATH =
            CuratedRuntimeCatalog.DBUS_FACE_OVERLAY_GUEST_DIR
                    + "/usr/sbin/lw-systemd-dbus-provider";

    /** The upstream session-bus config inside the overlay (deb-shipped). */
    private static final String SESSION_CONF = "usr/share/dbus-1/session.conf";

    /** Guest-relative provider unit, identical inside overlay and rootfs. */
    private static final String PROVIDER_UNIT =
            "etc/systemd/user/lw-systemd-dbus-provider.service";
    /**
     * Enablement link for {@link #PROVIDER_UNIT}, with the same relative
     * target {@code systemctl --user enable} writes. The user manager's
     * default target is {@code default.target} (lw-user-manager exports
     * SYSTEMD_DEFAULT_TARGET for exactly this).
     */
    private static final String PROVIDER_WANTS =
            "etc/systemd/user/default.target.wants/lw-systemd-dbus-provider.service";
    private static final String PROVIDER_WANTS_TARGET =
            "../lw-systemd-dbus-provider.service";

    /**
     * Overlay members re-exposed at the identical conventional guest path.
     * Each entry is relative to both the overlay root and the guest root and
     * is wired only when the overlay actually carries it.
     */
    private static final List<String> WIRED_PATHS =
            Collections.unmodifiableList(Arrays.asList(
                    "usr/bin/busctl",
                    "usr/sbin/lw-systemd-dbus-provider",
                    "usr/share/dbus-1",
                    "etc/dbus-1",
                    "usr/lib/python3/dist-packages",
                    PROVIDER_UNIT));
    /**
     * Guest-relative overlay members re-exposed as strict file binds at the
     * identical conventional path — the product entrypoints whose bytes the
     * consumer contract is pinned against. Same reasoning as the bridge's
     * strict binds: a symlink only wins when the rootfs lacks real content,
     * and a foreign or stale busctl must not answer the audited surface.
     */
    private static final List<String> STRICT_BIND_PATHS =
            Collections.unmodifiableList(Arrays.asList(
                    "usr/bin/busctl",
                    "usr/sbin/lw-systemd-dbus-provider"));

    /**
     * Guest-relative directory whose flat {@code .so} members are each linked
     * into the rootfs (the payload's shared-library dir).
     */
    private static final String PAYLOAD_LIB_DIR = "usr/lib/aarch64-linux-gnu";

    /**
     * Guest-relative runtime dir for the fake-root guest: the parent of the
     * {@code bus} socket the runtime-bus candidate probes. Created as a real
     * directory at wire-up so the path resolves before the provider starts.
     */
    private static final String RUNTIME_DIR = "run/user/0";

    /**
     * Guest-relative {@code machine-id} the daemon answers {@code GetId}
     * from. Generated once and then guest-owned.
     */
    private static final String MACHINE_ID = "etc/machine-id";

    private final String mountGuestDir;
    private final Path mountHostDir;

    private GuestSystemdBusFace(String mountGuestDir, Path mountHostDir) {
        this.mountGuestDir = mountGuestDir;
        this.mountHostDir = mountHostDir;
    }

    /** Why a D-Bus face is not usable — same split as the service bridge. */
    public enum Absence {
        /** No overlay was ever activated here: the documented opt-out. */
        NOT_INSTALLED,
        /** An activated overlay exists but does not verify: repair it. */
        CORRUPT
    }

    /** A detection outcome: exactly one of {@link #getFace()} / {@link #getAbsence()}. */
    public static final class Detection {
        private final GuestSystemdBusFace face;
        private final Absence absence;
        private final String detail;

        private Detection(GuestSystemdBusFace face, Absence absence, String detail) {
            this.face = face;
            this.absence = absence;
            this.detail = detail == null ? "" : detail;
        }

        static Detection usable(GuestSystemdBusFace face) {
            return new Detection(face, null, "");
        }

        static Detection missing(Absence absence, String detail) {
            return new Detection(null, absence, detail);
        }

        /** The face, or {@code null} when {@link #getAbsence()} is set. */
        public GuestSystemdBusFace getFace() {
            return face;
        }

        /** Why there is no face, or {@code null} when one was detected. */
        public Absence getAbsence() {
            return absence;
        }

        /** Which member failed verification, for a log line. */
        public String getDetail() {
            return detail;
        }
    }

    /**
     * Detect a usable D-Bus face, or {@code null} when none is installed or
     * the installed overlay fails verification. Callers that must
     * distinguish the two use {@link #inspect(Path)}.
     */
    public static GuestSystemdBusFace detect(Path addonOverlayDir) {
        return inspect(addonOverlayDir).getFace();
    }

    /**
     * Detect a usable D-Bus face <em>and say why</em> when there is none: the
     * entrypoint ELF ({@code usr/bin/dbus-daemon}) plus the session-bus
     * config plus every vendored member at its pinned digest. An overlay
     * missing any piece is {@link Absence#CORRUPT}, not absent — the rootfs
     * symlinks point into it, so the guest sees a {@code busctl} that exists
     * and fails.
     */
    public static Detection inspect(Path addonOverlayDir) {
        GuestAddonPayloadProfile profile =
                CuratedRuntimeCatalog.guestSystemdBusFace();
        if (addonOverlayDir == null || !Files.exists(addonOverlayDir)) {
            return Detection.missing(Absence.NOT_INSTALLED, "overlay absent");
        }
        Path entrypoint = addonOverlayDir.resolve(profile.getEntrypoint());
        Path sessionConf = addonOverlayDir.resolve(SESSION_CONF);
        Path busctl = addonOverlayDir.resolve("usr/bin/busctl");
        boolean entrypointPresent = Files.isRegularFile(entrypoint);
        boolean confPresent = Files.isRegularFile(sessionConf);
        boolean busctlPresent = Files.isRegularFile(busctl);
        if (!entrypointPresent && !confPresent && !busctlPresent) {
            return Detection.missing(Absence.NOT_INSTALLED, "overlay empty");
        }
        if (!entrypointPresent) {
            return Detection.missing(Absence.CORRUPT, profile.getEntrypoint());
        }
        if (!confPresent) {
            return Detection.missing(Absence.CORRUPT, SESSION_CONF);
        }
        if (!busctlPresent) {
            return Detection.missing(Absence.CORRUPT, "usr/bin/busctl");
        }
        for (VendoredFile vendored : profile.getVendoredFiles()) {
            Path member = addonOverlayDir.resolve(vendored.getOverlayPath());
            if (!Files.isRegularFile(member)
                    || !matchesPinnedDigest(member, vendored.getSha256())) {
                return Detection.missing(Absence.CORRUPT, vendored.getOverlayPath());
            }
        }
        return Detection.usable(new GuestSystemdBusFace(
                CuratedRuntimeCatalog.DBUS_FACE_OVERLAY_GUEST_DIR, addonOverlayDir));
    }

    /**
     * Stream {@code member} through SHA-256 and compare against the pinned
     * hex digest. Fails closed, like the bridge's copy.
     */
    private static boolean matchesPinnedDigest(Path member, String expectedSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input =
                         new BufferedInputStream(Files.newInputStream(member))) {
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
     * Wire the overlay's public guest paths into the active rootfs, enable
     * the provider unit for the next {@code systemctl --user init}, and
     * prepare the runtime dir and machine-id the bus needs. Idempotent; see
     * the class docs for the conflict rules.
     *
     * @return the guest-relative paths this call created or refreshed
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
                    if (wireLibraryFile(rootfsDir, PAYLOAD_LIB_DIR + "/" + name)) {
                        linked.add(PAYLOAD_LIB_DIR + "/" + name);
                    }
                }
            }
        }
        if (wireEnabled(rootfsDir, PROVIDER_UNIT, PROVIDER_WANTS,
                PROVIDER_WANTS_TARGET)) {
            linked.add(PROVIDER_WANTS);
        }
        Files.createDirectories(rootfsDir.resolve(RUNTIME_DIR));
        ensureMachineId(rootfsDir);
        return linked;
    }

    /**
     * Enable the provider unit before the next {@code systemctl --user init}
     * — the same snapshot-at-init constraint as the bridge's own enablement
     * links. Same conflict rules as {@link #wireGuestPath}.
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
     * Link {@code guestRelative} to the same path inside the bound overlay.
     * Identical contract to the bridge's: a missing overlay member is
     * skipped, real guest content always wins, only symlinks are replaced.
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
            Files.delete(link);
        } else if (Files.exists(link)) {
            return false;
        }
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, Paths.get(target));
        return true;
    }

    /**
     * Link one payload library into the flat rootfs lib dir. Tighter than
     * {@link #wireGuestPath} in one respect: a member that already
     * <em>resolves</em> — a real file or a healthy symlink, e.g. the service
     * bridge's identical pinned {@code libexpat.so.1} — is left alone, so the
     * two overlays never bounce the pointer back and forth. A dangling
     * symlink resolves to nothing and is still replaced.
     */
    private boolean wireLibraryFile(Path rootfsDir, String guestRelative)
            throws IOException {
        Path member = mountHostDir.resolve(guestRelative);
        if (!Files.exists(member)) {
            return false;
        }
        Path link = rootfsDir.resolve(guestRelative);
        if (Files.isRegularFile(link)) {
            // Real file or a symlink that resolves: the path is satisfied.
            return false;
        }
        if (Files.isSymbolicLink(link)) {
            Files.delete(link); // dangling symlink — still replaced
        }
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, Paths.get(mountGuestDir + "/" + guestRelative));
        return true;
    }

    /**
     * Write {@code etc/machine-id} once when absent — a fresh 32-hex id in
     * the conventional format. An existing file or a resolving symlink is
     * guest-owned and left alone; a dangling symlink is replaced rather than
     * followed (a write through it could land outside the rootfs).
     */
    private void ensureMachineId(Path rootfsDir) throws IOException {
        Path machineId = rootfsDir.resolve(MACHINE_ID);
        if (Files.exists(machineId)) {
            return;
        }
        if (Files.isSymbolicLink(machineId)) {
            Files.delete(machineId);
        }
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder id = new StringBuilder(32);
        for (byte b : random) {
            id.append(Character.forDigit((b >> 4) & 0xf, 16));
            id.append(Character.forDigit(b & 0xf, 16));
        }
        Files.createDirectories(machineId.getParent());
        Files.write(machineId, (id + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Extra bind mounts the launcher must add for this face: the overlay
     * directory bind, then the strict entrypoint binds of
     * {@link #STRICT_BIND_PATHS}. A missing overlay member is skipped rather
     * than bound dangling.
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
        return binds;
    }
}
