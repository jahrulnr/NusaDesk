package gh.nusashell.nusadesk.infrastructure.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioDeviceInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidCapabilityProtocol;
import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidCapabilityRequestHandler;
import gh.nusashell.nusadesk.infrastructure.androidbridge.BatteryStatus;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CalendarEventSnapshot;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CalendarWriteRequest;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CalendarWriteResult;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CalendarWriter;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CallLogSnapshot;
import gh.nusashell.nusadesk.infrastructure.androidbridge.ContactsSnapshot;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaMode;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaStatus;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LocationSnapshot;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LocationStreamEvent;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LocationStreamRequest;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LocationStreamSession;
import gh.nusashell.nusadesk.infrastructure.androidbridge.LiveMediaController;
import gh.nusashell.nusadesk.infrastructure.androidbridge.MediaPlayerModule;
import gh.nusashell.nusadesk.infrastructure.androidbridge.SensorReading;
import gh.nusashell.nusadesk.infrastructure.androidbridge.SmsSnapshot;
import gh.nusashell.nusadesk.infrastructure.androidbridge.TelephonyCellInfo;
import gh.nusashell.nusadesk.infrastructure.androidbridge.TelephonyDeviceInfo;
import gh.nusashell.nusadesk.infrastructure.androidbridge.UsbPassThroughSource;

