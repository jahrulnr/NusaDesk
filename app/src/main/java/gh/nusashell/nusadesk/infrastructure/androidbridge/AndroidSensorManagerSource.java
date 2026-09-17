package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot {@link SensorManager} adapter for the allowlisted sensor kinds.
 *
 * <p>Each read registers a listener on a dedicated bounded
 * {@link HandlerThread} (created lazily, stopped by {@link #close()}) and
 * waits at most the configured read window for the first event. The listener
 * is unregistered on success, timeout, registration failure, interruption, and
 * platform exceptions, so no read can outlive the bridge session or block a
 * bridge worker indefinitely. Non-finite platform values are rejected as an
 * explicit {@link SensorReading.State#ERROR} reading, never encoded.</p>
 *
 * <p>The supported kinds (accelerometer, gyroscope) need no runtime
 * permission on Android 10+; a missing {@link SensorManager} or sensor is
 * reported as {@link SensorReading.State#UNAVAILABLE} rather than fabricated.</p>
 */
public final class AndroidSensorManagerSource implements SensorReadingSource, AutoCloseable {
    private static final String THREAD_NAME = "android-capability-sensor";
    private static final int SAMPLING_PERIOD_MICROS = SensorManager.SENSOR_DELAY_GAME;
    private static final long DEFAULT_TIMEOUT_MILLIS = 2_000L;

    private final Context context;
    private final long timeoutMillis;
    private HandlerThread sensorThread;
    private volatile boolean closed;

    public AndroidSensorManagerSource(Context context) {
        this(context, DEFAULT_TIMEOUT_MILLIS);
    }

    AndroidSensorManagerSource(Context context, long timeoutMillis) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.context = context.getApplicationContext();
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public SensorReading read(SensorKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (closed) {
            // Fast path; sensorHandler() re-checks under the close monitor so a
            // read racing teardown never recreates the delivery thread.
            return SensorReading.error(kind.getName());
        }
        SensorManager manager = sensorManager();
        if (manager == null) {
            return SensorReading.unavailable(kind.getName());
        }
        Sensor sensor = manager.getDefaultSensor(androidType(kind));
        if (sensor == null) {
            return SensorReading.unavailable(kind.getName());
        }
        Handler handler = sensorHandler();
        if (handler == null) {
            // Teardown won the race between the closed check and thread
            // creation; never recreate the delivery thread.
            return SensorReading.error(kind.getName());
        }
        AtomicReference<SensorReading> outcome = new AtomicReference<>();
        AtomicInteger accuracy = new AtomicInteger(SensorManager.SENSOR_STATUS_UNRELIABLE);
        CountDownLatch latch = new CountDownLatch(1);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event == null || event.values == null || event.values.length < 3) {
                    settle(outcome, latch, SensorReading.error(kind.getName()), manager, this);
                    return;
                }
                accuracy.set(event.accuracy);
                try {
                    settle(outcome, latch, SensorReading.reading(
                            kind.getName(),
                            event.values[0], event.values[1], event.values[2],
                            accuracy.get(), event.timestamp), manager, this);
                } catch (IllegalArgumentException invalid) {
                    // Non-finite or otherwise invalid platform values become an
                    // explicit error, never a fabricated reading.
                    settle(outcome, latch, SensorReading.error(kind.getName()), manager, this);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int value) {
                accuracy.set(value);
            }
        };
        boolean registered;
        try {
            registered = manager.registerListener(
                    listener, sensor, SAMPLING_PERIOD_MICROS, handler);
        } catch (RuntimeException e) {
            return SensorReading.error(kind.getName());
        }
        if (!registered) {
            return SensorReading.error(kind.getName());
        }
        // The timeout runs on the same looper as the events, scheduled at the
        // read deadline, so an event delivered inside the window always wins:
        // whichever settles the outcome first wins.
        handler.postDelayed(() -> {
            if (outcome.compareAndSet(null, SensorReading.timeout(kind.getName()))) {
                safeUnregister(manager, listener);
                latch.countDown();
            }
        }, timeoutMillis);
        boolean delivered;
        try {
            delivered = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeUnregister(manager, listener);
            return SensorReading.error(kind.getName());
        }
        if (!delivered && outcome.compareAndSet(null, SensorReading.timeout(kind.getName()))) {
            // The await deadline expired just before the queued timeout ran;
            // settle once so the caller always sees a typed result.
            safeUnregister(manager, listener);
        }
        return outcome.get();
    }

    /** Stop the sensor delivery thread; idempotent and safe after close. */
    @Override
    public synchronized void close() {
        closed = true;
        HandlerThread thread = sensorThread;
        sensorThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /**
     * Return a handler on the shared delivery thread, or {@code null} when the
     * source is closed. Synchronized with {@link #close()} so a read racing
     * teardown can never create a thread after close has run.
     */
    private synchronized Handler sensorHandler() {
        if (closed) {
            return null;
        }
        HandlerThread thread = sensorThread;
        if (thread == null || !thread.isAlive()) {
            thread = new HandlerThread(THREAD_NAME);
            thread.start();
            sensorThread = thread;
        }
        return new Handler(thread.getLooper());
    }

    private SensorManager sensorManager() {
        try {
            return (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void settle(AtomicReference<SensorReading> outcome, CountDownLatch latch,
                               SensorReading reading, SensorManager manager,
                               SensorEventListener listener) {
        if (outcome.compareAndSet(null, reading)) {
            safeUnregister(manager, listener);
            latch.countDown();
        }
    }

    private static void safeUnregister(SensorManager manager, SensorEventListener listener) {
        try {
            manager.unregisterListener(listener);
        } catch (RuntimeException ignored) {
            // Unregistration is best-effort; the read outcome is already settled.
        }
    }

    private static int androidType(SensorKind kind) {
        return kind == SensorKind.GYROSCOPE
                ? Sensor.TYPE_GYROSCOPE : Sensor.TYPE_ACCELEROMETER;
    }
}
