package gh.nusashell.nusadesk.infrastructure.service;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the live media foreground-service boundary at the manifest level.
 *
 * <p>The live media service must be unexported (no other app can start it),
 * typed {@code camera|microphone} (the exact permissions are already
 * declared), and never a {@code dataSync} server. The file is read from the
 * module directory (Gradle's unit-test working directory), with the
 * repository-relative path as a fallback.</p>
 */
public class LiveMediaServiceManifestTest {

    private static final String[] MANIFEST_CANDIDATES = {
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
    };

    @Test
    public void liveMediaServiceIsUnexportedAndTypedForCameraAndMicrophone()
            throws Exception {
        String manifest = manifest();

        assertTrue("the live media service must be declared",
                manifest.contains("android:name=\".infrastructure.service.LiveMediaService\""));
        assertTrue("the live media service must not be startable by other apps",
                manifest.contains("android:exported=\"false\""));
        assertTrue("the service must carry the camera|microphone foreground type",
                manifest.contains("android:foregroundServiceType=\"camera|microphone\""));
        assertFalse("dataSync must not be used as an indefinite server type",
                manifest.contains("dataSync"));
    }

    @Test
    public void cameraMicrophoneAndForegroundTypePermissionsStayDeclared()
            throws Exception {
        String manifest = manifest();

        assertTrue(manifest.contains("android.permission.CAMERA"));
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"));
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CAMERA"));
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"));
    }

    private static String manifest() throws Exception {
        for (String candidate : MANIFEST_CANDIDATES) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("AndroidManifest.xml not found from "
                + Paths.get("").toAbsolutePath());
    }
}
