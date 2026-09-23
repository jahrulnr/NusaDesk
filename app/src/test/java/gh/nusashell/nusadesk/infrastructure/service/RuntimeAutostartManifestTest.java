package gh.nusashell.nusadesk.infrastructure.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guards the autostart boundary at the manifest level (ADR-0013, amended by
 * ADR-0037).
 *
 * <p>Linux starts from a user-visible launch — an Activity foreground event
 * calling {@link RuntimeHostService#ensureRunning} — or, only when the user
 * opted in, from the single reviewed boot receiver. Every other background
 * path stays forbidden: no job, alarm, worker, or exported component another
 * app could trigger. The receiver declaration is pinned here rather than
 * merely allowed, so a second wake-up path can never slip in beside it. The
 * file is read from the module directory (Gradle's unit-test working
 * directory), with the repository-relative path as a fallback.</p>
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

    /**
     * The manifest with XML comments removed.
     *
     * <p>Structural assertions must run against this, not the raw text: the
     * manifest documents the components it deliberately does <em>not</em>
     * declare (and names like {@code LOCKED_BOOT_COMPLETED} in prose), so a
     * comment naming a forbidden component would otherwise read as if the
     * component were present.</p>
     */
    private static String manifestStructure() throws Exception {
        return manifest().replaceAll("(?s)<!--.*?-->", "");
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle);
                index >= 0;
                index = haystack.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** The single declared receiver element, verbatim. */
    private static String receiverBlock(String manifest) {
        int start = manifest.indexOf("<receiver");
        assertTrue("the opt-in boot receiver must be declared", start >= 0);
        int end = manifest.indexOf("</receiver>", start);
        assertTrue("the receiver element must close", end > start);
        return manifest.substring(start, end);
    }

    @Test
    public void declaresOnlyTheOptInBootReceiver() throws Exception {
        String manifest = manifestStructure();

        assertEquals("exactly one receiver component may exist (ADR-0037)",
                1, occurrences(manifest, "<receiver"));
        String receiver = receiverBlock(manifest);
        assertTrue("the one receiver is the opt-in boot trigger",
                receiver.contains(".infrastructure.boot.BootStartReceiver"));
        assertTrue("the boot receiver must not be triggerable by other apps",
                receiver.contains("android:exported=\"false\""));
        assertFalse("the boot receiver must never run before the first unlock",
                receiver.contains("directBootAware=\"true\""));
    }

    @Test
    public void bootReceiverListensOnlyToTheTwoReviewedActions() throws Exception {
        String manifest = manifestStructure();

        assertFalse("credential-encrypted storage is unavailable before unlock",
                manifest.contains("LOCKED_BOOT_COMPLETED"));
        String receiver = receiverBlock(manifest);
        assertEquals("the filter carries exactly the two reviewed actions",
                2, occurrences(receiver, "<action"));
        assertTrue(receiver.contains("\"android.intent.action.BOOT_COMPLETED\""));
        assertTrue(receiver.contains("\"android.intent.action.MY_PACKAGE_REPLACED\""));
    }

    @Test
    public void declaresNoOtherBackgroundAutostartPath() throws Exception {
        String manifest = manifestStructure();

        assertEquals("the opt-in boot permission is declared exactly once",
                1, occurrences(manifest, "android.permission.RECEIVE_BOOT_COMPLETED"));
        assertFalse("no job/alarm/worker based autostart may be declared",
                manifest.contains("android.app.job.JobService"));
        assertFalse(manifest.contains("android.permission.SCHEDULE_EXACT_ALARM"));
        assertFalse(manifest.contains("android.permission.USE_EXACT_ALARM"));
    }

    @Test
    public void hostServiceStaysUnexportedAndTypedForTheRuntime() throws Exception {
        String manifest = manifest();

        assertTrue(manifest.contains(
                "android:name=\".infrastructure.service.RuntimeHostService\""));
        assertTrue("the host service must not be startable by other apps",
                manifest.contains("android:exported=\"false\""));
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""));
        // Comments may name the type (the manifest documents that it is
        // deliberately absent); only a real declaration counts.
        assertFalse("dataSync must not be used as an indefinite server type",
                manifest.replaceAll("(?s)<!--.*?-->", "").contains("dataSync"));
    }

    @Test
    public void permissionsStayAnchoredToTheAutostartBoundary() throws Exception {
        String manifest = manifest();

        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"));
        // The full permission set moved to an explicit allow-list when the
        // automation surface was declared; see BridgePermissionsManifestTest,
        // which asserts the declared set equals a reviewed list exactly.
    }
}
