package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLocationManager;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Foreground-only continuous LocationManager stream behavior on the JVM.
 *
 * <p>Robolectric's ShadowLocationManager records per-provider listener
 * registrations and delivers simulated fixes on the adapter's real
 * HandlerThread looper, so provider selection, the requested interval,
 * fix delivery, and listener cleanup are all observable without a device.
 * The permission state comes from a fake checker so grant-required/denied
 * branches are exercised without depending on permission shadows. No test
 * claims device streaming success: fix cadence, GPS availability, and OEM
 * behavior remain device-verification items.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidLocationStreamSessionTest {

    private static final long LONG_SILENCE_MILLIS = 10_000L;
    private static final long SHORT_SILENCE_MILLIS = 150L;
    private static final long SUPPRESSION_SILENCE_MILLIS = 300L;
    private static final long EVENT_POLL_MILLIS = 500L;
    private static final long REQUESTED_INTERVAL_MILLIS = 5_000L;

    @Test
    public void reportsPermissionRequiredWhenNoGrantIsRecorded() {
        ShadowLocationManager shadow = shadowOf(RuntimeEnvironment.getApplication());
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.REQUIRED);
        try {
            session.start(fineRequest());
            assertEquals(LocationStreamSession.State.STOPPED, session.state());
            assertEquals(LocationStreamEvent.State.PERMISSION_REQUIRED,
                    session.poll(0).getState());
            assertTrue("no listener may be registered without a grant",
                    shadow.getLocationUpdateListeners().isEmpty());
            assertNull(session.latestReading());
        } finally {
            session.close();
        }
    }

    @Test
    public void reportsPermissionDeniedWhenGrantWasRefused() {
        ShadowLocationManager shadow = shadowOf(RuntimeEnvironment.getApplication());
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.DENIED);
        try {
            session.start(fineRequest());
            assertEquals(LocationStreamSession.State.STOPPED, session.state());
            assertEquals(LocationStreamEvent.State.PERMISSION_DENIED,
                    session.poll(0).getState());
            assertTrue("no listener may be registered without a grant",
                    shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    public void coarseOnlyGrantStreamsOnNetworkEvenWhenFineWasRequested() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.COARSE_ONLY);
        try {
            session.start(fineRequest());
            assertEquals(LocationStreamSession.State.STREAMING, session.state());
            awaitSingleListener(shadow);
            // A fine request under a coarse-only grant must degrade to the
            // network provider, never request GPS without the fine grant.
            assertEquals(1, shadow.getLocationUpdateListeners(
                    LocationManager.NETWORK_PROVIDER).size());
            assertTrue(shadow.getLocationUpdateListeners(
                    LocationManager.GPS_PROVIDER).isEmpty());
            assertEquals(REQUESTED_INTERVAL_MILLIS, shadow.getLegacyLocationRequests(
                    LocationManager.NETWORK_PROVIDER).get(0).getIntervalMillis());

            shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER,
                    -6.9175, 107.6191, 12.5f, 1_700_000_000_000L, 768.25, 1.75f, 42.0f));
            LocationSnapshot snapshot = pollReading(session);
            assertEquals(LocationManager.NETWORK_PROVIDER, snapshot.getProvider());
            assertEquals(-6.9175, snapshot.getLatitude(), 1e-4);
            assertEquals(107.6191, snapshot.getLongitude(), 1e-4);
            assertEquals(12.5, snapshot.getAccuracyMeters(), 1e-4);
            assertEquals(1_700_000_000_000L, snapshot.getTimestampUtcMillis());
            assertEquals(768.25, snapshot.getAltitudeMeters(), 1e-4);
            assertEquals(1.75, snapshot.getSpeedMetersPerSecond(), 1e-4);
            assertEquals(42.0, snapshot.getBearingDegrees(), 1e-4);
            assertSame("the latest reading must be the delivered fix instance",
                    snapshot, session.latestReading());
        } finally {
            session.close();
        }
    }

    @Test
    public void fineGrantAndFineRequestPrefersGps() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);
            assertEquals(1, shadow.getLocationUpdateListeners(
                    LocationManager.GPS_PROVIDER).size());
            assertTrue(shadow.getLocationUpdateListeners(
                    LocationManager.NETWORK_PROVIDER).isEmpty());

            shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER,
                    51.5074, -0.1278, 6.0f, 42L, null, null, null));
            LocationSnapshot snapshot = pollReading(session);
            assertEquals(LocationManager.GPS_PROVIDER, snapshot.getProvider());
            assertEquals(51.5074, snapshot.getLatitude(), 1e-4);
            assertEquals(-0.1278, snapshot.getLongitude(), 1e-4);
            assertEquals(6.0, snapshot.getAccuracyMeters(), 1e-4);
        } finally {
            session.close();
        }
    }

    @Test
    public void fineRequestFallsBackToNetworkWhenGpsIsAbsent() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.removeProvider(LocationManager.GPS_PROVIDER);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);
            assertEquals(1, shadow.getLocationUpdateListeners(
                    LocationManager.NETWORK_PROVIDER).size());

            shadow.simulateLocation(fix(LocationManager.NETWORK_PROVIDER,
                    1.0, 2.0, 30.0f, 7L, null, null, null));
            LocationSnapshot snapshot = pollReading(session);
            assertEquals(LocationManager.NETWORK_PROVIDER, snapshot.getProvider());
        } finally {
            session.close();
        }
    }

    @Test
    public void coarseRequestUsesNetworkEvenWithFineGrant() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(LocationStreamRequest.create(
                    REQUESTED_INTERVAL_MILLIS, LocationStreamRequest.Accuracy.COARSE));
            awaitSingleListener(shadow);
            assertEquals(1, shadow.getLocationUpdateListeners(
                    LocationManager.NETWORK_PROVIDER).size());
            assertTrue(shadow.getLocationUpdateListeners(
                    LocationManager.GPS_PROVIDER).isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    public void reportsUnavailableWhenNoUsableProviderForTheGrant() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.removeProvider(LocationManager.GPS_PROVIDER);
        shadow.removeProvider(LocationManager.NETWORK_PROVIDER);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            assertEquals(LocationStreamSession.State.STOPPED, session.state());
            assertEquals(LocationStreamEvent.State.UNAVAILABLE, session.poll(0).getState());
            assertTrue(shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    public void reportsUnavailableWhenLocationIsDisabled() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(false);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            // Location master switch off: every provider is reported disabled,
            // so the session fails typed and fast instead of timing out.
            session.start(fineRequest());
            assertEquals(LocationStreamSession.State.STOPPED, session.state());
            assertEquals(LocationStreamEvent.State.UNAVAILABLE, session.poll(0).getState());
            assertTrue(shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    public void rejectsInvalidFixAsErrorAndKeepsStreaming() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);

            Location nonFinite = fix(LocationManager.GPS_PROVIDER,
                    Double.NaN, 2.0, 6.0f, 42L, null, null, null);
            shadow.simulateLocation(nonFinite);
            assertEquals(LocationStreamEvent.State.ERROR, session.poll(EVENT_POLL_MILLIS).getState());
            assertEquals("a rejected fix must not end the stream",
                    LocationStreamSession.State.STREAMING, session.state());
            assertNull("a rejected fix must never become the latest reading",
                    session.latestReading());

            // The shadow rejects simulated deliveries closer than the requested
            // minTime, so the second fix must carry a later elapsed time.
            Location valid = fix(LocationManager.GPS_PROVIDER,
                    1.0, 2.0, 6.0f, 43L, null, null, null);
            valid.setElapsedRealtimeNanos(nonFinite.getElapsedRealtimeNanos()
                    + REQUESTED_INTERVAL_MILLIS * 1_000_000L + 100_000_000L);
            shadow.simulateLocation(valid);
            LocationSnapshot snapshot = pollReading(session);
            assertEquals(1.0, snapshot.getLatitude(), 1e-4);
        } finally {
            session.close();
        }
    }

    @Test
    public void emitsTimeoutEventsWhileStreamingWhenNoFixArrives() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(SHORT_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);

            // Pull-based evaluation: a poll that ends past the fix-silence
            // window returns a TIMEOUT, and a later window emits another one.
            assertEquals(LocationStreamEvent.State.TIMEOUT,
                    session.poll(EVENT_POLL_MILLIS).getState());
            assertEquals("a timeout is not a terminal failure",
                    LocationStreamSession.State.STREAMING, session.state());
            assertEquals(LocationStreamEvent.State.TIMEOUT,
                    session.poll(EVENT_POLL_MILLIS).getState());
            assertNull(session.latestReading());
        } finally {
            session.close();
        }
    }

    @Test
    public void fixWithinTheSilenceWindowSuppressesTimeoutEvents() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(SUPPRESSION_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);
            // Deliver the fix mid-window; from then on the window restarts.
            Thread.sleep(SUPPRESSION_SILENCE_MILLIS / 2);
            shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER,
                    1.0, 2.0, 6.0f, 42L, null, null, null));
            pollReading(session);

            // Polls that end inside the window must not produce a TIMEOUT.
            assertNull(session.poll(SUPPRESSION_SILENCE_MILLIS / 4));
            assertNull(session.poll(SUPPRESSION_SILENCE_MILLIS / 4));
            // A poll that ends past the window does.
            assertEquals(LocationStreamEvent.State.TIMEOUT,
                    session.poll(SUPPRESSION_SILENCE_MILLIS).getState());
            assertEquals("a timeout must not end the stream",
                    LocationStreamSession.State.STREAMING, session.state());
        } finally {
            session.close();
        }
    }

    @Test
    public void stopUnregistersListenerAndPushesStoppedEventWithoutLaterReadings() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);

            session.stop();
            session.stop();
            assertEquals(LocationStreamSession.State.STOPPED, session.state());
            assertTrue("stop must unregister the platform listener",
                    shadow.getLocationUpdateListeners().isEmpty());
            assertEquals(LocationStreamEvent.State.STOPPED, session.poll(0).getState());
            assertNull("only one terminal event may be emitted", session.poll(0));

            // A fix delivered after stop must never produce a reading.
            shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER,
                    1.0, 2.0, 6.0f, 42L, null, null, null));
            assertNull(session.poll(100L));
        } finally {
            session.close();
        }
    }

    @Test
    public void providerDisableStopsSessionAsUnavailable() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);
            LocationListener listener = shadow.getLocationUpdateListeners().get(0);

            listener.onProviderDisabled(LocationManager.GPS_PROVIDER);

            assertEquals(LocationStreamSession.State.STOPPED, session.state());
            assertEquals(LocationStreamEvent.State.UNAVAILABLE, session.poll(0).getState());
            assertTrue("provider loss must unregister the platform listener",
                    shadow.getLocationUpdateListeners().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    public void sessionRestartsAfterStopWithAFreshBuffer() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            session.start(fineRequest());
            awaitSingleListener(shadow);
            shadow.simulateLocation(fix(LocationManager.GPS_PROVIDER,
                    1.0, 2.0, 6.0f, 42L, null, null, null));
            pollReading(session);
            session.stop();
            assertEquals(LocationStreamEvent.State.STOPPED, session.poll(0).getState());

            session.start(fineRequest());
            assertEquals(LocationStreamSession.State.STREAMING, session.state());
            assertNull("restart must discard events of the previous session",
                    session.poll(0));
            awaitSingleListener(shadow);

            // Elapsed time must advance past the requested minTime or the
            // shadow rejects the simulated delivery as "too fast".
            Location restartedFix = fix(LocationManager.GPS_PROVIDER,
                    3.0, 4.0, 6.0f, 43L, null, null, null);
            restartedFix.setElapsedRealtimeNanos(
                    REQUESTED_INTERVAL_MILLIS * 1_000_000L + 100_000_000L);
            shadow.simulateLocation(restartedFix);
            LocationSnapshot snapshot = pollReading(session);
            assertEquals(3.0, snapshot.getLatitude(), 1e-4);
            assertEquals(4.0, snapshot.getLongitude(), 1e-4);
        } finally {
            session.close();
        }
    }

    @Test
    public void closeStopsStreamingUnregistersAndIsIdempotent() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        session.start(fineRequest());
        awaitSingleListener(shadow);

        session.close();
        session.close();
        assertEquals(LocationStreamSession.State.CLOSED, session.state());
        assertTrue("close must unregister the platform listener",
                shadow.getLocationUpdateListeners().isEmpty());
        assertEquals(LocationStreamEvent.State.STOPPED, session.poll(0).getState());
        try {
            session.start(fineRequest());
            fail("start after close must fail");
        } catch (IllegalStateException expected) {
            // Terminal lifecycle, not an error path.
        }
    }

    @Test
    public void closeWithoutStartIsIdempotentAndStartAfterCloseThrows() {
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        assertEquals(LocationStreamSession.State.IDLE, session.state());
        session.close();
        session.close();
        assertEquals(LocationStreamSession.State.CLOSED, session.state());
        try {
            session.start(fineRequest());
            fail("start after close must fail");
        } catch (IllegalStateException expected) {
            // Terminal lifecycle.
        }
    }

    @Test
    public void startRejectsInvalidRequestsAndDoubleStart() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowLocationManager shadow = shadowOf(context);
        shadow.setLocationEnabled(true);
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        AndroidLocationStreamSession session = newSession(LONG_SILENCE_MILLIS,
                () -> LocationGrant.FINE);
        try {
            try {
                session.start(null);
                fail("null request must be rejected");
            } catch (IllegalArgumentException expected) {
                // Boundary validation.
            }
            try {
                session.start(LocationStreamRequest.create(
                        REQUESTED_INTERVAL_MILLIS, null));
                fail("request without accuracy must be rejected");
            } catch (IllegalArgumentException expected) {
                // Boundary validation.
            }
            session.start(fineRequest());
            try {
                session.start(fineRequest());
                fail("double start must be rejected");
            } catch (IllegalStateException expected) {
                // One stream per session.
            }
        } finally {
            session.close();
        }
    }

    private static LocationStreamRequest fineRequest() {
        return LocationStreamRequest.create(
                REQUESTED_INTERVAL_MILLIS, LocationStreamRequest.Accuracy.FINE);
    }

    private static AndroidLocationStreamSession newSession(long fixSilenceMillis,
                                                           LocationPermissionChecker checker) {
        return new AndroidLocationStreamSession(
                RuntimeEnvironment.getApplication(), fixSilenceMillis, checker, 8);
    }

    private static LocationSnapshot pollReading(AndroidLocationStreamSession session) {
        LocationStreamEvent event = session.poll(EVENT_POLL_MILLIS);
        if (event == null || event.getState() != LocationStreamEvent.State.READING) {
            throw new AssertionError("expected a reading event, got "
                    + (event == null ? "none" : event.getState()));
        }
        return event.getSnapshot();
    }

    private static ShadowLocationManager shadowOf(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(
                Context.LOCATION_SERVICE);
        if (manager == null) {
            throw new AssertionError("Robolectric must provide a LocationManager");
        }
        // Shadow.extract is the same generic accessor used by the one-shot
        // adapter test; Shadows.shadowOf would need FingerprintManager.
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

    /** Wait until the session registered exactly one listener. */
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
}
