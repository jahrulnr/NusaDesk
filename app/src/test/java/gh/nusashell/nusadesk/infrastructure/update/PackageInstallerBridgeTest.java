package gh.nusashell.nusadesk.infrastructure.update;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageInstaller;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gh.nusashell.nusadesk.infrastructure.update.PackageInstallerBridge.InstallStatus;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The session contract of the assisted install flow (ADR-0038): the verified
 * APK is written into a {@code MODE_FULL_INSTALL} session, the session is
 * committed with the token-carrying status sender, any staging failure
 * abandons the session, and status broadcasts parse into a typed
 * {@link InstallStatus}.
 *
 * <p>Shadow limits: {@link ShadowPackageInstaller} does not copy
 * {@code SessionParams.mode} onto the {@code SessionInfo} it records, so the
 * mode is asserted on the params object captured by
 * {@link RecordingInstallerShadow}; its {@code openWrite} returns a
 * discarding sink, so {@link RecordingSessionShadow} captures the streamed
 * bytes. Neither the real confirmation UI nor the platform's status fill-in
 * exists under Robolectric — the commit path is verified by observing the
 * broadcast the committed {@link PendingIntent} fires.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, shadows = {PackageInstallerBridgeTest.RecordingSessionShadow.class,
        PackageInstallerBridgeTest.RecordingInstallerShadow.class})
public class PackageInstallerBridgeTest {

    private static final String ACTION = "gh.nusashell.nusadesk.update.INSTALL_STATUS";
    private static final String TOKEN = "4b1d0e2e-8f0a-4f9f-9d7c-2f9b0a1c3d5e";

