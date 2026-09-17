package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Locates and launches the guest-native OpenSSH daemon for the curated rootfs.
 *
 * <p>The SSH endpoint is guest-native: a curated {@code sshd} binary is exec'd
 * through PRoot and binds a loopback port itself. The Android host never
 * implements SSH server logic for the guest — it hands the daemon the fixed
 * loopback port from the endpoint contract (ADR-0013), starts the fixed daemon
 * argv, and requires the daemon's own bind report plus a real SSH banner before
 * it publishes the endpoint.</p>
 *
 * <p>Two daemon locations are recognised, in order:</p>
 * <ul>
 *   <li><b>Overlay</b> — the curated guest-SSH add-on activated under
 *       {@code <filesDir>/linux-wrapper/addons/<addonId>/active} and bound into
 *       the guest at {@link CuratedRuntimeCatalog#SSH_OVERLAY_GUEST_DIR}
 *       ({@code /opt/lw-ssh}). This is the verified production profile
 *       (ADR-0010): the pinned daemon wins over any image-resident copy.</li>
 *   <li><b>Rootfs</b> — an {@code usr/sbin/sshd} shipped inside the active
 *       rootfs itself (not present in stock Ubuntu Base).</li>
 * </ul>
 *
 * <p>Neither is executed from writable app storage: PRoot execs the packaged
 * bridge from {@code nativeLibraryDir}, and the daemon is loaded by the guest
 * dynamic linker as data inside the emulated root — the same model the
 * curated-rootfs contract already relies on.</p>
 *
 * <p>OpenSSH specifics handled here:</p>
 * <ul>
 *   <li>{@code sshd} requires euid 0, so {@link #requiresFakeRoot()} is always
 *       true and the launcher must pass PRoot {@code -0}.</li>
 *   <li>PAM helpers ({@code chpasswd}, {@code usermod}) fail inside the guest —
 *       there is no PAM service and the audit netlink is unavailable. Setup
 *       therefore hashes the credential and edits {@code /etc/shadow} directly
 *       via the rootfs's own {@code perl}, atomically replacing the file.</li>
 *   <li>The host key and {@code sshd_config} are written by the host into the
 *       overlay's {@code etc/} directory; no {@code ssh-keygen} binary is
 *       required in the payload.</li>
 * </ul>
 *
 * <p>Pure JVM file/argv logic; unit-testable with temp directories.</p>
 */
public final class GuestSshDaemon {

    /** Recognised guest SSH daemon profiles. Only OpenSSH is supported. */
    public enum Kind { OPENSSH }

    /** Env var that carries the per-install credential into the setup step. */
    public static final String TOKEN_ENV = "LW_SSH_TOKEN";

    /**
     * Env var that carries the guest {@code /etc/group} entries naming the
     * Android supplementary group IDs this process tree really carries
     * (newline-separated {@code name:x:gid:} lines, see
     * {@link GuestSupplementaryGroups} and ADR-0011). Non-secret data, but kept
     * out of argv like the token so the fixed setup argv stays fixed.
     */
    public static final String GROUP_ENTRIES_ENV = "LW_GUEST_GROUP_ENTRIES";

    private static final String ROOTFS_HOST_KEY = "/etc/ssh/ssh_host_ed25519_key";
    private static final String ROOTFS_CONFIG = "/etc/ssh/sshd_config";
    private static final String ROOTFS_PID_FILE = "/etc/ssh/sshd.pid";

    /** Overlay guest dirs: shared libraries and the daemon's config dir. */
    private static final String OVERLAY_LIB_DIR =
            CuratedRuntimeCatalog.SSH_OVERLAY_GUEST_DIR + "/usr/lib/aarch64-linux-gnu";
    private static final String OVERLAY_ETC_DIR =
            CuratedRuntimeCatalog.SSH_OVERLAY_GUEST_DIR + "/etc";
    private static final String OVERLAY_HOST_KEY =
            OVERLAY_ETC_DIR + "/ssh_host_ed25519_key";
    private static final String OVERLAY_CONFIG = OVERLAY_ETC_DIR + "/sshd_config";
    private static final String OVERLAY_PID_FILE = OVERLAY_ETC_DIR + "/sshd.pid";

    private final Kind kind;
    private final String binaryPath;
    private final String mountGuestDir;
    private final Path mountHostDir;

    private GuestSshDaemon(String binaryPath, String mountGuestDir, Path mountHostDir) {
        this.kind = Kind.OPENSSH;
        this.binaryPath = binaryPath;
        this.mountGuestDir = mountGuestDir;
        this.mountHostDir = mountHostDir;
    }

    /**
     * Detect a usable SSH daemon: first the curated add-on overlay, then the
     * active rootfs.
     *
     * @param rootfsDir   host path of the active guest rootfs (may be null)
     * @param addonOverlayDir host path of the activated SSH add-on overlay
     *                        (may be null/absent)
     * @return the detected daemon, or {@code null} when none is available
     */
    public static GuestSshDaemon detect(Path rootfsDir, Path addonOverlayDir) {
        if (addonOverlayDir != null
                && Files.isRegularFile(addonOverlayDir.resolve(
                        CuratedRuntimeCatalog.SSH_OVERLAY_ENTRYPOINT))) {
            return new GuestSshDaemon(
                    CuratedRuntimeCatalog.SSH_OVERLAY_GUEST_DIR
                            + "/" + CuratedRuntimeCatalog.SSH_OVERLAY_ENTRYPOINT,
                    CuratedRuntimeCatalog.SSH_OVERLAY_GUEST_DIR,
                    addonOverlayDir);
        }
        if (rootfsDir != null && Files.isRegularFile(rootfsDir.resolve("usr/sbin/sshd"))) {
            return new GuestSshDaemon("/usr/sbin/sshd", null, null);
        }
        return null;
    }

    public Kind getKind() {
        return kind;
    }

    /** Guest-visible absolute path of the daemon binary (never client text). */
    public String getBinaryPath() {
        return binaryPath;
    }

    /**
     * Whether the daemon lives in the bound add-on overlay rather than the
     * rootfs itself.
     */
    public boolean isOverlay() {
        return mountGuestDir != null;
    }

    /**
     * Extra bind mounts the launcher must add for this daemon: the overlay
     * bind for overlay daemons, none for rootfs-resident ones.
     */
    public List<ProotBindMount> requiredBinds() {
        if (!isOverlay()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(
                ProotBindMount.of(mountHostDir.toString(), mountGuestDir));
    }

    /**
     * {@code LD_LIBRARY_PATH} value the guest needs for overlay shared
     * libraries, or {@code null} for rootfs-resident daemons.
     */
    public String overlayLibraryPath() {
        return isOverlay() ? OVERLAY_LIB_DIR : null;
    }

    /**
     * {@code sshd} refuses to run without uid 0; PRoot {@code -0} provides it.
     */
    public boolean requiresFakeRoot() {
        return true;
    }

    /** Guest-visible path of the persisted host key used/pinned by the host. */
    public String hostKeyPath() {
        return isOverlay() ? OVERLAY_HOST_KEY : ROOTFS_HOST_KEY;
    }

    /** Guest-visible path of the daemon configuration file. */
    public String configPath() {
        return isOverlay() ? OVERLAY_CONFIG : ROOTFS_CONFIG;
    }

    /** Guest-visible path of the public half of the persisted host key. */
    public String hostPublicKeyPath() {
        return hostKeyPath() + ".pub";
    }

    /**
     * Guest-visible path of the daemon's own pid file. Guest {@code sshd}
     * writes its pid here on start; the host reads it to signal the daemon
     * directly, because the PRoot tracer is a different process and killing the
     * tracer leaves the daemon running (see {@link GuestSshdPidFile}).
     */
    public String pidFilePath() {
        return isOverlay() ? OVERLAY_PID_FILE : ROOTFS_PID_FILE;
    }

    /** Resolve the pid file to its host-side path for the given active rootfs. */
    public Path resolvePidFile(Path rootfsDir) {
        return resolveGuestFile(pidFilePath(), rootfsDir);
    }

    /**
     * Resolve a guest-absolute config path belonging to this daemon (host key,
     * config file) to the host-side file. Overlay paths resolve under the
     * activated overlay directory; rootfs paths under the active rootfs.
     */
    public Path resolveGuestFile(String guestPath, Path rootfsDir) {
        if (isOverlay() && guestPath.startsWith(mountGuestDir + "/")) {
            return mountHostDir.resolve(guestPath.substring(mountGuestDir.length() + 1));
        }
        if (guestPath.startsWith("/")) {
            return rootfsDir.resolve(guestPath.substring(1));
        }
        throw new IllegalArgumentException("not a daemon guest path: " + guestPath);
    }

    /**
     * Fixed {@code sshd_config} text for this daemon. Written host-side into
     * {@link #resolveGuestFile} of {@link #configPath()} before every start —
     * the file is deterministic product config, not guest state.
     */
    public String configText() {
        return "ListenAddress 127.0.0.1\n"
                + "HostKey " + hostKeyPath() + "\n"
                + "PermitRootLogin yes\n"
                + "PasswordAuthentication yes\n"
                + "KbdInteractiveAuthentication no\n"
                + "UsePAM no\n"
                + "PidFile " + pidFilePath() + "\n"
                + "AllowTcpForwarding no\n"
                + "AllowAgentForwarding no\n"
                + "X11Forwarding no\n"
                + "PermitTunnel no\n"
                + "Subsystem sftp internal-sftp\n";
    }

    /**
     * One-shot guest setup argv: create the privilege-separation directory and
     * account, name the inherited Android group IDs in the guest's group
     * database, then set {@code root}'s password to the per-install token
     * carried in {@link #TOKEN_ENV}. Neither the token nor the group entries is
     * an argv element.
     *
     * <p>PAM-based helpers cannot run inside the guest (no PAM service, no
     * audit netlink — verified on-device), so the script hashes the token with
     * the rootfs's own {@code perl} {@code crypt()} and replaces
     * {@code /etc/shadow} atomically. Only field 2 of the {@code root} entry is
     * changed.</p>
     *
     * <p>The optional {@link #GROUP_ENTRIES_ENV} block appends one
     * {@code name:x:gid:} line per inherited Android AID (ADR-0011) when the
     * guest does not already name that GID, and does nothing when the variable
     * is unset. It never edits or removes an existing entry: the guest's own
     * account database wins, so an Android ID can never be aliased onto a guest
     * group. Ubuntu's {@code /etc/bash.bashrc} runs {@code groups} on every
     * interactive login, which is what made the unnamed IDs visible.</p>
     */
    public List<String> setupArgv() {
        String script =
                "set -e; "
                + "mkdir -p /run/sshd; chmod 0755 /run/sshd; "
                + "if ! grep -q '^sshd:' /etc/passwd; then "
                + "echo 'sshd:x:105:65534::/run/sshd:/usr/sbin/nologin' >> /etc/passwd; fi; "
                + "if ! grep -q '^sshd:' /etc/group; then echo 'sshd:x:105:' >> /etc/group; fi; "
                + "if [ -n \"$" + GROUP_ENTRIES_ENV + "\" ]; then "
                + "printf '%s\\n' \"$" + GROUP_ENTRIES_ENV + "\" | "
                + "while IFS=: read -r lw_name lw_skip lw_gid lw_rest; do "
                + "[ -n \"$lw_name\" ] || continue; "
                + "grep -q \":$lw_gid:\" /etc/group || "
                + "printf '%s:x:%s:\\n' \"$lw_name\" \"$lw_gid\" >> /etc/group; "
                + "done; fi; "
                + "/usr/bin/perl -e '"
                + "my $t = $ENV{" + TOKEN_ENV + "}; "
                + "die qq{missing token} if !defined($t) || $t eq q{}; "
                + "my @s = (q{.}, q{/}, 0..9, q{A}..q{Z}, q{a}..q{Z}); "
                + "my $salt = join q{}, map { $s[int(rand(scalar(@s)))] } 1..16; "
                + "my $h = crypt($t, qq{\\$6\\$$salt\\$}); "
                + "die qq{crypt failed} if $h !~ m{^\\$6\\$}; "
                + "open(my $in, q{<}, q{/etc/shadow}) or die qq{shadow open}; "
                + "my @l = <$in>; close($in); "
                + "open(my $out, q{>}, q{/etc/shadow.lw}) or die qq{shadow write}; "
                + "my $ok = 0; "
                + "for my $line (@l) { $ok = 1 if $line =~ s{^root:[^:]*}{root:$h}; "
                + "print $out $line; } "
                + "close($out) or die qq{shadow close}; "
                + "die qq{no root entry} if !$ok; "
                + "chmod 0640, q{/etc/shadow.lw}; "
                + "rename(q{/etc/shadow.lw}, q{/etc/shadow}) or die qq{shadow rename};"
                + "'";
        return Arrays.asList("/bin/sh", "-c", script);
    }

    /**
     * Fixed daemon argv: foreground mode, stderr logging, the fixed config
     * written host-side, and the fixed loopback port. Loopback binding,
     * password auth, and the host key are all in the config — argv carries no
     * secrets.
     *
     * <p>{@code LogLevel VERBOSE} is required, not cosmetic: it makes the daemon
     * log {@code Server listening on 127.0.0.1 port N.} on stderr, which is the
     * only report that attributes the bound endpoint to this process
     * ({@link GuestSshdStartupLog}). {@code -D} keeps it in the foreground so the
     * host owns its lifetime, and {@code -e} routes the log to the pipe the host
     * drains.</p>
     *
     * @param loopbackPort the fixed loopback port from the endpoint contract;
     *                     validated positive
     */
    public List<String> daemonArgv(int loopbackPort) {
        if (loopbackPort <= 0 || loopbackPort > 65535) {
            throw new IllegalArgumentException("loopbackPort out of range: " + loopbackPort);
        }
        return Arrays.asList(binaryPath, "-D", "-e",
                "-o", "LogLevel=VERBOSE",
                "-f", configPath(),
                "-p", Integer.toString(loopbackPort));
    }

    /** Non-secret human label for logs/UI, e.g. {@code "OpenSSH sshd"}. */
    public String label() {
        return "OpenSSH sshd";
    }
}
