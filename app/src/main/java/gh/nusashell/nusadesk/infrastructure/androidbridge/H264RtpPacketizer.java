package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * H.264/AVC RTP packetization (RFC 6184), Android-free.
 *
 * <p>Encoders deliver Annex-B access units; this class splits them into NAL
 * units, packetizes each NAL as a single RTP packet when it fits the bounded
 * packet size or as an FU-A fragmentation unit otherwise, and derives the
 * SDP {@code sprop-parameter-sets} / {@code profile-level-id} values from
 * the SPS. All packets of one frame share the timestamp; the marker bit is
 * set on the final packet of the frame.</p>
 */
public final class H264RtpPacketizer {
    /** NAL unit type for a fragment of a larger NAL (RFC 6184 5.8). */
    public static final int NAL_TYPE_FU_A = 28;
    /** NAL unit type of an IDR slice. */
    public static final int NAL_TYPE_IDR = 5;
    /** NAL unit type of the SPS. */
    public static final int NAL_TYPE_SPS = 7;
    /** NAL unit type of the PPS. */
    public static final int NAL_TYPE_PPS = 8;

    private H264RtpPacketizer() {
    }

    /** NAL unit type of the given Annex-B NAL unit. */
    public static int nalType(byte[] nalu) {
        if (nalu == null || nalu.length == 0) {
            throw new IllegalArgumentException("NAL unit must not be empty");
        }
        return nalu[0] & 0x1F;
    }

    public static boolean isIdr(byte[] nalu) {
        return nalType(nalu) == NAL_TYPE_IDR;
    }

    public static boolean isSps(byte[] nalu) {
        return nalType(nalu) == NAL_TYPE_SPS;
    }

    /**
     * Split an Annex-B buffer (start codes {@code 00 00 01} or
     * {@code 00 00 00 01}) into NAL units including their NAL header. A
     * buffer with no start code is treated as one NAL; an empty buffer yields
     * no NALs.
     */
    public static List<byte[]> splitAnnexB(byte[] buffer) {
        List<byte[]> nalus = new ArrayList<>();
        if (buffer == null || buffer.length == 0) {
            return nalus;
        }
        int first = findStartCode(buffer, 0);
        if (first < 0) {
            nalus.add(buffer.clone());
            return nalus;
        }
        int naluStart = first + startCodeLength(buffer, first);
        while (naluStart < buffer.length) {
            int next = findStartCode(buffer, naluStart);
            int naluEnd = next < 0 ? buffer.length : next;
            // Annex-B permits zero-byte padding before the next start code;
            // do not leak that padding into the NAL header/payload.
            while (naluEnd > naluStart && buffer[naluEnd - 1] == 0) {
                naluEnd--;
            }
            if (naluEnd > naluStart) {
                nalus.add(copyOfRange(buffer, naluStart, naluEnd));
            }
            if (next < 0) {
                break;
            }
            naluStart = next + startCodeLength(buffer, next);
        }
        return nalus;
    }

    /**
     * {@code profile-level-id} token for the SDP fmtp line: the SPS payload's
     * profile_idc, constraint bytes, and level_idc as six hex digits.
     */
    public static String profileLevelId(byte[] spsNalu) {
        if (!isSps(spsNalu) || spsNalu.length < 4) {
            throw new IllegalArgumentException("SPS NAL unit is too short");
        }
        return hexByte(spsNalu[1]) + hexByte(spsNalu[2]) + hexByte(spsNalu[3]);
    }

