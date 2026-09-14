package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.infrastructure.runtimehost.HttpHealthProbe;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for the bounded readiness observation, using a fake probe. No real
 * socket is opened and no Linux process is started.
 */
public class WebAppReadinessObserverTest {
    private static final int CONNECT_TIMEOUT = 1_000;
    private static final int READ_TIMEOUT = 1_500;

    private final FakeProbe probe = new FakeProbe(200);
    private final WebAppReadinessObserver observer =
            new WebAppReadinessObserver(probe, CONNECT_TIMEOUT, READ_TIMEOUT);

    @Test
    public void probesTheGeneratedRootPathOnLoopbackWithTheConfiguredTimeouts() {
        observer.observe(8080);

        assertEquals(1, probe.calls.get());
        assertEquals("http", probe.scheme);
        assertEquals("127.0.0.1", probe.host);
        assertEquals(8080, probe.port);
        assertEquals("/", probe.path);
        assertEquals(CONNECT_TIMEOUT, probe.connectTimeoutMillis);
        assertEquals(READ_TIMEOUT, probe.readTimeoutMillis);
    }

    @Test
    public void anyHttpResponseCountsAsReachable() {
        int[] statuses = {200, 204, 301, 401, 403, 404, 500, 503};
        for (int status : statuses) {
            WebAppReadinessObserver.Result result =
                    new WebAppReadinessObserver(new FakeProbe(status), 10, 10).observe(8080);
            assertEquals("HTTP " + status + " proves a listener is serving HTTP",
                    WebAppReadinessObserver.Outcome.REACHABLE, result.getOutcome());
            assertTrue(result.isReachable());
            assertEquals(status, result.getHttpStatus());
        }
    }

    @Test
    public void unreachablePortReportsUnreachableWithZeroStatus() {
        WebAppReadinessObserver.Result result =
                new WebAppReadinessObserver(
                        new FakeProbe(new IOException("connection refused")), 10, 10)
                        .observe(8080);

        assertEquals(WebAppReadinessObserver.Outcome.UNREACHABLE, result.getOutcome());
        assertFalse(result.isReachable());
        assertEquals(0, result.getHttpStatus());
        assertEquals("connection refused", result.getDetail());
    }

    @Test
    public void timeoutsAreReportedAsUnreachable() {
        WebAppReadinessObserver.Result result =
                new WebAppReadinessObserver(
                        new FakeProbe(new SocketTimeoutException("read timed out")), 10, 10)
                        .observe(8080);

        assertEquals(WebAppReadinessObserver.Outcome.UNREACHABLE, result.getOutcome());
        assertEquals("read timed out", result.getDetail());
    }

    @Test
    public void aFailureWithoutAMessageStillHasADetail() {
        WebAppReadinessObserver.Result result =
                new WebAppReadinessObserver(new FakeProbe(new IOException()), 10, 10)
                        .observe(8080);

        assertNotNull(result.getDetail());
        assertFalse(result.getDetail().trim().isEmpty());
    }

    @Test
    public void rejectsPortsThatAreNotUsableGuestWebAppPorts() {
        assertRejected(0);
        assertRejected(-1);
        assertRejected(65536);
        assertRejected(GuestPortPolicy.RESERVED_GUEST_SSH_PORT);
        assertEquals("an invalid port must never reach the network", 0, probe.calls.get());
    }

    @Test
    public void rejectsInvalidConstruction() {
        try {
            new WebAppReadinessObserver(null, 10, 10);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new WebAppReadinessObserver(probe, -1, 10);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new WebAppReadinessObserver(probe, 10, -1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void defaultConstructionIsBoundedBySaneTimeouts() {
        assertTrue(WebAppReadinessObserver.DEFAULT_CONNECT_TIMEOUT_MILLIS > 0);
        assertTrue(WebAppReadinessObserver.DEFAULT_READ_TIMEOUT_MILLIS > 0);
        assertEquals("/", WebAppReadinessObserver.HEALTH_PATH);
        assertNotNull(new WebAppReadinessObserver());
    }

    private static void assertRejected(int guestPort) {
        try {
            new WebAppReadinessObserver(new FakeProbe(200), 10, 10).observe(guestPort);
            fail("expected rejection of port " + guestPort);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** Records what the observer asked for and returns a configured status. */
    private static final class FakeProbe implements HttpHealthProbe {
        private final int status;
        private final IOException failure;
        private final AtomicInteger calls = new AtomicInteger();
        private String scheme;
        private String host;
        private int port;
        private String path;
        private int connectTimeoutMillis;
        private int readTimeoutMillis;

        FakeProbe(int status) {
            this(status, null);
        }

        FakeProbe(IOException failure) {
            this(-1, failure);
        }

        private FakeProbe(int status, IOException failure) {
            this.status = status;
            this.failure = failure;
        }

        @Override
        public int probe(String scheme, String host, int port, String path,
                         int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
            calls.incrementAndGet();
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
            if (failure != null) {
                throw failure;
            }
            return status;
        }
    }
}
