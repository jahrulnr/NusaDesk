package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Small loopback-only RTSP/RTP server for the unified live media stream,
 * Android-free and JVM-testable.
 *
 * <p>This is deliberately not a general RTSP server: it serves the tracks of
 * exactly one live media session on the fixed path {@code /} to at most
 * {@code clientLimit} local consumers, and it enforces the product security
 * boundary by construction — the listener always binds the explicit IPv4
 * loopback address passed to the constructor, and the constructor rejects
 * any non-loopback address. Video (H.264) and audio (AAC-LC) are independent:
 * a session may carry only one of them, in which case the SDP advertises only
 * that track, {@code SETUP} for the other answers 404, {@code PLAY} requires
 * only the advertised tracks, and only those tracks get RTCP. The transport
 * is RTP over TCP interleaving
 * only (RFC 2326 interleaved mode), so no UDP port range is ever opened and
 * a single local guest consumer (ffplay/ffmpeg
 * {@code -rtsp_transport tcp}) is the intended peer.</p>
 *
 * <p>Encoders feed frames through {@link #publishVideoFrame} /
 * {@link #publishAudioFrame}; the server packetizes them (RFC 6184 H.264,
 * RFC 3640 AAC), keeps the last keyframe for new clients, and relays live
 * frames to every playing client. Each client gets a bounded frame queue:
 * a consumer that stops reading is disconnected instead of growing memory or
 * stalling the encoder pumps. RTSP control is one request per connection,
 * and interleaved RTCP/RTP bytes arriving from the client are consumed and
 * discarded so a keepalive exchange never deadlocks the control channel.</p>
 *
 * <p>{@link #close()} is terminal: it closes the listener and every client
 * socket, which unblocks the per-client threads; the pumps must stop calling
 * {@code publish*} afterwards (they observe {@link IllegalStateException}).</p>
 */
public final class LoopbackRtspServer implements AutoCloseable {
    public static final int PAYLOAD_TYPE_VIDEO = 96;
    public static final int PAYLOAD_TYPE_AUDIO = 97;
    public static final int CHANNEL_VIDEO_RTP = 0;
    public static final int CHANNEL_VIDEO_RTCP = 1;
    public static final int CHANNEL_AUDIO_RTP = 2;
    public static final int CHANNEL_AUDIO_RTCP = 3;

    private static final int REQUEST_BOUND_BYTES = 16 * 1024;
    private static final int CONTROL_READ_TIMEOUT_MILLIS = 1_000;
    private static final int RTCP_PACKET_BYTES = 28;
    private static final int MAX_STORED_KEYFRAME_BYTES = 512 * 1024;
    private static final long NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L;

    private final InetAddress bindAddress;
    private final int clientLimit;
    private final int frameQueueCapacity;
    private final long rtcpIntervalMillis;
    private final VideoTrack video = new VideoTrack();
    private final AudioTrack audio = new AudioTrack();
    private final List<ClientSession> sessions = new ArrayList<>();

    private volatile byte[] sps;
    private volatile byte[] pps;
    private volatile int videoWidth = -1;
    private volatile int videoHeight = -1;
    private volatile byte[] audioConfig;
    private volatile int audioSampleRate = -1;
    private volatile int audioChannels = -1;
    private volatile ServerSocket serverSocket;
    private volatile boolean closed;

    public LoopbackRtspServer(InetAddress bindAddress, int clientLimit) {
        this(bindAddress, clientLimit, LiveMediaDefaults.FRAME_QUEUE_CAPACITY,
                LiveMediaDefaults.RTCP_INTERVAL_MILLIS);
    }

    /**
     * Full constructor with the bounded queue/RTCP policy exposed for tests.
     * The bind address must be IPv4 loopback: the product boundary is an
     * explicit {@code 127.0.0.1} listener, never a wildcard.
     */
    LoopbackRtspServer(InetAddress bindAddress, int clientLimit,
                       int frameQueueCapacity, long rtcpIntervalMillis) {
        if (bindAddress == null || !bindAddress.isLoopbackAddress()
                || !(bindAddress instanceof Inet4Address)) {
            throw new IllegalArgumentException(
                    "RTSP server must bind IPv4 loopback, got: " + bindAddress);
        }
        if (clientLimit < 1) {
            throw new IllegalArgumentException("clientLimit must be positive");
        }
        if (frameQueueCapacity < 1) {
            throw new IllegalArgumentException("frameQueueCapacity must be positive");
        }
        this.bindAddress = bindAddress;
        this.clientLimit = clientLimit;
        this.frameQueueCapacity = frameQueueCapacity;
        this.rtcpIntervalMillis = rtcpIntervalMillis;
    }

    /** Bind the loopback listener and start accepting; each instance is single-use. */
    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("RTSP server instances are single-use");
        }
        if (serverSocket != null) {
            throw new IllegalStateException("RTSP server is already started");
        }
        ServerSocket server = new ServerSocket();
        // Explicit loopback bind only; the constructor already refused any
        // non-loopback address, and 0 selects an ephemeral port.
        server.bind(new java.net.InetSocketAddress(bindAddress, 0), 4);
        server.setSoTimeout(CONTROL_READ_TIMEOUT_MILLIS);
        serverSocket = server;
        Thread accept = new Thread(this::acceptLoop, "live-media-rtsp-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** Bound loopback port, or -1 before {@link #start()}. */
    public int getPort() {
        ServerSocket server = serverSocket;
        return server == null ? -1 : server.getLocalPort();
    }

    /** True once the SDP can describe the video track. */
    public boolean hasVideoFormat() {
        return sps != null && pps != null;
    }

    /** True once the SDP can describe the audio track. */
    public boolean hasAudioFormat() {
        return audioConfig != null;
    }

    /** Provide the H.264 SPS/PPS (raw NAL units) and the coded dimensions. */
    public void setVideoFormat(byte[] spsNalu, byte[] ppsNalu, int width, int height) {
        if (spsNalu == null || spsNalu.length == 0 || ppsNalu == null || ppsNalu.length == 0) {
            throw new IllegalArgumentException("SPS/PPS must not be empty");
        }
        this.sps = spsNalu.clone();
        this.pps = ppsNalu.clone();
        this.videoWidth = width;
        this.videoHeight = height;
    }

    /** Provide the AAC AudioSpecificConfig and its sample rate/channels. */
    public void setAudioFormat(byte[] audioSpecificConfig, int sampleRate, int channels) {
        if (audioSpecificConfig == null || audioSpecificConfig.length == 0) {
            throw new IllegalArgumentException("audio config must not be empty");
        }
        this.audioConfig = audioSpecificConfig.clone();
        this.audioSampleRate = sampleRate;
        this.audioChannels = channels;
    }

    /**
     * Publish one video frame (one access unit's Annex-B NAL units) with its
     * presentation time in microseconds. A keyframe is stored (bounded) and
     * replayed to clients that PLAY after it, so a new consumer never joins
     * mid-GOP.
     */
    public synchronized void publishVideoFrame(List<byte[]> nalus,
                                               long presentationTimeUs,
                                               boolean keyframe) {
        ensurePublishing();
        // RTP clock for H.264 is 90 kHz, so microseconds convert as
        // us * 90_000 / 1_000_000. Multiplying by 90 alone puts every frame
        // ~33 s apart on the wire, which decoders read as a frozen picture.
        long rtpTimestamp = presentationTimeUs * 90L / 1_000L;
        List<RtpPacket> packets = packetizeVideo(nalus, rtpTimestamp);
        recordVideo(packets);
        if (keyframe) {
            storeKeyframe(nalus, rtpTimestamp);
        }
        relay(packets, true);
    }

    /** Publish one raw AAC frame (no ADTS) with its presentation time in microseconds. */
    public synchronized void publishAudioFrame(byte[] aacFrame, long presentationTimeUs) {
        ensurePublishing();
        long rtpTimestamp = presentationTimeUs * LiveMediaDefaults.AUDIO_SAMPLE_RATE_HZ
                / 1_000_000L;
        RtpPacket packet = AacRtpPacketizer.packetize(
                aacFrame, rtpTimestamp, audio.nextSequence, audio.ssrc);
        audio.nextSequence = (audio.nextSequence + 1) & 0xFFFF;
        audio.packetsSent++;
        audio.octetsSent += packet.payloadOctets();
        audio.lastRtpTimestamp = rtpTimestamp;
        audio.lastFrame = aacFrame.clone();
        audio.lastFrameTimestamp = rtpTimestamp;
        List<RtpPacket> packets = new ArrayList<>(1);
        packets.add(packet);
        relay(packets, false);
    }

    /**
     * Terminate the server: close the listener and every client socket and
     * unblock the per-client threads. Idempotent; {@code publish*} after
     * close fails with {@link IllegalStateException}.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Listener teardown is best-effort; the accept thread polls closed.
            }
        }
        for (ClientSession session : new ArrayList<>(sessions)) {
            session.disconnect();
        }
        sessions.clear();
    }

    private void ensurePublishing() {
        if (closed || serverSocket == null) {
            throw new IllegalStateException("RTSP server is not publishing");
        }
    }

    private List<RtpPacket> packetizeVideo(List<byte[]> nalus, long rtpTimestamp) {
        List<RtpPacket> packets = new ArrayList<>();
        for (byte[] nalu : nalus) {
            List<RtpPacket> nalPackets = H264RtpPacketizer.packetize(nalu, rtpTimestamp,
                    video.nextSequence, video.ssrc, LiveMediaDefaults.RTP_MAX_PACKET_BYTES);
            packets.addAll(nalPackets);
            video.nextSequence = (video.nextSequence + nalPackets.size()) & 0xFFFF;
        }
        // RFC 6184's marker bit terminates the complete access unit, not each
        // NAL. A frame may contain SPS, PPS, and an IDR; only its final RTP
        // packet may carry marker=1.
        for (int index = 0; index < packets.size() - 1; index++) {
            packets.set(index, packets.get(index).withMarker(false));
        }
        return packets;
    }

    private void recordVideo(List<RtpPacket> packets) {
        video.packetsSent += packets.size();
        for (RtpPacket packet : packets) {
            video.octetsSent += packet.payloadOctets();
        }
        if (!packets.isEmpty()) {
            video.lastRtpTimestamp = packets.get(packets.size() - 1).getTimestamp();
        }
    }

    private void storeKeyframe(List<byte[]> nalus, long rtpTimestamp) {
        int total = 0;
        for (byte[] nalu : nalus) {
            total += nalu.length;
        }
        if (total > MAX_STORED_KEYFRAME_BYTES) {
            return; // never hold an unbounded keyframe in memory
        }
        List<byte[]> copy = new ArrayList<>(nalus.size());
        for (byte[] nalu : nalus) {
            copy.add(nalu.clone());
        }
        video.storedKeyframeNalus = copy;
        video.storedKeyframeTimestamp = rtpTimestamp;
    }

    private void relay(List<RtpPacket> packets, boolean videoTrack) {
        if (packets.isEmpty()) {
            return;
        }
        // The interleaved channel is chosen by the client at SETUP, so the wire
        // framing happens per session: an audio-only session whose SETUP used
        // 0-1 must not be answered on channel 2.
        for (ClientSession session : new ArrayList<>(sessions)) {
            int channel = videoTrack ? session.videoRtpChannel : session.audioRtpChannel;
            if (!session.enqueue(wireFrames(packets, channel))) {
                session.disconnect();
            }
        }
        sessions.removeIf(ClientSession::isDead);
    }

    private static byte[] wireFrame(int channel, byte[] rtpBytes) {
        byte[] frame = new byte[4 + rtpBytes.length];
        frame[0] = '$';
        frame[1] = (byte) channel;
        frame[2] = (byte) (rtpBytes.length >> 8);
        frame[3] = (byte) rtpBytes.length;
        System.arraycopy(rtpBytes, 0, frame, 4, rtpBytes.length);
        return frame;
    }

    private static List<byte[]> wireFrames(List<RtpPacket> packets, int channel) {
        List<byte[]> frames = new ArrayList<>(packets.size());
        for (RtpPacket packet : packets) {
            frames.add(wireFrame(channel, packet.getBytes()));
        }
        return frames;
    }

    private void acceptLoop() {
        while (!closed) {
            ServerSocket server = serverSocket;
            if (server == null) {
                return;
            }
            try {
                Socket client = server.accept();
                ClientSession session;
                synchronized (this) {
                    if (closed) {
                        closeQuietly(client);
                        return;
                    }
                    if (sessions.size() >= clientLimit) {
                        // At the fixed client cap: reject the extra consumer.
                        closeQuietly(client);
                        continue;
                    }
                    session = new ClientSession(client, frameQueueCapacity);
                    sessions.add(session);
                }
                Thread connection = new Thread(
                        () -> serveSession(session), "live-media-rtsp-client");
                connection.setDaemon(true);
                connection.start();
            } catch (SocketTimeoutException ignored) {
                // Poll closed so close() never depends on interrupting accept.
            } catch (IOException e) {
                if (!closed) {
                    return;
                }
            }
        }
    }

    private void serveSession(ClientSession session) {
        try (Socket socket = session.socket) {
            socket.setSoTimeout(CONTROL_READ_TIMEOUT_MILLIS);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            long handshakeDeadline = System.currentTimeMillis()
                    + LiveMediaDefaults.HANDSHAKE_TIMEOUT_MILLIS;
            while (!session.isDead()) {
                if (!session.isPlaying()
                        && System.currentTimeMillis() > handshakeDeadline) {
                    // A client that never completed PLAY holds a slot; drop it.
                    return;
                }
                byte[] message;
                try {
                    message = readControlMessage(input);
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    return; // EOFException is an IOException: client closed.
                }
                if (message == null) {
                    return; // EOF: client closed.
                }
                if (message.length == 0) {
                    continue; // interleaved data was consumed and discarded.
                }
                byte[] response;
                synchronized (this) {
                    response = handleRequest(session, message);
                }
                if (response == null) {
                    return;
                }
                try {
                    output.write(response);
                    output.flush();
                } catch (IOException e) {
                    return;
                }
                // The data writer starts only after the control response is on
                // the wire, so a PLAY reply is never reordered behind RTP
                // frames on the shared connection.
                if (session.isPlaying() && session.writerThread == null) {
                    Thread writer = new Thread(() -> writeLoop(session),
                            "live-media-rtsp-writer");
                    writer.setDaemon(true);
                    session.writerThread = writer;
                    writer.start();
                }
                if (session.isDead() || session.teardown) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // Client disconnect or server close: session teardown below.
        } finally {
            synchronized (this) {
                sessions.remove(session);
            }
            session.disconnect();
        }
    }

    /**
     * Read one control message. Interleaved frames (a {@code $} marker) are
     * consumed and discarded so client RTCP never blocks the control channel;
     * an empty byte array marks one consumed interleaved frame.
     */
    private static byte[] readControlMessage(InputStream input) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        while (true) {
            int first = input.read();
            if (first == -1) {
                return null;
            }
            if (first == '$') {
                int channel = input.read();
                int high = input.read();
                int low = input.read();
                if (channel == -1 || high == -1 || low == -1) {
                    throw new EOFException("truncated interleaved frame");
                }
                int length = (high << 8) | low;
                skipFully(input, length);
                if (request.size() == 0) {
                    return new byte[0]; // standalone interleaved data
                }
                continue; // interleaved bytes inside a request: keep reading
            }
            request.write(first);
            if (request.size() > REQUEST_BOUND_BYTES) {
                throw new IOException("RTSP request exceeds bound");
            }
            if (endsWithCrLfCrLf(request.toByteArray())) {
                return request.toByteArray();
            }
        }
    }

    private static void skipFully(InputStream input, int length) throws IOException {
        int remaining = length;
        byte[] buffer = new byte[Math.min(length, 4096)];
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new EOFException("truncated interleaved frame");
            }
            remaining -= read;
        }
    }

    private static boolean endsWithCrLfCrLf(byte[] bytes) {
        int length = bytes.length;
        return length >= 4
                && bytes[length - 4] == '\r' && bytes[length - 3] == '\n'
                && bytes[length - 2] == '\r' && bytes[length - 1] == '\n';
    }

    /**
     * Handle one RTSP request and return the response bytes, or {@code null}
     * when the connection must close. Called under the server lock so session
     * state, sequence numbers, and keyframe replays stay consistent with
     * concurrent publishes.
     */
    private byte[] handleRequest(ClientSession session, byte[] requestBytes) {
        Request request;
        try {
            request = Request.parse(requestBytes);
        } catch (IllegalArgumentException e) {
            return response(session, "400 Bad Request", null, null, null);
        }
        String cseq = request.cseq();
        if (cseq == null) {
            return response(session, "400 Bad Request", null, null, null);
        }
        String method = request.method.toUpperCase(Locale.ROOT);
        switch (method) {
            case "OPTIONS":
                return response(session, "200 OK", cseq,
                        "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER",
                        null);
            case "DESCRIBE":
                return describe(session, cseq, request);
            case "SETUP":
                return setup(session, cseq, request);
            case "PLAY":
                return play(session, cseq, request);
            case "GET_PARAMETER":
                return response(session, "200 OK", cseq, "Content-Length: 0", null);
            case "TEARDOWN":
                // Reply first, then let serveSession close the connection.
                session.teardown = true;
                return response(session, "200 OK", cseq, null, null);
            default:
                return response(session, "501 Not Implemented", cseq, null, null);
        }
    }

    private byte[] describe(ClientSession session, String cseq, Request request) {
        if (!hasVideoFormat() && !hasAudioFormat()) {
            return response(session, "404 Not Found", cseq, null, null);
        }
        String body = buildSdp();
        byte[] bodyBytes = body.getBytes(StandardCharsets.US_ASCII);
        String headers = "Content-Type: application/sdp\r\n"
                + "Content-Length: " + bodyBytes.length;
        return response(session, "200 OK", cseq, headers, bodyBytes);
    }

    private byte[] setup(ClientSession session, String cseq, Request request) {
        String track = trackId(request.uri);
        boolean videoTrack = "trackID=0".equals(track);
        boolean audioTrack = "trackID=1".equals(track);
        if (!videoTrack && !audioTrack) {
            return response(session, "404 Not Found", cseq, null, null);
        }
        // A track that this session does not carry does not exist for the
        // client, so a camera-only stream has no trackID=1 and vice versa.
        if ((videoTrack && !hasVideoFormat()) || (audioTrack && !hasAudioFormat())) {
            return response(session, "404 Not Found", cseq, null, null);
        }
        int rtpChannel = videoTrack ? CHANNEL_VIDEO_RTP : CHANNEL_AUDIO_RTP;
        int rtcpChannel = rtpChannel + 1;
        String transport = request.header("Transport");
        if (transport == null
                || !transport.toUpperCase(Locale.ROOT).contains("RTP/AVP/TCP")) {
            // Only interleaved TCP is supported: no UDP port range is ever
            // opened.
            return response(session, "461 Unsupported Transport", cseq, null, null);
        }
        // The client picks the interleaved channel pair at SETUP and numbers it
        // in the order it sets the advertised tracks up, so an audio-only
        // session is normally asked for on 0-1 (not the audio default 2-3).
        // Honour the request and remember it for this session's relay.
        int[] requested = requestedInterleaved(transport);
        if (requested == null) {
            return response(session, "461 Unsupported Transport", cseq, null, null);
        }
        rtpChannel = requested[0];
        rtcpChannel = requested[1];
        String interleaved = rtpChannel + "-" + rtcpChannel;
        boolean alreadySetup = videoTrack ? session.videoSetup : session.audioSetup;
        if (alreadySetup) {
            return response(session, "455 Method Not Valid In This State",
                    cseq, null, null);
        }
        if (videoTrack) {
            session.videoSetup = true;
            session.videoRtpChannel = rtpChannel;
        } else {
            session.audioSetup = true;
            session.audioRtpChannel = rtpChannel;
        }
        String transportReply = "Transport: RTP/AVP/TCP;unicast;interleaved="
                + interleaved + ";mode=play";
        return response(session, "200 OK", cseq, transportReply, null);
    }

    /**
     * Parse the client's {@code interleaved=<rtp>-<rtcp>} request into a
     * channel pair. Requires two non-negative 8-bit channels where the RTCP
     * channel follows the RTP one; {@code null} when the value is absent or
     * unusable.
     */
    private static int[] requestedInterleaved(String transport) {
        int start = transport.toLowerCase(Locale.ROOT).indexOf("interleaved=");
        if (start < 0) {
            return null;
        }
        String value = transport.substring(start + "interleaved=".length());
        int commaOrSemicolon = value.indexOf(';');
        if (commaOrSemicolon >= 0) {
            value = value.substring(0, commaOrSemicolon);
        }
        int dash = value.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        try {
            int rtp = Integer.parseInt(value.substring(0, dash).trim());
            int rtcp = Integer.parseInt(value.substring(dash + 1).trim());
            if (rtp < 0 || rtp > 254 || rtcp != rtp + 1) {
                return null;
            }
            return new int[] {rtp, rtcp};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private byte[] play(ClientSession session, String cseq, Request request) {
        boolean hasVideo = hasVideoFormat();
        boolean hasAudio = hasAudioFormat();
        // Only the tracks this session actually carries must be set up: a
        // camera-only stream plays after a single video SETUP.
        if ((hasVideo && !session.videoSetup) || (hasAudio && !session.audioSetup)) {
            return response(session, "455 Method Not Valid In This State",
                    cseq, null, null);
        }
        // RTP-Info must name the first packet of the stream: capture the
        // sequence numbers before the keyframe/audio replay advances them.
        int seqVideoAtPlay = video.nextSequence;
        int seqAudioAtPlay = audio.nextSequence;
        if (!session.isPlaying()) {
            session.playing = true;
            // Replay the stored keyframe (and the last audio frame) so a new
            // consumer starts on a decodable point, then live frames follow.
            if (hasVideo && video.storedKeyframeNalus != null) {
                List<RtpPacket> replay = packetizeVideo(
                        video.storedKeyframeNalus, video.storedKeyframeTimestamp);
                recordVideo(replay);
                if (!session.enqueue(wireFrames(replay, session.videoRtpChannel))) {
                    session.teardown = true;
                    return response(session, "200 OK", cseq, null, null);
                }
            }
            if (hasAudio && audio.lastFrame != null) {
                RtpPacket packet = AacRtpPacketizer.packetize(audio.lastFrame,
                        audio.lastFrameTimestamp, audio.nextSequence, audio.ssrc);
                audio.nextSequence = (audio.nextSequence + 1) & 0xFFFF;
                audio.packetsSent++;
                audio.octetsSent += packet.payloadOctets();
                List<RtpPacket> packets = new ArrayList<>(1);
                packets.add(packet);
                if (!session.enqueue(wireFrames(packets, session.audioRtpChannel))) {
                    session.teardown = true;
                    return response(session, "200 OK", cseq, null, null);
                }
            }
            // The writer thread is started by serveSession after the PLAY
            // response is flushed, so the control reply precedes the data.
        }
        StringBuilder rtpInfo = new StringBuilder("RTP-Info: ");
        if (hasVideo) {
            rtpInfo.append("url=rtsp://127.0.0.1:").append(getPort())
                    .append("/trackID=0;seq=").append(seqVideoAtPlay)
                    .append(";rtptime=").append(video.lastRtpTimestamp);
        }
        if (hasAudio) {
            if (hasVideo) {
                rtpInfo.append(',');
            }
            rtpInfo.append("url=rtsp://127.0.0.1:").append(getPort())
                    .append("/trackID=1;seq=").append(seqAudioAtPlay)
                    .append(";rtptime=").append(audio.lastRtpTimestamp);
        }
        return response(session, "200 OK", cseq, rtpInfo.toString(), null);
    }

    private void writeLoop(ClientSession session) {
        long lastRtcp = System.currentTimeMillis();
        try (Socket socket = session.socket) {
            OutputStream output = socket.getOutputStream();
            while (!session.isDead()) {
                byte[] frame = session.queue.poll(500, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    output.write(frame);
                    output.flush();
                }
                long now = System.currentTimeMillis();
                if (now - lastRtcp >= rtcpIntervalMillis) {
                    // Sender reports only for the tracks this session carries,
                    // on the channel pair the client asked for at SETUP.
                    if (hasVideoFormat()) {
                        output.write(wireFrame(session.videoRtpChannel + 1,
                                rtcpSenderReport(video)));
                    }
                    if (hasAudioFormat()) {
                        output.write(wireFrame(session.audioRtpChannel + 1,
                                rtcpSenderReport(audio)));
                    }
                    output.flush();
                    lastRtcp = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Consumer gone or server closed: session teardown.
        } finally {
            session.disconnect();
        }
    }

    private static byte[] rtcpSenderReport(Track track) {
        byte[] report = new byte[RTCP_PACKET_BYTES];
        report[0] = (byte) 0x80; // V=2, no report blocks
        report[1] = (byte) 200;  // Sender Report
        report[2] = 0;
        report[3] = 6;           // 28 bytes minus 4, in 32-bit words
        putInt(report, 4, track.ssrc);
        long now = System.currentTimeMillis();
        long ntpSeconds = now / 1000L + NTP_UNIX_EPOCH_OFFSET_SECONDS;
        long ntpFraction = ((now % 1000L) * 0x1_0000_0000L) / 1000L;
        putInt(report, 8, ntpSeconds);
        putInt(report, 12, ntpFraction);
        putInt(report, 16, track.lastRtpTimestamp);
        putInt(report, 20, track.packetsSent);
        putInt(report, 24, track.octetsSent);
        return report;
    }

    private static void putInt(byte[] target, int offset, long value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }

    private String buildSdp() {
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=- 1 1 IN IP4 127.0.0.1\r\n");
        sdp.append("s=NusaDesk Live Media\r\n");
        sdp.append("c=IN IP4 127.0.0.1\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("a=control:*\r\n");
        if (hasVideoFormat()) {
            String profileLevelId = H264RtpPacketizer.profileLevelId(sps);
            String sprop = Base64.getEncoder().encodeToString(sps)
                    + "," + Base64.getEncoder().encodeToString(pps);
            sdp.append("m=video 0 RTP/AVP ").append(PAYLOAD_TYPE_VIDEO).append("\r\n");
            sdp.append("a=rtpmap:").append(PAYLOAD_TYPE_VIDEO).append(" H264/90000\r\n");
            sdp.append("a=fmtp:").append(PAYLOAD_TYPE_VIDEO)
                    .append(" packetization-mode=1;profile-level-id=").append(profileLevelId)
                    .append(";sprop-parameter-sets=").append(sprop).append("\r\n");
            sdp.append("a=control:trackID=0\r\n");
        }
        if (hasAudioFormat()) {
            sdp.append("m=audio 0 RTP/AVP ").append(PAYLOAD_TYPE_AUDIO).append("\r\n");
            sdp.append("a=rtpmap:").append(PAYLOAD_TYPE_AUDIO)
                    .append(" MPEG4-GENERIC/").append(audioSampleRate)
                    .append("/").append(audioChannels).append("\r\n");
            sdp.append("a=fmtp:").append(PAYLOAD_TYPE_AUDIO)
                    .append(" streamtype=5;profile-level-id=1;mode=AAC-hbr;")
                    .append("sizelength=13;indexlength=3;indexdeltalength=3;config=")
                    .append(hex(audioConfig)).append("\r\n");
            sdp.append("a=control:trackID=1\r\n");
        }
        return sdp.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    private static String trackId(String uri) {
        if (uri == null) {
            return "";
        }
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? uri : uri.substring(slash + 1);
    }

    private static byte[] response(ClientSession session, String status, String cseq,
                                   String extraHeaders, byte[] body) {
        StringBuilder response = new StringBuilder(128);
        response.append("RTSP/1.0 ").append(status).append("\r\n");
        if (cseq != null) {
            response.append("CSeq: ").append(cseq).append("\r\n");
        }
        response.append("Session: ").append(session.sessionId).append("\r\n");
        if (extraHeaders != null) {
            response.append(extraHeaders).append("\r\n");
        }
        if (body == null) {
            response.append("Content-Length: 0\r\n");
        } else {
            response.append("Content-Length: ").append(body.length).append("\r\n");
        }
        response.append("\r\n");
        byte[] head = response.toString().getBytes(StandardCharsets.US_ASCII);
        if (body == null) {
            return head;
        }
        byte[] full = new byte[head.length + body.length];
        System.arraycopy(head, 0, full, 0, head.length);
        System.arraycopy(body, 0, full, head.length, body.length);
        return full;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort rejection path.
        }
    }

    /** One RTSP control connection: per-track setup flags, bounded queue, threads. */
    private static final class ClientSession {
        final Socket socket;
        final ArrayBlockingQueue<byte[]> queue;
        final String sessionId = Integer.toHexString(
                ThreadLocalRandom.current().nextInt(0x10000000));
        volatile boolean videoSetup;
        volatile boolean audioSetup;
        volatile boolean playing;
        volatile boolean teardown;
        volatile boolean dead;
        volatile Thread writerThread;
        /** Interleaved RTP channels the client requested (RTCP is +1). */
        volatile int videoRtpChannel = CHANNEL_VIDEO_RTP;
        volatile int audioRtpChannel = CHANNEL_AUDIO_RTP;

        ClientSession(Socket socket, int queueCapacity) {
            this.socket = socket;
            this.queue = new ArrayBlockingQueue<>(queueCapacity);
        }

        /** Queue the frames; false means the bounded queue overflowed. */
        boolean enqueue(List<byte[]> frames) {
            if (!playing || dead) {
                return true; // not playing yet: nothing to buffer
            }
            for (byte[] frame : frames) {
                if (!queue.offer(frame)) {
                    return false; // bounded queue overflow: drop this client
                }
            }
            return true;
        }

        boolean isDead() {
            return dead;
        }

        boolean isPlaying() {
            return playing;
        }

        void disconnect() {
            dead = true;
            closeQuietly(socket);
            queue.clear();
        }
    }

    /** One RTSP request: method, URI, bounded headers. */
    private static final class Request {
        final String method;
        final String uri;
        final Map<String, String> headers = new LinkedHashMap<>();

        private Request(String method, String uri) {
            this.method = method;
            this.uri = uri;
        }

        static Request parse(byte[] bytes) {
            String text = new String(bytes, StandardCharsets.US_ASCII);
            String[] lines = text.split("\r\n");
            if (lines.length < 1) {
                throw new IllegalArgumentException("empty request");
            }
            String[] first = lines[0].split(" ");
            if (first.length != 3 || !first[2].startsWith("RTSP/")) {
                throw new IllegalArgumentException("bad request line");
            }
            Request request = new Request(first[0], first[1]);
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 1) {
                    throw new IllegalArgumentException("bad header line");
                }
                request.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            return request;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String cseq() {
            return headers.get("cseq");
        }
    }

    /** Per-track sender state: SSRC, sequence, statistics, stored last data. */
    private static class Track {
        final long ssrc = (ThreadLocalRandom.current().nextInt() & 0xFFFF_FFFFL);
        int nextSequence = ThreadLocalRandom.current().nextInt(0x10000);
        long packetsSent;
        long octetsSent;
        long lastRtpTimestamp;
    }

    /** Video sender state plus the bounded stored keyframe. */
    private static final class VideoTrack extends Track {
        List<byte[]> storedKeyframeNalus;
        long storedKeyframeTimestamp;
    }

    /** Audio sender state plus the last frame, for the RTP-Info and replay. */
    private static final class AudioTrack extends Track {
        byte[] lastFrame;
        long lastFrameTimestamp;
    }
}
