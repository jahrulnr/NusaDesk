package gh.nusashell.nusadesk.infrastructure.webapp;

import android.graphics.Bitmap;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The fetch path against a real loopback server.
 *
 * <p>The request itself is real — a socket on {@code 127.0.0.1} answers with a
 * canned HTTP response — so what the app actually asks for, what it does with a
 * redirect, and how it bounds a response are asserted rather than assumed. The
 * image decode is the one step a JVM unit test cannot perform (a real
 * {@link Bitmap} cannot be constructed without a device), so it sits behind
 * {@link WebAppFaviconFetcher.Decoder} and is replaced by a recording fake here;
 * the Android decode itself is verified on the emulator.</p>
 */
public class WebAppFaviconFetcherTest {

    private static final int SHORT_TIMEOUT_MILLIS = 400;

    @Test
    public void theDeclaredIconIsPreferredOverTheConventionalPath() throws Exception {
        byte[] icon = new byte[] {9, 9, 9};
        try (TestServer server = TestServer.start(TestServer.routes(
                "/", TestServer.html("<html><head><link rel=\"icon\" "
                        + "href=\"./nusashell-mark.png\" type=\"image/png\"></head></html>"),
                "/nusashell-mark.png", TestServer.ok(icon),
                "/favicon.ico", TestServer.ok(new byte[] {7, 7})))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(512, 512));

            fetcher(decoder).fetch(webApp(server.port()));

            // The fake decoder never yields an image, so the declared icon is
            // tried first and the conventional path is still the fallback: the
            // declaration decides the ORDER, which is what the fix is about.
            assertEquals(3, server.requestCount());
            assertEquals("GET / HTTP/1.1", server.requestLineAt(0));
            assertEquals("GET /nusashell-mark.png HTTP/1.1", server.requestLineAt(1));
            assertEquals("GET /favicon.ico HTTP/1.1", server.requestLineAt(2));
            assertEquals("both candidates reach the decoder", 2, decoder.decodeCalls.get());
            assertEquals("the fallback's bytes are the last decode", 2, decoder.decodedLength);
        }
    }

    @Test
    public void withoutADeclarationTheConventionalPathIsStillTried() throws Exception {
        try (TestServer server = TestServer.start(TestServer.routes(
                "/", TestServer.html("<html><head><title>no icon here</title></head></html>"),
                "/favicon.ico", TestServer.ok(new byte[] {1, 2, 3})))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            fetcher(decoder).fetch(webApp(server.port()));

            assertEquals(2, server.requestCount());
            assertEquals("GET /favicon.ico HTTP/1.1", server.requestLineAt(1));
            assertEquals(3, decoder.decodedLength);
        }
    }

    @Test
    public void aCrossOriginDeclarationIsNeverFollowed() throws Exception {
        try (TestServer server = TestServer.start(TestServer.routes(
                "/", TestServer.html("<link rel=\"icon\" href=\"http://example.com/evil.png\">"),
                "/favicon.ico", TestServer.ok(new byte[] {4, 5})))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(16, 16));

            fetcher(decoder).fetch(webApp(server.port()));

            assertEquals("only the app's own origin is ever asked", 2, server.requestCount());
            assertEquals("GET /favicon.ico HTTP/1.1", server.requestLineAt(1));
        }
    }

    @Test
    public void aDocumentThatCannotBeReadStillFallsBackToTheConventionalPath() throws Exception {
        try (TestServer server = TestServer.start(TestServer.routes(
                "/favicon.ico", TestServer.ok(new byte[] {7})))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(16, 16));

            fetcher(decoder).fetch(webApp(server.port()));

            assertEquals("the document 404s, the fallback answers", 2, server.requestCount());
            assertEquals("GET /favicon.ico HTTP/1.1", server.requestLineAt(1));
            assertEquals(1, decoder.decodedLength);
        }
    }

    @Test
    public void aUsableResponseReachesTheDecoderWithTheExactBytesAndNoDownsample() throws Exception {
        byte[] payload = new byte[64];
        Arrays.fill(payload, (byte) 0x42);
        try (TestServer server = TestServer.start(TestServer.ok(payload))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            fetcher(decoder).fetch(webApp(server.port()));

            assertTrue(Arrays.equals(payload, decoder.decodedBytes));
            assertEquals(1, decoder.sampleSize);
        }
    }

    @Test
    public void anImageLargerThanTheTileIsDecodedDownsampled() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ok(new byte[] {1}))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(1_024, 512));

            fetcher(decoder).fetch(webApp(server.port()));

            assertEquals(4, decoder.sampleSize);
        }
    }

    @Test
    public void aMissingFaviconIsSimplyNoImageAndTheDecoderIsNeverCalled() throws Exception {
        try (TestServer server = TestServer.start(TestServer.status(404, "Not Found"))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            assertNull(fetcher(decoder).fetch(webApp(server.port())));

            assertEquals(0, decoder.boundsCalls.get());
            assertEquals(0, decoder.decodeCalls.get());
        }
    }

    @Test
    public void aRedirectIsNotFollowedAndTheErrorBodyIsNeverDecoded() throws Exception {
        try (TestServer server = TestServer.start(TestServer.status(302, "Found"))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            assertNull(fetcher(decoder).fetch(webApp(server.port())));

            // The document and the conventional path both answer 302: neither is
            // followed, so the redirect target is never requested and nothing
            // reaches the decoder.
            assertEquals("a redirect must never be followed into another request",
                    2, server.requestCount());
            assertEquals("GET /favicon.ico HTTP/1.1", server.requestLineAt(1));
            assertEquals(0, decoder.decodeCalls.get());
        }
    }

    @Test
    public void aDeclaredPayloadOverTheCapIsRejectedBeforeItIsRead() throws Exception {
        try (TestServer server = TestServer.start(
                TestServer.declaredLength(FaviconResponsePolicy.MAX_RESPONSE_BYTES + 1))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            assertNull(fetcher(decoder).fetch(webApp(server.port())));

            assertEquals(0, decoder.boundsCalls.get());
        }
    }

    @Test
    public void aBodyThatGrowsPastTheCapIsRejectedEvenWithoutAContentLength() throws Exception {
        try (TestServer server = TestServer.start(
                TestServer.undeclaredBody(FaviconResponsePolicy.MAX_RESPONSE_BYTES * 2))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            assertNull(fetcher(decoder).fetch(webApp(server.port())));

            assertEquals(0, decoder.boundsCalls.get());
        }
    }

    @Test
    public void bytesThatAreNotAnImageAreRejectedAfterTheRead() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ok(new byte[] {9, 9, 9, 9}))) {
            // BitmapFactory answers -1/-1 when the header is not an image.
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(-1, -1));

            assertNull(fetcher(decoder).fetch(webApp(server.port())));

            assertEquals(1, decoder.boundsCalls.get());
            assertEquals("an unreadable header must not reach the pixel decode",
                    0, decoder.decodeCalls.get());
        }
    }

    @Test
    public void anOversizedImageIsRejectedBeforeItsPixelsAreDecoded() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ok(new byte[] {1}))) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(30_000, 30_000));

            assertNull(fetcher(decoder).fetch(webApp(server.port())));

            assertEquals(0, decoder.decodeCalls.get());
        }
    }

    @Test
    public void anUnreachableEndpointIsNoImageRatherThanAFailure() throws Exception {
        int closedPort;
        try (TestServer server = TestServer.start(TestServer.ok(new byte[] {1}))) {
            closedPort = server.port();
        }
        FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

        assertNull(fetcher(decoder).fetch(webApp(closedPort)));

        assertEquals(0, decoder.boundsCalls.get());
    }

    @Test
    public void anEndpointThatNeverAnswersIsBoundedByTheReadTimeout() throws Exception {
        try (TestServer server = TestServer.start(TestServer.neverAnswers())) {
            FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

            long startedAt = System.currentTimeMillis();
            assertNull(fetcher(decoder).fetch(webApp(server.port())));
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue("a silent endpoint must not hang the fetch: " + elapsed + "ms",
                    elapsed < 10 * SHORT_TIMEOUT_MILLIS);
            assertEquals(0, decoder.boundsCalls.get());
        }
    }

    @Test
    public void rejectsInvalidConstruction() {
        FakeDecoder decoder = new FakeDecoder(new WebAppFaviconFetcher.Size(32, 32));

        assertRejected(null, 10, 10);
        assertRejected(decoder, -1, 10);
        assertRejected(decoder, 10, -1);
    }

    @Test
    public void rejectsAMissingDefinition() {
        try {
            new WebAppFaviconFetcher().fetch(null);
            assertTrue("expected a null definition to be rejected", false);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void defaultConstructionIsBoundedBySaneTimeouts() {
        assertTrue(WebAppFaviconFetcher.DEFAULT_CONNECT_TIMEOUT_MILLIS > 0);
        assertTrue(WebAppFaviconFetcher.DEFAULT_READ_TIMEOUT_MILLIS > 0);
        assertTrue(WebAppFaviconFetcher.DEFAULT_CONNECT_TIMEOUT_MILLIS <= 2_000);
        assertTrue(WebAppFaviconFetcher.DEFAULT_READ_TIMEOUT_MILLIS <= 3_000);
    }

    private static void assertRejected(
            WebAppFaviconFetcher.Decoder decoder, int connectTimeout, int readTimeout) {
        try {
            new WebAppFaviconFetcher(decoder, connectTimeout, readTimeout);
            assertTrue("expected invalid construction to be rejected", false);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static WebAppFaviconFetcher fetcher(WebAppFaviconFetcher.Decoder decoder) {
        return new WebAppFaviconFetcher(decoder, SHORT_TIMEOUT_MILLIS, SHORT_TIMEOUT_MILLIS);
    }

    private static WebAppDefinition webApp(int port) {
        return new WebAppDefinition(WebAppId.of("fixture"), "Fixture", null, port, 1, 1, 0);
    }

    /**
     * Records what the fetcher asked the decoder to do. The decode returns
     * {@code null} because a JVM unit test cannot build a real bitmap; the
     * assertions are therefore about the decision that led there.
     */
    private static final class FakeDecoder implements WebAppFaviconFetcher.Decoder {
        private final WebAppFaviconFetcher.Size bounds;
        private final AtomicInteger boundsCalls = new AtomicInteger();
        private final AtomicInteger decodeCalls = new AtomicInteger();
        private volatile byte[] decodedBytes;
        private volatile int decodedLength;
        private volatile int sampleSize;

        FakeDecoder(WebAppFaviconFetcher.Size bounds) {
            this.bounds = bounds;
        }

        @Override
        public WebAppFaviconFetcher.Size boundsOf(byte[] bytes, int length) {
            boundsCalls.incrementAndGet();
            return bounds;
        }

        @Override
        public Bitmap decode(byte[] bytes, int length, int sampleSize) {
            decodeCalls.incrementAndGet();
            decodedBytes = Arrays.copyOf(bytes, length);
            decodedLength = length;
            this.sampleSize = sampleSize;
            return null;
        }
    }

    /** A minimal loopback HTTP server: one connection at a time, canned answers. */
    private static final class TestServer implements Closeable {

        interface Responder {
            void respond(String requestLine, OutputStream out) throws IOException;
        }

        private final ServerSocket serverSocket;
        private final Thread thread;
        private final List<String> requestLines =
                Collections.synchronizedList(new ArrayList<>());
        private volatile boolean closed;

        private TestServer(Responder responder) throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 4);
            thread = new Thread(() -> serve(responder), "favicon-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Starts a server on a port a web-app definition accepts: the terminal's
         * reserved guest port is not a valid web-app port, so it is skipped.
         */
        static TestServer start(Responder responder) throws IOException {
            while (true) {
                TestServer server = new TestServer(responder);
                if (!GuestPortPolicy.isReserved(server.port())) {
                    return server;
                }
                server.close();
            }
        }

        /** Answers by request path; anything else is 404. */
        static Responder routes(Object... pairs) {
            java.util.Map<String, Responder> byPath = new java.util.LinkedHashMap<>();
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                byPath.put((String) pairs[i], (Responder) pairs[i + 1]);
            }
            return (requestLine, out) -> {
                Responder inner = byPath.get(pathOf(requestLine));
                if (inner == null) {
                    writeHead(out, "404 Not Found", "Content-Length: 0\r\n");
                    out.flush();
                    return;
                }
                inner.respond(requestLine, out);
            };
        }

        /** A document response, so the fetcher can read its icon declarations. */
        static Responder html(String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            return (requestLine, out) -> {
                writeHead(out, "200 OK", "Content-Length: " + bytes.length + "\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n");
                out.write(bytes);
                out.flush();
            };
        }

        static String pathOf(String requestLine) {
            if (requestLine == null) {
                return "";
            }
            String[] parts = requestLine.split(" ");
            return parts.length >= 2 ? parts[1] : "";
        }

        static Responder ok(byte[] body) {
            return (requestLine, out) -> {
                writeHead(out, "200 OK", "Content-Length: " + body.length + "\r\n"
                        + "Content-Type: image/png\r\n");
                out.write(body);
                out.flush();
            };
        }

        static Responder status(int code, String reason) {
            return (requestLine, out) -> {
                writeHead(out, code + " " + reason, "Content-Length: 0\r\n");
                out.flush();
            };
        }

        static Responder declaredLength(int declaredBytes) {
            return (requestLine, out) -> {
                writeHead(out, "200 OK", "Content-Length: " + declaredBytes + "\r\n"
                        + "Content-Type: image/png\r\n");
                out.flush();
            };
        }

        static Responder undeclaredBody(int bodyBytes) {
            return (requestLine, out) -> {
                writeHead(out, "200 OK", "Content-Type: image/png\r\n");
                byte[] chunk = new byte[8 * 1024];
                int written = 0;
                while (written < bodyBytes) {
                    out.write(chunk);
                    out.flush();
                    written += chunk.length;
                }
            };
        }

        static Responder neverAnswers() {
            return (requestLine, out) -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
        }

        private static void writeHead(OutputStream out, String status, String headers)
                throws IOException {
            out.write(("HTTP/1.1 " + status + "\r\n" + headers
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int requestCount() {
            return requestLines.size();
        }

        String firstRequestLine() {
            return requestLineAt(0);
        }

        String requestLineAt(int index) {
            synchronized (requestLines) {
                return index < requestLines.size() ? requestLines.get(index) : "";
            }
        }

        private void serve(Responder responder) {
            while (!closed) {
                try (Socket socket = serverSocket.accept()) {
                    String requestLine = readRequestHead(socket);
                    try (OutputStream out = socket.getOutputStream()) {
                        responder.respond(requestLine, out);
                    }
                } catch (IOException expected) {
                    // The client disconnected or the server was closed mid-answer.
                    if (closed) {
                        return;
                    }
                }
            }
        }

        private String readRequestHead(Socket socket) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return "";
            }
            requestLines.add(requestLine.trim());
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Headers are read so the client's request is fully consumed.
            }
            return requestLine.trim();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
            thread.interrupt();
        }
    }
}
