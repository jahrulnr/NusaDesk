package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.view.ViewGroup;
import android.webkit.WebView;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWebView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder;

/**
 * The web-app surface's cache actions (ADR-0064) under Robolectric: the
 * no-cache reload drops the app's WebView cache and reloads the page in
 * place — the same WebView, the same URL — while the plain reload keeps its
 * fresh-renderer, entry-point contract.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppSurfaceViewCacheTest {

    private ExecutorService executor;
    private FixtureServer server;
    private WebAppSurfaceView surface;
    private WebAppDefinition definition;

    @Before
    public void setUp() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        server = FixtureServer.start();
        surface = new WebAppSurfaceView(RuntimeEnvironment.getApplication());
        definition = new WebAppDefinition(
                WebAppId.of("app-1"), "Notebook", null, server.port(), 0L, 0L, 0);
    }

    @After
    public void tearDown() throws Exception {
        surface.release();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                fail("probe executor did not stop");
            }
        } finally {
            server.close();
        }
    }

    @Test
    public void reloadWithoutCacheDropsTheCacheAndReloadsInPlace() {
        bind();
        WebView before = surfaceWebView();
        assertNotNull("a reachable app gets a WebView", before);
        ShadowWebView shadow = Shadow.extract(before);

        surface.reloadWithoutCache();
        settle();

        assertSame("the current page is kept, not rebuilt from the entry point",
                before, surfaceWebView());
        assertTrue("the app's WebView cache is dropped first",
                shadow.wasClearCacheCalled());
        assertTrue(shadow.didClearCacheIncludeDiskFiles());
        assertEquals("the page reloads once, in place",
                1, shadow.getReloadInvocations());
        assertEquals(WebAppSurfaceView.State.LOADED, surface.getState());
        assertEquals("the URL is the page the user is on, not a re-probe",
                definition.getEndpointUrl(), before.getUrl());
    }

    @Test
    public void reloadWithoutCacheOnAnUnboundSurfaceIsANoOp() {
        surface.reloadWithoutCache();

        assertEquals(WebAppSurfaceView.State.PROBING, surface.getState());
    }

    @Test
    public void clearWebCacheClearsThroughTheLiveWebViewAndSaysSo() {
        bind();
        WebView live = surfaceWebView();
        assertNotNull(live);

        surface.clearWebCache();

        assertTrue("a loaded page's WebView performs the app-wide clear",
                ((ShadowWebView) Shadow.extract(live)).wasClearCacheCalled());
        assertEquals("Cache cleared.", ShadowToast.getTextOfLatestToast());
        assertEquals("the page itself is left alone",
                WebAppSurfaceView.State.LOADED, surface.getState());
    }

    @Test
    public void clearWebCacheWithoutALoadedPageStillAnswers() {
        // Nothing is bound and no WebView exists, so a throwaway one does the
        // clear: the action must still report its outcome rather than do
        // nothing silently.
        surface.clearWebCache();

        assertEquals("Cache cleared.", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void clearWebCacheOnABoundButUnreachableSurfaceStillAnswers() throws Exception {
        // Bound, so a definition exists — but the endpoint never answered, so
        // no WebView was created and the throwaway path runs the clear.
        server.stopAnswering();
        // The unavailable endpoint must stay owned by this fixture. Closing
        // it would let an unrelated listener reuse the port before the probe.
        try (ServerSocket competitor = new ServerSocket()) {
            try {
                competitor.bind(new InetSocketAddress("127.0.0.1", server.port()));
                fail("unreachable fixture released its port to another listener");
            } catch (BindException expected) {
                // Still owned; no unrelated HTTP app can make the probe pass.
            }
        }
        bind();
        assertEquals(WebAppSurfaceView.State.UNREACHABLE, surface.getState());
        assertNull("no WebView exists while the endpoint is down",
                surfaceWebView());

        surface.clearWebCache();

        assertEquals("Cache cleared.", ShadowToast.getTextOfLatestToast());
        assertEquals(WebAppSurfaceView.State.UNREACHABLE, surface.getState());
    }

    private void bind() {
        surface.bind(definition, executor, new HttpAuthResponder() {
            @Override
            public void onChallenge(
                    String host, String realm, HttpAuthResponder.Answer answer) {
                answer.cancel();
            }
        });
        settle();
    }

    /** Drains the main looper until the background probe's result is applied. */
    private void settle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            if (surface.getState() != WebAppSurfaceView.State.PROBING) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for the probe");
                return;
            }
        }
        fail("the probe did not settle within 10 seconds");
    }

    private WebView surfaceWebView() {
        ViewGroup container = surface.findViewById(R.id.webapp_view_container);
        return container.getChildCount() == 0 ? null : (WebView) container.getChildAt(0);
    }

    /** A minimal loopback HTTP server: any request gets one short 200 response. */
    private static final class FixtureServer implements Closeable {

        private final ServerSocket serverSocket;
        private final Thread thread;
        private volatile boolean closed;
        private volatile boolean answering = true;

        private FixtureServer() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 4);
            thread = new Thread(this::serve, "webapp-cache-fixture");
            thread.setDaemon(true);
            thread.start();
        }

        /** Binds on a port a web-app definition accepts. */
        static FixtureServer start() throws IOException {
            while (true) {
                FixtureServer server = new FixtureServer();
                if (!GuestPortPolicy.isReserved(server.port())) {
                    return server;
                }
                server.close();
            }
        }

        void stopAnswering() {
            // Reject TCP connections with a reset, while retaining the bound
            // endpoint. A closed listener cannot promise an unused port.
            answering = false;
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            byte[] body = "<html><body>fixture</body></html>".getBytes(StandardCharsets.UTF_8);
            while (!closed) {
                try (Socket socket = serverSocket.accept()) {
                    if (!answering) {
                        socket.setSoLinger(true, 0);
                        continue;
                    }
                    InputStream in = socket.getInputStream();
                    byte[] request = new byte[1024];
                    in.read(request);
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/html; charset=utf-8\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                } catch (IOException closedOrInterrupted) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
        }
    }
}
