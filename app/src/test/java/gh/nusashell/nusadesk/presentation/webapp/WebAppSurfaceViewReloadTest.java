package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import android.view.ViewGroup;
import android.webkit.WebView;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import org.robolectric.shadows.ShadowLooper;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpAuthResponder;

/**
 * The web-app surface's reload action (ADR-0061) under Robolectric: a reload
 * re-probes the app's registered endpoint and loads it again on a fresh
 * renderer, which is what recovers a page that navigated somewhere with no way
 * back. The app's ordinary load path is the same probe the failure panel uses,
 * so the test drives it through a real loopback fixture server.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppSurfaceViewReloadTest {

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
        server.close();
        executor.shutdownNow();
    }

    @Test
    public void reloadLoadsTheRegisteredAppAgainOnAFreshRenderer() {
        bind();
        assertEquals("the fixture answers, so the app is loaded",
                WebAppSurfaceView.State.LOADED, surface.getState());
        WebView first = surfaceWebView();
        assertNotNull("a reachable app gets a WebView", first);

        surface.reload();
        settle();

        assertEquals(WebAppSurfaceView.State.LOADED, surface.getState());
        WebView second = surfaceWebView();
        assertNotSame("a reload starts from a clean renderer, so whatever page "
                + "the app had navigated to is gone", first, second);
        assertEquals("the registered address is what loads",
                definition.getEndpointUrl(), second.getUrl());
        assertEquals("only the permanent root tab remains",
                1, surface.getTabs().size());
    }

    @Test
    public void reloadBeforeBindingIsANoOp() {
        surface.reload();

        assertEquals("nothing is bound, so there is nothing to reload",
                WebAppSurfaceView.State.PROBING, surface.getState());
    }

    @Test
    public void anUnreachableAppStaysRecoverableThroughTheSameAction() throws Exception {
        server.close();
        bind();

        assertEquals("nothing answers on the port any more",
                WebAppSurfaceView.State.UNREACHABLE, surface.getState());

        surface.reload();
        settle();

        assertEquals("the reload is the same probe, so it stays honestly "
                + "unreachable instead of showing a browser error page",
                WebAppSurfaceView.State.UNREACHABLE, surface.getState());
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

        private FixtureServer() throws IOException {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 4);
            thread = new Thread(this::serve, "webapp-reload-fixture");
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

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            byte[] body = "<html><body>fixture</body></html>".getBytes(StandardCharsets.UTF_8);
            while (!closed) {
                try (Socket socket = serverSocket.accept()) {
                    InputStream in = socket.getInputStream();
                    byte[] request = new byte[1024];
                    // Read the request head; a GET is answered without waiting
                    // for a body the fixture never sends.
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
