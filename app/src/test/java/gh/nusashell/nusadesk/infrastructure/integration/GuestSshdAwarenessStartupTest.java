package gh.nusashell.nusadesk.infrastructure.integration;

import gh.nusashell.nusadesk.BuildConfig;
import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.proot.GuestAwarenessReadmeWriter;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.service.WorkloadListener;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the real startup entry point without executing a guest process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GuestSshdAwarenessStartupTest {
    @Test public void everyStartRefreshesManagedAwarenessBeforeGuestExecution() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        Path root = ProotPaths.activeRootfsPath(context.getFilesDir().toPath(), "ubuntu-base-arm64");
        // Recognized rootfs daemon, but no native bridge. An absent test
        // credential stops startup before any network or process launch.
        Files.createDirectories(root.resolve("usr/sbin"));
        Files.writeString(root.resolve("usr/sbin/sshd"), "fixture");
        var workload = new GuestSshdWorkload(context, () -> null,
                (host, port, timeout) -> { fail("must not probe SSH"); return false; },
                new HostKeyTrustStore() {
                    public HostKeyRecord load(String host) { return null; }
                    public void save(HostKeyRecord record) { fail("must not trust a host key"); }
                    public void clear(String host) { }
                }, System::currentTimeMillis, Runnable::run, Runnable::run);
        WorkloadListener listener = new WorkloadListener() {
            public void onReadiness(ReadinessFrame frame) { fail("fixture must not run"); }
            public void onStopped() { fail("fixture must fail for absent credentials"); }
            public void onFailed(String reason) { assertEquals("local SSH credential is absent", reason); }
        };
        SessionSnapshot session = new SessionSnapshot("s", "ubuntu-base-arm64", "1",
                SessionState.STARTING, null, 1, 2, "", 0);
        Path guide = root.resolve("root/docs/desktop.md");
        workload.start(session, listener);
        assertTrue("real workload startup must generate the desktop guide", Files.isRegularFile(guide));
        String expected = new String(GuestAwarenessReadmeWriter.loadDoc(
                GuestAwarenessReadmeWriter.assetSource(context.getAssets()),
                "docs/desktop.md", BuildConfig.VERSION_NAME),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expected, Files.readString(guide));

        Files.writeString(guide, "stale advice");
        workload.start(session, listener);
        assertEquals("restart must refresh content without requiring an APK version change",
                expected, Files.readString(guide));
        assertTrue(Files.readString(root.resolve("root/README.md")).contains("(docs/desktop.md)"));
        assertTrue(Files.readString(root.resolve("root/docs/README.md")).contains("(desktop.md)"));
    }
}
