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
 * the guest binds a <em>user-chosen</em> folder on Android 11+ (ADR-0023), and
 * on Android 10 the platform's legacy storage model is the only door to that
 * same folder — measured on the S7 Edge (API 29, 2026-09-21): with the flag and
 * both runtime grants the app reads and writes {@code /sdcard} normally, and
 * with either piece missing it sees {@code list=null canRead=false}. The legacy
 * permissions therefore exist, and they stay bounded to API 29 (ADR-0047) so an
 * Android 11+ device never sees them.</p>
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
    public void declaresLegacyStorageOnlyBoundedToAndroidTen() throws Exception {
        String manifest = manifest();

        assertTrue("Android 10 binds shared folders through the legacy model (ADR-0047)",
                manifest.contains("android:requestLegacyExternalStorage=\"true\""));
        for (String permission : new String[]{
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"}) {
            int at = manifest.indexOf(permission);
            assertTrue(permission + " must be declared for the Android 10 workspace", at > 0);
            assertTrue(permission + " must be bounded to API 29",
                    manifest.substring(at, Math.min(manifest.length(), at + 120))
                            .contains("maxSdkVersion=\"29\""));
        }
        assertFalse("media permissions stay out",
                manifest.contains("android.permission.MANAGE_MEDIA"));
        assertFalse(manifest.contains("android.permission.ACCESS_MEDIA_LOCATION"));
    }
}
