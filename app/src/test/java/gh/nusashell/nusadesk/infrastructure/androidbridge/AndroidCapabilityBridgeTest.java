package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Bridge session wiring on the JVM: capability sources are constructed once,
 * owned by the bridge, and closed in both {@code close()} paths. The live
 * media controller is closed with the bridge session, so a media session
 * never outlives its guest bridge. Required binds cover only the session
 * config file and the optional sysfs projection — never any media artifact
 * directory. The platform adapters are replaced by fakes (their device APIs
 * are not shadowable); the bridge transport itself runs on the real loopback
 * socket.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidCapabilityBridgeTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void closeWithoutStartClosesTheLocationStreamAndTheMediaController() {
        FakeClosableStream stream = new FakeClosableStream();
        FakeClosableMedia media = new FakeClosableMedia();
        AndroidCapabilityBridge bridge = new AndroidCapabilityBridge(
                RuntimeEnvironment.getApplication(), stream, media);

        bridge.close();

        assertTrue("location stream must be closed by the bridge", stream.closed);
        assertTrue("media controller must be closed by the bridge", media.closed);
        assertFalse(bridge.isRunning());
    }

    @Test
    public void closeAfterStartClosesTheLocationStreamAndTheMediaController()
            throws IOException {
        FakeClosableStream stream = new FakeClosableStream();
        FakeClosableMedia media = new FakeClosableMedia();
        AndroidCapabilityBridge bridge = new AndroidCapabilityBridge(
                RuntimeEnvironment.getApplication(), stream, media);

        bridge.start();
        assertTrue(bridge.isRunning());
        assertTrue("bridge must bind an ephemeral loopback port", bridge.getPort() > 0);
        assertNotNull("bridge must generate a session token", bridge.getToken());

        bridge.close();

        assertTrue("location stream must be closed by the bridge", stream.closed);
        assertTrue("media controller must be closed by the bridge", media.closed);
        assertFalse(bridge.isRunning());
    }

    @Test
    public void publicConstructorBuildsRealAdaptersAndStartsAndClosesCleanly()
            throws IOException {
        // The production entry point constructs every real capability adapter
        // once per session; on the JVM that must be safe (no device I/O at
        // construction) and start/close must not leak platform threads.
        AndroidCapabilityBridge bridge = new AndroidCapabilityBridge(
                RuntimeEnvironment.getApplication());

        bridge.start();
        assertTrue(bridge.isRunning());
        assertTrue(bridge.getPort() > 0);
        assertNotNull(bridge.getToken());

        bridge.close();
        bridge.close();
        assertFalse(bridge.isRunning());
    }

    @Test
    public void bridgeIsSingleUseAndCloseIsIdempotent() throws IOException {
        AndroidCapabilityBridge bridge = new AndroidCapabilityBridge(
                RuntimeEnvironment.getApplication(), new FakeClosableStream(),
                new FakeClosableMedia());

        bridge.start();
        bridge.close();
        bridge.close(); // idempotent
        try {
            bridge.start();
            fail("a closed bridge instance must not restart");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertFalse(bridge.isRunning());
    }

    @Test
    public void requiredBindsExposeSessionConfigOnlyWithNoMediaArtifactPaths()
            throws IOException {
        Path rootfs = temporaryFolder.newFolder("rootfs").toPath();
        AndroidCapabilityBridge bridge = new AndroidCapabilityBridge(
                RuntimeEnvironment.getApplication(), new FakeClosableStream(),
                new FakeClosableMedia());

        // Not running: no binds at all.
        assertTrue(bridge.requiredBinds(rootfs).isEmpty());

        bridge.start();
        List<ProotBindMount> binds = bridge.requiredBinds(rootfs);

        assertTrue("the session config file must still be strictly bound",
                binds.contains(ProotBindMount.ofStrict(
                        sessionConfigPath().toString(),
                        AndroidCapabilityBridge.GUEST_SESSION_CONFIG_PATH)));
        assertTrue("the guest run/nusadesk parent must exist inside the rootfs",
                Files.isDirectory(rootfs.resolve("run/nusadesk")));
        for (ProotBindMount bind : binds) {
            assertFalse("no media artifact guest path may be bound: " + bind,
                    bind.getGuestPath().startsWith("/run/nusadesk/camera-snapshots"));
            assertFalse("no media artifact guest path may be bound: " + bind,
                    bind.getGuestPath().startsWith("/run/nusadesk/microphone-recordings"));
            assertFalse("no bind may expose the whole cache directory: " + bind,
                    bind.getHostPath().endsWith("/cache"));
            assertTrue("every host bind must be absolute: " + bind,
                    bind.getHostPath().startsWith("/"));
            assertTrue("every guest bind must be absolute: " + bind,
                    bind.getGuestPath().startsWith("/"));
        }
        bridge.close();
    }

    private Path sessionConfigPath() {
        return RuntimeEnvironment.getApplication().getFilesDir().toPath()
                .resolve("linux-wrapper/state/android-bridge/android-bridge.env");
    }

    /** Live media fake with observable close. */
    private static final class FakeClosableMedia implements LiveMediaController {
        boolean closed;

        @Override
        public LiveMediaStatus start() {
            return LiveMediaStatus.failed(LiveMediaError.UNAVAILABLE);
        }

        @Override
        public LiveMediaStatus status() {
            return LiveMediaStatus.stopped();
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Location stream fake with observable close. */
    private static final class FakeClosableStream implements LocationStreamSession {
        boolean closed;

        @Override
        public void start(LocationStreamRequest request) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public State state() {
            return State.IDLE;
        }

        @Override
        public LocationStreamEvent poll(long timeoutMillis) {
            return null;
        }

        @Override
        public LocationSnapshot latestReading() {
            return null;
        }
    }
}