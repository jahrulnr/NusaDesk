package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

/**
 * Per-mode camera/microphone grant check for the live media stream.
 *
 * <p>Permission presence is read per request with
 * {@link Context#checkSelfPermission}, so a grant granted or revoked while
 * the app runs is observed on the next read. Only the permissions the
 * requested {@link LiveMediaMode} uses are considered: a camera-only session
 * never requires (or reports a denial for) the microphone, and a
 * microphone-only session never touches the camera grant. When a needed
 * permission is missing, the app-op state distinguishes a never-asked grant
 * ({@link CapabilityPermission#REQUIRED}) from a previously denied one
 * ({@link CapabilityPermission#DENIED}): denying the runtime permission
 * records the matching app-op as {@link AppOpsManager#MODE_IGNORED}, while
 * an untouched permission stays at its default mode. The app-op state is a
 * hint, not a grant source: if it cannot be read, the check reports
 * {@link CapabilityPermission#REQUIRED} so the user-visible consent flow can
 * ask rather than assume a refusal. The controller never prompts by itself.</p>
 */
public final class AndroidMediaPermissionChecker implements MediaPermissionChecker {
    private final Context context;

    public AndroidMediaPermissionChecker(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public CapabilityPermission check(LiveMediaMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        boolean cameraGranted = !mode.hasVideo() || granted(Manifest.permission.CAMERA);
        boolean microphoneGranted = !mode.hasAudio()
                || granted(Manifest.permission.RECORD_AUDIO);
        if (cameraGranted && microphoneGranted) {
            return CapabilityPermission.GRANTED;
        }
        return previouslyDenied(mode) ? CapabilityPermission.DENIED
                : CapabilityPermission.REQUIRED;
    }

    private boolean granted(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Only the ops the mode actually uses can mark it as previously denied. */
    private boolean previouslyDenied(LiveMediaMode mode) {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            return (mode.hasVideo() && ignored(appOps, AppOpsManager.OPSTR_CAMERA))
                    || (mode.hasAudio()
                    && ignored(appOps, AppOpsManager.OPSTR_RECORD_AUDIO));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean ignored(AppOpsManager appOps, String op) {
        return appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.getPackageName())
                == AppOpsManager.MODE_IGNORED;
    }
}
