package gh.nusashell.nusadesk.infrastructure.update;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins the assisted-install wiring a device run exposed as missing: the checker
 * parses the release asset (HTTPS URL, channel digest, size) and the install
 * dialog reads it back from {@link UpdateCheckPrefs}, but nothing ever persisted
 * it — so the banner's Install action always answered "The channel did not
 * report the APK over HTTPS…" while the check itself was fine (observed on the
 * S7 Edge, 2026-09-21, with `last_seen_tag=v0.6.0` and no asset keys stored).
 *
 * <p>A wiring pin, not a substitute for the device run: the flow itself is
 * verified on a device (banner → Install → download), and this only keeps the
 * one call from disappearing again.</p>
 */
public class UpdateAssetWiringTest {

    @Test
    public void theCoordinatorPersistsTheAssetTheCheckerReported() throws Exception {
        String activity = read("app/src/main/java/gh/nusashell/nusadesk/presentation/MainActivity.java");

        assertTrue("the update result must persist the reported asset",
                activity.contains("updatePrefs.recordAsset("));
        assertTrue("the persisted URL must come from the checked result",
                activity.contains("result.getDownloadUrl()"));
        assertTrue("the persisted digest must come from the checked result",
                activity.contains("result.getDigest()"));
        assertTrue("the persisted size must come from the checked result",
                activity.contains("result.getSizeBytes()"));
    }

    private static String read(String relative) throws Exception {
        String[] candidates = {relative, "../" + relative};
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("missing file: " + relative);
    }
}
