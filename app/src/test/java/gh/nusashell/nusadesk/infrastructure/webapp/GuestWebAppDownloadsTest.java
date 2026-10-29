package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import gh.nusashell.nusadesk.application.webapp.WebAppCredentialStore;
import gh.nusashell.nusadesk.application.webapp.WebAppDownloadPort;
import gh.nusashell.nusadesk.domain.webapp.*;

public class GuestWebAppDownloadsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private FixtureServer server;
    private GuestWebAppDownloads downloads;
    private Path rootfs;
    private String origin;
    private final AtomicReference<String> cookie = new AtomicReference<>();
    private final AtomicReference<String> agent = new AtomicReference<>();
    private final AtomicReference<WebAppSignInCredential> credential = new AtomicReference<>();

    @Before public void setUp() throws Exception {
        rootfs = temp.newFolder("active").toPath();
        Files.createDirectory(rootfs.resolve("root"));
        server = new FixtureServer();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/file", exchange -> {
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = new byte[] {0, 1, 2, (byte) 255};
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/file");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/external", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://example.org/report");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/blocked", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:22022/file");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(new byte[] {1, 2}); exchange.close();
        });
        server.createContext("/auth", exchange -> {
            if (!"Basic dXNlcjpwYXNz".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
            } else {
                exchange.sendResponseHeaders(200, 1); exchange.getResponseBody().write(42);
            }
            exchange.close();
        });
        server.start();
        WebAppDefinition definition = new WebAppDefinition(WebAppId.of("download-test"), "Downloads", null,
                server.getAddress().getPort(), 0, 0, 0);
        downloads = new GuestWebAppDownloads(rootfs, definition, new WebAppCredentialStore() {
            public WebAppSignInCredential find(WebAppId id) { return credential.get(); }
            public void save(WebAppSignInCredential value) { credential.set(value); }
            public void clear(WebAppId id) { credential.set(null); }
        });
    }

    @After public void tearDown() { downloads.close(); server.stop(0); }

    @Test public void streamsExactBytesAndSessionHeadersToGuestWithoutOverwriting() throws Exception {
        Path directory = Files.createDirectory(rootfs.resolve("root/Downloads"));
        Files.writeString(directory.resolve("report.bin"), "old guest file");
        Result result = fetch("/redirect", "report.bin");
        assertEquals(WebAppDownloadStatus.State.SAVED, result.last().getState());
        assertEquals("report (1).bin", result.last().getName());
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, Files.readAllBytes(directory.resolve("report (1).bin")));
        assertEquals("old guest file", Files.readString(directory.resolve("report.bin")));
        assertEquals("session=abc", cookie.get());
        assertEquals("NusaDesk-Test", agent.get());
        assertNoPartials(directory);
    }

    @Test public void externalAndBlockedRedirectsNeverFetchTheirTargets() throws Exception {
        Result external = fetch("/external", "a");
        assertEquals("https://example.org/report", external.external);
        Result blocked = fetch("/blocked", "b");
        assertEquals("http://127.0.0.1:22022/file", blocked.blocked);
        assertFalse(Files.exists(rootfs.resolve("root/Downloads")));
    }

    @Test public void invalidSchemesAndOtherPortsAreNotFetched() throws Exception {
        for (String url : new String[] {"about:blank", "about:blank#fragment", "blob:" + origin + "/id", "data:text/plain,hello",
                "file:///root/a", "http://127.0.0.1:22022/file"}) {
            Result result = request(url, "a");
            assertEquals(url, result.blocked);
        }
    }

    @Test public void truncatedResponseCleansPartialAndCanBeRetried() throws Exception {
        assertEquals(WebAppDownloadStatus.State.FAILED, fetch("/broken", "a").last().getState());
        Path directory = rootfs.resolve("root/Downloads");
        assertFalse(Files.exists(directory.resolve("a")));
        assertNoPartials(directory);
        assertEquals(WebAppDownloadStatus.State.SAVED, fetch("/file", "a").last().getState());
    }

    @Test public void httpFailureDoesNotCreateDestinationFile() throws Exception {
        Result result = fetch("/missing", "missing");
        assertEquals(WebAppDownloadStatus.State.FAILED, result.last().getState());
        assertEquals("HTTP 404", result.last().getDetail());
        assertFalse(Files.exists(rootfs.resolve("root/Downloads")));
    }

    @Test public void savedAppBasicCredentialAnswersProtectedDownload() throws Exception {
        credential.set(new WebAppSignInCredential(WebAppId.of("download-test"), "user", "pass"));
        assertEquals(WebAppDownloadStatus.State.SAVED, fetch("/auth", "protected").last().getState());
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(rootfs.resolve("root/Downloads/protected")));
    }

    @Test public void guestSymlinkCannotRedirectTheHostWriter() throws Exception {
        Path outside = temp.newFolder("outside").toPath();
        Files.createSymbolicLink(rootfs.resolve("root/Downloads"), outside);
        assertEquals(WebAppDownloadStatus.State.FAILED, fetch("/file", "escape").last().getState());
        assertFalse(Files.exists(outside.resolve("escape")));
    }

    @Test public void binaryChunksPreserveBytesAndUseTheSameCollisionPolicy() throws Exception {
        Result result = new Result();
        CountDownLatch ready = new CountDownLatch(1);
        WebAppDownloadPort.BinaryTransfer transfer = downloads.beginBinary("binary.bin", 4, result,
                error -> { assertNull(error); ready.countDown(); });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        CountDownLatch written = new CountDownLatch(1);
        transfer.append(new byte[] {0, 1, 2, (byte) 255}, error -> { assertNull(error); written.countDown(); });
        assertTrue(written.await(5, TimeUnit.SECONDS));
        CountDownLatch finished = new CountDownLatch(1);
        transfer.finish(error -> { assertNull(error); finished.countDown(); });
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, Files.readAllBytes(rootfs.resolve("root/Downloads/binary.bin")));
        assertEquals("binary (1).bin", fetch("/file", "binary.bin").last().getName());
        assertNoPartials(rootfs.resolve("root/Downloads"));
    }

    @Test public void incompleteBinaryIsNotPublishedAndAbortRemovesItsPartial() throws Exception {
        Result result = new Result();
        CountDownLatch ready = new CountDownLatch(1);
        WebAppDownloadPort.BinaryTransfer transfer = downloads.beginBinary("missing.bin", 10, result, error -> ready.countDown());
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        transfer.finish(error -> assertNotNull(error));
        assertTrue(result.done.await(5, TimeUnit.SECONDS));
        assertEquals(WebAppDownloadStatus.State.FAILED, result.last().getState());
        assertFalse(Files.exists(rootfs.resolve("root/Downloads/missing.bin")));
        assertNoPartials(rootfs.resolve("root/Downloads"));
    }

    @Test public void closeCancelsActiveAndQueuedDownloadsAndRemovesPartial() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            started.countDown();
            try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        Result slow = new Result();
        downloads.download(origin + "/slow", null, null, "slow", slow);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(slow.started.await(5, TimeUnit.SECONDS));
        downloads.download(origin + "/file", null, null, "queued", new Result());
        downloads.close(); finish.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Path directory = rootfs.resolve("root/Downloads");
        while (System.nanoTime() < deadline) {
            try (var files = Files.list(directory)) {
                if (files.findAny().isEmpty()) break;
            }
            Thread.sleep(10);
        }
        assertNoPartials(directory);
        assertFalse(Files.exists(directory.resolve("slow")));
        assertFalse(Files.exists(directory.resolve("queued")));
    }

    private Result fetch(String path, String name) throws Exception { return request(origin + path, name); }
    private Result request(String url, String name) throws Exception {
        Result result = new Result();
        downloads.download(url, "NusaDesk-Test", "session=abc", name, result);
        assertTrue("download callback", result.done.await(5, TimeUnit.SECONDS));
        return result;
    }
    private static void assertNoPartials(Path directory) throws Exception {
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".part"))); }
    }
    private static final class Result implements WebAppDownloadPort.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final List<WebAppDownloadStatus> statuses = Collections.synchronizedList(new ArrayList<>());
        volatile String external, blocked;
        public void onStatus(WebAppDownloadStatus status) {
            statuses.add(status);
            if (status.getState() == WebAppDownloadStatus.State.DOWNLOADING) started.countDown();
            if (status.getState() == WebAppDownloadStatus.State.SAVED || status.getState() == WebAppDownloadStatus.State.FAILED) done.countDown();
        }
        public void onExternal(String url) { external = url; done.countDown(); }
        public void onBlocked(String url) { blocked = url; done.countDown(); }
        WebAppDownloadStatus last() { return statuses.get(statuses.size() - 1); }
    }
    private interface Handler { void handle(Exchange exchange) throws IOException; }
    private static final class Headers extends HashMap<String, String> {
        void set(String key, String value) { put(key.toLowerCase(java.util.Locale.ROOT), value); }
        String getFirst(String key) { return get(key.toLowerCase(java.util.Locale.ROOT)); }
    }
    private static final class Exchange {
        final Socket socket;
        final Headers request = new Headers(), response = new Headers();
        Exchange(Socket socket) { this.socket = socket; }
        Headers getRequestHeaders() { return request; }
        Headers getResponseHeaders() { return response; }
        void sendResponseHeaders(int code, long length) throws IOException {
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + code + " Result\r\nContent-Length: " + Math.max(0, length)
                    + "\r\nConnection: close\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            for (Map.Entry<String, String> entry : response.entrySet())
                out.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); out.flush();
        }
        OutputStream getResponseBody() throws IOException { return socket.getOutputStream(); }
        void close() throws IOException { socket.close(); }
    }
    private static final class FixtureServer {
        final ServerSocket socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        final Map<String, Handler> routes = new java.util.concurrent.ConcurrentHashMap<>();
        final ExecutorService clients = Executors.newCachedThreadPool();
        FixtureServer() throws IOException { }
        InetSocketAddress getAddress() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
        void createContext(String path, Handler handler) { routes.put(path, handler); }
        void start() {
            clients.execute(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        clients.execute(() -> {
                            try (Socket owned = client) {
                                owned.setSoTimeout(5000);
                                BufferedReader reader = new BufferedReader(new InputStreamReader(owned.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                                String first = reader.readLine();
                                if (first == null) return;
                                Exchange exchange = new Exchange(owned);
                                for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                                    int colon = line.indexOf(':');
                                    if (colon > 0) exchange.request.set(line.substring(0, colon), line.substring(colon + 1).trim());
                                }
                                Handler handler = routes.get(first.split(" ")[1]);
                                if (handler != null) handler.handle(exchange);
                                else { exchange.sendResponseHeaders(404, -1); exchange.close(); }
                            } catch (IOException ignored) { /* Client cancellation is intentional. */ }
                        });
                    } catch (IOException stopped) { return; }
                }
            });
        }
        void stop(int ignored) {
            try { socket.close(); } catch (IOException failure) { throw new AssertionError(failure); }
            clients.shutdownNow();
        }
    }

}
