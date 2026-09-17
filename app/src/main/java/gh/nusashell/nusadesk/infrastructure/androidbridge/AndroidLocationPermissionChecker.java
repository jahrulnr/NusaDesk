package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

/**
 * Foreground-only location grant check.
 *
 * <p>Permission presence is read per request with
 * {@link Context#checkSelfPermission}, so a grant granted or revoked while
 * the app runs is observed on the next read. When neither coarse nor fine is
 * granted, the app-op state distinguishes a never-asked grant
 * ({@link LocationGrant#REQUIRED}) from a previously denied one
 * ({@link LocationGrant#DENIED}): denying the runtime permission records the
 * matching location app-op as {@link AppOpsManager#MODE_IGNORED}, while an
 * untouched permission stays at its default mode. The app-op state is a hint,
 * not a grant source: if it cannot be read, the check reports
 * {@link LocationGrant#REQUIRED} so the user-visible consent flow can ask
 * rather than assume a refusal.</p>
 */
public final class AndroidLocationPermissionChecker implements LocationPermissionChecker {
    private final Context context;

    public AndroidLocationPermissionChecker(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public LocationGrant check() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return LocationGrant.FINE;
        }
        if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return LocationGrant.COARSE_ONLY;
        }
        return previouslyDenied() ? LocationGrant.DENIED : LocationGrant.REQUIRED;
    }

    private boolean granted(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean previouslyDenied() {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            return ignored(appOps, AppOpsManager.OPSTR_FINE_LOCATION)
                    || ignored(appOps, AppOpsManager.OPSTR_COARSE_LOCATION);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean ignored(AppOpsManager appOps, String op) {
        return appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_IGNORED;
    }
}
