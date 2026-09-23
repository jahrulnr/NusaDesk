package gh.nusashell.nusadesk.infrastructure.androidbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gh.nusashell.nusadesk.infrastructure.androidbridge.BluetoothRfcommModule.RfcommTransport.RfcommLink;
import gh.nusashell.nusadesk.infrastructure.androidbridge.BluetoothRfcommModule.RfcommTransport.RfcommListener;

/**
 * Unit coverage of {@link BluetoothRfcommModule}: the one-link state machine
 * (listen/accept/connect/read/write/close), the bounded-window join between
 * {@code listen} and {@code accept}, and the typed-error surface — all
 * through the {@link BluetoothRfcommModule.RfcommTransport} seam, so no
 * Bluetooth radio or Android framework is needed.
 */
public class BluetoothRfcommModuleTest {

    private static final String PEER = "11:22:33:44:55:66";

    // ------------------------------------------------------------------
    // Shape and validation
    // ------------------------------------------------------------------

    @Test
    public void declaresTheSixRfcommMethods() {
        BluetoothRfcommModule module = module(new FakeTransport());
        assertEquals(java.util.List.of("bt.rfcomm.listen", "bt.rfcomm.accept",
                "bt.rfcomm.connect", "bt.rfcomm.read", "bt.rfcomm.write",
                "bt.rfcomm.close"), module.methods());
        assertEquals(java.util.Set.of("bt.rfcomm.listen", "bt.rfcomm.connect",
                "bt.rfcomm.read", "bt.rfcomm.write"), module.parameterMethods());
    }

