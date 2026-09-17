package gh.nusashell.nusadesk.infrastructure.service;

import android.app.Notification;
import android.content.Context;
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
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaState;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Foreground-service lifecycle seams of the live media service on the JVM:
 * the camera|microphone foreground type, the user-visible notification, the
 * typed start result published to the registry, and the clean stop. The real
 * capture pipeline is replaced by a deterministic fake; this test does not
 * pretend to prove real camera encoders. SDK 30 is used so the runtime
 * foreground types (camera/microphone, API 30+) are exercised; on API 29 the
 * service falls back to the manifest-declared type by design.
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
    public void startPromotesToForegroundWithCameraMicrophoneTypeAndPublishesRunning()
            throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:12345/", "h264", "aac", 1280, 720, 30, 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller =
                Robolectric.buildService(LiveMediaService.class, startIntent())
                        .create();
        controller.startCommand(0, 1);

        ShadowService shadow = Shadow.extract(controller.get());
        // The shadow's type getter is protected, so reflection reaches it and
        // the runtime startForeground type bits stay asserted.
        java.lang.reflect.Method typeMethod = ShadowService.class
                .getDeclaredMethod("getForegroundServiceType");
        typeMethod.setAccessible(true);
        int type = (Integer) typeMethod.invoke(shadow);
        assertTrue("camera foreground type must be declared at runtime",
                (type & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0);
        assertTrue("microphone foreground type must be declared at runtime",
                (type & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0);
        Notification notification = shadow.getLastForegroundNotification();
        assertNotNull("a user-visible notification must be posted while streaming",
                notification);

        LiveMediaStatus result = LiveMediaRegistry.getInstance()
                .awaitStartResult(2_000);
        assertEquals(LiveMediaState.RUNNING, result.getState());
        assertEquals("rtsp://127.0.0.1:12345/", result.getRtspUrl());
        assertEquals(2L, result.getClientLimit());
        assertEquals(1, pipeline.startCalls);
        assertEquals("the URL must be loopback", true,
                result.getRtspUrl().startsWith("rtsp://127.0.0.1:"));
    }

    @Test
    public void failedStartPublishesTheTypedErrorAndStopsItself() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.failed(LiveMediaError.PERMISSION_REQUIRED);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller =
                Robolectric.buildService(LiveMediaService.class, startIntent())
                        .create();
        controller.startCommand(0, 1);

        LiveMediaStatus result = LiveMediaRegistry.getInstance()
                .awaitStartResult(2_000);
        assertEquals(LiveMediaState.FAILED, result.getState());
        assertEquals("media-permission-required", result.getError());
        awaitStoppedBySelf(controller);
    }

    @Test
    public void stopActionTearsDownThePipelineAndPublishesStopped() throws Exception {
        FakePipeline pipeline = new FakePipeline();
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:12345/", "h264", "aac", 1280, 720, 30, 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller =
                Robolectric.buildService(LiveMediaService.class, startIntent())
                        .create();
        controller.startCommand(0, 1);
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
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:12345/", "h264", "aac", 1280, 720, 30, 2);
        pipeline.startGate = new CountDownLatch(1);
        pipeline.startEntered = new CountDownLatch(1);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller =
                Robolectric.buildService(LiveMediaService.class, startIntent())
                        .create();
        controller.startCommand(0, 1);
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
        pipeline.result = LiveMediaStatus.running(
                "rtsp://127.0.0.1:12345/", "h264", "aac", 1280, 720, 30, 2);
        LiveMediaService.pipelineOverride = pipeline;

        ServiceController<LiveMediaService> controller =
                Robolectric.buildService(LiveMediaService.class, startIntent())
                        .create();
        controller.startCommand(0, 1);
        LiveMediaRegistry.getInstance().awaitStartResult(2_000);

        controller.destroy();

        assertTrue("destroy must tear the pipeline down", pipeline.stopped);
        assertEquals(LiveMediaState.STOPPED,
                LiveMediaRegistry.getInstance().currentStatus().getState());
    }

    private static Intent startIntent() {
        return new Intent(RuntimeEnvironment.getApplication(), LiveMediaService.class)
                .setAction(LiveMediaService.ACTION_START);
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

    private static void awaitStoppedBySelf(ServiceController<LiveMediaService> controller) {
        awaitStopped(controller);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Deterministic pipeline fake with an observable stop. */
    public static final class FakePipeline implements LiveMediaPipeline {
        public LiveMediaStatus result = LiveMediaStatus.failed(LiveMediaError.START_FAILED);
        public volatile int startCalls;
        public volatile boolean stopped;
        public volatile CountDownLatch startEntered;
        public volatile CountDownLatch startGate;

        @Override
        public LiveMediaStatus start() {
            startCalls++;
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
