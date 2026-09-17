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

/**
 * Per-mode grant mapping on the JVM: a mode needs only its own permission, a
 * missing grant untouched is REQUIRED, a previously denied grant (app-op
 * MODE_IGNORED) is DENIED, and a denial for one track never leaks into a mode
 * that does not use it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidMediaPermissionCheckerTest {

    @Test
    public void everyModeIsGrantedWhenItsPermissionsArePresent() {
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO);

        assertEquals(CapabilityPermission.GRANTED, checker().check(LiveMediaMode.BOTH));
        assertEquals(CapabilityPermission.GRANTED, checker().check(LiveMediaMode.CAMERA));
        assertEquals(CapabilityPermission.GRANTED,
                checker().check(LiveMediaMode.MICROPHONE));
    }

    @Test
    public void cameraOnlyNeedsOnlyTheCameraGrant() {
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.CAMERA);

        assertEquals("a camera-only session must not require the microphone",
                CapabilityPermission.GRANTED, checker().check(LiveMediaMode.CAMERA));
        assertEquals(CapabilityPermission.REQUIRED,
                checker().check(LiveMediaMode.MICROPHONE));
        assertEquals(CapabilityPermission.REQUIRED, checker().check(LiveMediaMode.BOTH));
    }

    @Test
    public void microphoneOnlyNeedsOnlyTheMicrophoneGrant() {
        Shadow.<ShadowContextWrapper>extract(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.RECORD_AUDIO);

        assertEquals("a microphone-only session must not require the camera",
                CapabilityPermission.GRANTED, checker().check(LiveMediaMode.MICROPHONE));
        assertEquals(CapabilityPermission.REQUIRED, checker().check(LiveMediaMode.CAMERA));
        assertEquals(CapabilityPermission.REQUIRED, checker().check(LiveMediaMode.BOTH));
    }

    @Test
    public void missingUntouchedGrantsMapToRequired() {
        assertEquals(CapabilityPermission.REQUIRED, checker().check(LiveMediaMode.BOTH));
    }

    @Test
    public void previouslyDeniedCameraGrantMapsToDeniedOnlyForCameraModes() {
        ignoreAppOp(AppOpsManager.OPSTR_CAMERA);

        assertEquals(CapabilityPermission.DENIED, checker().check(LiveMediaMode.CAMERA));
        assertEquals(CapabilityPermission.DENIED, checker().check(LiveMediaMode.BOTH));
        assertEquals("a camera denial must not deny a microphone-only session",
                CapabilityPermission.REQUIRED, checker().check(LiveMediaMode.MICROPHONE));
    }

    @Test
    public void previouslyDeniedMicrophoneGrantMapsToDeniedOnlyForMicrophoneModes() {
        ignoreAppOp(AppOpsManager.OPSTR_RECORD_AUDIO);

        assertEquals(CapabilityPermission.DENIED,
                checker().check(LiveMediaMode.MICROPHONE));
        assertEquals(CapabilityPermission.DENIED, checker().check(LiveMediaMode.BOTH));
        assertEquals("a microphone denial must not deny a camera-only session",
                CapabilityPermission.REQUIRED, checker().check(LiveMediaMode.CAMERA));
    }

    private static void ignoreAppOp(String op) {
        AppOpsManager appOps = (AppOpsManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.APP_OPS_SERVICE);
        Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                op, Process.myUid(),
                RuntimeEnvironment.getApplication().getPackageName(),
                AppOpsManager.MODE_IGNORED);
    }

    private static AndroidMediaPermissionChecker checker() {
        return new AndroidMediaPermissionChecker(RuntimeEnvironment.getApplication());
    }
}