    @Test
    public void rejectsUnknownParamsAndBadBounds() {
        BluetoothRfcommModule module = module(new FakeTransport());
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("1", "bt.rfcomm.listen", "{\"bogus\":1}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.rfcomm.listen", "{\"timeout_ms\":0}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.rfcomm.listen", "{\"timeout_ms\":30001}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("4", "bt.rfcomm.connect",
                        "{\"address\":\"" + PEER + "\",\"service_uuid\":\"not-a-uuid\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("5", "bt.rfcomm.connect", "{\"timeout_ms\":100}")));
    }

    // ------------------------------------------------------------------
    // listen / accept
    // ------------------------------------------------------------------

    @Test
    public void listenAcceptsAPeerAndAcceptIsIdempotent() {
        FakeTransport transport = new FakeTransport();
        transport.nextListener.accepted = new FakeLink(PEER);
        BluetoothRfcommModule module = module(transport);

        AndroidCapabilityProtocol.Response listen = module.handle(
                request("1", "bt.rfcomm.listen",
                        "{\"timeout_ms\":5000,\"name\":\"serial\"}"));

        assertTrue(listen.isOk());
        assertEquals(Boolean.TRUE, listen.getFields().get("connected"));
        assertEquals(PEER, listen.getFields().get("address"));
        assertEquals(-1L, listen.getFields().get("channel"));
        assertEquals("serial", transport.listenName);
        assertTrue(transport.nextListener.closed);

        AndroidCapabilityProtocol.Response again = module.handle(
                request("2", "bt.rfcomm.accept"));
        assertTrue(again.isOk());
        assertEquals(PEER, again.getFields().get("address"));
    }

    @Test
    public void listenTimeoutClosesTheListenerSoARetryStartsClean() {
        FakeTransport transport = new FakeTransport();
        transport.nextListener.timeout = true;
        BluetoothRfcommModule module = module(transport);

        AndroidCapabilityProtocol.Response timedOut = module.handle(
                request("1", "bt.rfcomm.listen", "{\"timeout_ms\":1000}"));

        assertFalse(timedOut.isOk());
        assertEquals("bt-rfcomm-listen-timeout", timedOut.getError());
        assertTrue(transport.nextListener.closed);
        assertTrue(transport.nextListener.lastTimeoutMs > 0);

        // A retry opens a fresh listener instead of tripping already-listening.
        transport.nextListener = new FakeListener();
        transport.nextListener.accepted = new FakeLink(PEER);
        AndroidCapabilityProtocol.Response retried = module.handle(
                request("2", "bt.rfcomm.listen", "{\"timeout_ms\":1000}"));
        assertTrue(retried.isOk());
        assertEquals(PEER, retried.getFields().get("address"));
    }

    @Test
    public void acceptWithoutAListenIsNotListening() {
        BluetoothRfcommModule module = module(new FakeTransport());
        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.rfcomm.accept"));
        assertFalse(response.isOk());
        assertEquals("bt-rfcomm-not-listening", response.getError());
    }

    @Test
    public void acceptJoinsAnInflightListenForTheRestOfItsWindow() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.nextListener.gated = true;
        BluetoothRfcommModule module = module(transport);

        AtomicReference<AndroidCapabilityProtocol.Response> listenOut =
                new AtomicReference<>();
        Thread owner = new Thread(() -> listenOut.set(module.handle(
                request("1", "bt.rfcomm.listen", "{\"timeout_ms\":5000}"))));
        owner.start();
        assertTrue(transport.nextListener.entered.await(5, TimeUnit.SECONDS));

        AtomicReference<AndroidCapabilityProtocol.Response> acceptOut =
                new AtomicReference<>();
        Thread joiner = new Thread(() -> acceptOut.set(
                module.handle(request("2", "bt.rfcomm.accept"))));
        joiner.start();
        // The joiner must still be waiting while the window is open.
        Thread.sleep(150);
        assertTrue(joiner.isAlive());

        transport.nextListener.open(new FakeLink(PEER));
        owner.join(5000);
        joiner.join(5000);

        assertTrue(listenOut.get().isOk());
        assertEquals(PEER, listenOut.get().getFields().get("address"));
        assertTrue(acceptOut.get().isOk());
        assertEquals(Boolean.TRUE, acceptOut.get().getFields().get("connected"));
        assertEquals(PEER, acceptOut.get().getFields().get("address"));
    }

    @Test
    public void aSecondListenDuringAnInflightOneIsAlreadyListening() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.nextListener.gated = true;
        BluetoothRfcommModule module = module(transport);

        AtomicReference<AndroidCapabilityProtocol.Response> listenOut =
                new AtomicReference<>();
        Thread owner = new Thread(() -> listenOut.set(module.handle(
                request("1", "bt.rfcomm.listen", "{\"timeout_ms\":5000}"))));
        owner.start();
        try {
            assertTrue(transport.nextListener.entered.await(5, TimeUnit.SECONDS));

            AndroidCapabilityProtocol.Response second = module.handle(
                    request("2", "bt.rfcomm.listen", "{\"timeout_ms\":1000}"));

            assertFalse(second.isOk());
            assertEquals("bt-rfcomm-already-listening", second.getError());
        } finally {
            transport.nextListener.open(new FakeLink(PEER));
            owner.join(5000);
        }
        assertTrue(listenOut.get().isOk());
    }

    @Test
    public void closeDuringListenAbortsTheWaitAndTheListener() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.nextListener.gated = true;
        BluetoothRfcommModule module = module(transport);

        AtomicReference<AndroidCapabilityProtocol.Response> listenOut =
                new AtomicReference<>();
        Thread owner = new Thread(() -> listenOut.set(module.handle(
                request("1", "bt.rfcomm.listen", "{\"timeout_ms\":5000}"))));
        owner.start();
        assertTrue(transport.nextListener.entered.await(5, TimeUnit.SECONDS));

        AndroidCapabilityProtocol.Response closed = module.handle(
                request("2", "bt.rfcomm.close"));
        assertTrue(closed.isOk());
        assertEquals(Boolean.TRUE, closed.getFields().get("closed"));
        assertTrue(transport.nextListener.closed);

        owner.join(5000);
        assertFalse(listenOut.get().isOk());
        // The listener was closed underneath the owner's accept.
        assertEquals("bt-rfcomm-listen-failed", listenOut.get().getError());
    }

    @Test
    public void listenPlatformFailureIsTyped() {
        FakeTransport transport = new FakeTransport();
        transport.listenFailure = new IOException("sdp busy");
        BluetoothRfcommModule module = module(transport);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.rfcomm.listen"));

        assertFalse(response.isOk());
        assertEquals("bt-rfcomm-listen-failed", response.getError());
    }

    @Test
    public void listenWhileConnectedIsAlreadyConnected() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        assertTrue(module.handle(request("1", "bt.rfcomm.connect",
                "{\"address\":\"" + PEER + "\"}")).isOk());

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.rfcomm.listen"));

        assertFalse(response.isOk());
        assertEquals("bt-rfcomm-already-connected", response.getError());
    }

    // ------------------------------------------------------------------
    // connect
    // ------------------------------------------------------------------

    @Test
    public void connectRecordsAddressUuidAndTimeoutAndReportsPeer() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        String uuid = "00001101-0000-1000-8000-00805f9b34fb";

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.rfcomm.connect",
                        "{\"address\":\"" + PEER + "\",\"service_uuid\":\"" + uuid
                                + "\",\"timeout_ms\":3000}"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("connected"));
        assertEquals(PEER, response.getFields().get("address"));
        assertEquals(PEER, transport.connectAddress);
        assertEquals(UUID.fromString(uuid), transport.connectUuid);
        assertEquals(3000L, transport.connectTimeoutMs);
    }

    @Test
    public void connectDefaultsToTheSppServiceUuid() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);

        assertTrue(module.handle(request("1", "bt.rfcomm.connect",
                "{\"address\":\"" + PEER + "\"}")).isOk());

        assertEquals(UUID.fromString(BluetoothRfcommModule.DEFAULT_SERVICE_UUID),
                transport.connectUuid);
    }

    @Test
    public void connectRejectsAMalformedAddressAsDeviceUnknown() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.rfcomm.connect", "{\"address\":\"not-a-mac\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-device-unknown:not-a-mac", response.getError());
        assertNull(transport.connectAddress);
    }

    @Test
    public void connectTimeoutAndIoFailureAreTyped() {
        FakeTransport timeout = new FakeTransport();
        timeout.connectFailure = new SocketTimeoutException("slow");
        AndroidCapabilityProtocol.Response timedOut = module(timeout).handle(
                request("1", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertFalse(timedOut.isOk());
        assertEquals("bt-rfcomm-connect-failed:timeout", timedOut.getError());

        FakeTransport io = new FakeTransport();
        io.connectFailure = new IOException("refused");
        AndroidCapabilityProtocol.Response failed = module(io).handle(
                request("2", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertFalse(failed.isOk());
        assertEquals("bt-rfcomm-connect-failed:io", failed.getError());

        FakeTransport unknown = new FakeTransport();
        unknown.connectRuntime = new IllegalArgumentException("bad address");
        AndroidCapabilityProtocol.Response device = module(unknown).handle(
                request("3", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertFalse(device.isOk());
        assertEquals("bt-device-unknown:" + PEER, device.getError());
    }

    @Test
    public void connectWhileConnectedOrListeningIsRejected() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        assertTrue(module.handle(request("1", "bt.rfcomm.connect",
                "{\"address\":\"" + PEER + "\"}")).isOk());

        AndroidCapabilityProtocol.Response again = module.handle(
                request("2", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertFalse(again.isOk());
        assertEquals("bt-rfcomm-already-connected", again.getError());
    }

    // ------------------------------------------------------------------
    // read / write
    // ------------------------------------------------------------------

    @Test
    public void readWithoutALinkIsNotConnected() {
        BluetoothRfcommModule module = module(new FakeTransport());
        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.rfcomm.read"));
        assertFalse(response.isOk());
        assertEquals("bt-rfcomm-not-connected", response.getError());
    }

    @Test
    public void readReturnsHexBytesLengthAndNoEof() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);
        transport.link.reads.add(new byte[] {(byte) 0xde, (byte) 0xad,
                (byte) 0xbe, (byte) 0xef});

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.rfcomm.read", "{\"timeout_ms\":1000}"));

        assertTrue(response.isOk());
        assertEquals("deadbeef", response.getFields().get("data_hex"));
        assertEquals(4L, response.getFields().get("length"));
        assertEquals(Boolean.FALSE, response.getFields().get("eof"));
        assertEquals(1000L, transport.link.lastReadTimeoutMs);
    }

    @Test
    public void readTimeoutIsTypedAndKeepsTheLink() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);
        transport.link.reads.add(FakeLink.TIMEOUT);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.rfcomm.read", "{\"timeout_ms\":50}"));

        assertFalse(response.isOk());
        assertEquals("bt-rfcomm-timeout", response.getError());
        // The link survives a timeout: the next read works again.
        transport.link.reads.add(new byte[] {1});
        assertTrue(module.handle(request("3", "bt.rfcomm.read")).isOk());
    }

    @Test
    public void readEofRetiresTheLink() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);
        transport.link.reads.add(FakeLink.EOF);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.rfcomm.read"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("eof"));
        assertEquals(0L, response.getFields().get("length"));
        assertEquals("", response.getFields().get("data_hex"));
        assertTrue(transport.link.closed);

        AndroidCapabilityProtocol.Response after = module.handle(
                request("3", "bt.rfcomm.read"));
        assertFalse(after.isOk());
        assertEquals("bt-rfcomm-not-connected", after.getError());
    }

    @Test
    public void aDeadLinkReadsAsEofAndIsRetired() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);
        transport.link.reads.add(FakeLink.FAIL);

        AndroidCapabilityProtocol.Response response = module.handle(
                request("2", "bt.rfcomm.read"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("eof"));
        assertTrue(transport.link.closed);
    }

    @Test
    public void writeAcceptsExactlyOneValueForm() {
        BluetoothRfcommModule module = module(new FakeTransport());
        connect(module);
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("1", "bt.rfcomm.write", "{}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("2", "bt.rfcomm.write",
                        "{\"value_hex\":\"aa\",\"value_utf8\":\"x\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("3", "bt.rfcomm.write", "{\"value_hex\":\"abc\"}")));
        assertThrows(CapabilityParams.Invalid.class, () -> module.handle(
                request("4", "bt.rfcomm.write", "{\"value_hex\":\"zz\"}")));
    }

    @Test
    public void writeHexAndUtf8BothReachTheLink() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);

        AndroidCapabilityProtocol.Response hex = module.handle(
                request("1", "bt.rfcomm.write", "{\"value_hex\":\"deadbeef\"}"));
        assertTrue(hex.isOk());
        assertEquals(4L, hex.getFields().get("written"));
        assertEquals("deadbeef", hex(transport.link.written));

        AndroidCapabilityProtocol.Response utf8 = module.handle(
                request("2", "bt.rfcomm.write", "{\"value_utf8\":\"hi\"}"));
        assertTrue(utf8.isOk());
        assertEquals(2L, utf8.getFields().get("written"));
        assertEquals("hi", new String(transport.link.written, StandardCharsets.UTF_8));
    }

    @Test
    public void writeOnADeadLinkIsTypedAndRetiresIt() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);
        transport.link.writeFailure = new IOException("broken pipe");

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.rfcomm.write", "{\"value_hex\":\"00\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-rfcomm-write-failed", response.getError());
        assertTrue(transport.link.closed);
        AndroidCapabilityProtocol.Response after = module.handle(
                request("2", "bt.rfcomm.write", "{\"value_hex\":\"00\"}"));
        assertEquals("bt-rfcomm-not-connected", after.getError());
    }

    // ------------------------------------------------------------------
    // close
    // ------------------------------------------------------------------

    @Test
    public void closeIsIdempotentAndReleasesEverything() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule module = module(transport);
        connect(module);

        AndroidCapabilityProtocol.Response first = module.handle(
                request("1", "bt.rfcomm.close"));
        AndroidCapabilityProtocol.Response second = module.handle(
                request("2", "bt.rfcomm.close"));

        assertTrue(first.isOk());
        assertEquals(Boolean.TRUE, first.getFields().get("closed"));
        assertTrue(second.isOk());
        assertTrue(transport.link.closed);

        module.close();
        assertTrue(transport.closed);
    }

    // ------------------------------------------------------------------
    // permission and radio gates
    // ------------------------------------------------------------------

    @Test
    public void aMissingConnectGrantIsTheTypedPermissionPair() {
        FakeTransport transport = new FakeTransport();
        BluetoothRfcommModule required = new BluetoothRfcommModule(
                transport, () -> CapabilityPermission.REQUIRED);
        AndroidCapabilityProtocol.Response need = required.handle(
                request("1", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertFalse(need.isOk());
        assertEquals("bt-permission-required:grant BLUETOOTH_CONNECT"
                + " via permission.request", need.getError());
        assertNull(transport.connectAddress);

        BluetoothRfcommModule denied = new BluetoothRfcommModule(
                new FakeTransport(), () -> CapabilityPermission.DENIED);
        AndroidCapabilityProtocol.Response no = denied.handle(
                request("2", "bt.rfcomm.listen"));
        assertFalse(no.isOk());
        assertEquals("bt-permission-denied", no.getError());
    }

    @Test
    public void adapterStateIsTheTypedUnavailable() {
        FakeTransport absent = new FakeTransport();
        absent.unavailable = "bt-unavailable";
        AndroidCapabilityProtocol.Response noAdapter = module(absent).handle(
                request("1", "bt.rfcomm.listen"));
        assertEquals("bt-unavailable", noAdapter.getError());

        FakeTransport off = new FakeTransport();
        off.unavailable = "bt-unavailable:bluetooth is off";
        AndroidCapabilityProtocol.Response radioOff = module(off).handle(
                request("2", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertEquals("bt-unavailable:bluetooth is off", radioOff.getError());
    }

    // ------------------------------------------------------------------
    // fakes
    // ------------------------------------------------------------------

    /** Scripted {@link BluetoothRfcommModule.RfcommTransport}. */
    private static final class FakeTransport
            implements BluetoothRfcommModule.RfcommTransport {
        String unavailable;
        IOException listenFailure;
        IOException connectFailure;
        RuntimeException connectRuntime;
        FakeListener nextListener = new FakeListener();
        FakeLink link;
        boolean closed;

        String listenName;
        UUID listenUuid;
        String connectAddress;
        UUID connectUuid;
        long connectTimeoutMs = -1;

        @Override
        public String unavailableError() {
            return unavailable;
        }

        @Override
        public RfcommListener listen(String name, UUID serviceUuid)
                throws IOException {
            listenName = name;
            listenUuid = serviceUuid;
            if (listenFailure != null) {
                throw listenFailure;
            }
            return nextListener;
        }

        @Override
        public RfcommLink connect(String address, UUID serviceUuid,
                                  long timeoutMillis) throws IOException {
            connectAddress = address;
            connectUuid = serviceUuid;
            connectTimeoutMs = timeoutMillis;
            if (connectRuntime != null) {
                throw connectRuntime;
            }
            if (connectFailure != null) {
                throw connectFailure;
            }
            link = new FakeLink(address);
            return link;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A fake listener whose {@code accept} honours its bound: a scripted
     * immediate answer, a timeout, a failure, or a gate the test opens when
     * a "peer" arrives.
     */
    private static final class FakeListener
            implements BluetoothRfcommModule.RfcommTransport.RfcommListener {
        RfcommLink accepted;
        boolean timeout;
        IOException failure;
        /** When true, accept parks until {@link #open} or the window ends. */
        boolean gated;
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        volatile RfcommLink gatedLink;
        volatile boolean closed;
        long lastTimeoutMs = -1;

        @Override
        public RfcommLink accept(long timeoutMillis) throws IOException {
            lastTimeoutMs = timeoutMillis;
            entered.countDown();
            if (failure != null) {
                throw failure;
            }
            if (!gated) {
                if (timeout) {
                    throw new SocketTimeoutException("accept timed out");
                }
                return accepted;
            }
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!closed && gate.getCount() > 0) {
                if (System.currentTimeMillis() >= deadline) {
                    throw new SocketTimeoutException("accept timed out");
                }
                try {
                    gate.await(Math.min(5L, Math.max(1L,
                            deadline - System.currentTimeMillis())),
                            TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            if (closed) {
                throw new IOException("listener closed");
            }
            return gatedLink;
        }

        void open(RfcommLink peer) {
            gatedLink = peer;
            gate.countDown();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** A fake link with a scripted read queue and a recorded write sink. */
    private static final class FakeLink
            implements BluetoothRfcommModule.RfcommTransport.RfcommLink {
        static final Object EOF = new Object();
        static final Object TIMEOUT = new Object();
        static final Object FAIL = new Object();

        final String address;
        final Deque<Object> reads = new ArrayDeque<>();
        IOException writeFailure;
        byte[] written;
        boolean closed;
        long lastReadTimeoutMs = -1;

        FakeLink(String address) {
            this.address = address;
        }

        @Override
        public String peerAddress() {
            return address;
        }

        @Override
        public int read(byte[] buffer, long timeoutMillis) throws IOException {
            lastReadTimeoutMs = timeoutMillis;
            Object next = reads.poll();
            if (next == null) {
                throw new SocketTimeoutException("nothing scripted");
            }
            if (next == EOF) {
                return -1;
            }
            if (next == TIMEOUT) {
                throw new SocketTimeoutException("read timed out");
            }
            if (next == FAIL) {
                throw new IOException("link dead");
            }
            byte[] data = (byte[]) next;
            System.arraycopy(data, 0, buffer, 0, data.length);
            return data.length;
        }

        @Override
        public void write(byte[] data, long timeoutMillis) throws IOException {
            if (writeFailure != null) {
                throw writeFailure;
            }
            written = data.clone();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static BluetoothRfcommModule module(FakeTransport transport) {
        return new BluetoothRfcommModule(
                transport, () -> CapabilityPermission.GRANTED);
    }

    private static void connect(BluetoothRfcommModule module) {
        AndroidCapabilityProtocol.Response response = module.handle(
                request("c", "bt.rfcomm.connect", "{\"address\":\"" + PEER + "\"}"));
        assertTrue(response.isOk());
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\","
                        + "\"method\":\"" + method + "\"}");
    }

    private static AndroidCapabilityProtocol.Request request(String id, String method,
                                                             String paramsJson) {
        return AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"" + id + "\",\"token\":\"t\","
                        + "\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
    }
}
