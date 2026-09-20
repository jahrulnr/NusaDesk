package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.domain.backup.BackupManifest;
import gh.nusashell.nusadesk.domain.backup.BackupMode;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Manifest codec round-trips and every malformed shape that must surface as
 * {@link BackupManifestCodec.Malformed}. Runs under Robolectric for the real
 * {@code org.json} implementation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class BackupManifestCodecTest {

    private static BackupManifest manifest() {
        return new BackupManifest(BackupManifest.FORMAT_VERSION, BackupMode.CUSTOM,
                "ubuntu-base-arm64", "24.04.5", "0.4.0", 1_726_000_000_000L,
                Arrays.asList("/etc", "/usr/local"), 123L, 45_678L);
    }

    @Test
    public void roundTripPreservesEveryField() throws Exception {
        BackupManifest decoded = BackupManifestCodec.decode(
                BackupManifestCodec.encode(manifest()));
        assertEquals(BackupManifest.FORMAT_VERSION, decoded.getFormatVersion());
        assertEquals(BackupMode.CUSTOM, decoded.getMode());
        assertEquals("ubuntu-base-arm64", decoded.getRuntimeAppId());
        assertEquals("24.04.5", decoded.getRuntimeVersion());
        assertEquals("0.4.0", decoded.getAppVersion());
        assertEquals(1_726_000_000_000L, decoded.getCreatedAtEpochMs());
        assertEquals(Arrays.asList("/etc", "/usr/local"), decoded.getRoots());
        assertEquals(123L, decoded.getEntries());
        assertEquals(45_678L, decoded.getTotalBytes());
    }

    @Test
    public void malformedJsonIsRejected() throws Exception {
        try {
            BackupManifestCodec.decode("not json".getBytes(StandardCharsets.UTF_8));
            fail("non-JSON bytes must be rejected");
        } catch (BackupManifestCodec.Malformed expected) {
        }
    }

    @Test
    public void missingFieldsAreRejected() throws Exception {
        String json = "{\"formatVersion\":1,\"mode\":\"full\"}";
        try {
            BackupManifestCodec.decode(json.getBytes(StandardCharsets.UTF_8));
            fail("a manifest without runtimeAppId must be rejected");
        } catch (BackupManifestCodec.Malformed expected) {
        }
    }

    @Test
    public void unknownFormatVersionIsRejected() throws Exception {
        String json = "{\"formatVersion\":2,\"mode\":\"full\","
                + "\"runtimeAppId\":\"ubuntu-base-arm64\",\"runtimeVersion\":\"24.04.5\","
                + "\"appVersion\":\"0.4.0\",\"createdAtEpochMs\":0,\"roots\":[],"
                + "\"entries\":0,\"totalBytes\":0}";
        try {
            BackupManifestCodec.decode(json.getBytes(StandardCharsets.UTF_8));
            fail("format version 2 must be rejected");
        } catch (BackupManifestCodec.Malformed expected) {
        }
    }

    @Test
    public void unknownModeIsRejected() throws Exception {
        String json = "{\"formatVersion\":1,\"mode\":\"everything\","
                + "\"runtimeAppId\":\"ubuntu-base-arm64\",\"runtimeVersion\":\"24.04.5\","
                + "\"appVersion\":\"0.4.0\",\"createdAtEpochMs\":0,\"roots\":[],"
                + "\"entries\":0,\"totalBytes\":0}";
        try {
            BackupManifestCodec.decode(json.getBytes(StandardCharsets.UTF_8));
            fail("an unknown mode must be rejected");
        } catch (BackupManifestCodec.Malformed expected) {
        }
    }

    @Test
    public void rootsOutsideTheModeContractAreRejected() throws Exception {
        String json = "{\"formatVersion\":1,\"mode\":\"custom\","
                + "\"runtimeAppId\":\"ubuntu-base-arm64\",\"runtimeVersion\":\"24.04.5\","
                + "\"appVersion\":\"0.4.0\",\"createdAtEpochMs\":0,"
                + "\"roots\":[\"/proc\"],\"entries\":0,\"totalBytes\":0}";
        try {
            BackupManifestCodec.decode(json.getBytes(StandardCharsets.UTF_8));
            fail("a custom manifest may never list /proc");
        } catch (BackupManifestCodec.Malformed expected) {
        }
    }
}
