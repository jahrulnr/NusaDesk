package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLocationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One-shot, permission-aware LocationManager adapter behavior on the JVM.
 *
 * <p>Robolectric's ShadowLocationManager provides the real framework plumbing
 * without a device: it records single-update listeners, delivers simulated
 * fixes on the listener's looper, and can remove/disable providers. The read
 * call itself blocks up to its bounded window, so tests that deliver a fix run
 * the read on a real thread and drive the shadow from the test thread. The
 * permission state comes from a fake checker so the grant-required/denied
 * branches are exercised without depending on permission shadows.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidLocationManagerSourceTest {

    private static final long SUCCESS_WINDOW_MILLIS = 500L;
    private static final long TIMEOUT_WINDOW_MILLIS = 150L;

    @Test
    public void reportsPermissionRequiredWhenNoGrantIsRecorded() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        AndroidLocationManagerSource source = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.REQUIRED);
        try {
            LocationSnapshot snapshot = source.read();
            assertEquals(LocationSnapshot.State.PERMISSION_REQUIRED, snapshot.getState());
            assertTrue("no listener may be registered without a grant",
                    shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    public void reportsPermissionDeniedWhenGrantWasRefused() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        AndroidLocationManagerSource source = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.DENIED);
        try {
            LocationSnapshot snapshot = source.read();
            assertEquals(LocationSnapshot.State.PERMISSION_DENIED, snapshot.getState());
            assertTrue("no listener may be registered without a grant",
                    shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    public void coarseGrantReadsOneNetworkFixAndUnregisters() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationManagerSource source = newSource(
                SUCCESS_WINDOW_MILLIS, () -> LocationGrant.COARSE_ONLY);
        try {
            AtomicReference<LocationSnapshot> result = new AtomicReference<>();
            Thread reader = readOnThread(source, result);
            try {
                awaitSingleListener(shadow);
                Location fix = fix(LocationManager.NETWORK_PROVIDER, -6.9175, 107.6191,
                        12.5f, 1_700_000_000_000L, 768.25, 1.75f, 42.0f);
                shadow.simulateLocation(fix);

                join(reader);
                LocationSnapshot snapshot = result.get();
                assertEquals(LocationSnapshot.State.READING, snapshot.getState());
                assertEquals(LocationManager.NETWORK_PROVIDER, snapshot.getProvider());
                assertEquals(-6.9175, snapshot.getLatitude(), 1e-4);
                assertEquals(107.6191, snapshot.getLongitude(), 1e-4);
                assertEquals(12.5, snapshot.getAccuracyMeters(), 1e-4);
                assertEquals(1_700_000_000_000L, snapshot.getTimestampUtcMillis());
                assertEquals(768.25, snapshot.getAltitudeMeters(), 1e-4);
                assertEquals(1.75, snapshot.getSpeedMetersPerSecond(), 1e-4);
                assertEquals(42.0, snapshot.getBearingDegrees(), 1e-4);
                assertTrue("listener must be unregistered after a successful read",
                        shadow.getLocationUpdateListeners().isEmpty());
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void fineGrantPrefersGpsProvider() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationManagerSource source = newSource(
                SUCCESS_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            AtomicReference<LocationSnapshot> result = new AtomicReference<>();
            Thread reader = readOnThread(source, result);
            try {
                awaitSingleListener(shadow);
                shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER,
                        51.5074, -0.1278, 6.0f, 42L, null, null, null));

                join(reader);
                LocationSnapshot snapshot = result.get();
                assertEquals(LocationSnapshot.State.READING, snapshot.getState());
                assertEquals(LocationManager.GPS_PROVIDER, snapshot.getProvider());
                assertEquals(51.5074, snapshot.getLatitude(), 1e-4);
                assertEquals(-0.1278, snapshot.getLongitude(), 1e-4);
                assertEquals(6.0, snapshot.getAccuracyMeters(), 1e-4);
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void fineGrantFallsBackToNetworkWhenGpsIsAbsent() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.removeProvider(LocationManager.GPS_PROVIDER);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationManagerSource source = newSource(
                SUCCESS_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            AtomicReference<LocationSnapshot> result = new AtomicReference<>();
            Thread reader = readOnThread(source, result);
            try {
                awaitSingleListener(shadow);
                shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER,
                        1.0, 2.0, 30.0f, 7L, null, null, null));

                join(reader);
                LocationSnapshot snapshot = result.get();
                assertEquals(LocationSnapshot.State.READING, snapshot.getState());
                assertEquals(LocationManager.NETWORK_PROVIDER, snapshot.getProvider());
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void usesRequestedProviderWhenFixCarriesNoProviderName() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationManagerSource source = newSource(
                SUCCESS_WINDOW_MILLIS, () -> LocationGrant.COARSE_ONLY);
        try {
            AtomicReference<LocationSnapshot> result = new AtomicReference<>();
            Thread reader = readOnThread(source, result);
            try {
                awaitSingleListener(shadow);
                Location fix = fix(LocationManager.NETWORK_PROVIDER,
                        1.0, 2.0, 30.0f, 7L, null, null, null);
                fix.setProvider(null);
                shadow.simulateLocation(LocationManager.NETWORK_PROVIDER, fix);

                join(reader);
                LocationSnapshot snapshot = result.get();
                assertEquals(LocationSnapshot.State.READING, snapshot.getState());
                assertEquals(LocationManager.NETWORK_PROVIDER, snapshot.getProvider());
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }
    }

    @Test
    public void reportsUnavailableWhenNoUsableProviderForTheGrant() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.removeProvider(LocationManager.GPS_PROVIDER);
        shadow.removeProvider(LocationManager.NETWORK_PROVIDER);
        AndroidLocationManagerSource source = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            LocationSnapshot snapshot = source.read();
            assertEquals(LocationSnapshot.State.UNAVAILABLE, snapshot.getState());
            assertTrue(shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    public void reportsUnavailableWhenLocationOrTheProviderIsDisabled() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(false);
        AndroidLocationManagerSource source = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            // Location master switch off: every provider is reported disabled,
            // so the read fails typed and fast instead of timing out.
            LocationSnapshot snapshot = source.read();
            assertEquals(LocationSnapshot.State.UNAVAILABLE, snapshot.getState());
            assertTrue(shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            source.close();
        }
        shadow.setLocationEnabled(true);
        // Coarse grant with the network provider still disabled.
        AndroidLocationManagerSource coarse = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.COARSE_ONLY);
        try {
            LocationSnapshot snapshot = coarse.read();
            assertEquals(LocationSnapshot.State.UNAVAILABLE, snapshot.getState());
            assertTrue(shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            coarse.close();
        }
    }

    @Test
    public void timesOutWithoutAFixAndUnregisters() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationManagerSource source = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            long start = System.nanoTime();
            LocationSnapshot snapshot = source.read();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

            assertEquals(LocationSnapshot.State.TIMEOUT, snapshot.getState());
            assertTrue("read must respect its bounded window",
                    elapsedMillis >= TIMEOUT_WINDOW_MILLIS / 2
                            && elapsedMillis <= TIMEOUT_WINDOW_MILLIS * 10);
            assertTrue("no listener may remain registered after a timeout",
                    shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    public void rejectsInvalidFixAsErrorAndUnregisters() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationManagerSource source = newSource(
                SUCCESS_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            AtomicReference<LocationSnapshot> result = new AtomicReference<>();
            Thread reader = readOnThread(source, result);
            try {
                awaitSingleListener(shadow);
                Location nonFinite = fix(LocationManager.GPS_PROVIDER,
                        Double.NaN, 2.0, 6.0f, 42L, null, null, null);
                shadow.simulateLocation(nonFinite);

                join(reader);
                assertEquals(LocationSnapshot.State.ERROR, result.get().getState());
                assertTrue("listener must be unregistered after a rejected fix",
                        shadow.getLocationUpdateListeners().isEmpty());
            } finally {
                reader.interrupt();
            }
        } finally {
            source.close();
        }

        AndroidLocationManagerSource sourceWithoutAccuracy = newSource(
                SUCCESS_WINDOW_MILLIS, () -> LocationGrant.FINE);
        try {
            AtomicReference<LocationSnapshot> result = new AtomicReference<>();
            Thread reader = readOnThread(sourceWithoutAccuracy, result);
            try {
                awaitSingleListener(shadow);
                Location noAccuracy = new Location(LocationManager.GPS_PROVIDER);
                noAccuracy.setLatitude(1.0);
                noAccuracy.setLongitude(2.0);
                noAccuracy.setTime(42L);
                shadow.simulateLocation(noAccuracy);

                join(reader);
                assertEquals(LocationSnapshot.State.ERROR, result.get().getState());
                assertTrue(shadow.getLocationUpdateListeners().isEmpty());
            } finally {
                reader.interrupt();
            }
        } finally {
            sourceWithoutAccuracy.close();
        }
    }

    @Test
    public void readsAfterCloseFailClosedWithoutRecreatingTheThread() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        AndroidLocationManagerSource source = newSource(
                TIMEOUT_WINDOW_MILLIS, () -> LocationGrant.FINE);
        source.close();
        source.close();
        try {
            LocationSnapshot snapshot = source.read();
            assertEquals(LocationSnapshot.State.ERROR, snapshot.getState());
            assertTrue(shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            source.close();
        }
    }

    private static AndroidLocationManagerSource newSource(long timeoutMillis,
                                                          LocationPermissionChecker checker) {
        return new AndroidLocationManagerSource(
                RuntimeEnvironment.getApplication(), timeoutMillis, checker);
    }

    private static ShadowLocationManager shadowOf(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(
                Context.LOCATION_SERVICE);
        if (manager == null) {
            throw new AssertionError("Robolectric must provide a LocationManager");
        }
        // Shadows.shadowOf(...) is unusable here: that class has an overload
        // referencing FingerprintManager, which the unit-test mockable
        // android.jar does not contain. Shadow.extract is a clean generic
        // accessor to the same per-instance shadow.
        return Shadow.extract(manager);
    }

    private static Location fix(String provider, double latitude, double longitude,
                                float accuracy, long time, Double altitude, Float speed,
                                Float bearing) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(accuracy);
        location.setTime(time);
        if (altitude != null) {
            location.setAltitude(altitude);
        }
        if (speed != null) {
            location.setSpeed(speed);
        }
        if (bearing != null) {
            location.setBearing(bearing);
        }
        return location;
    }

    private static Thread readOnThread(AndroidLocationManagerSource source,
                                       AtomicReference<LocationSnapshot> result) {
        Thread thread = new Thread(() -> result.set(source.read()), "location-read-test");
        thread.start();
        return thread;
    }

    /** Wait until the read registered exactly one listener. */
    private static void awaitSingleListener(ShadowLocationManager shadow)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<android.location.LocationListener> listeners =
                    shadow.getLocationUpdateListeners();
            if (listeners.size() == 1) {
                return;
            }
            if (!listeners.isEmpty()) {
                throw new AssertionError("expected exactly one listener, got "
                        + listeners.size());
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no location listener was registered in time");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(SUCCESS_WINDOW_MILLIS * 2);
        assertFalse("location read thread must finish", thread.isAlive());
    }
}
