package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the terminal continuity contract across device rotation. */
public class MainActivityRotationManifestTest {

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
    public void mainActivityHandlesRotationWithoutRecreatingTerminalSession() throws Exception {
        String manifest = manifest();

        assertTrue("MainActivity must retain its WebView and SSH bridge across rotation",
                manifest.contains(
                        "android:configChanges=\"orientation|screenSize|keyboardHidden\""));
    }
}
