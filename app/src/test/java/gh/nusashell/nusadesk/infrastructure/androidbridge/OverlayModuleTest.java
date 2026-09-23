package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.os.Looper;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link OverlayModule} on the JVM. The view surface is injected as an
 * {@link OverlayModule.OverlayBackend} fake so the method contract runs
 * without a real {@link android.view.WindowManager}; the draw-over-apps grant
 * is driven through Robolectric's {@link ShadowSettings}. Robolectric runs
 * the test thread on the main looper, so {@code onMain} executes inline; one
 * test drives the posted path from a worker thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class OverlayModuleTest {
    private Context context;
    private FakeBackend backend;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        backend = new FakeBackend();
        ShadowSettings.setCanDrawOverlays(false);
    }

    @Test
    public void declaresTheOverlayMethodsAndParamSet() {
        OverlayModule module = module();
        assertEquals(List.of("overlay.show", "overlay.update", "overlay.status",
                "overlay.hide"), module.methods());
        assertEquals(Set.of("overlay.show", "overlay.update"),
                module.parameterMethods());
    }

    @Test
    public void unknownMethodIsUnsupported() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "overlay.nope"));
        assertFalse(response.isOk());
        assertEquals("unsupported-method", response.getError());
    }

    @Test
    public void showWithoutGrantIsTypedPermissionError() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "overlay.show", "{\"text\":\"hi\"}"));
        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("overlay-permission-required"));
        assertTrue(response.getError().contains("SYSTEM_ALERT_WINDOW"));
        assertTrue(response.getError().contains("mode=settings"));
        assertTrue(backend.applied.isEmpty());
    }

    @Test
    public void showAttachesAndEchoesPosition() {
        grantOverlay();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "overlay.show", "{\"text\":\"hello\"}"));
        assertTrue(response.isOk());
        assertEquals(true, response.getFields().get("shown"));
        assertEquals(24L, response.getFields().get("x"));
        assertEquals(120L, response.getFields().get("y"));
        assertEquals(1, backend.views.size());
        OverlayModule.OverlaySpec applied = backend.applied.get(0);
        assertEquals("hello", applied.text);
        assertEquals(24, applied.x);
        assertEquals(120, applied.y);
        assertEquals(16, applied.size);
        assertEquals("", applied.color);
    }

    @Test
    public void showValidatesParams() {
        grantOverlay();
        OverlayModule module = module();
        String longText = new String(new char[257]).replace('\0', 'a');
        assertThrowsInvalid(module, "1", "overlay.show", "{}");
        assertThrowsInvalid(module, "2", "overlay.show",
                "{\"text\":\"" + longText + "\"}");
        assertThrowsInvalid(module, "3", "overlay.show",
                "{\"text\":\"hi\",\"x\":5001}");
        assertThrowsInvalid(module, "4", "overlay.show",
                "{\"text\":\"hi\",\"y\":-5001}");
        assertThrowsInvalid(module, "5", "overlay.show",
                "{\"text\":\"hi\",\"size\":9}");
        assertThrowsInvalid(module, "6", "overlay.show",
                "{\"text\":\"hi\",\"size\":73}");
        assertThrowsInvalid(module, "7", "overlay.show",
                "{\"text\":\"hi\",\"color\":\"red\"}");
        assertThrowsInvalid(module, "8", "overlay.show",
                "{\"text\":\"hi\",\"color\":\"#GGGGGG\"}");
        assertThrowsInvalid(module, "9", "overlay.show",
                "{\"text\":\"hi\",\"bogus\":1}");
        assertTrue(backend.views.isEmpty());
    }

    @Test
    public void secondShowIsAlreadyShown() {
        grantOverlay();
        OverlayModule module = module();
        assertTrue(module.handle(
                request("1", "overlay.show", "{\"text\":\"hi\"}")).isOk());

        AndroidCapabilityProtocol.Response second = module.handle(
                request("2", "overlay.show", "{\"text\":\"again\"}"));
        assertFalse(second.isOk());
        assertEquals("overlay-already-shown", second.getError());
        assertEquals(1, backend.views.size());
    }

    @Test
    public void updateWithoutShowIsNotShown() {
        grantOverlay();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "overlay.update", "{\"text\":\"x\"}"));
        assertFalse(response.isOk());
        assertEquals("overlay-not-shown", response.getError());
    }

    @Test
    public void updateRequiresAtLeastOneParam() {
        grantOverlay();
        OverlayModule module = module();
        assertTrue(module.handle(
                request("1", "overlay.show", "{\"text\":\"hi\"}")).isOk());
        assertThrowsInvalid(module, "2", "overlay.update", "{}");
        assertThrowsInvalid(module, "3", "overlay.update",
                "{\"bogus\":1}");
    }

    @Test
    public void updateMergesIntoTheLiveSpec() {
        grantOverlay();
        OverlayModule module = module();
        assertTrue(module.handle(request("1", "overlay.show",
                "{\"text\":\"hi\",\"x\":10,\"y\":20,\"size\":20,"
                        + "\"color\":\"#FF0000FF\"}")).isOk());

        AndroidCapabilityProtocol.Response moved = module.handle(
                request("2", "overlay.update", "{\"x\":30}"));
        assertTrue(moved.isOk());
        assertEquals(true, moved.getFields().get("updated"));
        OverlayModule.OverlaySpec merged = backend.applied.get(1);
        assertEquals("hi", merged.text);
        assertEquals(30, merged.x);
        assertEquals(20, merged.y);
        assertEquals(20, merged.size);
        assertEquals("#FF0000FF", merged.color);

        assertTrue(module.handle(
                request("3", "overlay.update", "{\"color\":\"#80FF0000\"}")).isOk());
        assertEquals("#80FF0000", backend.applied.get(2).color);
        assertEquals(30, backend.applied.get(2).x);
    }

    @Test
    public void updatePlatformRefusalKeepsTheOldSpec() {
        grantOverlay();
        OverlayModule module = module();
        assertTrue(module.handle(request("1", "overlay.show",
                "{\"text\":\"hi\",\"x\":10}")).isOk());
        backend.failOnUpdate = true;
        AndroidCapabilityProtocol.Response failed = module.handle(
                request("2", "overlay.update", "{\"x\":30}"));
        assertFalse(failed.isOk());
        assertEquals("overlay-failed:update rejected", failed.getError());
        AndroidCapabilityProtocol.Response status =
                module.handle(request("3", "overlay.status"));
        assertEquals(10L, status.getFields().get("x"));
    }

    @Test
    public void statusReportsEmptyThenLiveState() {
        OverlayModule module = module();
        AndroidCapabilityProtocol.Response empty =
                module.handle(request("1", "overlay.status"));
        assertTrue(empty.isOk());
        assertEquals(false, empty.getFields().get("shown"));
        assertEquals("", empty.getFields().get("text"));
        assertEquals(0L, empty.getFields().get("x"));
        assertEquals(0L, empty.getFields().get("y"));
        assertEquals(0L, empty.getFields().get("size"));
        assertEquals("", empty.getFields().get("color"));

        grantOverlay();
        assertTrue(module.handle(request("2", "overlay.show",
                "{\"text\":\"hi\",\"x\":5,\"y\":6,\"size\":30,"
                        + "\"color\":\"#FF00FF00\"}")).isOk());
        AndroidCapabilityProtocol.Response live =
                module.handle(request("3", "overlay.status"));
        assertEquals(true, live.getFields().get("shown"));
        assertEquals("hi", live.getFields().get("text"));
        assertEquals(5L, live.getFields().get("x"));
        assertEquals(6L, live.getFields().get("y"));
        assertEquals(30L, live.getFields().get("size"));
        assertEquals("#FF00FF00", live.getFields().get("color"));
    }

    @Test
    public void hideIsIdempotent() {
        OverlayModule module = module();
        AndroidCapabilityProtocol.Response idle =
                module.handle(request("1", "overlay.hide"));
        assertTrue(idle.isOk());
        assertEquals(false, idle.getFields().get("shown"));
        assertEquals(false, idle.getFields().get("was_shown"));

        grantOverlay();
        assertTrue(module.handle(
                request("2", "overlay.show", "{\"text\":\"hi\"}")).isOk());
        View view = backend.views.get(0);
        AndroidCapabilityProtocol.Response hidden =
                module.handle(request("3", "overlay.hide"));
        assertTrue(hidden.isOk());
        assertEquals(false, hidden.getFields().get("shown"));
        assertEquals(true, hidden.getFields().get("was_shown"));
        assertTrue(backend.views.isEmpty());
        assertEquals(view, backend.detached.get(0));

        AndroidCapabilityProtocol.Response again =
                module.handle(request("4", "overlay.hide"));
        assertTrue(again.isOk());
        assertEquals(false, again.getFields().get("was_shown"));
        assertEquals(1, backend.detached.size());
    }

    @Test
    public void closeReleasesTheView() {
        grantOverlay();
        OverlayModule module = module();
        assertTrue(module.handle(
                request("1", "overlay.show", "{\"text\":\"hi\"}")).isOk());

        module.close();
        assertTrue(backend.views.isEmpty());
        assertEquals(1, backend.detached.size());

        AndroidCapabilityProtocol.Response status =
                module.handle(request("2", "overlay.status"));
        assertEquals(false, status.getFields().get("shown"));

        AndroidCapabilityProtocol.Response shown = module.handle(
                request("3", "overlay.show", "{\"text\":\"hi\"}"));
        assertFalse(shown.isOk());
        assertEquals("overlay-failed:module closed", shown.getError());
    }

    @Test
    public void closeWithoutOverlayIsHarmless() {
        OverlayModule module = module();
        module.close();
        assertTrue(backend.detached.isEmpty());
        module.close();
    }

    @Test
    public void platformRefusalOnAddIsTypedFailure() {
        grantOverlay();
        OverlayModule module = module();
        backend.failOnAdd = true;
        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "overlay.show", "{\"text\":\"hi\"}"));
        assertFalse(response.isOk());
        assertEquals("overlay-failed:window add rejected", response.getError());
        assertEquals(false, module.handle(request("2", "overlay.status"))
                .getFields().get("shown"));
    }

    @Test
    public void showFromWorkerThreadRunsViewOpsOnMain() throws Exception {
        grantOverlay();
        OverlayModule module = module();
        AtomicReference<AndroidCapabilityProtocol.Response> ref =
                new AtomicReference<>();
        Thread worker = new Thread(() -> ref.set(module.handle(
                request("1", "overlay.show", "{\"text\":\"hi\"}"))));
        worker.start();
        // The worker parked the view add on the main queue; drain it so its
        // bounded wait resolves.
        ShadowLooper main = Shadow.extract(Looper.getMainLooper());
        for (int i = 0; i < 200 && ref.get() == null; i++) {
            main.idle();
            Thread.sleep(5);
        }
        worker.join(2_000);
        assertNotNull("worker must finish", ref.get());
        assertTrue(ref.get().isOk());
        assertEquals(1, backend.views.size());
    }

    @Test
    public void realBackendBuildsAndRemovesTheView() {
        // The platform seam against the real WindowManager shadow: addView
        // must accept the TYPE_APPLICATION_OVERLAY params on the JVM.
        ShadowSettings.setCanDrawOverlays(true);
        OverlayModule module = new OverlayModule(context);
        AndroidCapabilityProtocol.Response shown = module.handle(
                request("1", "overlay.show", "{\"text\":\"hi\",\"color\":\"#80FF0000\"}"));
        assertTrue(shown.getError(), shown.isOk());
        assertEquals(true, module.handle(request("2", "overlay.status"))
                .getFields().get("shown"));
        assertTrue(module.handle(request("3", "overlay.hide")).isOk());
        assertEquals(false, module.handle(request("4", "overlay.status"))
                .getFields().get("shown"));
        module.close();
    }

    // --- helpers ---------------------------------------------------------

    private OverlayModule module() {
        return new OverlayModule(context, backend);
    }

    private static void grantOverlay() {
        ShadowSettings.setCanDrawOverlays(true);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                           String paramsJson) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        AndroidCapabilityProtocol.Request request =
                AndroidCapabilityProtocol.decodeRequest(frame);
        assertNotNull("test frame must decode", request);
        return request;
    }

    private static void assertThrowsInvalid(CapabilityModule module, String id,
                                            String method, String paramsJson) {
        try {
            module.handle(request(id, method, paramsJson));
        } catch (CapabilityParams.Invalid e) {
            return;
        }
        throw new AssertionError(method + " must reject params " + paramsJson);
    }

    /**
     * The {@link OverlayModule.OverlayBackend} fake: every applied spec is
     * recorded, live views are tracked in {@link #views}, and detach calls
     * land in {@link #detached}.
     */
    private final class FakeBackend implements OverlayModule.OverlayBackend {
        final List<OverlayModule.OverlaySpec> applied = new ArrayList<>();
        final List<View> views = new ArrayList<>();
        final List<View> detached = new ArrayList<>();
        boolean failOnAdd;
        boolean failOnUpdate;
        boolean failOnRemove;

        @Override
        public View add(OverlayModule.OverlaySpec spec) {
            if (failOnAdd) {
                throw new IllegalStateException("window rejected");
            }
            View view = new View(context);
            views.add(view);
            applied.add(spec);
            return view;
        }

        @Override
        public void update(View view, OverlayModule.OverlaySpec spec) {
            if (failOnUpdate) {
                throw new IllegalStateException("update rejected");
            }
            applied.add(spec);
        }

        @Override
        public void remove(View view) {
            if (failOnRemove) {
                throw new IllegalStateException("detach rejected");
            }
            detached.add(view);
            views.remove(view);
        }
    }
}
