package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link PackagesModule} on the JVM. Installed packages and launch intents
 * come from Robolectric's real {@link ShadowPackageManager} state
 * ({@code installPackage} / {@code addResolveInfoForIntent}); only the final
 * {@code startActivity} is seamed, because a unit test cannot put the JVM
 * process into the background to make the platform refuse the start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PackagesModuleTest {
    private Context context;
    private ShadowPackageManager packages;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packages = Shadow.extract(context.getPackageManager());
    }

    @Test
    public void declaresThePackagesMethodsAndParamSet() {
        PackagesModule module = module();
        assertEquals(3, module.methods().size());
        assertTrue(module.methods().contains("packages.list"));
        assertTrue(module.methods().contains("packages.info"));
        assertTrue(module.methods().contains("packages.launch"));
        assertEquals(Set.of("packages.list", "packages.info", "packages.launch"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "packages.nope"));
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void listFiltersAndSortsByLabel() {
        install("com.example.beta", "Beta", false);
        install("com.example.alpha", "Alpha", false);
        install("org.other.app", "Other", false);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.list", "{\"filter\":\"com.example.\"}"));
        assertTrue(response.isOk());
        assertEquals("["
                        + "{\"package\":\"com.example.alpha\",\"label\":\"Alpha\","
                        + "\"version_name\":\"1.2.3\",\"version_code\":7,"
                        + "\"system\":false,\"enabled\":true}"
                        + ",{\"package\":\"com.example.beta\",\"label\":\"Beta\","
                        + "\"version_name\":\"1.2.3\",\"version_code\":7,"
                        + "\"system\":false,\"enabled\":true}"
                        + "]",
                response.getFields().get("packages_json"));
        assertEquals(2L, response.getFields().get("count"));
        assertEquals(false, response.getFields().get("truncated"));
    }

    @Test
    public void listFilterMatchesLabelPrefixCaseInsensitively() {
        install("com.example.alpha", "Alpha App", false);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.list", "{\"filter\":\"alpha\"}"));
        assertTrue(response.isOk());
        assertEquals(1L, response.getFields().get("count"));
        assertTrue(((String) response.getFields().get("packages_json"))
                .contains("\"package\":\"com.example.alpha\""));
    }

    @Test
    public void listLimitBoundsRowsAndReportsTruncation() {
        install("com.example.a", "A", false);
        install("com.example.b", "B", false);
        install("com.example.c", "C", false);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.list", "{\"filter\":\"com.example.\",\"limit\":2}"));
        assertTrue(response.isOk());
        assertEquals(2L, response.getFields().get("count"));
        assertEquals(true, response.getFields().get("truncated"));
        String json = (String) response.getFields().get("packages_json");
        assertTrue(json.contains("com.example.a"));
        assertTrue(json.contains("com.example.b"));
        assertFalse(json.contains("com.example.c"));
    }

    @Test
    public void listExcludesSystemPackagesWhenAsked() {
        install("com.example.user", "User", false);
        install("com.example.sys", "Sys", true);

        AndroidCapabilityProtocol.Response all = module().handle(
                request("1", "packages.list", "{\"filter\":\"com.example.\"}"));
        assertEquals(2L, all.getFields().get("count"));

        AndroidCapabilityProtocol.Response userOnly = module().handle(
                request("2", "packages.list",
                        "{\"filter\":\"com.example.\",\"include_system\":false}"));
        assertTrue(userOnly.isOk());
        assertEquals(1L, userOnly.getFields().get("count"));
        String json = (String) userOnly.getFields().get("packages_json");
        assertTrue(json.contains("\"system\":false"));
        assertFalse(json.contains("com.example.sys"));
    }

    @Test
    public void listReportsNullVersionName() {
        PackageInfo info = install("com.example.bare", "Bare", false);
        info.versionName = null;

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.list", "{\"filter\":\"com.example.bare\"}"));
        assertTrue(response.isOk());
        assertTrue(((String) response.getFields().get("packages_json"))
                .contains("\"version_name\":null"));
    }

    @Test
    public void listRejectsBadParams() {
        PackagesModule module = module();
        assertThrowsInvalid(module, "1", "packages.list", "{\"limit\":0}");
        assertThrowsInvalid(module, "2", "packages.list", "{\"limit\":101}");
        assertThrowsInvalid(module, "3", "packages.list", "{\"limit\":\"2\"}");
        assertThrowsInvalid(module, "4", "packages.list", "{\"bogus\":1}");
        assertThrowsInvalid(module, "5", "packages.list", "{\"include_system\":\"yes\"}");
    }

    @Test
    public void infoUnknownPackageIsTypedError() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.info", "{\"package\":\"com.example.missing\"}"));
        assertFalse(response.isOk());
        assertEquals("packages-unknown:com.example.missing", response.getError());
    }

    @Test
    public void infoReportsPackageDetails() {
        install("com.example.alpha", "Alpha", false);
        makeLaunchable("com.example.alpha", "com.example.alpha.MainActivity");

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.info", "{\"package\":\"com.example.alpha\"}"));
        assertTrue(response.isOk());
        assertEquals("com.example.alpha", response.getFields().get("package"));
        assertEquals("Alpha", response.getFields().get("label"));
        assertEquals("1.2.3", response.getFields().get("version_name"));
        assertEquals(7L, response.getFields().get("version_code"));
        assertEquals(34L, response.getFields().get("target_sdk"));
        assertEquals(29L, response.getFields().get("min_sdk"));
        assertEquals(1_700_000_000_000L,
                response.getFields().get("first_install_time_ms"));
        assertEquals(1_700_000_100_000L,
                response.getFields().get("last_update_time_ms"));
        assertEquals(false, response.getFields().get("system"));
        assertEquals(true, response.getFields().get("enabled"));
        assertEquals(true, response.getFields().get("launchable"));
    }

    @Test
    public void infoWithoutLaunchIntentIsNotLaunchable() {
        install("com.example.alpha", "Alpha", false);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.info", "{\"package\":\"com.example.alpha\"}"));
        assertTrue(response.isOk());
        assertEquals(false, response.getFields().get("launchable"));
    }

    @Test
    public void infoRejectsBadParams() {
        PackagesModule module = module();
        assertThrowsInvalid(module, "1", "packages.info", "{}");
        assertThrowsInvalid(module, "2", "packages.info", "{\"package\":\"\"}");
        assertThrowsInvalid(module, "3", "packages.info",
                "{\"package\":\"" + repeat('a', 129) + "\"}");
        assertThrowsInvalid(module, "4", "packages.info",
                "{\"package\":\"com.example.a\",\"bogus\":1}");
    }

    @Test
    public void launchUnknownPackageIsTypedError() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.launch", "{\"package\":\"com.example.missing\"}"));
        assertFalse(response.isOk());
        assertEquals("packages-unknown:com.example.missing", response.getError());
    }

    @Test
    public void launchWithoutLaunchIntentIsTypedError() {
        install("com.example.alpha", "Alpha", false);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.launch", "{\"package\":\"com.example.alpha\"}"));
        assertFalse(response.isOk());
        assertEquals("packages-no-launch-intent", response.getError());
    }

    @Test
    public void launchStartsTheResolvedComponent() {
        install("com.example.alpha", "Alpha", false);
        makeLaunchable("com.example.alpha", "com.example.alpha.MainActivity");

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.launch", "{\"package\":\"com.example.alpha\"}"));
        assertTrue(response.isOk());
        assertEquals(true, response.getFields().get("launched"));
        assertEquals("com.example.alpha/.MainActivity",
                response.getFields().get("component"));

        Intent started = Shadow.<ShadowContextWrapper>extract(context)
                .getNextStartedActivity();
        assertNotNull(started);
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals("com.example.alpha", started.getComponent().getPackageName());
        assertEquals("com.example.alpha.MainActivity",
                started.getComponent().getClassName());
    }

    @Test
    public void launchWithActivityRetargetsTheComponent() {
        install("com.example.alpha", "Alpha", false);
        makeLaunchable("com.example.alpha", "com.example.alpha.MainActivity");

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "packages.launch",
                        "{\"package\":\"com.example.alpha\","
                                + "\"activity\":\".SettingsActivity\"}"));
        assertTrue(response.isOk());
        assertEquals("com.example.alpha/.SettingsActivity",
                response.getFields().get("component"));

        Intent started = Shadow.<ShadowContextWrapper>extract(context)
                .getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.example.alpha.SettingsActivity",
                started.getComponent().getClassName());
    }

    @Test
    public void launchRefusedFromBackgroundIsTypedBlocked() {
        install("com.example.alpha", "Alpha", false);
        makeLaunchable("com.example.alpha", "com.example.alpha.MainActivity");
        PackagesModule module = new PackagesModule(context,
                intent -> { throw new ActivityNotFoundException("BAL"); });

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "packages.launch", "{\"package\":\"com.example.alpha\"}"));
        assertFalse(response.isOk());
        assertEquals("packages-launch-blocked:background activity start is restricted;"
                        + " run it while the app is visible",
                response.getError());
    }

    @Test
    public void launchSecurityExceptionIsTypedBlocked() {
        install("com.example.alpha", "Alpha", false);
        makeLaunchable("com.example.alpha", "com.example.alpha.MainActivity");
        PackagesModule module = new PackagesModule(context,
                intent -> { throw new SecurityException("no activity start"); });

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "packages.launch", "{\"package\":\"com.example.alpha\"}"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("packages-launch-blocked"));
    }

    @Test
    public void launchRuntimeFailureIsTypedFailure() {
        install("com.example.alpha", "Alpha", false);
        makeLaunchable("com.example.alpha", "com.example.alpha.MainActivity");
        PackagesModule module = new PackagesModule(context,
                intent -> { throw new IllegalStateException("start rejected"); });

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "packages.launch", "{\"package\":\"com.example.alpha\"}"));
        assertFalse(response.isOk());
        assertEquals("packages-launch-failed:activity start rejected", response.getError());
    }

    @Test
    public void launchRejectsBadParams() {
        PackagesModule module = module();
        assertThrowsInvalid(module, "1", "packages.launch", "{}");
        assertThrowsInvalid(module, "2", "packages.launch", "{\"package\":\"\"}");
        assertThrowsInvalid(module, "3", "packages.launch",
                "{\"package\":\"com.example.a\",\"bogus\":1}");
    }

    @Test
    public void nullRequestIsUnsupported() {
        AndroidCapabilityProtocol.Response response = module().handle(null);
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    // --- helpers ---------------------------------------------------------

    private PackagesModule module() {
        return new PackagesModule(context);
    }

    private PackageInfo install(String packageName, String label, boolean system) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.versionName = "1.2.3";
        info.versionCode = 7;
        info.firstInstallTime = 1_700_000_000_000L;
        info.lastUpdateTime = 1_700_000_100_000L;
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = packageName;
        app.nonLocalizedLabel = label;
        app.flags = system ? ApplicationInfo.FLAG_SYSTEM : 0;
        app.enabled = true;
        app.targetSdkVersion = 34;
        app.minSdkVersion = 29;
        info.applicationInfo = app;
        packages.installPackage(info);
        return info;
    }

    /**
     * Register a launcher entry point for the package: the shadow resolves
     * {@code getLaunchIntentForPackage} from the same {@code ACTION_MAIN} +
     * {@code CATEGORY_LAUNCHER} + package intent the platform builds.
     */
    private void makeLaunchable(String packageName, String activityClass) {
        Intent query = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        ResolveInfo resolve = new ResolveInfo();
        ActivityInfo activity = new ActivityInfo();
        activity.packageName = packageName;
        activity.name = activityClass;
        activity.applicationInfo = new ApplicationInfo();
        activity.applicationInfo.packageName = packageName;
        activity.applicationInfo.enabled = true;
        resolve.activityInfo = activity;
        packages.addResolveInfoForIntent(query, resolve);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                           String paramsJson) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        AndroidCapabilityProtocol.Request request = AndroidCapabilityProtocol.decodeRequest(frame);
        assertNotNull("test frame must decode", request);
        return request;
    }

    private static void assertThrowsInvalid(CapabilityModule module, String id,
                                            String method, String paramsJson) {
        try {
            module.handle(request(id, method, paramsJson));
        } catch (CapabilityParams.Invalid e) {
            return;
        }
        throw new AssertionError(method + " must reject params " + paramsJson);
    }

    private static String repeat(char c, int n) {
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            out.append(c);
        }
        return out.toString();
    }
}
