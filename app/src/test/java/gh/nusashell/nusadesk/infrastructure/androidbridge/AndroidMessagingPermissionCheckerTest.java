package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowContextWrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Grant-resolution behavior of the messaging permission checker on the JVM.
 *
 * <p>Robolectric auto-grants every permission the merged manifest declares,
 * so the granted path runs by default; the denied paths explicitly revoke the
 * grant, and the app-op mode distinguishes a recorded refusal from a
 * never-asked grant exactly like the location checker does.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidMessagingPermissionCheckerTest {

    private static final String[] PERMISSIONS = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
    };

    @Test
    public void reportsGrantedWhenTheRuntimeGrantExists() {
        // Robolectric 4.15 defaults checkSelfPermission to DENIED even for
        // manifest-declared permissions, so the grant is applied explicitly.
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .grantPermissions(PERMISSIONS);
        AndroidMessagingPermissionChecker checker = checker();
        for (String permission : PERMISSIONS) {
            assertEquals("granted permission must resolve to GRANTED: " + permission,
                    CapabilityPermission.GRANTED, checker.check(permission));
        }
    }

    @Test
    public void reportsRequiredWhenDeniedWithoutARecordedRefusal() {
        Context context = RuntimeEnvironment.getApplication();
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(PERMISSIONS);
        AndroidMessagingPermissionChecker checker = new AndroidMessagingPermissionChecker(context);
        for (String permission : PERMISSIONS) {
            assertEquals("denied-without-appop must resolve to REQUIRED: " + permission,
                    CapabilityPermission.REQUIRED, checker.check(permission));
        }
    }

    @Test
    public void reportsDeniedWhenDeniedAndTheAppOpIsIgnored() {
        Context context = RuntimeEnvironment.getApplication();
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(PERMISSIONS);
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        ShadowAppOpsManager shadowAppOps = Shadow.extract(appOps);
        String packageName = context.getPackageName();
        int uid = Process.myUid();
        for (String permission : PERMISSIONS) {
            shadowAppOps.setMode(opName(permission), uid, packageName,
                    AppOpsManager.MODE_IGNORED);
        }

        AndroidMessagingPermissionChecker checker = new AndroidMessagingPermissionChecker(context);
        for (String permission : PERMISSIONS) {
            assertEquals("ignored app-op must resolve to DENIED: " + permission,
                    CapabilityPermission.DENIED, checker.check(permission));
        }
    }

    @Test
    public void reportsRequiredForUnknownPermissionsAndRejectsNull() {
        Context context = RuntimeEnvironment.getApplication();
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_CALENDAR);
        AndroidMessagingPermissionChecker checker = new AndroidMessagingPermissionChecker(context);
        assertEquals(CapabilityPermission.REQUIRED,
                checker.check(Manifest.permission.READ_CALENDAR));
        assertThrows(IllegalArgumentException.class, () -> checker.check(null));
    }

    private static AndroidMessagingPermissionChecker checker() {
        return new AndroidMessagingPermissionChecker(RuntimeEnvironment.getApplication());
    }

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
        return AppOpsManager.OPSTR_READ_PHONE_STATE;
    }
}
