package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package capability domain: enumerate installed packages and launch one.
 * There is no upstream Termux:API counterpart; the {@code packages.*} surface
 * is NusaDesk-native and is the capability the manifest's
 * {@code QUERY_ALL_PACKAGES} declaration exists for — package visibility
 * without it collapses to a filtered subset on API 30+.
 *
 * <p>{@code packages.list} (params {@code filter} optional &le;64 chars —
 * case-insensitive prefix on package name or label, {@code limit} optional
 * 1..{@value #MAX_LIMIT} default {@value #DEFAULT_LIMIT},
 * {@code include_system} optional bool default true) reads
 * {@link PackageManager#getInstalledPackages}. The response carries
 * {@code packages_json} — rows of {@code package}, {@code label},
 * {@code version_name} (JSON null when the package declares none),
 * {@code version_code}, {@code system} ({@link ApplicationInfo#FLAG_SYSTEM}),
 * and {@code enabled}, sorted by label — plus {@code count} (the emitted row
 * count) and {@code truncated} (whether the filtered set exceeded
 * {@code limit}).</p>
 *
 * <p>{@code packages.info} (param {@code package} required &le;128 chars)
 * reports one package: {@code package}, {@code label}, {@code version_name}
 * (omitted when null), {@code version_code}, {@code target_sdk},
 * {@code min_sdk}, {@code first_install_time_ms},
 * {@code last_update_time_ms}, {@code system}, {@code enabled}, and
 * {@code launchable} (whether the package has a launcher entry point). An
 * unknown package answers {@code packages-unknown:<pkg>}.</p>
 *
 * <p>{@code packages.launch} (params {@code package} required &le;128 chars,
 * {@code activity} optional &le;256 chars) resolves
 * {@link PackageManager#getLaunchIntentForPackage}, retargets it to the
 * named class through {@link Intent#setClassName} when {@code activity} is
 * given, and starts it with {@link Intent#FLAG_ACTIVITY_NEW_TASK} — the
 * bridge runs outside an activity context. An unknown package answers
 * {@code packages-unknown:<pkg>}; a package with no launcher entry point
 * answers {@code packages-no-launch-intent}. Android 10+ restricts activity
 * starts from the background (BAL): the platform's refusal — a
 * {@link SecurityException} or {@link ActivityNotFoundException} — answers
 * {@code packages-launch-blocked:background activity start is restricted;
 * run it while the app is visible} rather than being guessed at (the
 * {@code SYSTEM_ALERT_WINDOW} grant is one documented exemption when the
 * user has enabled it). Any other start failure answers
 * {@code packages-launch-failed:activity start rejected}. On success the
 * fields are {@code launched=true} and {@code component} (the flattened
 * started component).</p>
 */
public final class PackagesModule implements CapabilityModule {
    private static final String TAG = "PackagesModule";

    private static final String METHOD_LIST = "packages.list";
    private static final String METHOD_INFO = "packages.info";
    private static final String METHOD_LAUNCH = "packages.launch";

    private static final int MAX_FILTER_CHARS = 64;
    private static final int MAX_PACKAGE_CHARS = 128;
    private static final int MAX_ACTIVITY_CHARS = 256;
    private static final int MAX_TEXT_CHARS = 128;
    private static final long DEFAULT_LIMIT = 50L;
    private static final long MAX_LIMIT = 100L;

    /**
     * The activity start, seamed for tests: a JVM test cannot put the process
     * into the background to make the platform refuse the start, so the
     * blocked case is driven by a launcher that throws like the platform.
     */
    interface ActivityLauncher {
        void start(Intent intent);
    }

    /** One listing row: the package plus its resolved label for sort/filter. */
    private static final class PackageRow {
        final PackageInfo info;
        final String label;
        final boolean system;

        PackageRow(PackageInfo info, String label, boolean system) {
            this.info = info;
            this.label = label;
            this.system = system;
        }
    }

    private final Context context;
    private final ActivityLauncher launcher;

    public PackagesModule(Context context) {
        this(context, null);
    }

    /**
     * Package-private seam constructor: the activity start is injectable so
     * the background-start refusal is testable without a device.
     */
    PackagesModule(Context context, ActivityLauncher launcher) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.launcher = launcher != null ? launcher : this.context::startActivity;
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_LIST, METHOD_INFO, METHOD_LAUNCH);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_LIST, METHOD_INFO, METHOD_LAUNCH);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_LIST:
                return list(request);
            case METHOD_INFO:
                return info(request);
            case METHOD_LAUNCH:
                return launch(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code packages.list} — params {@code filter}, {@code limit},
     * {@code include_system}. The label is resolved through
     * {@link ApplicationInfo#loadLabel} and falls back to the package name
     * when the package has no usable label, so one broken package can never
     * fail the whole listing.
     */
    private AndroidCapabilityProtocol.Response list(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("filter", "limit", "include_system"));
        String filter = params.optionalString("filter", MAX_FILTER_CHARS, "");
        long limit = params.optionalLong("limit", 1, MAX_LIMIT, DEFAULT_LIMIT);
        boolean includeSystem = params.optionalBoolean("include_system", true);
        PackageManager pm = packageManager();
        if (pm == null) {
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        List<PackageInfo> installed;
        try {
            installed = installedPackages(pm);
        } catch (RuntimeException e) {
            Log.w(TAG, "packages.list failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        List<PackageRow> rows = new ArrayList<>();
        for (PackageInfo info : installed) {
            if (info == null || info.packageName == null || info.applicationInfo == null) {
                continue;
            }
            boolean system = isSystem(info.applicationInfo);
            if (system && !includeSystem) {
                continue;
            }
            String label = label(pm, info.applicationInfo, info.packageName);
            if (!needle.isEmpty()
                    && !info.packageName.toLowerCase(Locale.ROOT).startsWith(needle)
                    && !label.toLowerCase(Locale.ROOT).startsWith(needle)) {
                continue;
            }
            rows.add(new PackageRow(info, label, system));
        }
        rows.sort((a, b) -> {
            int byLabel = String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
            return byLabel != 0 ? byLabel : a.info.packageName.compareTo(b.info.packageName);
        });
        int count = (int) Math.min(rows.size(), limit);
        StringBuilder json = new StringBuilder(count * 192);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(rowJson(rows.get(i)));
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("packages_json", json.toString());
        fields.put("count", (long) count);
        fields.put("truncated", rows.size() > count);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /**
     * {@code packages.info} — param {@code package}. A missing package is its
     * own typed error so the caller can tell "not installed" apart from a
     * platform failure.
     */
    private AndroidCapabilityProtocol.Response info(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("package"));
        String pkg = params.requireString("package", MAX_PACKAGE_CHARS);
        if (pkg.isEmpty()) {
            throw new CapabilityParams.Invalid("parameter must not be empty: package");
        }
        PackageManager pm = packageManager();
        if (pm == null) {
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        PackageInfo info;
        try {
            info = packageInfo(pm, pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return AndroidCapabilityProtocol.Response.error(id, "packages-unknown:" + pkg);
        } catch (RuntimeException e) {
            Log.w(TAG, "packages.info failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        ApplicationInfo app = info.applicationInfo;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("package", pkg);
        fields.put("label", app != null ? label(pm, app, pkg) : pkg);
        if (info.versionName != null) {
            fields.put("version_name", bounded(info.versionName));
        }
        fields.put("version_code", info.getLongVersionCode());
        fields.put("target_sdk", app != null ? (long) app.targetSdkVersion : 0L);
        fields.put("min_sdk", app != null ? (long) app.minSdkVersion : 0L);
        fields.put("first_install_time_ms", info.firstInstallTime);
        fields.put("last_update_time_ms", info.lastUpdateTime);
        fields.put("system", app != null && isSystem(app));
        fields.put("enabled", app != null && app.enabled);
        fields.put("launchable", launchable(pm, pkg));
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /**
     * {@code packages.launch} — params {@code package}, {@code activity}.
     * Package existence is checked before the intent lookup so an unknown
     * package and a package with no launcher entry point stay distinct typed
     * errors.
     */
    private AndroidCapabilityProtocol.Response launch(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("package", "activity"));
        String pkg = params.requireString("package", MAX_PACKAGE_CHARS);
        if (pkg.isEmpty()) {
            throw new CapabilityParams.Invalid("parameter must not be empty: package");
        }
        String activity = params.optionalString("activity", MAX_ACTIVITY_CHARS, "");
        PackageManager pm = packageManager();
        if (pm == null) {
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        try {
            packageInfo(pm, pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return AndroidCapabilityProtocol.Response.error(id, "packages-unknown:" + pkg);
        } catch (RuntimeException e) {
            Log.w(TAG, "packages.launch package lookup failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        Intent intent;
        try {
            intent = pm.getLaunchIntentForPackage(pkg);
        } catch (RuntimeException e) {
            Log.w(TAG, "packages.launch intent lookup failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "packages-unavailable");
        }
        if (intent == null) {
            return AndroidCapabilityProtocol.Response.error(id, "packages-no-launch-intent");
        }
        if (!activity.isEmpty()) {
            // A ".Foo" class name is relative to the package, matching
            // `am start pkg/.Foo` and ComponentName.createRelative — the raw
            // ComponentName constructor keeps the literal and it would never
            // resolve to a real class.
            intent.setClassName(pkg,
                    activity.charAt(0) == '.' ? pkg + activity : activity);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            launcher.start(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            // The platform's BAL refusal: do not fabricate a success.
            Log.w(TAG, "packages.launch blocked", e);
            return AndroidCapabilityProtocol.Response.error(id,
                    "packages-launch-blocked:background activity start is restricted;"
                            + " run it while the app is visible");
        } catch (RuntimeException e) {
            Log.w(TAG, "packages.launch failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    id, "packages-launch-failed:activity start rejected");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("launched", true);
        ComponentName component = intent.getComponent();
        if (component != null) {
            fields.put("component", component.flattenToShortString());
        }
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    private PackageManager packageManager() {
        try {
            return context.getPackageManager();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code getInstalledPackages} across the API 33 flag-type split. */
    @SuppressLint("Deprecation")
    private static List<PackageInfo> installedPackages(PackageManager pm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
        }
        return pm.getInstalledPackages(0);
    }

    /** {@code getPackageInfo} across the API 33 flag-type split. */
    @SuppressLint("Deprecation")
    private static PackageInfo packageInfo(PackageManager pm, String packageName)
            throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0));
        }
        return pm.getPackageInfo(packageName, 0);
    }

    /**
     * The package label for display and filtering: the app-supplied label
     * when it resolves, else the package name. Bounded so an oversized label
     * cannot blow the 64 KiB response frame.
     */
    private static String label(PackageManager pm, ApplicationInfo app, String packageName) {
        try {
            CharSequence loaded = app.loadLabel(pm);
            if (loaded != null && loaded.length() > 0) {
                return bounded(loaded.toString());
            }
        } catch (RuntimeException e) {
            // A broken package resource is not a listing failure.
        }
        return packageName;
    }

    private static boolean isSystem(ApplicationInfo app) {
        return (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /** Whether the package has a launcher entry point; never throws. */
    private static boolean launchable(PackageManager pm, String packageName) {
        try {
            return pm.getLaunchIntentForPackage(packageName) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String bounded(String value) {
        return value.length() > MAX_TEXT_CHARS ? value.substring(0, MAX_TEXT_CHARS) : value;
    }

    /** One {@code packages_json} row in the documented field order. */
    private static String rowJson(PackageRow row) {
        PackageInfo info = row.info;
        StringBuilder json = new StringBuilder(192);
        json.append("{\"package\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(info.packageName))
                .append(",\"label\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(row.label))
                .append(",\"version_name\":")
                .append(info.versionName == null
                        ? "null"
                        : AndroidCapabilityProtocol.encodeStringValue(
                                bounded(info.versionName)))
                .append(",\"version_code\":").append(info.getLongVersionCode())
                .append(",\"system\":").append(row.system)
                .append(",\"enabled\":").append(info.applicationInfo.enabled)
                .append('}');
        return json.toString();
    }
}
