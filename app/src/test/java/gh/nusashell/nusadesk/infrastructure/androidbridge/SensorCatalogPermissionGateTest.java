package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Process;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAppOpsManager;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The ACTIVITY_RECOGNITION gate of {@link SensorCatalogModule} on the JVM.
 *
 * <p>{@code step_counter} and {@code step_detector} are the two sensor types
 * the platform docs gate behind the ACTIVITY_RECOGNITION runtime grant on
 * Android 10+. Robolectric defaults every manifest permission to denied with
 * no recorded refusal, which {@link AndroidPermissionChecker} reports as
 * REQUIRED; {@link #deny} additionally records the app-op refusal so the
 * checker reports DENIED. A granted read still blocks on its bounded window,
 * so the event is delivered from the test thread while the bridge call runs
 * on a worker — the same shape {@link AndroidSensorManagerSourceTest} uses.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SensorCatalogPermissionGateTest {

    private Context context;
    private ShadowSensorManager shadowSensors;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // The shadow keeps sensors and listeners in static state, so a
        // directly constructed instance drives the same state the
        // framework-bound shadow uses (see AndroidSensorManagerSourceTest).
        SensorManager manager = (SensorManager) context.getSystemService(
                Context.SENSOR_SERVICE);
        assertNotNull("Robolectric must provide a SensorManager", manager);
        shadowSensors = new ShadowSensorManager();
        shadowSensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER));
        shadowSensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_DETECTOR));
        shadowSensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
    }

    @Test
    public void listMarksOnlyTheGatedRows() {
        AndroidCapabilityProtocol.Response response =
                module().handle(request("1", "sensor.list"));

        assertTrue(response.isOk());
        String json = (String) response.getFields().get("sensors_json");
        assertTrue(json.contains("\"name\":\"step_counter\""));
        assertTrue(json.contains("\"name\":\"step_detector\""));
        assertTrue(json.contains("\"name\":\"accelerometer\""));
        assertTrue("step_counter row carries the gate marker",
                row(json, "step_counter").contains(
                        "\"requires\":\"activity_recognition\""));
        assertTrue("step_detector row carries the gate marker",
                row(json, "step_detector").contains(
                        "\"requires\":\"activity_recognition\""));
        assertFalse("ungated rows stay unchanged",
                row(json, "accelerometer").contains("requires"));
    }

    @Test
    public void readStepCounterWithoutGrantIsPermissionRequired() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "sensor.read", "{\"sensor\":\"step_counter\"}"));

        assertFalse(response.isOk());
        assertEquals("sensor-permission-required:grant "
                        + "android.permission.ACTIVITY_RECOGNITION"
                        + " (permission.request mode=runtime)",
                response.getError());
        assertTrue("no listener may be registered without the grant",
                shadowSensors.getListeners().isEmpty());
    }

    @Test
    public void streamStartStepDetectorWithoutGrantIsPermissionRequired() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "sensor.stream.start", "{\"sensor\":\"step_detector\"}"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("sensor-permission-required"));
        assertTrue(response.getError().contains("ACTIVITY_RECOGNITION"));
        assertTrue(shadowSensors.getListeners().isEmpty());
    }

    @Test
    public void readStepCounterWithRecordedDenialIsPermissionDenied() {
        deny(Manifest.permission.ACTIVITY_RECOGNITION);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "sensor.read", "{\"sensor\":\"step_counter\"}"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("sensor-permission-denied"));
        assertTrue(shadowSensors.getListeners().isEmpty());
    }

    @Test
    public void mixedSelectionWithoutGrantIsPermissionRequired() {
        AndroidCapabilityProtocol.Response response = module().handle(request(
                "1", "sensor.read", "{\"sensor\":\"accelerometer,step_counter\"}"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("sensor-permission-required"));
        assertTrue(shadowSensors.getListeners().isEmpty());
    }

    @Test
    public void readStepCounterWithGrantReadsNormally() throws Exception {
        grant(Manifest.permission.ACTIVITY_RECOGNITION);
        SensorCatalogModule module = module();
        try {
            AtomicReference<AndroidCapabilityProtocol.Response> result =
                    new AtomicReference<>();
            Thread worker = new Thread(() -> result.set(module.handle(request(
                    "1", "sensor.read", "{\"sensor\":\"step_counter\"}"))),
                    "sensor-gate-test");
            worker.setDaemon(true);
            worker.start();
            try {
                awaitListener(shadowSensors);
                SensorEvent event = ShadowSensorManager.createSensorEvent(1);
                event.values[0] = 42f;
                shadowSensors.sendSensorEventToListeners(event);
                worker.join(10_000L);
            } finally {
                worker.interrupt();
            }
            AndroidCapabilityProtocol.Response response = result.get();
            assertNotNull("the bounded read must finish", response);
            assertTrue(response.isOk());
            String samples = (String) response.getFields().get("samples_json");
            assertTrue(samples.contains("\"step_counter\":{\"values\":[42.0]}"));
        } finally {
            module.close();
        }
    }

    @Test
    public void streamStartWithGrantStartsAndStops() {
        grant(Manifest.permission.ACTIVITY_RECOGNITION);
        SensorCatalogModule module = module();
        try {
            AndroidCapabilityProtocol.Response start = module.handle(
                    request("1", "sensor.stream.start", "{\"sensor\":\"step_counter\"}"));
            assertTrue(start.isOk());
            assertEquals(true, start.getFields().get("stream_started"));
            assertEquals("step_counter", start.getFields().get("sensors"));

            AndroidCapabilityProtocol.Response stop =
                    module.handle(request("2", "sensor.stream.stop"));
            assertTrue(stop.isOk());
            assertEquals(true, stop.getFields().get("was_running"));
        } finally {
            module.close();
        }
    }

    @Test
    public void accelerometerReadIsNotGated() throws Exception {
        SensorCatalogModule module = module();
        try {
            AtomicReference<AndroidCapabilityProtocol.Response> result =
                    new AtomicReference<>();
            Thread worker = new Thread(() -> result.set(module.handle(request(
                    "1", "sensor.read", "{\"sensor\":\"accelerometer\"}"))),
                    "sensor-gate-test");
            worker.setDaemon(true);
            worker.start();
            try {
                awaitListener(shadowSensors);
                SensorEvent event = ShadowSensorManager.createSensorEvent(3);
                event.values[0] = 0.5f;
                event.values[1] = -9.81f;
                event.values[2] = 0.25f;
                shadowSensors.sendSensorEventToListeners(event);
                worker.join(10_000L);
            } finally {
                worker.interrupt();
            }
            AndroidCapabilityProtocol.Response response = result.get();
            assertNotNull("the bounded read must finish", response);
            assertTrue(response.isOk());
            assertTrue(((String) response.getFields().get("samples_json"))
                    .contains("accelerometer"));
        } finally {
            module.close();
        }
    }

    // --- helpers ---------------------------------------------------------

    private SensorCatalogModule module() {
        return new SensorCatalogModule(context);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                           String paramsJson) {
        String frame = "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\",\"method\":\""
                + method + "\"" + (paramsJson == null ? "" : ",\"params\":" + paramsJson) + "}";
        AndroidCapabilityProtocol.Request request = AndroidCapabilityProtocol.decodeRequest(frame);
        assertNotNull("test frame must decode", request);
        return request;
    }

    private void grant(String permission) {
        Shadow.<ShadowContextWrapper>extract(context).grantPermissions(permission);
    }

    private void deny(String permission) {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(permission);
        String op = AppOpsManager.permissionToOp(permission);
        if (op != null) {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            Shadow.<ShadowAppOpsManager>extract(appOps).setMode(
                    op, Process.myUid(), context.getPackageName(),
                    AppOpsManager.MODE_IGNORED);
        }
    }

    /** The JSON object of one named row inside the flat sensors array. */
    private static String row(String sensorsJson, String name) {
        int start = sensorsJson.indexOf("{\"name\":\"" + name + "\"");
        assertTrue("row " + name + " must exist", start >= 0);
        int end = sensorsJson.indexOf('}', start);
        assertTrue("row " + name + " must close", end > start);
        return sensorsJson.substring(start, end + 1);
    }

    /** Wait until the read registered its first listener. */
    private static void awaitListener(ShadowSensorManager shadow)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!shadow.getListeners().isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no sensor listener was registered in time");
    }
}
