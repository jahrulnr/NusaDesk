package gh.nusashell.nusadesk.infrastructure.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guards the ADR-0013 autostart boundary at the manifest level.
 *
 * <p>Linux may only start from a user-visible launch: an Activity foreground
 * event calling {@link RuntimeHostService#ensureRunning}. Android would need a
 * manifest declaration for any of the forbidden paths — a
 * {@code BOOT_COMPLETED} receiver, a boot permission, or an exported service
 * another app could start — so asserting their absence here is the cheapest
 * durable guard for that rule. The file is read from the module directory
 * (Gradle's unit-test working directory), with the repository-relative path as a
 * fallback.</p>
 */
public class RuntimeAutostartManifestTest {

    private static final String[] MANIFEST_CANDIDATES = {
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
    };

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

    @Test
    public void declaresNoBackgroundAutostartPath() throws Exception {
        String manifest = manifest();

        assertFalse("no boot permission may be requested",
                manifest.contains("RECEIVE_BOOT_COMPLETED"));
        assertFalse("no boot receiver may be declared",
                manifest.contains("android.intent.action.BOOT_COMPLETED"));
        assertFalse("no broadcast receiver may be declared for this product",
                manifest.contains("<receiver"));
        assertFalse("no job/alarm/worker based autostart may be declared",
                manifest.contains("android.app.job.JobService"));
    }

    @Test
    public void hostServiceStaysUnexportedAndTypedForTheRuntime() throws Exception {
        String manifest = manifest();

        assertTrue(manifest.contains(
                "android:name=\".infrastructure.service.RuntimeHostService\""));
        assertTrue("the host service must not be startable by other apps",
                manifest.contains("android:exported=\"false\""));
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""));
        assertFalse("dataSync must not be used as an indefinite server type",
                manifest.contains("dataSync"));
    }

    @Test
    public void permissionsStayMinimal() throws Exception {
        String manifest = manifest();

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"));
        assertFalse(manifest.contains("android.permission.WAKE_LOCK"));
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"));
    }
}
