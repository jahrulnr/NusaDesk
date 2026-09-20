package gh.nusashell.nusadesk.infrastructure.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Pins the exact manifest permission set.
 *
 * <p>This is deliberately an allow-list equality check rather than a set of
 * "must not contain" assertions. The permission surface is now large enough
 * that a deny-list would leave gaps: any permission not on the list is a
 * failure, so adding one is always a deliberate edit to this file and its
 * stated reason.</p>
 *
 * <p>The set exists because some users want to drive Android from inside their
 * Linux workspace. The product exposes the API and holds the grant; whether a
 * capability is ever exercised is the user's decision. Consequences this test
 * also protects: nothing is requested at launch, special access is never
 * assumed, and component-level capabilities that are a different class of risk
 * (accessibility, notification listener, SMS broadcast trigger) stay out.</p>
 */
public class BridgePermissionsManifestTest {

    private static final String[] MANIFEST_CANDIDATES = {
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
    };

    /**
     * Every permission the manifest is allowed to declare, with the reason it
     * is there. Keep this list and the manifest in step.
     */
    private static final String[] ALLOWED_PERMISSIONS = {
            // Runtime foundation.
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            // Assisted in-app update (ADR-0039): stages the verified release
            // APK through the platform PackageInstaller; the user enables the
            // per-app unknown-sources toggle from the install popup, and the
            // platform confirms every install. Never a silent install.
            "android.permission.REQUEST_INSTALL_PACKAGES",
            // Opt-in "Start Linux at boot" trigger (ADR-0037): a normal
            // install-time permission, default OFF, no runtime grant.
            "android.permission.RECEIVE_BOOT_COMPLETED",
            // Workspace folder the user picks (ADR-0023).
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            // The same workspace on Android 10 (ADR-0047): the platform's legacy
            // storage model is the only door to a shared folder there, and it
            // needs these two runtime permissions. Both are declared with
            // maxSdkVersion="29", so Android 11+ never sees them.
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            // Camera / microphone / location capabilities.
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            // Sensor and motion inputs.
            "android.permission.HIGH_SAMPLING_RATE_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION",
            // Bridge-owned status notifications.
            "android.permission.POST_NOTIFICATIONS",
            // Bluetooth capability (modern pair plus the capped legacy pair).
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            // Foreground-service types a capability declares at runtime.
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            // Telephony / messaging / personal-data inputs for automation.
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALENDAR",
            // Calendar write access for the bounded calendar.insert /
            // calendar.update / calendar.delete methods. Never requested at
            // launch: the user grants it from Android App Info, and a missing
            // grant is a typed calendar-permission-* error.
            "android.permission.WRITE_CALENDAR",
            // Keep-alive and user-granted special access.
            "android.permission.WAKE_LOCK",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.QUERY_ALL_PACKAGES",
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

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle);
                index >= 0;
                index = haystack.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** Every permission name declared by a {@code <uses-permission>} element. */
    private static Set<String> declaredPermissions(String manifest) {
        String flat = manifest.replaceAll("\\s+", " ");
        Matcher matcher = Pattern
                .compile("<uses-permission[^>]*?android:name=\"([^\"]+)\"")
                .matcher(flat);
        Set<String> declared = new LinkedHashSet<>();
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    @Test
    public void declaredPermissionSetMatchesTheAllowListExactly() throws Exception {
        Set<String> declared = new TreeSet<>(declaredPermissions(manifest()));
        Set<String> allowed = new TreeSet<>(java.util.Arrays.asList(ALLOWED_PERMISSIONS));
        assertEquals("declared permissions must match the reviewed allow-list;"
                        + " update ALLOWED_PERMISSIONS and the manifest together",
                allowed, declared);
    }

    @Test
    public void everyAllowedPermissionHasItsRationaleInTheManifest() throws Exception {
        // The allow-list is only trustworthy if each entry is explained next to
        // it. A permission with no comment is a permission nobody reviewed.
        //
        // A comment block that sits directly above the element closes with "-->"
        // in the run-up, which is what this checks. Testing for the opening
        // "<!--" instead would fail on a long block whose opening marker falls
        // outside the window.
        String manifest = manifest();
        String flat = manifest.replaceAll("\\s+", " ");
        for (String permission : ALLOWED_PERMISSIONS) {
            int index = flat.indexOf(permission);
            assertTrue(permission + " must be declared", index > 0);
            String preceding = flat.substring(Math.max(0, index - 700), index);
            assertTrue(permission + " must carry an explanatory comment",
                    preceding.contains("-->"));
        }
    }

    @Test
    public void runtimeOnlyGrantDisciplineIsDocumented() throws Exception {
        // The behavioural rule the whole block depends on: no launch-time
        // prompt, no assumed special access.
        String flat = manifest().replaceAll("\\s+", " ");
        assertTrue("the manifest must state that nothing requests a grant at launch",
                flat.contains("Nothing may request a runtime grant at launch"));
        assertTrue("the manifest must state that special access is never assumed",
                flat.contains("is never assumed"));
    }

    @Test
    public void componentLevelRisksStayUndeclared() throws Exception {
        // These are not permissions you can opt into; each one gives the app a
        // different class of access. They need their own accepted decision.
        // Comments are stripped first, because the manifest documents the
        // components it deliberately does NOT declare.
        String manifest = manifest().replaceAll("(?s)<!--.*?-->", "");
        assertFalse("no accessibility service may be declared",
                manifest.contains("BIND_ACCESSIBILITY_SERVICE"));
        assertFalse("no notification listener may be declared",
                manifest.contains("BIND_NOTIFICATION_LISTENER_SERVICE"));
        // A manifest receiver is a background wake-up surface; the single
        // reviewed exception is the opt-in boot trigger (ADR-0037). Anything
        // else — an SMS receive trigger above all — stays out.
        assertEquals("only the opt-in boot receiver may be declared",
                1, occurrences(manifest, "<receiver"));
        assertTrue("the one allowed receiver is the opt-in boot trigger",
                manifest.contains(".infrastructure.boot.BootStartReceiver"));
        // The legacy storage permissions exist for Android 10's workspace only
        // (ADR-0047) and must stay bounded to that release, so an API 30+ device
        // never sees them as an alternative to the all-files grant.
        int write = manifest.indexOf("android.permission.WRITE_EXTERNAL_STORAGE");
        assertTrue("the Android 10 workspace needs the write permission", write > 0);
        assertTrue("the write permission must be bounded to API 29",
                manifest.substring(write, Math.min(manifest.length(), write + 120))
                        .contains("maxSdkVersion=\"29\""));
        int read = manifest.indexOf("android.permission.READ_EXTERNAL_STORAGE");
        assertTrue("the Android 10 workspace needs the read permission", read > 0);
        assertTrue("the read permission must be bounded to API 29",
                manifest.substring(read, Math.min(manifest.length(), read + 120))
                        .contains("maxSdkVersion=\"29\""));
        assertTrue("Android 10 binds shared folders through the legacy model",
                manifest.contains("android:requestLegacyExternalStorage=\"true\""));
    }

    @Test
    public void bluetoothScanNeverClaimsToDeriveLocation() throws Exception {
        String manifest = manifest();
        int scan = manifest.indexOf("android.permission.BLUETOOTH_SCAN");
        assertTrue("BLUETOOTH_SCAN must be declared", scan > 0);
        String declaration = manifest.substring(scan, Math.min(manifest.length(), scan + 200));
        assertTrue("BLUETOOTH_SCAN must assert neverForLocation",
                declaration.contains("neverForLocation"));
    }

    @Test
    public void legacyBluetoothPairIsCappedToApi30() throws Exception {
        // The SCAN/CONNECT pair only exists from API 31; below that the legacy
        // install-granted pair must not linger on modern devices.
        String manifest = manifest();
        for (String legacy : new String[]{
                "android.permission.BLUETOOTH\"",
                "android.permission.BLUETOOTH_ADMIN\""}) {
            int index = manifest.indexOf(legacy);
            assertTrue(legacy + " must be declared for the API 29 floor", index > 0);
            String declaration = manifest.substring(index,
                    Math.min(manifest.length(), index + 180));
            assertTrue(legacy + " must be capped with maxSdkVersion",
                    declaration.contains("android:maxSdkVersion=\"30\""));
        }
    }

    @Test
    public void impliedHardwareFeaturesStayOptional() throws Exception {
        // Declaring CAMERA / RECORD_AUDIO / ACCESS_*_LOCATION makes Play imply
        // the matching hardware features as required. Each must be pinned
        // optional so the app stays installable on devices without them.
        String manifest = manifest();

        for (String feature : new String[]{
                "android.hardware.camera",
                "android.hardware.camera.autofocus",
                "android.hardware.microphone",
                "android.hardware.location",
                "android.hardware.location.gps",
                "android.hardware.bluetooth"}) {
            int index = manifest.indexOf(feature);
            assertTrue(feature + " must be declared explicitly", index > 0);
            String declaration = manifest.substring(index,
                    Math.min(manifest.length(), index + 140));
            assertTrue(feature + " must be required=\"false\"",
                    declaration.contains("android:required=\"false\""));
        }
    }
}
