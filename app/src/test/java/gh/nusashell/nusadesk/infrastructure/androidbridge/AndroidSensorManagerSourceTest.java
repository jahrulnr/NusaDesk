package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One-shot SensorManager adapter behavior on the JVM.
 *
 * <p>Robolectric's ShadowSensorManager lets a test register real framework
 * sensor plumbing without a device: it records listeners, delivers synthetic
 * {@link SensorEvent}s, and can force registration failures. The read call
 * itself blocks up to its bounded window, so tests that deliver an event run
 * the read on a real thread and drive the shadow from the test thread.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidSensorManagerSourceTest {

    private static final long SUCCESS_WINDOW_MILLIS = 500L;
    private static final long TIMEOUT_WINDOW_MILLIS = 150L;

    @Test
    public void reportsUnavailableWhenTheSensorDoesNotExist() {
        AndroidSensorManagerSource source = newSource(TIMEOUT_WINDOW_MILLIS);
        try {
            SensorReading reading = source.read(SensorKind.ACCELEROMETER);
            assertEquals(SensorReading.State.UNAVAILABLE, reading.getState());
            assertEquals("accelerometer", reading.getSensorName());
        } finally {
            source.close();
        }
    }

    @Test
    public void readsOneFiniteSnapshotAndUnregistersImmediately() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSensorManager shadow = shadowSensorManager(context);
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        AndroidSensorManagerSource source = newSource(SUCCESS_WINDOW_MILLIS);
        try {
            AtomicReference<SensorReading> result = new AtomicReference<>();
            Thread reader = readOnThread(source, SensorKind.ACCELEROMETER, result);
            try {
                SensorEventListener captured = awaitSingleListener(shadow);
                SensorEvent event = ShadowSensorManager.createSensorEvent(3);
                event.values[0] = 0.5f;
                event.values[1] = -9.81f;
                event.values[2] = 0.25f;
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
                event.timestamp = 1_700_000_000_123_456L;
                shadow.sendSensorEventToListeners(event);

                join(reader);
                SensorReading reading = result.get();
                assertEquals(SensorReading.State.READING, reading.getState());
                assertEquals("accelerometer", reading.getSensorName());
                // SensorEvent values are floats widened to double; compare with
                // the float rounding tolerance.
                assertEquals(0.5, reading.getX(), 1e-4);
                assertEquals(-9.81, reading.getY(), 1e-4);
                assertEquals(0.25, reading.getZ(), 1e-4);
                assertEquals("high", reading.getAccuracy());
                assertEquals(1_700_000_000_123_456L, reading.getTimestampNanos());
                assertFalse("listener must be unregistered after a successful read",
                        shadow.hasListener(captured));
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void timesOutWithoutAnEventAndUnregisters() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSensorManager shadow = shadowSensorManager(context);
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE));
        AndroidSensorManagerSource source = newSource(TIMEOUT_WINDOW_MILLIS);
        try {
            long start = System.nanoTime();
            SensorReading reading = source.read(SensorKind.GYROSCOPE);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

            assertEquals(SensorReading.State.TIMEOUT, reading.getState());
            assertEquals("gyroscope", reading.getSensorName());
            assertTrue("read must respect its bounded window",
                    elapsedMillis >= TIMEOUT_WINDOW_MILLIS / 2
                            && elapsedMillis <= TIMEOUT_WINDOW_MILLIS * 10);
            assertTrue("no listener may remain registered after a timeout",
                    shadow.getListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    public void reportsErrorWhenRegistrationFails() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSensorManager shadow = shadowSensorManager(context);
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        shadow.setForceListenersToFail(true);
        AndroidSensorManagerSource source = newSource(TIMEOUT_WINDOW_MILLIS);
        try {
            SensorReading reading = source.read(SensorKind.ACCELEROMETER);
            assertEquals(SensorReading.State.ERROR, reading.getState());
            assertTrue(shadow.getListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    public void rejectsNonFinitePlatformValuesAsError() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSensorManager shadow = shadowSensorManager(context);
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        AndroidSensorManagerSource source = newSource(SUCCESS_WINDOW_MILLIS);
        try {
            AtomicReference<SensorReading> result = new AtomicReference<>();
            Thread reader = readOnThread(source, SensorKind.ACCELEROMETER, result);
            try {
                awaitSingleListener(shadow);
                SensorEvent event = ShadowSensorManager.createSensorEvent(3);
                event.values[0] = Float.NaN;
                event.values[1] = 0.0f;
                event.values[2] = 0.0f;
                event.accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
                event.timestamp = 42L;
                shadow.sendSensorEventToListeners(event);

                join(reader);
                SensorReading reading = result.get();
                assertEquals(SensorReading.State.ERROR, reading.getState());
                assertTrue("listener must be unregistered after a rejected event",
                        shadow.getListeners().isEmpty());
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void mapsKindToTheCorrectSensorType() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSensorManager shadow = shadowSensorManager(context);
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE));
        AndroidSensorManagerSource source = newSource(SUCCESS_WINDOW_MILLIS);
        try {
            AtomicReference<SensorReading> result = new AtomicReference<>();
            Thread reader = readOnThread(source, SensorKind.GYROSCOPE, result);
            try {
                awaitSingleListener(shadow);
                SensorEvent event = ShadowSensorManager.createSensorEvent(3);
                event.values[0] = 0.01f;
                event.values[1] = -0.02f;
                event.values[2] = 0.0f;
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
                event.timestamp = 7L;
                shadow.sendSensorEventToListeners(event);

                join(reader);
                SensorReading reading = result.get();
                assertEquals(SensorReading.State.READING, reading.getState());
                assertEquals("gyroscope", reading.getSensorName());
                assertEquals(0.01, reading.getX(), 1e-4);
                assertEquals("medium", reading.getAccuracy());
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void readsAfterCloseFailClosedWithoutRecreatingTheThread() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowSensorManager shadow = shadowSensorManager(context);
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        AndroidSensorManagerSource source = newSource(TIMEOUT_WINDOW_MILLIS);
        source.close();
        source.close();
        try {
            SensorReading reading = source.read(SensorKind.ACCELEROMETER);
            assertEquals(SensorReading.State.ERROR, reading.getState());
            assertTrue(shadow.getListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    private static AndroidSensorManagerSource newSource(long timeoutMillis) {
        return new AndroidSensorManagerSource(
                RuntimeEnvironment.getApplication(), timeoutMillis);
    }

    private static ShadowSensorManager shadowSensorManager(Context context) {
        // The shadow stores listeners and sensors in static state, so a
        // directly constructed instance drives the same state the
        // framework-bound shadow uses. (Shadows.shadowOf(...) is unusable
        // here: the Shadows class has an overload referencing
        // FingerprintManager, which the unit-test mockable android.jar does
        // not contain.)
        SensorManager manager = (SensorManager) context.getSystemService(
                Context.SENSOR_SERVICE);
        if (manager == null) {
            throw new AssertionError("Robolectric must provide a SensorManager");
        }
        return new ShadowSensorManager();
    }

    private static Thread readOnThread(AndroidSensorManagerSource source, SensorKind kind,
                                       AtomicReference<SensorReading> result) {
        Thread thread = new Thread(() -> result.set(source.read(kind)), "sensor-read-test");
        thread.start();
        return thread;
    }

    /** Wait until the read registered exactly one listener; return it. */
    private static SensorEventListener awaitSingleListener(ShadowSensorManager shadow)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<SensorEventListener> listeners = shadow.getListeners();
            if (listeners.size() == 1) {
                return listeners.get(0);
            }
            if (!listeners.isEmpty()) {
                throw new AssertionError("expected exactly one listener, got "
                        + listeners.size());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no sensor listener was registered in time");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(SUCCESS_WINDOW_MILLIS * 2);
        assertFalse("sensor read thread must finish", thread.isAlive());
    }
}
