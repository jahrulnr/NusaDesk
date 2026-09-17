package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wire-shape contract of the live media status: flat protocol fields, the
 * explicit state always present, the bounded error only in the failed state,
 * the RTSP/encoder metadata only in the running state, and never a file path
 * or secret.
 */
public class LiveMediaStatusTest {

    @Test
    public void stoppedCarriesOnlyTheExplicitState() {
        Map<String, Object> fields = LiveMediaStatus.stopped().responseFields();

        assertEquals(1, fields.size());
        assertEquals("stopped", fields.get("state"));
        assertFalse("a stopped status must not carry an error", fields.containsKey("error"));
        assertFalse("a stopped status must not carry an rtsp url", fields.containsKey("rtsp_url"));
    }

    @Test
    public void startingCarriesOnlyTheExplicitState() {
        Map<String, Object> fields = LiveMediaStatus.starting().responseFields();

        assertEquals(1, fields.size());
        assertEquals("starting", fields.get("state"));
    }

    @Test
    public void runningCarriesTheFlatStartContractFields() {
        LiveMediaStatus status = LiveMediaStatus.running(
                "rtsp://127.0.0.1:39871/", "h264", "aac", 1280, 720, 30, 2);
        Map<String, Object> fields = status.responseFields();

        assertEquals("running", fields.get("state"));
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
    public void failedCarriesTheBoundedErrorCode() {
        LiveMediaStatus status = LiveMediaStatus.failed(LiveMediaError.PERMISSION_REQUIRED);
        Map<String, Object> fields = status.responseFields();

        assertEquals(2, fields.size());
        assertEquals("failed", fields.get("state"));
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
        assertEquals("media-encoder-unavailable", LiveMediaError.ENCODER_UNAVAILABLE.code());
        assertEquals("media-start-failed", LiveMediaError.START_FAILED.code());
    }

    @Test
    public void runningRejectsBlankUrlAndNonPositiveClientLimit() {
        try {
            LiveMediaStatus.running("", "h264", "aac", 1280, 720, 30, 2);
            throw new AssertionError("blank rtsp url must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            LiveMediaStatus.running("rtsp://127.0.0.1:1/", "h264", "aac", 1280, 720, 30, 0);
            throw new AssertionError("zero client limit must be rejected");
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

        LiveMediaStatus failed = LiveMediaStatus.failed(LiveMediaError.BUSY);
        assertEquals(LiveMediaState.FAILED, failed.getState());
        assertEquals("media-busy", failed.getError());
        assertTrue(failed.toString().contains("media-busy"));
    }
}
