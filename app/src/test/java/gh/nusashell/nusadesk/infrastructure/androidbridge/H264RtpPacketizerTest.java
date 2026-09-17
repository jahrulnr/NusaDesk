package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * H.264 RTP packetization (RFC 6184): Annex-B splitting, NAL typing, the
 * SDP profile-level-id, single-NAL packets, and FU-A fragmentation with
 * sequence wrapping.
 */
public class H264RtpPacketizerTest {

    private static final long SSRC = 0x12345678L;

    @Test
    public void splitsAnnexBIntoNalusWithBothStartCodeWidths() {
        byte[] buffer = concat(
                new byte[] {0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F},
                new byte[] {0, 0, 1, 0x68, (byte) 0xCE, 0x3C},
                new byte[] {0, 0, 0, 1, 0x65, 0x11, 0x22});

        List<byte[]> nalus = H264RtpPacketizer.splitAnnexB(buffer);

        assertEquals(3, nalus.size());
        assertArrayEquals(new byte[] {0x67, 0x42, 0x00, 0x1F}, nalus.get(0));
        assertArrayEquals(new byte[] {0x68, (byte) 0xCE, 0x3C}, nalus.get(1));
        assertArrayEquals(new byte[] {0x65, 0x11, 0x22}, nalus.get(2));
    }

    @Test
    public void treatsStartCodeLessBufferAsOneNalAndEmptyAsNone() {
        byte[] raw = {0x67, 0x42, 0x00};
        List<byte[]> nalus = H264RtpPacketizer.splitAnnexB(raw);
        assertEquals(1, nalus.size());
        assertArrayEquals(raw, nalus.get(0));
        assertTrue(H264RtpPacketizer.splitAnnexB(new byte[0]).isEmpty());
        assertTrue(H264RtpPacketizer.splitAnnexB(null).isEmpty());
    }

    @Test
    public void classifiesNalTypesAndIdr() {
        assertTrue(H264RtpPacketizer.isSps(new byte[] {0x67, 1, 2, 3}));
        assertTrue(H264RtpPacketizer.isIdr(new byte[] {0x65, 1, 2, 3}));
        assertFalse(H264RtpPacketizer.isIdr(new byte[] {0x41, 1, 2, 3}));
        assertFalse(H264RtpPacketizer.isSps(new byte[] {0x41, 1, 2, 3}));
        assertEquals(7, H264RtpPacketizer.nalType(new byte[] {0x67, 0}));
        assertEquals(5, H264RtpPacketizer.nalType(new byte[] {0x65, 0}));
        assertEquals(8, H264RtpPacketizer.nalType(new byte[] {0x68, 0}));
    }

    @Test
    public void derivesProfileLevelIdFromTheSps() {
        // profile_idc 0x42, constraint bytes 0x00, level_idc 0x1F.
        byte[] sps = {0x67, 0x42, 0x00, 0x1F, (byte) 0xEA};
        assertEquals("42001F", H264RtpPacketizer.profileLevelId(sps));
    }

    @Test
    public void packetizesSmallNalAsOneMarkerPacket() {
        byte[] nalu = {0x65, 1, 2, 3, 4, 5};
        List<RtpPacket> packets = H264RtpPacketizer.packetize(
                nalu, 90_000L, 100, SSRC, 1200);

        assertEquals(1, packets.size());
        RtpPacket packet = packets.get(0);
        assertEquals(100, packet.getSequence());
        assertEquals(90_000L, packet.getTimestamp());
        assertTrue(packet.isMarker());
        assertEquals(12 + nalu.length, packet.getBytes().length);
        assertEquals((byte) 0x80, packet.getBytes()[0]); // V=2
        assertEquals((byte) (0x80 | 96), packet.getBytes()[1]); // M + PT 96
        assertEquals(0x12, packet.getBytes()[8] & 0xFF); // SSRC
        assertEquals(0x34, packet.getBytes()[9] & 0xFF);
        assertArrayEquals(nalu, Arrays.copyOfRange(packet.getBytes(), 12, packet.getBytes().length));
    }

    @Test
    public void fragmentsLargeNalWithFuAAndPreservesNriAndType() {
        byte[] nalu = new byte[3000];
        nalu[0] = 0x65; // NRI 3, type 5 (IDR)
        for (int i = 1; i < nalu.length; i++) {
            nalu[i] = (byte) (i & 0xFF);
        }
        List<RtpPacket> packets = H264RtpPacketizer.packetize(
                nalu, 123_456L, 500, SSRC, 1200);

        assertTrue("a 3000-byte NAL must fragment", packets.size() > 1);
        assertEquals(500, packets.get(0).getSequence());
        // First fragment: FU indicator (NRI kept, type 28), FU header S=1.
        assertEquals((byte) 0x7C, packets.get(0).getBytes()[12]);
        assertEquals((byte) 0x85, packets.get(0).getBytes()[13]);
        assertFalse(packets.get(0).isMarker());
        // Last fragment: FU header E=1, marker set.
        RtpPacket last = packets.get(packets.size() - 1);
        assertEquals((byte) 0x45, last.getBytes()[13]);
        assertTrue(last.isMarker());
        // Sequence increments per fragment, timestamp stays the same.
        assertEquals(500 + packets.size() - 1, last.getSequence());
        for (RtpPacket packet : packets) {
            assertEquals(123_456L, packet.getTimestamp());
        }
        // Payload reassembles to the original NAL.
        byte[] reassembled = new byte[nalu.length];
        reassembled[0] = nalu[0];
        int offset = 1;
        for (RtpPacket packet : packets) {
            byte[] bytes = packet.getBytes();
            int fragmentLength = bytes.length - 14;
            System.arraycopy(bytes, 14, reassembled, offset, fragmentLength);
            offset += fragmentLength;
        }
        assertArrayEquals(nalu, reassembled);
    }

    @Test
    public void sequenceWrapsAt16Bits() {
        byte[] nalu = {0x65, 1, 2};
        List<RtpPacket> packets = H264RtpPacketizer.packetize(
                nalu, 1L, 0xFFFE, SSRC, 1200);

        assertEquals(1, packets.size());
        assertEquals(0xFFFE, packets.get(0).getSequence());

        byte[] big = new byte[3000];
        big[0] = 0x65;
        List<RtpPacket> wrapped = H264RtpPacketizer.packetize(big, 1L, 0xFFFE, SSRC, 1200);
        assertEquals(0xFFFE, wrapped.get(0).getSequence());
        assertEquals(0xFFFF, wrapped.get(1).getSequence());
        assertEquals(0x0000, wrapped.get(2).getSequence());
    }

    @Test
    public void rejectsEmptyNalAndTinyPackets() {
        try {
            H264RtpPacketizer.packetize(new byte[0], 1L, 0, SSRC, 1200);
            throw new AssertionError("empty NAL must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            H264RtpPacketizer.packetize(new byte[] {0x65, 1}, 1L, 0, SSRC, 12);
            throw new AssertionError("packet bound below header size must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
