package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

/**
 * Runtime-permission check for the messaging/telephony/contacts operations.
 *
 * <p>Permission presence is read per request with
 * {@link Context#checkSelfPermission}, so a grant granted or revoked while
 * the app runs is observed on the next read. When the permission is not
 * granted, the app-op state distinguishes a never-asked grant
 * ({@link CapabilityPermission#REQUIRED}) from a previously denied one
 * ({@link CapabilityPermission#DENIED}): denying the runtime permission
 * records the matching app-op as {@link AppOpsManager#MODE_IGNORED}, while an
 * untouched permission stays at its default mode. The app-op state is a hint,
 * not a grant source: if it cannot be read or the permission has no public
 * app-op name, the check reports {@link CapabilityPermission#REQUIRED} so the
 * user-visible consent flow can ask rather than assume a refusal. This
 * checker never requests permission and never opens a permission activity.</p>
 */
public final class AndroidMessagingPermissionChecker implements MessagingPermissionChecker {
    private final Context context;

    public AndroidMessagingPermissionChecker(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public CapabilityPermission check(String androidPermission) {
        if (androidPermission == null) {
            throw new IllegalArgumentException("androidPermission must not be null");
        }
        if (granted(androidPermission)) {
            return CapabilityPermission.GRANTED;
        }
        String op = opName(androidPermission);
        if (op == null) {
            return CapabilityPermission.REQUIRED;
        }
        return previouslyDenied(op) ? CapabilityPermission.DENIED
                : CapabilityPermission.REQUIRED;
    }

    private boolean granted(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Public app-op name for the fixed permission set this slice reads, or
     * {@code null} when the permission is not one of them (the check then
     * reports REQUIRED and never guesses a denial).
     */
    private static String opName(String permission) {
        if (Manifest.permission.READ_CONTACTS.equals(permission)) {
            return AppOpsManager.OPSTR_READ_CONTACTS;
        }
        if (Manifest.permission.READ_CALL_LOG.equals(permission)) {
            return AppOpsManager.OPSTR_READ_CALL_LOG;
        }
        if (Manifest.permission.READ_SMS.equals(permission)) {
            return AppOpsManager.OPSTR_READ_SMS;
        }
        if (Manifest.permission.READ_PHONE_STATE.equals(permission)) {
            return AppOpsManager.OPSTR_READ_PHONE_STATE;
        }
        return null;
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
}
