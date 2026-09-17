package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.Objects;

/**
 * One complete RTP packet (12-byte header + payload), Android-free.
 *
 * <p>Carries the parsed sequence number, timestamp, and marker bit alongside
 * the wire bytes so the RTSP server can update sender statistics and the
 * interleaved channel without re-parsing.</p>
 */
public final class RtpPacket {
    private final int sequence;
    private final long timestamp;
    private final boolean marker;
    private final byte[] bytes;

    public RtpPacket(int sequence, long timestamp, boolean marker, byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            throw new IllegalArgumentException("RTP packet bytes must carry a header");
        }
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.marker = marker;
        this.bytes = bytes;
    }

    public int getSequence() {
        return sequence;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isMarker() {
        return marker;
    }

    /** Full RTP packet: 12-byte header followed by the payload. */
    public byte[] getBytes() {
        return bytes;
    }

    /** Return a copy with the RTP marker bit rewritten in the wire header. */
    public RtpPacket withMarker(boolean nextMarker) {
        if (marker == nextMarker) {
            return this;
        }
        byte[] copy = bytes.clone();
        if (nextMarker) {
            copy[1] = (byte) (copy[1] | 0x80);
        } else {
            copy[1] = (byte) (copy[1] & 0x7F);
        }
        return new RtpPacket(sequence, timestamp, nextMarker, copy);
    }

    /** Payload octets (wire length minus the 12-byte header). */
    public int payloadOctets() {
        return bytes.length - 12;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RtpPacket)) {
            return false;
        }
        RtpPacket that = (RtpPacket) other;
        return sequence == that.sequence
                && timestamp == that.timestamp
                && marker == that.marker
                && java.util.Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, timestamp, marker, java.util.Arrays.hashCode(bytes));
    }

    @Override
    public String toString() {
        return "RtpPacket{seq=" + sequence + ", ts=" + timestamp
                + ", marker=" + marker + ", bytes=" + bytes.length + "}";
    }
}
