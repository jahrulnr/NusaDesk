package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * AAC-LC RTP packetization (RFC 3640 mpeg4-generic, AAC-hbr), Android-free.
 *
 * <p>Android's AAC encoder emits one raw AAC frame (no ADTS header) per
 * output buffer. Each frame is wrapped in the RFC 3640 two-byte
 * AU-headers-length field plus a two-byte AU header
 * (13-bit AU-size + 3-bit AU-index, per {@code sizelength=13;
 * indexlength=3;indexdeltalength=3}) and carried as one RTP packet with the
 * marker bit set. Frames above the 13-bit size bound are rejected: a
 * 64 kbps mono AAC-LC frame never approaches it, so exceeding it is a
 * programming error, not a supported mode.</p>
 */
public final class AacRtpPacketizer {
    /** 13-bit AU-size bound of the RFC 3640 header used here. */
    public static final int MAX_AU_SIZE_BYTES = 8191;

    private AacRtpPacketizer() {
    }

    /**
     * Wrap one raw AAC frame into one RTP packet. {@code firstSequence} is
     * the packet's sequence number; the timestamp is in the track's
     * 44.1 kHz clock.
     */
    public static RtpPacket packetize(byte[] aacFrame, long rtpTimestamp,
                                      int sequence, long ssrc) {
        if (aacFrame == null || aacFrame.length == 0) {
            throw new IllegalArgumentException("AAC frame must not be empty");
        }
        if (aacFrame.length > MAX_AU_SIZE_BYTES) {
            throw new IllegalArgumentException("AAC frame exceeds the 13-bit AU size bound");
        }
        byte[] packet = new byte[12 + 2 + 2 + aacFrame.length];
        byte[] header = H264RtpPacketizer.buildHeader(97, true, rtpTimestamp, sequence, ssrc);
        System.arraycopy(header, 0, packet, 0, header.length);
        // RFC 3640 carries the AU-headers-length field before the AU header.
        // One 13-bit size plus one 3-bit index consumes 16 bits.
        packet[12] = 0;
        packet[13] = 16;
        int auHeader = (aacFrame.length << 3) & 0xFFFF;
        packet[14] = (byte) (auHeader >> 8);
        packet[15] = (byte) auHeader;
        System.arraycopy(aacFrame, 0, packet, 16, aacFrame.length);
        return new RtpPacket(sequence, rtpTimestamp, true, packet);
    }

    /**
     * The two AU-header bytes for a given AU size (13-bit size + zero
     * index), exposed for tests and for the SDP-independent wrap logic.
     */
    public static byte[] auHeaderBytes(int auSize) {
        if (auSize < 1 || auSize > MAX_AU_SIZE_BYTES) {
            throw new IllegalArgumentException("AU size out of bounds");
        }
        int auHeader = (auSize << 3) & 0xFFFF;
        return new byte[] {(byte) (auHeader >> 8), (byte) auHeader};
    }
}
