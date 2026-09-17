package gh.nusashell.nusadesk.infrastructure.workspace;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guards the workspace feature's storage contract.
 *
 * <p>The workspace is the only reason this app asks for a broad permission, so
 * the manifest state is pinned deliberately: all-files access is present because
 * the guest binds a <em>user-chosen</em> folder (ADR-0023), and the legacy
 * broad-storage permissions are absent because they would be a silent, broader
 * grant than the feature needs — {@code WRITE_EXTERNAL_STORAGE} is ignored on
 * Android 11+ anyway, so declaring it would only mislead a reviewer.</p>
 */
public class WorkspaceStorageManifestTest {

    private static String manifest() throws Exception {
        String[] candidates = {
                "src/main/AndroidManifest.xml",
                "app/src/main/AndroidManifest.xml",
        };
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("AndroidManifest.xml not found");
    }

    @Test
    public void declaresAllFilesAccessForTheUserChosenWorkspace() throws Exception {
        String manifest = manifest();

        assertTrue("the workspace bind needs all-files access on Android 11+",
                manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"));
        assertTrue("the permission must stay explained in the manifest itself",
                manifest.contains("ADR-0023"));
    }

    @Test
    public void declaresNoLegacyBroadStoragePermissions() throws Exception {
        String manifest = manifest();

        assertFalse("WRITE_EXTERNAL_STORAGE is ignored from API 30 and would only widen the grant",
                manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("android.permission.MANAGE_MEDIA"));
        assertFalse(manifest.contains("android.permission.ACCESS_MEDIA_LOCATION"));
    }
}
