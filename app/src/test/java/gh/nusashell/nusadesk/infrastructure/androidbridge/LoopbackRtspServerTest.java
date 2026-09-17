package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Loopback RTSP protocol test with a fake encoded source: real sockets on
 * 127.0.0.1, OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN, interleaved RTP relay,
 * the loopback-only bind enforcement, the bounded client cap, and the
 * drop-on-overflow backpressure policy. This proves the server contract on
 * the JVM; it does not pretend to prove real camera encoders.
 */
public class LoopbackRtspServerTest {

    private final List<LoopbackRtspServer> servers = new ArrayList<>();

    @After
    public void tearDown() {
        for (LoopbackRtspServer server : servers) {
            server.close();
        }
        servers.clear();
    }

    private static final byte[] SPS = {0x67, 0x42, 0x00, 0x1F, (byte) 0xEA, 0x01, 0x10};
    private static final byte[] PPS = {0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
    private static final byte[] IDR = {0x65, 0x11, 0x22, 0x33};
    private static final byte[] AAC_FRAME = {0x21, 0x10, 0x04, 0x56, (byte) 0xE5, 0x79};

    @Test
    public void rejectsNonLoopbackBindConfiguration() throws Exception {
        try {
            new LoopbackRtspServer(InetAddress.getByName("0.0.0.0"), 2);
            fail("wildcard bind must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new LoopbackRtspServer(InetAddress.getByName("192.168.1.10"), 2);
            fail("LAN bind must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new LoopbackRtspServer(InetAddress.getByName("::1"), 2);
            fail("IPv6 loopback is not the supported IPv4 loopback endpoint");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new LoopbackRtspServer(null, 2);
            fail("null bind must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new LoopbackRtspServer(InetAddress.getByName("127.0.0.1"), 0);
            fail("zero client limit must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void servesOptionsDescribeSetupPlayAndTeardownWithInterleavedRtp()
            throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 5_000L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);
        // Publish a keyframe and an audio frame before the consumer joins so
        // PLAY replays a decodable start.
        server.publishVideoFrame(Arrays.asList(SPS, PPS, IDR), 1_000_000L, true);
        server.publishAudioFrame(AAC_FRAME, 1_000_000L);
        String base = "rtsp://127.0.0.1:" + server.getPort() + "/";

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            Response options = exchange(output, input, 1,
                    "OPTIONS " + base + " RTSP/1.0");
            assertEquals(200, options.status);
            assertTrue("Public must advertise the supported methods",
                    options.header("Public").contains("PLAY"));
            assertTrue(options.header("Public").contains("SETUP"));
            assertTrue(options.header("Public").contains("TEARDOWN"));

            Response describe = exchange(output, input, 2,
                    "DESCRIBE " + base + " RTSP/1.0");
            assertEquals(200, describe.status);
            String sdp = describe.body;
            assertTrue(sdp.contains("H264/90000"));
            assertTrue(sdp.contains("packetization-mode=1"));
            assertTrue(sdp.contains("profile-level-id=42001F"));
            assertTrue("SDP must carry sprop-parameter-sets",
                    sdp.contains("sprop-parameter-sets="));
            assertTrue(sdp.contains("MPEG4-GENERIC/44100/1"));
            assertTrue("ffmpeg expects the AAC config as hex",
                    sdp.contains("config=1210"));
            assertTrue(sdp.contains("a=control:trackID=0"));
            assertTrue(sdp.contains("a=control:trackID=1"));

            Response setupVideo = exchange(output, input, 3,
                    "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
            assertEquals(200, setupVideo.status);
            assertTrue(setupVideo.header("Transport").contains("interleaved=0-1"));
            assertNotNull("SETUP must issue a session id",
                    setupVideo.header("Session"));

            Response setupAudio = exchange(output, input, 4,
                    "SETUP " + base + "trackID=1 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3");
            assertEquals(200, setupAudio.status);
            assertTrue(setupAudio.header("Transport").contains("interleaved=2-3"));

            Response play = exchange(output, input, 5,
                    "PLAY " + base + " RTSP/1.0");
            assertEquals(200, play.status);
            String rtpInfo = play.header("RTP-Info");
            assertTrue(rtpInfo.contains("trackID=0;seq="));
            assertTrue(rtpInfo.contains("trackID=1;seq="));
            int videoSeqAtPlay = seqOf(rtpInfo, "trackID=0");
            int audioSeqAtPlay = seqOf(rtpInfo, "trackID=1");

            // Live frames after PLAY are relayed in publish order.
            server.publishVideoFrame(Arrays.asList(IDR), 1_033_000L, true);
            server.publishAudioFrame(AAC_FRAME, 1_033_000L);

            // The replayed keyframe is SPS+PPS+IDR on channel 0; read frames
            // until the audio replay on channel 2 arrives.
            InterleavedFrame video = readInterleaved(input);
            List<InterleavedFrame> replayVideo = new ArrayList<>();
            replayVideo.add(video);
            assertEquals("video RTP rides channel 0", 0, video.channel);
            assertEquals("first video seq matches RTP-Info", videoSeqAtPlay, video.rtpSequence());
            assertEquals(96, video.payloadType());
            assertEquals(1_000_000L * 90L, video.rtpTimestamp());
            assertTrue("the replayed keyframe starts with its SPS NAL",
                    video.payload[12] == SPS[0]);

            InterleavedFrame audio = null;
            int videoFramesSeen = 1;
            while (audio == null) {
                InterleavedFrame frame = readInterleaved(input);
                if (frame.channel == 0) {
                    replayVideo.add(frame);
                    videoFramesSeen++;
                } else if (frame.channel == 2) {
                    audio = frame;
                }
            }
            assertEquals("the keyframe replay is SPS+PPS+IDR", 3, videoFramesSeen);
            assertFalse("marker ends the complete access unit, not SPS", replayVideo.get(0).marker());
            assertFalse("marker ends the complete access unit, not PPS", replayVideo.get(1).marker());
            assertTrue("the final replay video packet carries marker",
                    replayVideo.get(replayVideo.size() - 1).marker());
            assertEquals("first audio seq matches RTP-Info", audioSeqAtPlay,
                    audio.rtpSequence());
            assertEquals(97, audio.payloadType());
            assertEquals("AU-headers-length, one AU header, and the AAC frame",
                    4 + AAC_FRAME.length, audio.payload.length - 12);
            assertEquals("AU-headers-length is 16 bits", 16,
                    ((audio.payload[12] & 0xFF) << 8) | (audio.payload[13] & 0xFF));
            assertTrue("AU header carries the 13-bit frame size",
                    ((audio.payload[14] & 0xFF) << 8 | (audio.payload[15] & 0xFF)) >> 3
                            == AAC_FRAME.length);

            Response teardown = exchange(output, input, 6,
                    "TEARDOWN " + base + " RTSP/1.0");
            assertEquals(200, teardown.status);
            assertEquals("the server closes the connection after TEARDOWN",
                    -1, input.read());
        }
    }

    @Test
    public void describeBeforeVideoFormatIs404() throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 5_000L);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            Response describe = exchange(socket.getOutputStream(), socket.getInputStream(),
                    1, "DESCRIBE rtsp://127.0.0.1:" + server.getPort() + "/ RTSP/1.0");
            assertEquals("no SDP before the video format is ready", 404, describe.status);
        }
    }

    @Test
    public void rejectsUdpTransportAndDuplicateTrackSetup() throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 5_000L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);
        String base = "rtsp://127.0.0.1:" + server.getPort() + "/";

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            Response udp = exchange(output, input, 1,
                    "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP;unicast;client_port=5000-5001");
            assertEquals("UDP transport must be refused", 461, udp.status);

            Response wrongChannels = exchange(output, input, 2,
                    "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=4-5");
            assertEquals("foreign interleaved channels must be refused", 461,
                    wrongChannels.status);

            Response setup = exchange(output, input, 3,
                    "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
            assertEquals(200, setup.status);

            Response duplicate = exchange(output, input, 4,
                    "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
            assertEquals("duplicate track SETUP must be refused", 455,
                    duplicate.status);

            Response earlyPlay = exchange(output, input, 5,
                    "PLAY " + base + " RTSP/1.0");
            assertEquals("PLAY before both tracks are set up must be refused",
                    455, earlyPlay.status);
        }
    }

    @Test
    public void keepaliveAndClientRtcpNeverBlockTheControlChannel() throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 5_000L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);
        String base = "rtsp://127.0.0.1:" + server.getPort() + "/";

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            exchange(output, input, 1, "OPTIONS " + base + " RTSP/1.0");
            exchange(output, input, 2, "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
            exchange(output, input, 3, "SETUP " + base + "trackID=1 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3");
            exchange(output, input, 4, "PLAY " + base + " RTSP/1.0");

            // A client RTCP receiver report on the interleaved channel must be
            // consumed and discarded, and control requests must keep working.
            output.write(new byte[] {'$', 1, 0, 4, (byte) 0x80, (byte) 201, 0, 1});
            output.flush();

            Response keepalive = exchange(output, input, 5,
                    "GET_PARAMETER " + base + " RTSP/1.0");
            assertEquals(200, keepalive.status);

            Response teardown = exchange(output, input, 6,
                    "TEARDOWN " + base + " RTSP/1.0");
            assertEquals(200, teardown.status);
        }
    }

    @Test
    public void thirdClientIsRejectedAtTheFixedCap() throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 5_000L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);

        try (Socket first = new Socket("127.0.0.1", server.getPort());
             Socket second = new Socket("127.0.0.1", server.getPort());
             Socket third = new Socket("127.0.0.1", server.getPort())) {
            first.setSoTimeout(5_000);
            second.setSoTimeout(5_000);
            third.setSoTimeout(5_000);
            // The two idle connections hold the cap; the third is closed by
            // the accept loop.
            assertEquals("the third consumer must be disconnected", -1,
                    third.getInputStream().read());
        }
    }

    @Test
    public void slowConsumerIsDisconnectedOnQueueOverflow() throws Exception {
        // Bounded queue of 4 frames: a consumer that stops reading must be
        // dropped instead of growing memory or stalling the publisher.
        LoopbackRtspServer server = newServer(2, 4, 5_000L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);
        String base = "rtsp://127.0.0.1:" + server.getPort() + "/";

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(10_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            exchange(output, input, 1, "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
            exchange(output, input, 2, "SETUP " + base + "trackID=1 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3");
            exchange(output, input, 3, "PLAY " + base + " RTSP/1.0");

            // 3000-byte NALs produce ~1200-byte wire frames; publish many
            // without reading so the writer blocks and the queue overflows.
            byte[] bigNal = new byte[3000];
            bigNal[0] = 0x65;
            for (int i = 0; i < 5_000; i++) {
                server.publishVideoFrame(List.of(bigNal), 2_000_000L + i, true);
            }

            // The connection must be closed (drop), not buffered forever:
            // drain whatever was already written, then expect EOF.
            boolean eof = false;
            try {
                while (input.read() != -1) {
                    // drain buffered frames
                }
                eof = true;
            } catch (java.net.SocketTimeoutException e) {
                // No EOF: the server kept the overflowing consumer alive.
            }
            assertTrue("the overflowing consumer must be disconnected", eof);
        }
    }

    @Test
    public void stopClosesTheListenerAndEveryClient() throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 5_000L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);
        String base = "rtsp://127.0.0.1:" + server.getPort() + "/";
        int port = server.getPort();

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        exchange(socket.getOutputStream(), socket.getInputStream(), 1,
                "SETUP " + base + "trackID=0 RTSP/1.0",
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");

        server.close();

        assertEquals("clients are disconnected on stop", -1,
                socket.getInputStream().read());
        socket.close();
        try {
            new Socket("127.0.0.1", port);
            fail("the listener must be closed after stop");
        } catch (IOException expected) {
            // expected
        }
        try {
            server.publishVideoFrame(List.of(IDR), 1L, true);
            fail("publish after stop must fail closed");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void rtcpSenderReportsArriveOnTheInterleavedRtcpChannels() throws Exception {
        LoopbackRtspServer server = newServer(2, 256, 200L);
        server.setVideoFormat(SPS, PPS, 1280, 720);
        server.setAudioFormat(new byte[] {0x12, 0x10}, 44_100, 1);
        String base = "rtsp://127.0.0.1:" + server.getPort() + "/";

        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(5_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            exchange(output, input, 1, "SETUP " + base + "trackID=0 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1");
            exchange(output, input, 2, "SETUP " + base + "trackID=1 RTSP/1.0",
                    "Transport: RTP/AVP/TCP;unicast;interleaved=2-3");
            exchange(output, input, 3, "PLAY " + base + " RTSP/1.0");
            server.publishVideoFrame(List.of(IDR), 500_000L, true);

            boolean sawVideoSr = false;
            boolean sawAudioSr = false;
            long deadline = System.currentTimeMillis() + 5_000;
            while ((!sawVideoSr || !sawAudioSr) && System.currentTimeMillis() < deadline) {
                InterleavedFrame frame = readInterleaved(input);
                // RTCP payload types are full bytes, not 7-bit RTP payloads.
                if (frame.channel == 1 && (frame.payload[1] & 0xFF) == 200) {
                    sawVideoSr = true;
                }
                if (frame.channel == 3 && (frame.payload[1] & 0xFF) == 200) {
                    sawAudioSr = true;
                }
            }
            assertTrue("video RTCP sender reports must arrive on channel 1", sawVideoSr);
            assertTrue("audio RTCP sender reports must arrive on channel 3", sawAudioSr);
        }
    }

    // --- fixtures and helpers -------------------------------------------------

    private LoopbackRtspServer newServer(int clientLimit, int queueCapacity,
                                         long rtcpIntervalMillis) throws Exception {
        LoopbackRtspServer server = new LoopbackRtspServer(
                InetAddress.getByName("127.0.0.1"), clientLimit,
                queueCapacity, rtcpIntervalMillis);
        server.start();
        assertTrue("the server must bind an ephemeral loopback port",
                server.getPort() > 0);
        servers.add(server);
        return server;
    }

    private static Response exchange(OutputStream output, InputStream input, int cseq,
                                     String requestLine, String... extraHeaders)
            throws IOException {
        StringBuilder request = new StringBuilder(requestLine).append("\r\n")
                .append("CSeq: ").append(cseq).append("\r\n");
        for (String header : extraHeaders) {
            request.append(header).append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return readResponse(input);
    }

    private static Response readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value == -1) {
                throw new EOFException("response head truncated");
            }
            head.write(value);
            if (head.size() > 8192) {
                throw new IOException("response head exceeds bound");
            }
            byte last = (byte) value;
            if (matched == 0 && last == '\r' || matched == 1 && last == '\n'
                    || matched == 2 && last == '\r' || matched == 3 && last == '\n') {
                matched++;
            } else {
                matched = last == '\r' ? 1 : 0;
            }
        }
        String text = head.toString(StandardCharsets.US_ASCII.name());
        Response response = new Response();
        response.status = Integer.parseInt(text.split("\r\n")[0].split(" ")[1]);
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                response.headers.put(line.substring(0, colon).trim().toLowerCase(
                        java.util.Locale.ROOT), line.substring(colon + 1).trim());
            }
        }
        String lengthText = response.headers.get("content-length");
        if (lengthText != null) {
            int length = Integer.parseInt(lengthText);
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(body, offset, length - offset);
                if (read == -1) {
                    throw new EOFException("response body truncated");
                }
                offset += read;
            }
            response.body = new String(body, StandardCharsets.US_ASCII);
        } else {
            response.body = "";
        }
        return response;
    }

    private static InterleavedFrame readInterleaved(InputStream input) throws IOException {
        int marker = input.read();
        if (marker == -1) {
            throw new EOFException("connection closed before an interleaved frame");
        }
        if (marker != '$') {
            throw new IOException("expected interleaved marker, got 0x"
                    + Integer.toHexString(marker));
        }
        int channel = input.read();
        int length = (input.read() << 8) | input.read();
        byte[] payload = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(payload, offset, length - offset);
            if (read == -1) {
                throw new EOFException("interleaved frame truncated");
            }
            offset += read;
        }
        return new InterleavedFrame(channel, payload);
    }

    private static int seqOf(String rtpInfo, String track) {
        String marker = track + ";seq=";
        int at = rtpInfo.indexOf(marker);
        assertTrue("RTP-Info must name " + track, at >= 0);
        int end = rtpInfo.indexOf(';', at + marker.length());
        if (end < 0) {
            end = rtpInfo.length();
        }
        return Integer.parseInt(rtpInfo.substring(at + marker.length(), end));
    }

    private static final class Response {
        int status;
        final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        String body;

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static final class InterleavedFrame {
        final int channel;
        final byte[] payload;

        InterleavedFrame(int channel, byte[] payload) {
            this.channel = channel;
            this.payload = payload;
        }

        boolean marker() {
            return (payload[1] & 0x80) != 0;
        }

        int payloadType() {
            return payload[1] & 0x7F;
        }

        int rtpSequence() {
            return (payload[2] & 0xFF) << 8 | (payload[3] & 0xFF);
        }

        long rtpTimestamp() {
            return (payload[4] & 0xFFL) << 24 | (payload[5] & 0xFFL) << 16
                    | (payload[6] & 0xFFL) << 8 | (payload[7] & 0xFFL);
        }
    }
}
