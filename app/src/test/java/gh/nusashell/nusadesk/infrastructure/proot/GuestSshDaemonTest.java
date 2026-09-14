package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.runtime.GuestSshPayloadProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure-JVM tests for {@link GuestSshDaemon}: overlay + rootfs detection, fixed
 * argv shape, loopback-only config, secret-by-env setup, and guest/host path
 * resolution. No Android, no process launches.
 */
public class GuestSshDaemonTest {

    @Rule
    public final TemporaryFolder rootfs = new TemporaryFolder();

    @Rule
    public final TemporaryFolder overlay = new TemporaryFolder();

    @Test
    public void detectReturnsNullForStockUbuntuBase() {
        // Ubuntu Base ships no sshd — the honest unavailable path.
        assertNull(GuestSshDaemon.detect(rootfs.getRoot().toPath(), null));
        assertNull(GuestSshDaemon.detect(null, null));
        assertNull(GuestSshDaemon.detect(null, overlay.getRoot().toPath()));
    }

    @Test
    public void detectFindsRootfsOpenSsh() throws Exception {
        touch(rootfs.getRoot(), "usr/sbin/sshd");
        GuestSshDaemon daemon = GuestSshDaemon.detect(rootfs.getRoot().toPath(), null);
        assertNotNull(daemon);
        assertEquals(GuestSshDaemon.Kind.OPENSSH, daemon.getKind());
        assertEquals("/usr/sbin/sshd", daemon.getBinaryPath());
        assertFalse(daemon.isOverlay());
        assertTrue(daemon.requiredBinds().isEmpty());
        assertNull(daemon.overlayLibraryPath());
        assertTrue(daemon.requiresFakeRoot());
        assertEquals("/etc/ssh/ssh_host_ed25519_key", daemon.hostKeyPath());
        assertEquals("/etc/ssh/ssh_host_ed25519_key.pub", daemon.hostPublicKeyPath());
        assertEquals("/etc/ssh/sshd_config", daemon.configPath());
    }

