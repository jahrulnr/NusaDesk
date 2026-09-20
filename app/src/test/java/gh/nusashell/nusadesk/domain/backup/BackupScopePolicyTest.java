package gh.nusashell.nusadesk.domain.backup;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The backup scope contract: the CUSTOM allowlist is the only surface a user
 * can select, the virtual dirs and the workspace bind target are excluded
 * from every archive, and manifest roots must match their mode.
 */
public class BackupScopePolicyTest {

    @Test
    public void customAllowlistCoversTheLockedSet() {
        List<String> allowed = BackupScopePolicy.allowedCustomRoots();
        assertTrue(allowed.containsAll(Arrays.asList(
                "/root", "/home", "/opt", "/usr/local", "/etc", "/var/lib")));
        for (String never : Arrays.asList("/proc", "/sys", "/dev", "/run", "/tmp")) {
            assertFalse(never + " must never be selectable", allowed.contains(never));
        }
    }

    @Test
    public void customRootsAreCanonicalizedAndOrderedByAllowlist() {
        List<String> roots = BackupScopePolicy.validateCustomRoots(
                Arrays.asList("var/lib", "/etc", "/etc"));
        assertEquals(Arrays.asList("/etc", "/var/lib"), roots);
    }

    @Test
    public void customRejectsEmptyAndNonAllowlistedRoots() {
        try {
            BackupScopePolicy.validateCustomRoots(Collections.emptyList());
            fail("an empty custom selection must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        for (String bad : Arrays.asList("/proc", "/tmp", "/usr", "/var", "/boot")) {
            try {
                BackupScopePolicy.validateCustomRoots(Collections.singletonList(bad));
                fail(bad + " is outside the custom allowlist");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void canonicalGuestPathRejectsTraversalAndEmpty() {
        for (String bad : Arrays.asList("../etc", "/etc/../root", "/..", "", "  ", "/")) {
            try {
                BackupScopePolicy.canonicalGuestPath(bad);
                fail(bad + " must be rejected");
            } catch (IllegalArgumentException expected) {
            }
        }
        assertEquals("/usr/local", BackupScopePolicy.canonicalGuestPath("//usr//local/"));
        assertEquals("usr/local", BackupScopePolicy.relativeOf("/usr/local"));
    }

    @Test
    public void exclusionsCoverVirtualDirsAndWorkspaceContents() {
        for (String excluded : Arrays.asList(
                "proc", "proc/cpuinfo", "sys/kernel", "dev/null", "run", "tmp/x",
                "root/nusadesk/file", "root/nusadesk/sub/dir")) {
            assertTrue(excluded + " must be excluded",
                    BackupScopePolicy.isExcludedFromArchive(excluded));
        }
        // The mount-point directory itself stays: the restored rootfs keeps an
        // empty mount point so the next workspace bind still has its target.
        assertFalse(BackupScopePolicy.isExcludedFromArchive("root/nusadesk"));
        for (String kept : Arrays.asList(
                "root", "root/.bashrc", "home/user/file", "etc/hostname",
                "usr/local/bin/tool", "var/lib/dpkg/status")) {
            assertFalse(kept + " must be archived",
                    BackupScopePolicy.isExcludedFromArchive(kept));
        }
    }

    /**
     * The add-on overlay mount points carry mode {@code 000} in the guest, so
     * an export that descends into them fails with an io-failure; their
     * entries stay (the mount point survives a restore) and their payloads
     * travel as the separate {@code addons/<id>} trees.
     */
    @Test
    public void exclusionsCoverAddonOverlayContentsButKeepTheirMountPoints() {
        for (String excluded : Arrays.asList(
                "opt/lw-ssh/usr", "opt/lw-ssh/usr/bin/sshd", "opt/lw-services/usr/bin/python3",
                "opt/lw-services/lw-proc")) {
            assertTrue(excluded + " must be excluded",
                    BackupScopePolicy.isExcludedFromArchive(excluded));
        }
        for (String kept : Arrays.asList(
                "opt", "opt/lw-ssh", "opt/lw-services", "opt/go/bin/go",
                "opt/nusadesk", "usr/share/lw-services/EUPL-LICENSE.md")) {
            assertFalse(kept + " must be archived",
                    BackupScopePolicy.isExcludedFromArchive(kept));
        }
    }

    /**
     * The walk needs a second predicate: an excluded *path* is not archived at
     * all, while a mount point keeps its entry and skips its subtree — the
     * only safe option for a directory the process cannot list.
     */
    @Test
    public void mountPointsKeepTheirEntryWithoutBeingDescendedInto() {
        for (String mountPoint : Arrays.asList(
                "root/nusadesk", "opt/lw-ssh", "opt/lw-services",
                "system", "apex", "linkerconfig")) {
            assertTrue(mountPoint + " is a mount point",
                    BackupScopePolicy.isMountPointWithoutContents(mountPoint));
        }
        for (String ordinary : Arrays.asList(
                "opt", "opt/go", "opt/lw-ssh/usr", "root", "etc", "system/bin",
                "apex/com.android.runtime", "", null)) {
            assertFalse(String.valueOf(ordinary) + " is not a mount point",
                    BackupScopePolicy.isMountPointWithoutContents(ordinary));
        }
    }

    @Test
    public void manifestRootsMustMatchTheirMode() {
        // FULL carries no selected roots.
        BackupScopePolicy.validateManifestRoots(BackupMode.FULL, Collections.emptyList());
        try {
            BackupScopePolicy.validateManifestRoots(
                    BackupMode.FULL, Collections.singletonList("/root"));
            fail("a full manifest must not declare roots");
        } catch (IllegalArgumentException expected) {
        }
        // HOME is exactly a subset of {/root, /home}.
        BackupScopePolicy.validateManifestRoots(
                BackupMode.HOME, Arrays.asList("/root", "/home"));
        BackupScopePolicy.validateManifestRoots(
                BackupMode.HOME, Collections.singletonList("/root"));
        try {
            BackupScopePolicy.validateManifestRoots(
                    BackupMode.HOME, Collections.singletonList("/etc"));
            fail("a home manifest may only list /root and /home");
        } catch (IllegalArgumentException expected) {
        }
        try {
            BackupScopePolicy.validateManifestRoots(
                    BackupMode.HOME, Collections.emptyList());
            fail("a home manifest needs at least one root");
        } catch (IllegalArgumentException expected) {
        }
        // CUSTOM is any non-empty allowlist subset.
        BackupScopePolicy.validateManifestRoots(
                BackupMode.CUSTOM, Collections.singletonList("/opt"));
        try {
            BackupScopePolicy.validateManifestRoots(
                    BackupMode.CUSTOM, Collections.singletonList("/proc"));
            fail("a custom manifest may never list /proc");
        } catch (IllegalArgumentException expected) {
        }
    }
}
