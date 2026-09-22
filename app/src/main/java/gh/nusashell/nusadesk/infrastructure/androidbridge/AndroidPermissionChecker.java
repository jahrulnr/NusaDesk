package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generic permission-state check for every manifest-declared permission.
 *
 * <p>This generalizes {@link AndroidMessagingPermissionChecker}: permission
 * presence is read per request with {@link Context#checkSelfPermission}, and
 * when the grant is missing the public app-op name
 * ({@link AppOpsManager#permissionToOp}) distinguishes a never-asked grant
 * ({@link CapabilityPermission#REQUIRED}) from a previously denied one
 * ({@link CapabilityPermission#DENIED}, recorded by the platform as
 * {@link AppOpsManager#MODE_IGNORED}). A permission with no public app-op
 * reports {@link CapabilityPermission#REQUIRED}; the check never guesses a
 * refusal.</p>
 *
 * <p>Special-access grants do not flow through {@code checkSelfPermission}
 * truthfully on every platform (an appop/signature-scoped permission can
 * report denied while its access toggle is on, or report granted while the
 * exemption it gates is off), so the well-known special permissions are read
 * through their documented platform source instead: overlay through
 * {@link Settings#canDrawOverlays}, system-settings writes through
 * {@link Settings.System#canWrite}, all-files through
 * {@link Environment#isExternalStorageManager} (API 30+), usage access through
 * its public app-op, battery exemption through
 * {@link PowerManager#isIgnoringBatteryOptimizations}, and notification
 * listening through the enabled-listener setting. Special access has no
 * recorded refusal, so it only ever reports GRANTED or REQUIRED — never a
 * guessed DENIED.</p>
 *
 * <p>This checker never requests permission and never opens a permission
 * activity; {@link #settingsIntent(String)} only builds the Settings intent a
 * foreground operation can start.</p>
 */
public final class AndroidPermissionChecker {
    private final Context context;
    /**
     * Manifest-declared permission names in declaration order. The manifest
     * cannot change while the app runs, so the set is loaded once and reused.
     */
    private volatile Set<String> declaredPermissions;

    public AndroidPermissionChecker(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    /** Resolve the current grant level for one Android permission name. */
    public CapabilityPermission check(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
        CapabilityPermission special = specialCheck(permission);
        if (special != null) {
            return special;
        }
        if (granted(permission)) {
            return CapabilityPermission.GRANTED;
        }
        String op = opName(permission);
        if (op == null) {
            return CapabilityPermission.REQUIRED;
        }
        return previouslyDenied(op) ? CapabilityPermission.DENIED
                : CapabilityPermission.REQUIRED;
    }

    /** Whether the manifest declares this permission name. */
    public boolean declared(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
        return declaredPermissions().contains(permission);
    }

    /** Every permission the manifest declares, in declaration order. */
    public Set<String> declaredPermissions() {
        Set<String> declared = declaredPermissions;
        if (declared == null) {
            declared = loadDeclaredPermissions();
            declaredPermissions = declared;
        }
        return declared;
    }

    /**
     * One-word state for {@code bridge.permissions}: {@code granted},
     * {@code denied}, or {@code required} for a declared permission the
     * platform knows, and {@code unsupported} for a name this app never
     * declared or this platform build does not define (for example
     * {@code MANAGE_EXTERNAL_STORAGE} below API 30, where the permission does
     * not exist at all).
     */
    public String describe(String permission) {
        if (!declared(permission) || !platformDefines(permission)) {
            return "unsupported";
        }
        CapabilityPermission state = check(permission);
        if (state == CapabilityPermission.GRANTED) {
            return "granted";
        }
        return state == CapabilityPermission.DENIED ? "denied" : "required";
    }

    /**
     * The Settings screen that can grant this permission, or the app-details
     * page when the permission has no dedicated screen. The {@code package:}
     * data URI is attached only to actions the platform documents as
     * accepting one.
     */
    public Intent settingsIntent(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
        Uri packageUri = Uri.parse("package:" + context.getPackageName());
        if (Manifest.permission.WRITE_SETTINGS.equals(permission)) {
            return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(packageUri);
        }
        if (Manifest.permission.MANAGE_EXTERNAL_STORAGE.equals(permission)) {
            // The all-files screen exists from API 30; below it the
            // permission is unsupported and app details is the only page.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(packageUri);
            }
            return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(packageUri);
        }
        if (Manifest.permission.SYSTEM_ALERT_WINDOW.equals(permission)) {
            return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(packageUri);
        }
        if (Manifest.permission.PACKAGE_USAGE_STATS.equals(permission)) {
            return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(packageUri);
        }
        if (Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE.equals(permission)) {
            // The listener screen takes no package URI input.
            return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        if (Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(permission)) {
            return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(packageUri);
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri);
    }

    private boolean granted(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The documented grant source for a special-access permission, or
     * {@code null} when the permission follows the normal runtime model.
     */
    private CapabilityPermission specialCheck(String permission) {
        try {
            if (Manifest.permission.SYSTEM_ALERT_WINDOW.equals(permission)) {
                return special(Settings.canDrawOverlays(context));
            }
            if (Manifest.permission.WRITE_SETTINGS.equals(permission)) {
                return special(Settings.System.canWrite(context));
            }
            if (Manifest.permission.MANAGE_EXTERNAL_STORAGE.equals(permission)) {
                // The permission does not exist below API 30; it is reported
                // REQUIRED here and "unsupported" by describe().
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? special(Environment.isExternalStorageManager())
                        : CapabilityPermission.REQUIRED;
            }
            if (Manifest.permission.PACKAGE_USAGE_STATS.equals(permission)) {
                return special(usageStatsAllowed());
            }
            if (Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(permission)) {
                return special(ignoringBatteryOptimizations());
            }
            if (Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE.equals(permission)) {
                return special(notificationListenerEnabled());
            }
        } catch (RuntimeException e) {
            // A flaky platform read is a missing grant, never a guessed denial.
            return CapabilityPermission.REQUIRED;
        }
        return null;
    }

    private static CapabilityPermission special(boolean granted) {
        return granted ? CapabilityPermission.GRANTED : CapabilityPermission.REQUIRED;
    }

    /** The permission's public app-op name, or null when it has none. */
    private static String opName(String permission) {
        try {
            return AppOpsManager.permissionToOp(permission);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean previouslyDenied(String op) {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            return appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.getPackageName())
                    == AppOpsManager.MODE_IGNORED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean usageStatsAllowed() {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean ignoringBatteryOptimizations() {
        try {
            PowerManager power =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null
                    && power.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether an enabled notification listener belongs to this package. The
     * enabled set is stored as colon-separated flattened component names
     * ({@code package/class}); only the package part is matched.
     */
    private boolean notificationListenerEnabled() {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (enabled == null) {
                return false;
            }
            String prefix = context.getPackageName() + "/";
            for (String component : enabled.split(":")) {
                if (component.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether the platform build defines this permission name at all. A name
     * from a newer API level (or a typo) resolves to nothing and is reported
     * {@code unsupported} rather than {@code denied}.
     */
    private boolean platformDefines(String permission) {
        try {
            context.getPackageManager().getPermissionInfo(permission, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            // An unreadable package service must not downgrade a real
            // permission to unsupported.
            return true;
        }
    }

    private Set<String> loadDeclaredPermissions() {
        Set<String> declared = new LinkedHashSet<>();
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = packageManager.getPackageInfo(context.getPackageName(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
            } else {
                info = packageManager.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_PERMISSIONS);
            }
            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) {
                    declared.add(permission);
                }
            }
        } catch (RuntimeException | PackageManager.NameNotFoundException e) {
            // An empty set answers "not declared" to everything; the bridge
            // reports each name as unsupported instead of throwing.
        }
        return declared;
    }
}
