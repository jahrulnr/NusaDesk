package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wire-shape contract of the live media status: flat protocol fields, the
 * explicit state always present, the requested mode whenever a session mode is
 * known, the bounded error only in the failed state, the RTSP metadata only in
 * the running state, and per-mode track fields — a microphone-only session
 * must never report a video size and a camera-only session never an audio
 * codec. Never a file path or secret.
 */
public class LiveMediaStatusTest {

    @Test
    public void stoppedCarriesOnlyTheExplicitState() {
        Map<String, Object> fields = LiveMediaStatus.stopped().responseFields();

        assertEquals(1, fields.size());
        assertEquals("stopped", fields.get("state"));
        assertFalse("a stopped status must not carry an error", fields.containsKey("error"));
        assertFalse("a stopped status must not carry an rtsp url", fields.containsKey("rtsp_url"));
        assertFalse("a stopped status must not carry a mode", fields.containsKey("mode"));
    }

    @Test
    public void startingCarriesTheStateAndTheRequestedMode() {
        Map<String, Object> fields = LiveMediaStatus.starting(LiveMediaMode.CAMERA)
                .responseFields();

        assertEquals(2, fields.size());
        assertEquals("starting", fields.get("state"));
        assertEquals("camera", fields.get("mode"));
    }

    @Test
    public void runningBothCarriesVideoAndAudioFields() {
        LiveMediaStatus status = LiveMediaStatus.running(LiveMediaMode.BOTH,
                "rtsp://127.0.0.1:39871/", "h264", 1280, 720, 30, "aac", 2);
        Map<String, Object> fields = status.responseFields();

        assertEquals("running", fields.get("state"));
        assertEquals("both", fields.get("mode"));
        assertEquals("rtsp://127.0.0.1:39871/", fields.get("rtsp_url"));
        assertEquals("h264", fields.get("video_codec"));
        assertEquals("aac", fields.get("audio_codec"));
        assertEquals(1280L, fields.get("video_width"));
        assertEquals(720L, fields.get("video_height"));
        assertEquals(30L, fields.get("video_fps"));
        assertEquals(2L, fields.get("client_limit"));
        assertFalse("the running status must never carry a file path",
                fields.keySet().stream().anyMatch(key -> key.contains("path") || key.contains("file")));
        assertFalse("the running status must never carry a token",
                fields.keySet().stream().anyMatch(key -> key.contains("token")));
    }

    @Test
    public void runningCameraOnlyOmitsEveryAudioField() {
        Map<String, Object> fields = LiveMediaStatus.running(LiveMediaMode.CAMERA,
                "rtsp://127.0.0.1:39871/", "h264", 1280, 720, 30, null, 2)
                .responseFields();

        assertEquals("camera", fields.get("mode"));
        assertEquals("h264", fields.get("video_codec"));
        assertFalse("a camera-only session has no audio codec",
                fields.containsKey("audio_codec"));
    }

    @Test
    public void runningMicrophoneOnlyOmitsEveryVideoField() {
        Map<String, Object> fields = LiveMediaStatus.running(LiveMediaMode.MICROPHONE,
                "rtsp://127.0.0.1:39871/", null, 0, 0, 0, "aac", 2)
                .responseFields();

        assertEquals("microphone", fields.get("mode"));
        assertEquals("aac", fields.get("audio_codec"));
        assertFalse("a microphone-only session has no video codec",
                fields.containsKey("video_codec"));
        assertFalse("a microphone-only session has no video size",
                fields.containsKey("video_width") || fields.containsKey("video_height"));
        assertFalse("a microphone-only session has no frame rate",
                fields.containsKey("video_fps"));
    }

    @Test
    public void failedCarriesTheBoundedErrorCodeAndRequestedMode() {
        Map<String, Object> fields = LiveMediaStatus
                .failed(LiveMediaMode.MICROPHONE, LiveMediaError.PERMISSION_REQUIRED)
                .responseFields();

        assertEquals(3, fields.size());
        assertEquals("failed", fields.get("state"));
        assertEquals("microphone", fields.get("mode"));
        assertEquals("media-permission-required", fields.get("error"));
        assertFalse("a failed status must not carry an rtsp url", fields.containsKey("rtsp_url"));
    }

    @Test
    public void everyTypedErrorMapsToItsFixedWireCode() {
        assertEquals("media-permission-required", LiveMediaError.PERMISSION_REQUIRED.code());
        assertEquals("media-permission-denied", LiveMediaError.PERMISSION_DENIED.code());
        assertEquals("media-foreground-required", LiveMediaError.FOREGROUND_REQUIRED.code());
        assertEquals("media-unavailable", LiveMediaError.UNAVAILABLE.code());
        assertEquals("media-busy", LiveMediaError.BUSY.code());
        assertEquals("media-mode-conflict", LiveMediaError.MODE_CONFLICT.code());
        assertEquals("media-encoder-unavailable", LiveMediaError.ENCODER_UNAVAILABLE.code());
        assertEquals("media-start-failed", LiveMediaError.START_FAILED.code());
    }

    @Test
    public void runningRejectsBlankUrlAndNonPositiveClientLimit() {
        try {
            LiveMediaStatus.running(LiveMediaMode.BOTH, "", "h264", 1280, 720, 30, "aac", 2);
            throw new AssertionError("blank rtsp url must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            LiveMediaStatus.running(LiveMediaMode.BOTH, "rtsp://127.0.0.1:1/",
                    "h264", 1280, 720, 30, "aac", 0);
            throw new AssertionError("zero client limit must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void runningRejectsTrackMetadataThatContradictsTheMode() {
        try {
            // A video mode without video metadata cannot describe its stream.
            LiveMediaStatus.running(LiveMediaMode.CAMERA, "rtsp://127.0.0.1:1/",
                    null, 0, 0, 0, null, 2);
            throw new AssertionError("video mode needs video metadata");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            // A microphone-only session must not smuggle in a video track.
            LiveMediaStatus.running(LiveMediaMode.MICROPHONE, "rtsp://127.0.0.1:1/",
                    "h264", 1280, 720, 30, "aac", 2);
            throw new AssertionError("audio-only mode must not carry video metadata");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            // A camera-only session must not smuggle in an audio track.
            LiveMediaStatus.running(LiveMediaMode.CAMERA, "rtsp://127.0.0.1:1/",
                    "h264", 1280, 720, 30, "aac", 2);
            throw new AssertionError("video-only mode must not carry an audio codec");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void valueSemanticsForStateComparisons() {
        LiveMediaStatus stopped = LiveMediaStatus.stopped();
        assertEquals(stopped, LiveMediaStatus.stopped());
        assertEquals(stopped.hashCode(), LiveMediaStatus.stopped().hashCode());
        assertEquals(LiveMediaState.STOPPED, stopped.getState());
        assertNull(stopped.getError());
        assertNull(stopped.getRtspUrl());
        assertNull(stopped.getMode());

        LiveMediaStatus failed = LiveMediaStatus.failed(LiveMediaError.BUSY);
        assertEquals(LiveMediaState.FAILED, failed.getState());
        assertEquals("media-busy", failed.getError());
        assertTrue(failed.toString().contains("media-busy"));

        LiveMediaStatus camera = LiveMediaStatus.starting(LiveMediaMode.CAMERA);
        assertEquals(LiveMediaMode.CAMERA, camera.getMode());
        assertTrue(camera.toString().contains("camera"));
    }
}
