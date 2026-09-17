package gh.nusashell.nusadesk.infrastructure.androidbridge;

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

/**
 * Camera + microphone grant mapping on the JVM: both grants present, missing
 * grants untouched (REQUIRED), and a previously denied grant (app-op
 * MODE_IGNORED) mapped to DENIED.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidMediaPermissionCheckerTest {

    @Test
    public void bothGrantsPresentMapToGranted() {
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO);

        assertEquals(CapabilityPermission.GRANTED, checker().check());
    }

    @Test
    public void missingUntouchedGrantsMapToRequired() {
        assertEquals(CapabilityPermission.REQUIRED, checker().check());
    }

    @Test
    public void previouslyDeniedCameraGrantMapsToDenied() {
        AppOpsManager appOps = (AppOpsManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.APP_OPS_SERVICE);
        Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                AppOpsManager.OPSTR_CAMERA, Process.myUid(),
                RuntimeEnvironment.getApplication().getPackageName(),
                AppOpsManager.MODE_IGNORED);

        assertEquals(CapabilityPermission.DENIED, checker().check());
    }

    @Test
    public void previouslyDeniedMicrophoneGrantMapsToDenied() {
        AppOpsManager appOps = (AppOpsManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.APP_OPS_SERVICE);
        Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(),
                RuntimeEnvironment.getApplication().getPackageName(),
                AppOpsManager.MODE_IGNORED);

        assertEquals(CapabilityPermission.DENIED, checker().check());
    }

    private static AndroidMediaPermissionChecker checker() {
        return new AndroidMediaPermissionChecker(RuntimeEnvironment.getApplication());
    }
}