    /**
     * The platform's confirmation-extra key; see the bridge. Verified
     * on-device (S10e, API 31): the status broadcast carries it under
     * {@code android.intent.extra.INTENT}.
     */
    private static final String PLATFORM_EXTRA_INTENT =
            "android.intent.extra.INTENT";
    private static final byte[] APK_BYTES =
            "fake-but-deterministic-apk-payload".getBytes(StandardCharsets.UTF_8);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * Records the bytes {@code stageApk} streams into the session; the stock
     * shadow discards them. Everything else — {@code commit}, {@code fsync},
     * {@code close}, {@code abandon} — is inherited from
     * {@link ShadowPackageInstaller.ShadowSession}.
     */
    @Implements(PackageInstaller.Session.class)
    public static class RecordingSessionShadow
            extends ShadowPackageInstaller.ShadowSession {
        final Map<String, ByteArrayOutputStream> writes = new HashMap<>();
        final Map<String, Long> declaredLengths = new HashMap<>();

        @Implementation
        protected OutputStream openWrite(String name, long offsetBytes, long lengthBytes) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writes.put(name, out);
            declaredLengths.put(name, lengthBytes);
            return out;
        }
    }

    /**
     * Records the {@code SessionParams} handed to {@code createSession} so the
     * install mode is verifiable end to end; the stock shadow does not copy it
     * onto the {@code SessionInfo} it records.
     */
    @Implements(PackageInstaller.class)
    public static class RecordingInstallerShadow extends ShadowPackageInstaller {
        final List<PackageInstaller.SessionParams> createdParams = new ArrayList<>();

        @Implementation
        protected int createSession(PackageInstaller.SessionParams params)
                throws IOException {
            createdParams.add(params);
            return super.createSession(params);
        }
    }

    @Test
    public void stagesTheApkAndCommitsWithTheStatusSender() throws Exception {
        Context context = context();
        File apk = fixtureApk();
        List<Intent> broadcasts = registerStatusReceiver(context);
        PendingIntent status =
                PackageInstallerBridge.buildStatusPendingIntent(context, ACTION, TOKEN, 0);

        int sessionId =
                PackageInstallerBridge.stageApk(context, apk, status.getIntentSender());

        assertTrue("a real session id is non-zero", sessionId > 0);
        PackageInstaller installer =
                context.getPackageManager().getPackageInstaller();
        RecordingInstallerShadow installerShadow = Shadow.extract(installer);
        assertEquals(1, installerShadow.createdParams.size());
        assertEquals("the session must be a full install",
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                (int) ReflectionHelpers.getField(
                        installerShadow.createdParams.get(0), "mode"));

        PackageInstaller.SessionInfo info = installer.getSessionInfo(sessionId);
        assertNotNull("the session must be recorded", info);
        assertTrue("the session is active after commit", info.isActive());

        PackageInstaller.Session session = installer.openSession(sessionId);
        RecordingSessionShadow shadow = Shadow.extract(session);
        assertArrayEquals("the whole APK must reach the session stream",
                APK_BYTES,
                shadow.writes.get("nusadesk-update-apk").toByteArray());
        assertEquals("the declared stream length is the file length",
                APK_BYTES.length,
                (long) shadow.declaredLengths.get("nusadesk-update-apk"));

        Robolectric.flushForegroundThreadScheduler();
        assertEquals("commit must fire the status broadcast once",
                1, broadcasts.size());
        Intent broadcast = broadcasts.get(0);
        assertEquals(ACTION, broadcast.getAction());
        assertEquals(TOKEN,
                broadcast.getStringExtra(PackageInstallerBridge.EXTRA_STATUS_TOKEN));
    }

    @Test
    public void aMissingApkAbandonsTheCreatedSession() throws Exception {
        Context context = context();
        PendingIntent status =
                PackageInstallerBridge.buildStatusPendingIntent(context, ACTION, TOKEN, 0);
        File missing = new File(folder.getRoot(), "missing.apk");

        assertThrows(IOException.class, () -> PackageInstallerBridge.stageApk(
                context, missing, status.getIntentSender()));

        PackageInstaller installer =
                context.getPackageManager().getPackageInstaller();
        assertTrue("a failed stage must abandon its session",
                installer.getMySessions().isEmpty());
    }

    @Test
    public void theStatusPendingIntentCarriesTheTokenAndIsMutableOnApi31() {
        Context context = context();

        PendingIntent status =
                PackageInstallerBridge.buildStatusPendingIntent(context, ACTION, TOKEN, 7);

        ShadowPendingIntent shadow = Shadow.extract(status);
        Intent saved = shadow.getSavedIntent();
        assertEquals(ACTION, saved.getAction());
        assertEquals(context.getPackageName(), saved.getPackage());
        assertEquals(TOKEN,
                saved.getStringExtra(PackageInstallerBridge.EXTRA_STATUS_TOKEN));
        assertEquals(7,
                saved.getIntExtra(PackageInstallerBridge.EXTRA_SESSION_ID, -1));
        assertTrue(shadow.isBroadcast());
        assertTrue("the PendingIntent must update in place",
                (shadow.getFlags() & PendingIntent.FLAG_UPDATE_CURRENT) != 0);
        assertEquals("API 29 needs no mutability flag",
                PendingIntent.FLAG_UPDATE_CURRENT, shadow.getFlags());
    }

    @Test
    @Config(sdk = 31, shadows = {PackageInstallerBridgeTest.RecordingSessionShadow.class,
            PackageInstallerBridgeTest.RecordingInstallerShadow.class})
    public void theStatusPendingIntentIsMutableFromApi31() {
        PendingIntent status =
                PackageInstallerBridge.buildStatusPendingIntent(context(), ACTION, TOKEN, 0);

        int flags = Shadow.<ShadowPendingIntent>extract(status).getFlags();
        assertTrue("the system writes status extras into the intent",
                (flags & PendingIntent.FLAG_MUTABLE) != 0);
        assertTrue((flags & PendingIntent.FLAG_UPDATE_CURRENT) != 0);
    }

    @Test
    public void parsesASuccessStatus() {
        Intent intent = new Intent(ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, 42);

        InstallStatus status = PackageInstallerBridge.parseStatus(intent);

        assertNotNull(status);
        assertTrue(status.isSuccess());
        assertFalse(status.isUserAborted());
        assertFalse(status.isPendingUserAction());
        assertEquals(PackageInstaller.STATUS_SUCCESS, status.getCode());
        assertEquals(42, status.getSessionId());
    }

    @Test
    public void parsesAnAbortedStatusAsUserAborted() {
        Intent intent = new Intent(ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE_ABORTED);

        InstallStatus status = PackageInstallerBridge.parseStatus(intent);

        assertNotNull(status);
        assertTrue(status.isUserAborted());
        assertFalse(status.isSuccess());
    }

    @Test
    public void parsesAnyOtherTerminalCodeAsFailureWithTheMessage() {
        Intent intent = new Intent(ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE_STORAGE)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "Insufficient storage");

        InstallStatus status = PackageInstallerBridge.parseStatus(intent);

        assertNotNull(status);
        assertFalse(status.isSuccess());
        assertFalse(status.isUserAborted());
        assertFalse(status.isPendingUserAction());
        assertEquals("Insufficient storage", status.getMessage());
    }

    @Test
    public void pendingUserActionCarriesTheConfirmationIntent() {
        Intent confirmation = new Intent("android.content.pm.action.CONFIRM_INSTALL");
        Intent intent = new Intent(ACTION)
                .putExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(PLATFORM_EXTRA_INTENT, confirmation);

        InstallStatus status = PackageInstallerBridge.parseStatus(intent);

        assertNotNull(status);
        assertTrue(status.isPendingUserAction());
        assertFalse(status.isSuccess());
        assertEquals("android.content.pm.action.CONFIRM_INSTALL",
                status.getConfirmationIntent().getAction());
    }

    @Test
    public void anIntentWithoutStatusParsesToNull() {
        assertNull(PackageInstallerBridge.parseStatus(new Intent(ACTION)));
        assertNull(PackageInstallerBridge.parseStatus(null));
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private File fixtureApk() throws Exception {
        File apk = new File(folder.getRoot(), "update.apk");
        Files.write(apk.toPath(), APK_BYTES);
        return apk;
    }

    /**
     * Test-only dynamic receiver for the session status; the lint flag rule
     * is suppressed because the two-arg overload is the only one that exists
     * across the test's SDK configs, and the test observes its own broadcast.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static List<Intent> registerStatusReceiver(Context context) {
        List<Intent> received = new ArrayList<>();
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                received.add(intent);
            }
        }, new IntentFilter(ACTION));
        return received;
    }
}