/**
 * Per-player audio routing slice ({@code mediaplayer.outputs} /
 * {@code mediaplayer.route} / {@code mediaplayer.route.clear} plus the
 * additive {@code mediaplayer.info} fields) on the JVM.
 *
 * <p>{@code MediaPlayer.setPreferredDevice} and the {@code AudioDeviceInfo}
 * device list are replaced by narrow seams: {@link FakeOutputs} supplies the
 * fresh output snapshot and {@link FakeRouting} records the routing calls a
 * real player would receive. What this test proves is the route policy —
 * fresh-list validation, sink enforcement, the process-local pending choice,
 * the stale-route refusal at track begin, and the wire shapes — not the
 * platform apply itself, which needs a device.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MediaPlaybackRoutingTest {

    private static final Path TRACK = Paths.get("/tmp/track.mp3");

    private Context app;
    private FakeOutputs outputs;
    private FakeRouting routing;
    private MediaPlayerModule module;
    private AndroidCapabilityRequestHandler handler;
    private ServiceController<MediaPlaybackService> service;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        MediaPlaybackService.resetForTest(app);
        // ShadowMediaPlayer.prepare() fails closed unless the source has a
        // registered MediaInfo; registering one is the shadow's own fixture
        // for a playable track, no media bytes are decoded.
        ShadowMediaPlayer.addMediaInfo(
                DataSource.toDataSource(TRACK.toAbsolutePath().toString()),
                new ShadowMediaPlayer.MediaInfo());
        outputs = new FakeOutputs();
        routing = new FakeRouting();
        MediaPlaybackService.setAudioRoutingForTest(outputs, player -> routing);
        module = new MediaPlayerModule(app);
        handler = mediaHandler(module);
    }

    @After
    public void tearDown() {
        if (service != null) {
            try {
                service.destroy();
            } catch (RuntimeException ignored) {
                // Best effort teardown.
            }
            service = null;
        }
        MediaPlaybackService.resetForTest(app);
    }

    // --- mediaplayer.outputs -------------------------------------------------

    @Test
    public void outputsAreSortedBoundedAndSanitized() {
        // 34 devices, ids descending, one overlong name with a control char.
        for (int id = 34; id >= 1; id--) {
            String name = id == 2
                    ? "bad\u0007" + "x".repeat(200) : "device-" + id;
            outputs.devices.add(new MediaPlaybackService.AudioOutput(
                    id, "built_in_speaker", name, true, null));
        }

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.outputs"));

        assertTrue(response.isOk());
        assertEquals(32L, response.getFields().get("count"));
        assertEquals(Boolean.TRUE, response.getFields().get("truncated"));
        String json = (String) response.getFields().get("outputs_json");
        assertNotNull(json);
        // Sorted ascending by id and capped at 32 rows.
        assertTrue(json.startsWith("[{\"device_id\":1,"));
        assertTrue(json.contains("{\"device_id\":32,"));
        assertFalse(json.contains("{\"device_id\":33,"));
        assertEquals(32, countRows(json));
        // The name is bounded and the control character is stripped.
        assertTrue(json.contains("\"name\":\"bad "));
        assertFalse(json.contains("\u0007"));
    }

    @Test
    public void outputsEmptyListHasNoTruncatedFlag() {
        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.outputs"));

        assertTrue(response.isOk());
        assertEquals("[]", response.getFields().get("outputs_json"));
        assertEquals(0L, response.getFields().get("count"));
        assertFalse(response.getFields().containsKey("truncated"));
    }

    @Test
    public void outputsFlagsNonSinkRowsHonestly() {
        outputs.devices.add(new MediaPlaybackService.AudioOutput(
                9, "built_in_mic", "mic", false, null));

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.outputs"));

        assertTrue(response.isOk());
        String json = (String) response.getFields().get("outputs_json");
        assertTrue(json.contains("\"is_sink\":false"));
    }

    // --- mediaplayer.route validation -----------------------------------------

    @Test
    public void routeUnknownDeviceIsTypedError() {
        outputs.devices.add(output(3, true));

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":9}"));

        assertFalse(response.isOk());
        assertEquals("mediaplayer-route-device-unknown", response.getError());
    }

    @Test
    public void routeNonSinkDeviceIsTypedError() {
        outputs.devices.add(output(4, false));

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":4}"));

        assertFalse(response.isOk());
        assertEquals("mediaplayer-route-device-not-output", response.getError());
    }

    @Test
    public void routeParamSchemaIsEnforced() {
        String[] bad = {
                "{}",                                  // missing device_id
                "{\"device_id\":\"7\"}",               // string, not integer
                "{\"device_id\":-1}",                  // out of range
                "{\"device_id\":7,\"extra\":1}",       // undeclared key
        };
        for (String params : bad) {
            AndroidCapabilityProtocol.Response response = handler.handle(
                    request("1", "mediaplayer.route", params));
            assertFalse(params, response.isOk());
            assertEquals(params, "invalid-argument", response.getError());
        }
    }

    @Test
    public void noParamMethodsRejectParams() {
        AndroidCapabilityProtocol.Response outputsResponse = handler.handle(
                request("1", "mediaplayer.outputs", "{\"x\":1}"));
        assertFalse(outputsResponse.isOk());
        assertEquals("unsupported-parameter", outputsResponse.getError());

        AndroidCapabilityProtocol.Response clearResponse = handler.handle(
                request("2", "mediaplayer.route.clear", "{\"x\":1}"));
        assertFalse(clearResponse.isOk());
        assertEquals("unsupported-parameter", clearResponse.getError());
    }

    // --- route semantics -------------------------------------------------------

    @Test
    public void routeWithNoPlayerIsPendingThenAppliesOnCreation() {
        outputs.devices.add(output(7, true));

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":7}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.FALSE, response.getFields().get("applied"));
        assertEquals(7L, response.getFields().get("device_id"));
        assertEquals("speaker-7", response.getFields().get("name"));
        assertEquals("bluetooth_a2dp", response.getFields().get("type"));
        assertNull("no player exists yet; nothing must be applied",
                routing.preferred);

        // Player creation applies the remembered choice while idle.
        MediaPlaybackService.PlaybackResult played =
                MediaPlaybackService.play(TRACK, "track");
        assertEquals(MediaPlaybackService.Outcome.PLAYED, played.getOutcome());
        assertEquals(Integer.valueOf(7), routing.preferred);
    }

    @Test
    public void routeOnIdlePlayerStaysPendingUntilNextTrackIsPrepared() {
        outputs.devices.add(output(7, true));
        assertEquals(MediaPlaybackService.Outcome.PLAYED,
                MediaPlaybackService.play(TRACK, "first").getOutcome());
        assertEquals(MediaPlaybackService.Outcome.STOPPED,
                MediaPlaybackService.stopTrack().getOutcome());

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":7}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.FALSE, response.getFields().get("applied"));
        assertNull(routing.preferred);
        assertEquals(MediaPlaybackService.Outcome.PLAYED,
                MediaPlaybackService.play(TRACK, "next").getOutcome());
        assertEquals(Integer.valueOf(7), routing.preferred);
    }

    @Test
    public void routeAppliesToCurrentPlayer() {
        outputs.devices.add(output(7, true));
        assertEquals(MediaPlaybackService.Outcome.PLAYED,
                MediaPlaybackService.play(TRACK, "track").getOutcome());
        int callsBefore = routing.applyCalls;

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":7}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("applied"));
        assertEquals(Integer.valueOf(7), routing.preferred);
        assertEquals(callsBefore + 1, routing.applyCalls);
    }

    @Test
    public void routeRefusalIsTypedFailureAndPendingSticks() {
        outputs.devices.add(output(7, true));
        routing.refuse = true;
        MediaPlaybackService.play(TRACK, "track");

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":7}"));

        assertFalse(response.isOk());
        assertEquals("mediaplayer-route-failed", response.getError());
        // The choice stays pending, so the next play surfaces the failure
        // again instead of silently playing on the default device.
        assertEquals(MediaPlaybackService.Outcome.ROUTE_FAILED,
                MediaPlaybackService.play(TRACK, "other").getOutcome());
    }

    @Test
    public void stalePendingRouteFailsThePlay() {
        outputs.devices.add(output(7, true));
        handler.handle(request("1", "mediaplayer.route", "{\"device_id\":7}"));
        // The device disappears before any track starts.
        outputs.devices.clear();

        assertEquals(MediaPlaybackService.Outcome.ROUTE_FAILED,
                MediaPlaybackService.play(TRACK, "track").getOutcome());
        assertEquals(MediaPlaybackService.Outcome.NO_TRACK,
                MediaPlaybackService.info().getOutcome());
    }

    @Test
    public void stalePendingRouteFailsModulePlayWithTypedError() throws Exception {
        outputs.devices.add(output(7, true));
        handler.handle(request("1", "mediaplayer.route", "{\"device_id\":7}"));
        outputs.devices.clear();
        startForegroundService();
        Path guestFile = stageGuestFile();

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("1", "mediaplayer.play",
                        "{\"path\":\"/tmp/track.mp3\"}"));

        assertFalse(response.isOk());
        assertEquals("mediaplayer-route-failed", response.getError());
        assertNotNull(guestFile);
    }

    @Test
    public void pendingRouteIsDroppedWhenCapabilitySessionCloses() {
        outputs.devices.add(output(7, true));
        AndroidCapabilityProtocol.Response selected = handler.handle(
                request("1", "mediaplayer.route", "{\"device_id\":7}"));
        assertTrue(selected.isOk());
        assertEquals(Integer.valueOf(7),
                MediaPlaybackService.info().getPreferredOutputId());

        MediaPlaybackService.releaseAll(app);

        assertNull(MediaPlaybackService.info().getPreferredOutputId());
    }

    @Test
    public void pendingRouteSurvivesTrackReplacement() {
        outputs.devices.add(output(7, true));
        handler.handle(request("1", "mediaplayer.route", "{\"device_id\":7}"));
        assertEquals(MediaPlaybackService.Outcome.PLAYED,
                MediaPlaybackService.play(TRACK, "one").getOutcome());
        assertEquals(Integer.valueOf(7), routing.preferred);

        // A new track resets the player; the pending route is reapplied.
        routing.preferred = null;
        int calls = routing.applyCalls;
        assertEquals(MediaPlaybackService.Outcome.PLAYED,
                MediaPlaybackService.play(TRACK, "two").getOutcome());
        assertEquals(Integer.valueOf(7), routing.preferred);
        assertTrue(routing.applyCalls > calls);
    }

    // --- mediaplayer.route.clear -----------------------------------------------

    @Test
    public void clearIsIdempotentAndReportsState() {
        // Nothing pending, no player: nothing to clear.
        AndroidCapabilityProtocol.Response none = handler.handle(
                request("1", "mediaplayer.route.clear"));
        assertTrue(none.isOk());
        assertEquals(Boolean.FALSE, none.getFields().get("cleared"));
        assertEquals(Boolean.FALSE, none.getFields().get("applied"));

        outputs.devices.add(output(7, true));
        handler.handle(request("2", "mediaplayer.route", "{\"device_id\":7}"));

        AndroidCapabilityProtocol.Response pending = handler.handle(
                request("3", "mediaplayer.route.clear"));
        assertEquals(Boolean.TRUE, pending.getFields().get("cleared"));
        // No live player to apply the clear to.
        assertEquals(Boolean.FALSE, pending.getFields().get("applied"));

        AndroidCapabilityProtocol.Response again = handler.handle(
                request("4", "mediaplayer.route.clear"));
        assertEquals(Boolean.FALSE, again.getFields().get("cleared"));
    }

    @Test
    public void clearOnLivePlayerDropsThePreferredDevice() {
        outputs.devices.add(output(7, true));
        MediaPlaybackService.play(TRACK, "track");
        handler.handle(request("1", "mediaplayer.route", "{\"device_id\":7}"));
        assertEquals(Integer.valueOf(7), routing.preferred);

        AndroidCapabilityProtocol.Response response = handler.handle(
                request("2", "mediaplayer.route.clear"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("cleared"));
        assertEquals(Boolean.TRUE, response.getFields().get("applied"));
        assertNull(routing.preferred);

        // The next play must not resurrect the cleared route.
        int calls = routing.applyCalls;
        MediaPlaybackService.play(TRACK, "again");
        assertEquals(calls, routing.applyCalls);
    }

    // --- mediaplayer.info -------------------------------------------------------

    @Test
    public void infoCarriesAdditiveRouteFields() {
        outputs.devices.add(output(7, true));
        handler.handle(request("1", "mediaplayer.route", "{\"device_id\":7}"));

        // Pending route is visible even with no track loaded.
        AndroidCapabilityProtocol.Response idle = handler.handle(
                request("2", "mediaplayer.info"));
        assertTrue(idle.isOk());
        assertEquals("No track currently!", idle.getFields().get("message"));
        assertEquals(7L, idle.getFields().get("preferred_output_id"));
        assertFalse(idle.getFields().containsKey("routed_output_id"));

        MediaPlaybackService.play(TRACK, "track");
        routing.routed = 7;
        AndroidCapabilityProtocol.Response playing = handler.handle(
                request("3", "mediaplayer.info"));
        assertTrue(playing.isOk());
        assertEquals("playing", playing.getFields().get("state"));
        assertEquals(7L, playing.getFields().get("preferred_output_id"));
        assertEquals(7L, playing.getFields().get("routed_output_id"));

        // A platform that reports no routed device must not be guessed.
        routing.routed = null;
        AndroidCapabilityProtocol.Response unrouted = handler.handle(
                request("4", "mediaplayer.info"));
        assertFalse(unrouted.getFields().containsKey("routed_output_id"));
    }

    // --- misc -------------------------------------------------------------------

    @Test
    public void playWithoutRouteIsUnchanged() {
        assertEquals(MediaPlaybackService.Outcome.PLAYED,
                MediaPlaybackService.play(TRACK, "track").getOutcome());
        assertEquals(0, routing.applyCalls);
        assertEquals(MediaPlaybackService.Outcome.PAUSED,
                MediaPlaybackService.pause().getOutcome());
        assertEquals(MediaPlaybackService.Outcome.STOPPED,
                MediaPlaybackService.stopTrack().getOutcome());
    }

    @Test
    public void deviceTypeNamesAreStable() {
        assertEquals("bluetooth_a2dp",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertEquals("bluetooth_sco",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertEquals("ble_headset",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_BLE_HEADSET));
        assertEquals("ble_speaker",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_BLE_SPEAKER));
        assertEquals("built_in_speaker",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertEquals("wired_headset",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_WIRED_HEADSET));
        assertEquals("usb_device",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_USB_DEVICE));
        assertEquals("unknown_999", MediaPlaybackService.deviceTypeName(999));
        assertEquals("unknown_0",
                MediaPlaybackService.deviceTypeName(AudioDeviceInfo.TYPE_UNKNOWN));
    }

    @Test
    public void bridgeInfoAdvertisesTheRoutingMethods() {
        AndroidCapabilityProtocol.Response info = handler.handle(
                request("1", "bridge.info"));
        assertTrue(info.isOk());
        String capabilities = (String) info.getFields().get("capabilities");
        assertTrue(capabilities.contains("mediaplayer.outputs"));
        assertTrue(capabilities.contains("mediaplayer.route"));
        assertTrue(capabilities.contains("mediaplayer.route.clear"));
        assertTrue(module.parameterMethods().contains("mediaplayer.route"));
        assertFalse(module.parameterMethods().contains("mediaplayer.outputs"));
    }

    // --- fixtures -----------------------------------------------------------------

    private static MediaPlaybackService.AudioOutput output(int id, boolean sink) {
        return new MediaPlaybackService.AudioOutput(
                id, "bluetooth_a2dp", "speaker-" + id, sink, null);
    }

    private static int countRows(String json) {
        int rows = 0;
        for (int i = json.indexOf("{\"device_id\""); i >= 0;
             i = json.indexOf("{\"device_id\"", i + 1)) {
            rows++;
        }
        return rows;
    }

    /**
     * A real service start so {@code ensureForeground} passes: the shadowed
     * service promotes itself, which flips the static foreground flag the
     * module gate waits on.
     */
    private void startForegroundService() {
        service = Robolectric.buildService(MediaPlaybackService.class).create();
        service.startCommand(0, 1);
    }

    /** A regular file at the host location guest {@code /tmp} resolves to. */
    private Path stageGuestFile() throws Exception {
        Path rootfs = app.getFilesDir().toPath()
                .resolve("linux-wrapper/runtimes/ubuntu-base-arm64/active");
        Path file = rootfs.resolve("tmp/track.mp3");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {0});
        return file;
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"right-token\","
                        + "\"method\":\"" + method + "\"}");
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                             String paramsJson) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"right-token\","
                        + "\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
    }

    /** Scriptable fresh output snapshot. */
    private static final class FakeOutputs
            implements MediaPlaybackService.AudioOutputProvider {
        final List<MediaPlaybackService.AudioOutput> devices = new ArrayList<>();

        @Override
        public List<MediaPlaybackService.AudioOutput> outputs() {
            return devices;
        }
    }

    /** Records the routing calls the real player would receive. */
    private static final class FakeRouting
            implements MediaPlaybackService.AudioRoutingPlayer {
        Integer preferred;
        Integer routed;
        boolean refuse;
        int applyCalls;

        @Override
        public boolean setPreferredOutput(MediaPlaybackService.AudioOutput output) {
            applyCalls++;
            if (refuse) {
                return false;
            }
            preferred = output == null ? null : output.getId();
            return true;
        }

        @Override
        public Integer preferredOutputId() {
            return preferred;
        }

        @Override
        public Integer routedOutputId() {
            return routed;
        }
    }

    private static final class OffStream implements LocationStreamSession {
        @Override
        public void start(LocationStreamRequest request) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }

        @Override
        public State state() {
            return State.IDLE;
        }

        @Override
        public LocationStreamEvent poll(long timeoutMillis) {
            return null;
        }

        @Override
        public LocationSnapshot latestReading() {
            return null;
        }
    }

    private static final class OffMedia implements LiveMediaController {
        @Override
        public LiveMediaStatus start(LiveMediaMode mode) {
            return LiveMediaStatus.stopped();
        }

        @Override
        public LiveMediaStatus status() {
            return LiveMediaStatus.stopped();
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }
    }

    private static AndroidCapabilityRequestHandler mediaHandler(MediaPlayerModule media) {
        return new AndroidCapabilityRequestHandler("right-token",
                () -> new BatteryStatus(false, 50, "unplugged", "good",
                        "unknown", false, 0, 0L, 0L, 0L, 0L),
                kind -> SensorReading.unavailable(kind.getName()),
                LocationSnapshot::unavailable,
                query -> ContactsSnapshot.unavailable(),
                query -> CallLogSnapshot.unavailable(),
                query -> SmsSnapshot.unavailable(),
                TelephonyDeviceInfo::unavailable,
                TelephonyCellInfo::unavailable,
                new OffStream(), new OffMedia(),
                query -> CalendarEventSnapshot.reading(Collections.emptyList(), false),
                new CalendarWriter() {
                    @Override
                    public CalendarWriteResult insert(CalendarWriteRequest request) {
                        return CalendarWriteResult.ok(0L);
                    }

                    @Override
                    public CalendarWriteResult update(CalendarWriteRequest request) {
                        return CalendarWriteResult.ok(0L);
                    }

                    @Override
                    public CalendarWriteResult delete(CalendarWriteRequest request) {
                        return CalendarWriteResult.ok(0L);
                    }
                },
                new UsbPassThroughSource.Empty(),
                List.of(media));
    }
}