    /**
     * Packetize one NAL unit into RTP packets. {@code firstSequence} is the
     * sequence number of the first packet; the sequence wraps at 16 bits.
     */
    public static List<RtpPacket> packetize(byte[] nalu, long rtpTimestamp,
                                            int firstSequence, long ssrc,
                                            int maxPacketBytes) {
        if (nalu == null || nalu.length == 0) {
            throw new IllegalArgumentException("NAL unit must not be empty");
        }
        if (maxPacketBytes < 14) {
            throw new IllegalArgumentException("maxPacketBytes must leave room for headers");
        }
        List<RtpPacket> packets = new ArrayList<>();
        if (nalu.length <= maxPacketBytes) {
            byte[] payload = new byte[12 + nalu.length];
            writeHeader(payload, 96, true, rtpTimestamp, firstSequence, ssrc);
            System.arraycopy(nalu, 0, payload, 12, nalu.length);
            packets.add(new RtpPacket(firstSequence, rtpTimestamp, true, payload));
            return packets;
        }
        // FU-A fragmentation (RFC 6184 5.8): one FU indicator, one FU header,
        // then fragments of the NAL payload.
        int fragmentPayload = maxPacketBytes - 14;
        int offset = 1;
        int sequence = firstSequence;
        int index = 0;
        while (offset < nalu.length) {
            int length = Math.min(fragmentPayload, nalu.length - offset);
            boolean start = offset == 1;
            boolean end = offset + length >= nalu.length;
            byte[] packet = new byte[12 + 2 + length];
            writeHeader(packet, 96, end, rtpTimestamp, sequence, ssrc);
            packet[12] = (byte) ((nalu[0] & 0xE0) | NAL_TYPE_FU_A);
            packet[13] = (byte) ((start ? 0x80 : 0) | (end ? 0x40 : 0)
                    | (nalu[0] & 0x1F));
            System.arraycopy(nalu, offset, packet, 14, length);
            packets.add(new RtpPacket(sequence, rtpTimestamp, end, packet));
            offset += length;
            sequence = (sequence + 1) & 0xFFFF;
            index++;
            if (index > 10_000) {
                throw new IllegalStateException("FU-A fragmentation did not terminate");
            }
        }
        return packets;
    }

    /** Build a 12-byte RTP header (V=2, no extensions, PT given). */
    public static byte[] buildHeader(int payloadType, boolean marker, long timestamp,
                                     int sequence, long ssrc) {
        byte[] header = new byte[12];
        writeHeader(header, payloadType, marker, timestamp, sequence, ssrc);
        return header;
    }

    private static void writeHeader(byte[] packet, int payloadType, boolean marker,
                                    long timestamp, int sequence, long ssrc) {
        packet[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
        packet[1] = (byte) ((marker ? 0x80 : 0) | (payloadType & 0x7F));
        packet[2] = (byte) (sequence >> 8);
        packet[3] = (byte) sequence;
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        packet[8] = (byte) (ssrc >> 24);
        packet[9] = (byte) (ssrc >> 16);
        packet[10] = (byte) (ssrc >> 8);
        packet[11] = (byte) ssrc;
    }

    private static final int START_CODE_THREE_BYTES = 3;
    private static final int START_CODE_FOUR_BYTES = 4;

    private static int findStartCode(byte[] buffer, int from) {
        for (int i = Math.max(from, 0); i + 2 < buffer.length; i++) {
            if (buffer[i] == 0 && buffer[i + 1] == 0 && buffer[i + 2] == 1) {
                return i;
            }
            if (i + 3 < buffer.length && buffer[i] == 0 && buffer[i + 1] == 0
                    && buffer[i + 2] == 0 && buffer[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] buffer, int start) {
        return start + 3 < buffer.length && buffer[start] == 0 && buffer[start + 1] == 0
                && buffer[start + 2] == 0 && buffer[start + 3] == 1
                ? START_CODE_FOUR_BYTES : START_CODE_THREE_BYTES;
    }

    private static byte[] copyOfRange(byte[] source, int from, int to) {
        byte[] copy = new byte[to - from];
        System.arraycopy(source, from, copy, 0, to - from);
        return copy;
    }

    private static String hexByte(byte value) {
        String hex = Integer.toHexString(value & 0xFF).toUpperCase(java.util.Locale.ROOT);
        return hex.length() == 1 ? "0" + hex : hex;
    }
}
