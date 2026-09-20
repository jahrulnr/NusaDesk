package gh.nusashell.nusadesk.domain.backup;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The manifest value object's format-v1 invariants. */
public class BackupManifestTest {

    private static BackupManifest manifest(BackupMode mode, java.util.List<String> roots) {
        return new BackupManifest(BackupManifest.FORMAT_VERSION, mode,
                "ubuntu-base-arm64", "24.04.5", "0.4.0", 1_726_000_000_000L,
                roots, 42L, 1024L);
    }

    @Test
    public void validManifestsConstruct() {
        BackupManifest full = manifest(BackupMode.FULL, Collections.<String>emptyList());
        assertEquals(BackupManifest.FORMAT_VERSION, full.getFormatVersion());
        assertEquals("ubuntu-base-arm64", full.getRuntimeAppId());
        assertEquals(1024L, full.getTotalBytes());
        manifest(BackupMode.HOME, Arrays.asList("/root", "/home"));
        manifest(BackupMode.CUSTOM, Collections.singletonList("/usr/local"));
    }

    @Test
    public void unsupportedFormatVersionIsRejected() {
        try {
            new BackupManifest(2, BackupMode.FULL, "ubuntu-base-arm64",
                    "24.04.5", "0.4.0", 0L, Collections.<String>emptyList(), 0L, 0L);
            fail("format version 2 must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rootsMustMatchTheMode() {
        try {
            manifest(BackupMode.FULL, Collections.singletonList("/root"));
            fail("a full manifest must not declare roots");
        } catch (IllegalArgumentException expected) {
        }
        try {
            manifest(BackupMode.HOME, Collections.singletonList("/etc"));
            fail("a home manifest may only list /root and /home");
        } catch (IllegalArgumentException expected) {
        }
        try {
            manifest(BackupMode.CUSTOM, Collections.singletonList("/tmp"));
            fail("a custom manifest may never list /tmp");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void negativeCountersAndBlankIdsAreRejected() {
        try {
            new BackupManifest(1, BackupMode.FULL, "", "24.04.5", "0.4.0",
                    0L, Collections.<String>emptyList(), 0L, 0L);
            fail("a blank runtimeAppId must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new BackupManifest(1, BackupMode.FULL, "ubuntu-base-arm64", "24.04.5",
                    "0.4.0", 0L, Collections.<String>emptyList(), -1L, 0L);
            fail("negative entry counts must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }
}
