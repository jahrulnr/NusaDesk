package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Fixed host-owned live media defaults: the largest safe size at or below
 * 1280x720, the bounded client cap, and the deterministic AAC config.
 */
public class LiveMediaDefaultsTest {

    @Test
    public void picksTheLargestSafeSizeAtOrBelow720p() {
        LiveMediaDefaults.Resolution picked = LiveMediaDefaults.pickResolution(Arrays.asList(
                new LiveMediaDefaults.Resolution(1920, 1080),
                new LiveMediaDefaults.Resolution(1280, 720),
                new LiveMediaDefaults.Resolution(640, 480),
                new LiveMediaDefaults.Resolution(320, 240)));

        assertEquals(new LiveMediaDefaults.Resolution(1280, 720), picked);
    }

    @Test
    public void rejectsOversizedCandidates() {
        LiveMediaDefaults.Resolution picked = LiveMediaDefaults.pickResolution(Collections.singletonList(
                new LiveMediaDefaults.Resolution(1920, 1080)));

        assertNull("no candidate at or below 1280x720", picked);
        assertNull("null input yields no resolution", LiveMediaDefaults.pickResolution(null));
    }

    @Test
    public void picksByAreaThenPrefers16By9OnATie() {
        // 1280x544 has a larger area than 960x720 and both fit the bound.
        LiveMediaDefaults.Resolution byArea = LiveMediaDefaults.pickResolution(Arrays.asList(
                new LiveMediaDefaults.Resolution(960, 720),
                new LiveMediaDefaults.Resolution(1280, 544)));
        assertEquals(new LiveMediaDefaults.Resolution(1280, 544), byArea);

        // 1280x720 and 960x960 share the area 921600; 16:9 wins.
        LiveMediaDefaults.Resolution byAspect = LiveMediaDefaults.pickResolution(Arrays.asList(
                new LiveMediaDefaults.Resolution(960, 960),
                new LiveMediaDefaults.Resolution(1280, 720)));
        assertEquals(new LiveMediaDefaults.Resolution(1280, 720), byAspect);
    }

    @Test
    public void fixedStreamDefaultsMatchTheContract() {
        assertEquals(1280, LiveMediaDefaults.TARGET_WIDTH);
        assertEquals(720, LiveMediaDefaults.TARGET_HEIGHT);
        assertEquals(30, LiveMediaDefaults.TARGET_FPS);
        assertEquals(2_000_000, LiveMediaDefaults.VIDEO_BITRATE_BPS);
        assertEquals("h264", LiveMediaDefaults.VIDEO_CODEC);
        assertEquals(44_100, LiveMediaDefaults.AUDIO_SAMPLE_RATE_HZ);
        assertEquals(1, LiveMediaDefaults.AUDIO_CHANNELS);
        assertEquals(64_000, LiveMediaDefaults.AUDIO_BITRATE_BPS);
        assertEquals("aac", LiveMediaDefaults.AUDIO_CODEC);
        assertEquals(2, LiveMediaDefaults.CLIENT_LIMIT);
        assertEquals("/", LiveMediaDefaults.RTSP_PATH);
        assertEquals(1200, LiveMediaDefaults.RTP_MAX_PACKET_BYTES);
        assertEquals(1, LiveMediaDefaults.CAMERA_FACING);
    }

    @Test
    public void deterministicAacConfigForAacLc44100Mono() {
        assertEquals("1210", LiveMediaDefaults.audioSpecificConfigHex());
        assertEquals(2, LiveMediaDefaults.audioSpecificConfigBytes().length);
        assertEquals((byte) 0x12, LiveMediaDefaults.audioSpecificConfigBytes()[0]);
        assertEquals((byte) 0x10, LiveMediaDefaults.audioSpecificConfigBytes()[1]);
    }
}
