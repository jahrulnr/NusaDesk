package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;

/**
 * Test helper: records an app-op as {@code MODE_IGNORED} (the platform's
 * "user refused this runtime permission" marker) through Robolectric's app-ops
 * shadow, mirroring what a real denial writes.
 */
final class AppOpsHelper {
    private AppOpsHelper() {
    }

    static void ignoreOp(Context context, String permission) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        String op = opName(permission);
        // Shadow.extract, not Shadows.shadowOf: the latter class cannot load
        // against the modern SDK stub jar (it references the removed
        // FingerprintManager), while extract is reflection-based.
        org.robolectric.shadows.ShadowAppOpsManager shadow =
                org.robolectric.shadow.api.Shadow.extract(appOps);
        shadow.setMode(op, Process.myUid(), context.getPackageName(),
                AppOpsManager.MODE_IGNORED);
    }

    private static String opName(String permission) {
        switch (permission) {
            case android.Manifest.permission.READ_CONTACTS:
                return AppOpsManager.OPSTR_READ_CONTACTS;
            case android.Manifest.permission.READ_CALL_LOG:
                return AppOpsManager.OPSTR_READ_CALL_LOG;
            case android.Manifest.permission.READ_SMS:
                return AppOpsManager.OPSTR_READ_SMS;
            case android.Manifest.permission.READ_PHONE_STATE:
                return AppOpsManager.OPSTR_READ_PHONE_STATE;
            default:
                throw new IllegalArgumentException("no app-op for " + permission);
        }
    }
}
