package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaError;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaMode;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaState;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Foreground-service lifecycle seams of the live media service on the JVM: the
 * per-mode camera/microphone foreground type, the user-visible per-mode
 * notification, the typed start result published to the registry, and the
 * clean stop. The real capture pipeline is replaced by a deterministic fake;
 * this test does not pretend to prove real camera encoders. SDK 30 is used so
 * the runtime foreground types (camera/microphone, API 30+) are exercised; on
 * API 29 the service falls back to the manifest-declared type by design.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LiveMediaServiceTest {

    private LiveMediaPipeline savedPipeline;

    @Before
    public void setUp() {
        savedPipeline = LiveMediaService.pipelineOverride;
        LiveMediaRegistry.getInstance().publishStopped();
    }

    @After
    public void tearDown() {
        LiveMediaService.pipelineOverride = savedPipeline;
        LiveMediaRegistry.getInstance().publishStopped();
    }

    @Test
    public void bothModePromotesWithCameraAndMicrophoneTypes() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:12345/", "h264", 1280, 720, 30, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller = startService(LiveMediaMode.BOTH);
        int type = foregroundType(controller);
        assertTrue("camera type must be declared for the combined mode",
                (type & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0);
        assertTrue("microphone type must be declared for the combined mode",
                (type & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0);

        LiveMediaStatus result = LiveMediaRegistry.getInstance().awaitStartResult(2_000);
        assertEquals(LiveMediaState.RUNNING, result.getState());
        assertEquals(LiveMediaMode.BOTH, result.getMode());
        assertEquals("rtsp://127.0.0.1:12345/", result.getRtspUrl());
        assertEquals(2L, result.getClientLimit());
        assertEquals(1, pipeline.startCalls);
        assertEquals("the service must start the pipeline in the requested mode",
                LiveMediaMode.BOTH, pipeline.lastMode);
        assertTrue(notificationText(controller).contains("Camera and microphone"));
    }

    @Test
    public void cameraOnlyPromotesWithTheCameraTypeOnlyAndNamesOnlyTheCamera()
            throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.CAMERA,
                "rtsp://127.0.0.1:12345/", "h264", 1280, 720, 30, null, 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller = startService(LiveMediaMode.CAMERA);
        int type = foregroundType(controller);
        assertTrue("camera type must be declared",
                (type & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0);
        assertEquals("a camera-only session must not claim the microphone type",
                0, type & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        LiveMediaStatus result = LiveMediaRegistry.getInstance().awaitStartResult(2_000);
        assertEquals(LiveMediaMode.CAMERA, result.getMode());
        assertEquals(LiveMediaMode.CAMERA, pipeline.lastMode);
        String text = notificationText(controller);
        assertTrue("the notification names the camera: " + text, text.contains("Camera"));
        assertTrue("the notification must not claim the microphone: " + text,
                !text.contains("microphone"));
    }

    @Test
    public void microphoneOnlyPromotesWithTheMicrophoneTypeOnlyAndNamesOnlyTheMicrophone()
            throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.MICROPHONE,
                "rtsp://127.0.0.1:12345/", null, 0, 0, 0, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller =
                startService(LiveMediaMode.MICROPHONE);
        int type = foregroundType(controller);
        assertTrue("microphone type must be declared",
                (type & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0);
        assertEquals("a microphone-only session must not claim the camera type",
                0, type & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);

        LiveMediaStatus result = LiveMediaRegistry.getInstance().awaitStartResult(2_000);
        assertEquals(LiveMediaMode.MICROPHONE, result.getMode());
        assertEquals(LiveMediaMode.MICROPHONE, pipeline.lastMode);
        String text = notificationText(controller);
        assertTrue("the notification names the microphone: " + text,
                text.contains("Microphone"));
        assertTrue("the notification must not claim the camera: " + text,
                !text.contains("Camera"));
    }

    @Test
    public void failedStartPublishesTheTypedErrorAndStopsItself() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.failed(LiveMediaMode.CAMERA,
                LiveMediaError.PERMISSION_REQUIRED);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller = startService(LiveMediaMode.CAMERA);

        LiveMediaStatus result = LiveMediaRegistry.getInstance().awaitStartResult(2_000);
        assertEquals(LiveMediaState.FAILED, result.getState());
        assertEquals("media-permission-required", result.getError());
        awaitStopped(controller);
    }

    @Test
    public void stopActionTearsDownThePipelineAndPublishesStopped() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:12345/", "h264", 1280, 720, 30, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller = startService(LiveMediaMode.BOTH);
        LiveMediaRegistry.getInstance().awaitStartResult(2_000);

        // The controller applies the intent set via withIntent per command.
        controller.withIntent(stopIntent()).startCommand(0, 2);

        awaitStopped(controller);
        assertTrue("the pipeline must be torn down on stop", pipeline.stopped);
        assertEquals(LiveMediaState.STOPPED,
                LiveMediaRegistry.getInstance().currentStatus().getState());
    }

    @Test
    public void stopDuringPipelineStartStillStopsTheInFlightPipeline() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:12345/", "h264", 1280, 720, 30, "aac", 2);
        pipeline.startGate = new CountDownLatch(1);
        pipeline.startEntered = new CountDownLatch(1);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller = startService(LiveMediaMode.BOTH);
        assertTrue("pipeline start must be in flight",
                pipeline.startEntered.await(2, TimeUnit.SECONDS));

        controller.withIntent(stopIntent()).startCommand(0, 2);
        pipeline.startGate.countDown();

        awaitStopped(controller);
        assertTrue("stop must reach a pipeline assigned before start()",
                pipeline.stopped);
        assertEquals(LiveMediaState.STOPPED,
                LiveMediaRegistry.getInstance().currentStatus().getState());
    }

    @Test
    public void destroyWithoutExplicitStopStillTearsDown() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:12345/", "h264", 1280, 720, 30, "aac", 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller = startService(LiveMediaMode.BOTH);
        LiveMediaRegistry.getInstance().awaitStartResult(2_000);

        controller.destroy();

        assertTrue("destroy must tear the pipeline down", pipeline.stopped);
        assertEquals(LiveMediaState.STOPPED,
                LiveMediaRegistry.getInstance().currentStatus().getState());
    }

    private static ServiceController<LiveMediaService> startService(LiveMediaMode mode) {
        ServiceController<LiveMediaService> controller =
                Robolectric.buildService(LiveMediaService.class, startIntent(mode)).create();
        controller.startCommand(0, 1);
        return controller;
    }

    /** The shadow's type getter is protected, so reflection reaches it. */
    private static int foregroundType(ServiceController<LiveMediaService> controller)
            throws Exception {
        ShadowService shadow = Shadow.extract(controller.get());
        java.lang.reflect.Method typeMethod = ShadowService.class
                .getDeclaredMethod("getForegroundServiceType");
        typeMethod.setAccessible(true);
        return (Integer) typeMethod.invoke(shadow);
    }

    private static String notificationText(ServiceController<LiveMediaService> controller)
            throws Exception {
        ShadowService shadow = Shadow.extract(controller.get());
        Notification notification = shadow.getLastForegroundNotification();
        assertNotNull("a user-visible notification must be posted while streaming",
                notification);
        return String.valueOf(notification.extras.get(Notification.EXTRA_TEXT));
    }

    private static Intent startIntent(LiveMediaMode mode) {
        return new Intent(RuntimeEnvironment.getApplication(), LiveMediaService.class)
                .setAction(LiveMediaService.ACTION_START)
                .putExtra(LiveMediaService.EXTRA_MODE, mode.name());
    }

    private static Intent stopIntent() {
        return new Intent(RuntimeEnvironment.getApplication(), LiveMediaService.class)
                .setAction(LiveMediaService.ACTION_STOP);
    }

    private static void awaitStopped(ServiceController<LiveMediaService> controller) {
        long deadline = System.currentTimeMillis() + 2_000;
        ShadowService shadow = Shadow.extract(controller.get());
        while (!shadow.isStoppedBySelf() && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertTrue("the service must stop itself after teardown",
                shadow.isStoppedBySelf());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Deterministic pipeline fake with an observable start mode and stop. */
    public static final class FakePipeline implements LiveMediaPipeline {
        public LiveMediaStatus result = LiveMediaStatus.failed(LiveMediaError.START_FAILED);
        public volatile int startCalls;
        public volatile boolean stopped;
        public volatile LiveMediaMode lastMode;
        public volatile CountDownLatch startEntered;
        public volatile CountDownLatch startGate;

        @Override
        public LiveMediaStatus start(LiveMediaMode mode) {
            startCalls++;
            lastMode = mode;
            CountDownLatch entered = startEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch gate = startGate;
            if (gate != null) {
                try {
                    gate.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
