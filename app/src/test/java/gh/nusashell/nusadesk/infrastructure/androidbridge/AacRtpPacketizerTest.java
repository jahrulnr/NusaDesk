package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AAC-LC RTP packetization (RFC 3640 mpeg4-generic, AAC-hbr): one AU header
 * per frame with the 13-bit AU size, marker always set, and the bounded
 * frame size rejected.
 */
public class AacRtpPacketizerTest {

    private static final long SSRC = 0xDEADBEEFL;

    @Test
    public void wrapsOneAacFrameWithAuHeaderAndMarker() {
        byte[] frame = new byte[100];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) i;
        }
        RtpPacket packet = AacRtpPacketizer.packetize(frame, 44_100L, 7, SSRC);

        assertEquals(7, packet.getSequence());
        assertEquals(44_100L, packet.getTimestamp());
        assertTrue(packet.isMarker());
        byte[] bytes = packet.getBytes();
        assertEquals(12 + 2 + 2 + 100, bytes.length);
        assertEquals((byte) 0x80, bytes[0]);
        assertEquals((byte) (0x80 | 97), bytes[1]); // M + PT 97
        // AU-headers-length is 16 bits, followed by size 100 << 3 = 0x0320.
        assertEquals(0x00, bytes[12] & 0xFF);
        assertEquals(0x10, bytes[13] & 0xFF);
        assertEquals(0x03, bytes[14] & 0xFF);
        assertEquals(0x20, bytes[15] & 0xFF);
        assertArrayEquals(frame, Arrays.copyOfRange(bytes, 16, bytes.length));
    }

    @Test
    public void auHeaderBytesMatchTheShiftedSize() {
        assertArrayEquals(new byte[] {0x03, 0x20}, AacRtpPacketizer.auHeaderBytes(100));
        assertArrayEquals(new byte[] {0x00, 0x08}, AacRtpPacketizer.auHeaderBytes(1));
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xF8},
                AacRtpPacketizer.auHeaderBytes(8191));
    }

    @Test
    public void rejectsFramesBeyondThe13BitAuSize() {
        try {
            AacRtpPacketizer.packetize(new byte[8192], 0L, 0, SSRC);
            throw new AssertionError("frames above 8191 bytes must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            AacRtpPacketizer.packetize(new byte[0], 0L, 0, SSRC);
            throw new AssertionError("empty frames must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
