package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.infrastructure.service.LiveMediaPipeline;
import gh.nusashell.nusadesk.infrastructure.service.LiveMediaRegistry;
import gh.nusashell.nusadesk.infrastructure.service.LiveMediaService;
import gh.nusashell.nusadesk.infrastructure.service.LiveMediaServiceTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bridge-side media controller behavior on the JVM: permission mapping,
 * foreground-eligibility mapping, the bounded start wait, idempotent start
 * while running, busy while starting, and close ownership. The service under
 * the controller runs with the fake pipeline, so no camera or encoder is
 * involved.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidLiveMediaControllerTest {

    private LiveMediaPipeline savedPipeline;
    private LiveMediaRegistry registry;

    @Before
    public void setUp() {
        savedPipeline = LiveMediaService.pipelineOverride;
        registry = LiveMediaRegistry.getInstance();
        registry.publishStopped();
    }

    @After
    public void tearDown() {
        LiveMediaService.pipelineOverride = savedPipeline;
        registry.publishStopped();
    }

    @Test
    public void missingPermissionMapsToPermissionRequiredWithoutStartingAService() {
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.REQUIRED, starter, registry, 500L);

        LiveMediaStatus status = controller.start();

        assertEquals(LiveMediaState.FAILED, status.getState());
        assertEquals("media-permission-required", status.getError());
        assertEquals("no service may be started without grants", 0, starter.calls);
        assertEquals("the registry reflects the typed failure",
                "media-permission-required", registry.currentStatus().getError());
    }

    @Test
    public void deniedPermissionMapsToPermissionDenied() {
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.DENIED, new RecordingStarter(), registry, 500L);

        LiveMediaStatus status = controller.start();

        assertEquals("media-permission-denied", status.getError());
    }

    @Test
    public void grantedPermissionsStartTheServiceAndReturnRunning() throws Exception {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:22222/", "h264", "aac", 1280, 720, 30, 2);
        LiveMediaService.pipelineOverride = pipeline;

        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 2_000L);

        LiveMediaStatus status = controller.start();

        assertEquals(LiveMediaState.RUNNING, status.getState());
        assertEquals("rtsp://127.0.0.1:22222/", status.getRtspUrl());
        assertEquals(1280, status.getVideoWidth());
        assertEquals(2, status.getClientLimit());
        assertEquals(1, starter.calls);
        assertEquals(LiveMediaService.ACTION_START,
                starter.lastIntent.getAction());
    }

    @Test
    public void backgroundStartBanMapsToForegroundRequired() {
        RecordingStarter starter = new RecordingStarter();
        starter.thrown = new IllegalStateException(
                "ForegroundServiceStartNotAllowedException: not allowed to start");
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 500L);

        LiveMediaStatus status = controller.start();

        assertEquals(LiveMediaState.FAILED, status.getState());
        assertEquals("media-foreground-required", status.getError());
    }

    @Test
    public void securityExceptionFromThePlatformMapsToPermissionDenied() {
        RecordingStarter starter = new RecordingStarter();
        starter.thrown = new SecurityException("permission denied by platform");
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 500L);

        LiveMediaStatus status = controller.start();

        assertEquals("media-permission-denied", status.getError());
    }

    @Test
    public void expiredBoundedWaitMapsToStartFailed() {
        // A starter that never brings a service up leaves the registry in
        // STARTING; the bounded wait expires and maps to media-start-failed.
        RecordingStarter starter = new RecordingStarter();
        starter.delegate = false;
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 100L);

        LiveMediaStatus status = controller.start();

        assertEquals(LiveMediaState.FAILED, status.getState());
        assertEquals("media-start-failed", status.getError());
    }

    @Test
    public void secondStartWhileRunningIsIdempotentSuccess() throws Exception {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:22222/", "h264", "aac", 1280, 720, 30, 2);
        LiveMediaService.pipelineOverride = pipeline;
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 2_000L);

        LiveMediaStatus first = controller.start();
        LiveMediaStatus second = controller.start();

        assertEquals(LiveMediaState.RUNNING, first.getState());
        assertEquals(LiveMediaState.RUNNING, second.getState());
        assertEquals("only one service start for two bridge calls", 1, starter.calls);
    }

    @Test
    public void startWhileStartingIsBusy() {
        registry.publishStarting();
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 500L);

        LiveMediaStatus status = controller.start();

        assertEquals("media-busy", status.getError());
        assertEquals(0, starter.calls);
    }

    @Test
    public void closeStopsTheSessionAndBlocksFurtherStarts() throws Exception {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:22222/", "h264", "aac", 1280, 720, 30, 2);
        LiveMediaService.pipelineOverride = pipeline;
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, starter, registry, 2_000L);

        assertEquals(LiveMediaState.RUNNING, controller.start().getState());
        controller.close();

        // The controller owns the stop request and immediately resets its
        // session registry. The service lifecycle owns the actual pipeline
        // stop; that path is covered by LiveMediaServiceTest.
        assertEquals(LiveMediaState.STOPPED, registry.currentStatus().getState());
        LiveMediaStatus afterClose = controller.start();
        assertEquals("a closed controller must not start a session",
                "media-unavailable", afterClose.getError());
    }

    @Test
    public void statusNeverThrowsAndReflectsTheRegistry() {
        registry.publishStarting();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                () -> CapabilityPermission.GRANTED, new RecordingStarter(), registry, 500L);

        assertEquals(LiveMediaState.STARTING, controller.status().getState());
        registry.publishResult(LiveMediaStatus.failed(LiveMediaError.BUSY));
        assertEquals(LiveMediaState.FAILED, controller.status().getState());
        assertEquals("media-busy", controller.status().getError());
    }

    /** Foreground-service starter that records the intent and can simulate platform refusals. */
    private static final class RecordingStarter
            implements AndroidLiveMediaController.ForegroundServiceStarter {
        int calls;
        Intent lastIntent;
        RuntimeException thrown;
        boolean delegate = true;

        @Override
        public void startForegroundService(Intent intent) {
            calls++;
            lastIntent = intent;
            if (thrown != null) {
                throw thrown;
            }
            if (!delegate) {
                return; // simulate a service that never comes up
            }
            // Robolectric records startService without executing it, so the
            // starter drives the service lifecycle explicitly; the intent and
            // action still flow through the real onStartCommand path.
            ServiceController<LiveMediaService> controller =
                    Robolectric.buildService(LiveMediaService.class, intent).create();
            controller.startCommand(0, 1);
        }
    }
}
