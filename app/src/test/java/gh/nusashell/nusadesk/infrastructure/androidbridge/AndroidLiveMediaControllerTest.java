package gh.nusashell.nusadesk.infrastructure.androidbridge;

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
 * Bridge-side media controller behavior on the JVM: per-mode permission
 * mapping, the mode handed to the service, foreground-eligibility mapping, the
 * bounded start wait, idempotent start in the same mode, mode conflict while
 * another mode runs, busy while starting, and close ownership. The service
 * under the controller runs with the fake pipeline, so no camera or encoder is
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
                new FixedPermissionChecker(CapabilityPermission.REQUIRED),
                starter, registry, 500L);

        LiveMediaStatus status = controller.start(LiveMediaMode.BOTH);

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
                new FixedPermissionChecker(CapabilityPermission.DENIED),
                new RecordingStarter(), registry, 500L);

        LiveMediaStatus status = controller.start(LiveMediaMode.BOTH);

        assertEquals("media-permission-denied", status.getError());
    }

    @Test
    public void grantedPermissionsStartTheServiceAndReturnRunning() {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:22222/", "h264", 1280, 720, 30, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;

        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 2_000L);

        LiveMediaStatus status = controller.start(LiveMediaMode.BOTH);

        assertEquals(LiveMediaState.RUNNING, status.getState());
        assertEquals(LiveMediaMode.BOTH, status.getMode());
        assertEquals("rtsp://127.0.0.1:22222/", status.getRtspUrl());
        assertEquals(1280, status.getVideoWidth());
        assertEquals(2, status.getClientLimit());
        assertEquals(1, starter.calls);
        assertEquals(LiveMediaService.ACTION_START, starter.lastIntent.getAction());
        assertEquals("BOTH", starter.lastIntent.getStringExtra(LiveMediaService.EXTRA_MODE));
    }

    @Test
    public void cameraOnlyChecksItsOwnModeAndHandsItToTheService() {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.CAMERA,
                "rtsp://127.0.0.1:22222/", "h264", 1280, 720, 30, null, 2);
        LiveMediaService.pipelineOverride = pipeline;

        RecordingPermissionChecker checker =
                new RecordingPermissionChecker(CapabilityPermission.GRANTED);
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(), checker, starter, registry, 2_000L);

        LiveMediaStatus status = controller.start(LiveMediaMode.CAMERA);

        assertEquals("the mode decides which grant is checked",
                LiveMediaMode.CAMERA, checker.lastMode);
        assertEquals("CAMERA", starter.lastIntent.getStringExtra(LiveMediaService.EXTRA_MODE));
        assertEquals(LiveMediaMode.CAMERA, status.getMode());
        assertEquals("h264", status.getVideoCodec());
        assertNull("a camera-only session reports no audio codec",
                status.getAudioCodec());
    }

    @Test
    public void microphoneOnlyChecksItsOwnModeAndHandsItToTheService() {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.MICROPHONE,
                "rtsp://127.0.0.1:22222/", null, 0, 0, 0, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;

        RecordingPermissionChecker checker =
                new RecordingPermissionChecker(CapabilityPermission.GRANTED);
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(), checker, starter, registry, 2_000L);

        LiveMediaStatus status = controller.start(LiveMediaMode.MICROPHONE);

        assertEquals("the mode decides which grant is checked",
                LiveMediaMode.MICROPHONE, checker.lastMode);
        assertEquals("MICROPHONE",
                starter.lastIntent.getStringExtra(LiveMediaService.EXTRA_MODE));
        assertEquals(LiveMediaMode.MICROPHONE, status.getMode());
        assertEquals("aac", status.getAudioCodec());
        assertNull("a microphone-only session reports no video codec",
                status.getVideoCodec());
    }

    @Test
    public void backgroundStartBanMapsToForegroundRequired() {
        RecordingStarter starter = new RecordingStarter();
        starter.thrown = new IllegalStateException(
                "ForegroundServiceStartNotAllowedException: not allowed to start");
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 500L);

        LiveMediaStatus status = controller.start(LiveMediaMode.BOTH);

        assertEquals(LiveMediaState.FAILED, status.getState());
        assertEquals("media-foreground-required", status.getError());
    }

    @Test
    public void securityExceptionFromThePlatformMapsToPermissionDenied() {
        RecordingStarter starter = new RecordingStarter();
        starter.thrown = new SecurityException("permission denied by platform");
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 500L);

        LiveMediaStatus status = controller.start(LiveMediaMode.BOTH);

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
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 100L);

        LiveMediaStatus status = controller.start(LiveMediaMode.BOTH);

        assertEquals(LiveMediaState.FAILED, status.getState());
        assertEquals("media-start-failed", status.getError());
    }

    @Test
    public void secondStartInTheSameModeIsIdempotentSuccess() {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.CAMERA,
                "rtsp://127.0.0.1:22222/", "h264", 1280, 720, 30, null, 2);
        LiveMediaService.pipelineOverride = pipeline;
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 2_000L);

        LiveMediaStatus first = controller.start(LiveMediaMode.CAMERA);
        LiveMediaStatus second = controller.start(LiveMediaMode.CAMERA);

        assertEquals(LiveMediaState.RUNNING, first.getState());
        assertEquals(LiveMediaState.RUNNING, second.getState());
        assertEquals("only one service start for two bridge calls", 1, starter.calls);
    }

    @Test
    public void startInAnotherModeWhileRunningIsModeConflict() {
        registry.publishResult(LiveMediaStatus.running(LiveMediaMode.MICROPHONE,
                "rtsp://127.0.0.1:22222/", null, 0, 0, 0, "aac", 2));
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 500L);

        LiveMediaStatus status = controller.start(LiveMediaMode.CAMERA);

        assertEquals(LiveMediaState.FAILED, status.getState());
        assertEquals("media-mode-conflict", status.getError());
        assertEquals("a conflicting start must not touch the running service",
                0, starter.calls);
        assertEquals("the running session is left alone",
                LiveMediaMode.MICROPHONE, registry.currentStatus().getMode());
    }

    @Test
    public void startWhileStartingIsBusy() {
        registry.publishStarting(LiveMediaMode.CAMERA);
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 500L);

        LiveMediaStatus status = controller.start(LiveMediaMode.CAMERA);

        assertEquals("media-busy", status.getError());
        assertEquals(0, starter.calls);
    }

    @Test
    public void closeStopsTheSessionAndBlocksFurtherStarts() {
        LiveMediaServiceTest.FakePipeline pipeline = new LiveMediaServiceTest.FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:22222/", "h264", 1280, 720, 30, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;
        RecordingStarter starter = new RecordingStarter();
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                starter, registry, 2_000L);

        assertEquals(LiveMediaState.RUNNING, controller.start(LiveMediaMode.BOTH).getState());
        controller.close();

        // The controller owns the stop request and immediately resets its
        // session registry. The service lifecycle owns the actual pipeline
        // stop; that path is covered by LiveMediaServiceTest.
        assertEquals(LiveMediaState.STOPPED, registry.currentStatus().getState());
        LiveMediaStatus afterClose = controller.start(LiveMediaMode.BOTH);
        assertEquals("a closed controller must not start a session",
                "media-unavailable", afterClose.getError());
    }

    @Test
    public void statusNeverThrowsAndReflectsTheRegistry() {
        registry.publishStarting(LiveMediaMode.MICROPHONE);
        AndroidLiveMediaController controller = new AndroidLiveMediaController(
                RuntimeEnvironment.getApplication(),
                new FixedPermissionChecker(CapabilityPermission.GRANTED),
                new RecordingStarter(), registry, 500L);

        assertEquals(LiveMediaState.STARTING, controller.status().getState());
        assertEquals(LiveMediaMode.MICROPHONE, controller.status().getMode());
        registry.publishResult(LiveMediaStatus.failed(LiveMediaError.BUSY));
        assertEquals(LiveMediaState.FAILED, controller.status().getState());
        assertEquals("media-busy", controller.status().getError());
    }

    /** Permission checker with a fixed answer that records the requested mode. */
    private static class RecordingPermissionChecker implements MediaPermissionChecker {
        private final CapabilityPermission result;
        LiveMediaMode lastMode;

        RecordingPermissionChecker(CapabilityPermission result) {
            this.result = result;
        }

        @Override
        public CapabilityPermission check(LiveMediaMode mode) {
            lastMode = mode;
            return result;
        }
    }

    private static final class FixedPermissionChecker extends RecordingPermissionChecker {
        FixedPermissionChecker(CapabilityPermission result) {
            super(result);
        }
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