    @Test
    public void detectFindsOverlayDaemon() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        assertNotNull(daemon);
        assertEquals(GuestSshDaemon.Kind.OPENSSH, daemon.getKind());
        assertTrue(daemon.isOverlay());
        assertEquals(GuestSshPayloadProfile.GUEST_DIR + "/" + GuestSshPayloadProfile.ENTRYPOINT,
                daemon.getBinaryPath());
        assertEquals(GuestSshPayloadProfile.GUEST_DIR + "/usr/lib/aarch64-linux-gnu",
                daemon.overlayLibraryPath());
        assertEquals(GuestSshPayloadProfile.GUEST_DIR + "/etc/ssh_host_ed25519_key",
                daemon.hostKeyPath());
        assertEquals(GuestSshPayloadProfile.GUEST_DIR + "/etc/sshd_config",
                daemon.configPath());
    }

    @Test
    public void overlayDaemonRequiresOverlayBind() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        List<ProotBindMount> binds = daemon.requiredBinds();
        assertEquals(1, binds.size());
        ProotBindMount bind = binds.get(0);
        assertEquals(overlay.getRoot().getAbsolutePath(), bind.getHostPath());
        assertEquals(GuestSshPayloadProfile.GUEST_DIR, bind.getGuestPath());
    }

    @Test
    public void overlayDaemonWinsOverRootfsDaemon() throws Exception {
        touch(rootfs.getRoot(), "usr/sbin/sshd");
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        assertTrue(daemon.isOverlay());
    }

    @Test
    public void resolveGuestFileMapsOverlayAndRootfsPaths() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon overlayDaemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        Path key = overlayDaemon.resolveGuestFile(
                overlayDaemon.hostKeyPath(), rootfs.getRoot().toPath());
        assertEquals(overlay.getRoot().toPath().resolve("etc/ssh_host_ed25519_key"), key);
        // A genuine guest-root path still resolves inside the rootfs.
        Path shadow = overlayDaemon.resolveGuestFile(
                "/etc/shadow", rootfs.getRoot().toPath());
        assertEquals(rootfs.getRoot().toPath().resolve("etc/shadow"), shadow);
    }

    @Test
    public void daemonArgvBindsLoopbackOnlyThroughConfig() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        List<String> argv = daemon.daemonArgv(45678);
        assertEquals(daemon.getBinaryPath(), argv.get(0));
        String joined = String.join(" ", argv);
        assertTrue(joined.contains("-D"));            // foreground
        assertTrue(joined.contains("-e"));            // stderr logging
        assertTrue(joined.contains("-f " + daemon.configPath()));
        assertTrue(joined.contains("-p 45678"));
        assertFalse(joined.contains("0.0.0.0"));
        assertFalse(joined.contains("::"));
        // The bind report the host requires is only emitted at VERBOSE.
        assertTrue(joined.contains("-o LogLevel=VERBOSE"));
        // The loopback bind lives in the fixed config, not argv.
        assertTrue(daemon.configText().contains("ListenAddress 127.0.0.1"));
        assertTrue(daemon.configText().contains("UsePAM no"));
        assertTrue(daemon.configText().contains("PasswordAuthentication yes"));
        assertTrue(daemon.configText().contains("HostKey " + daemon.hostKeyPath()));
        assertFalse(daemon.configText().contains("0.0.0.0"));
    }

    @Test
    public void pidFileIsPersistedInsideTheDaemonsConfigDirectory() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon overlayDaemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        assertEquals(GuestSshPayloadProfile.GUEST_DIR + "/etc/sshd.pid",
                overlayDaemon.pidFilePath());
        assertEquals(overlay.getRoot().toPath().resolve("etc/sshd.pid"),
                overlayDaemon.resolvePidFile(rootfs.getRoot().toPath()));
        // The daemon writes its own pid; the host signals that pid to stop it.
        assertTrue(overlayDaemon.configText().contains("PidFile " + overlayDaemon.pidFilePath()));
        assertFalse(overlayDaemon.configText().contains("PidFile none"));

        touch(rootfs.getRoot(), "usr/sbin/sshd");
        GuestSshDaemon rootfsDaemon = GuestSshDaemon.detect(rootfs.getRoot().toPath(), null);
        assertEquals("/etc/ssh/sshd.pid", rootfsDaemon.pidFilePath());
        assertEquals(rootfs.getRoot().toPath().resolve("etc/ssh/sshd.pid"),
                rootfsDaemon.resolvePidFile(rootfs.getRoot().toPath()));
    }

    @Test
    public void daemonArgvRejectsOutOfRangePorts() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        for (int port : new int[]{0, -1, 65536}) {
            try {
                daemon.daemonArgv(port);
                fail("port " + port + " must be rejected");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void setupArgvCarriesTokenByEnvOnlyAndAvoidsPamHelpers() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        List<String> argv = daemon.setupArgv();
        assertEquals("/bin/sh", argv.get(0));
        assertEquals("-c", argv.get(1));
        String script = argv.get(2);
        // The secret is referenced by env name only — never embedded.
        assertTrue(script.contains("$ENV{" + GuestSshDaemon.TOKEN_ENV + "}"));
        // PAM helpers cannot run inside the guest; setup uses perl crypt()
        // against /etc/shadow directly and atomically renames the result.
        assertFalse(script.contains("chpasswd"));
        assertFalse(script.contains("usermod"));
        assertTrue(script.contains("/usr/bin/perl"));
        assertTrue(script.contains("/etc/shadow"));
        assertTrue(script.contains("crypt("));
        assertTrue(script.contains("/run/sshd"));
        // The privsep account sshd requires is provisioned idempotently.
        assertTrue(script.contains("grep -q '^sshd:' /etc/passwd"));
    }

    @Test
    public void setupArgvNamesInheritedAndroidGroupsByIdempotentGuestEntry() throws Exception {
        touch(overlay.getRoot(), GuestSshPayloadProfile.ENTRYPOINT);
        GuestSshDaemon daemon = GuestSshDaemon.detect(
                rootfs.getRoot().toPath(), overlay.getRoot().toPath());
        List<String> argv = daemon.setupArgv();
        String script = argv.get(2);
        // The inherited group IDs are data carried by env, never argv/script text.
        assertTrue(script.contains("$" + GuestSshDaemon.GROUP_ENTRIES_ENV));
        assertTrue(script.contains("/etc/group"));
        assertFalse(script.contains("3003"));
        assertFalse(script.contains("aid_"));
        // Entries are only added when the guest does not already name that GID,
        // and only ever appended: the guest's own account database wins.
        assertTrue(script.contains("grep -q \":$lw_gid:\" /etc/group"));
        int block = script.indexOf("$" + GuestSshDaemon.GROUP_ENTRIES_ENV + "\" ]");
        assertTrue(block > 0);
        String groupBlock = script.substring(block, script.indexOf("done; fi;", block));
        assertTrue(groupBlock.contains(">> /etc/group"));
        assertFalse(groupBlock.contains("sed "));
        assertFalse(groupBlock.contains("mv "));
        assertFalse(groupBlock.contains("/etc/passwd"));
        assertFalse(groupBlock.contains("/etc/shadow"));
    }

    private void touch(File base, String guestRelativePath) throws Exception {
        File f = new File(base, guestRelativePath);
        assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
        assertTrue(f.createNewFile());
    }
}
