package gh.nusashell.nusadesk.infrastructure.runtimehost;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.ReadinessHealth;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link ReadinessHealthVerifier} using a fake {@link HttpHealthProbe}.
 * No real network or Linux process is used.
 */
public class ReadinessHealthVerifierTest {

    private static final String APP = "ubuntu-base";
    private static final String VER = "0.1.0";

    private static ReadinessFrame frame(ReadinessHealth health, int port) {
        return new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA, APP, VER, "sess-1",
                new RuntimePort("127.0.0.1", port), health, 1_000L);
    }

    /** Fake probe that returns a configured status code or throws on a sentinel. */
    private static final class FakeProbe implements HttpHealthProbe {
        final int status;
        final IOException failure;
        final AtomicInteger calls = new AtomicInteger(0);

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
            if (failure != null) {
                throw failure;
            }
            assertEquals("http", scheme);
            assertEquals("127.0.0.1", host);
            assertEquals("/healthz", path);
            return status;
        }
    }

    private ReadinessHealthVerifier verifierWith(HttpHealthProbe probe) {
        return new ReadinessHealthVerifier(probe, "/healthz", 500, 500);
    }

    @Test
    public void healthyFrameAnd2xxProbeIsHealthy() {
        FakeProbe probe = new FakeProbe(200);
        ReadinessHealthVerifier.Result result =
                verifierWith(probe).verify(frame(ReadinessHealth.HEALTHY, 8080), APP, VER);
        assertEquals(ReadinessHealthVerifier.Outcome.HEALTHY, result.getOutcome());
        assertEquals(1, probe.calls.get());
    }

    @Test
    public void non2xxProbeIsUnhealthy() {
        FakeProbe probe = new FakeProbe(503);
        ReadinessHealthVerifier.Result result =
                verifierWith(probe).verify(frame(ReadinessHealth.HEALTHY, 8080), APP, VER);
        assertEquals(ReadinessHealthVerifier.Outcome.UNHEALTHY, result.getOutcome());
    }

    @Test
    public void unreachableProbeIsUnreachable() {
        FakeProbe probe = new FakeProbe(new IOException("connection refused"));
        ReadinessHealthVerifier.Result result =
                verifierWith(probe).verify(frame(ReadinessHealth.HEALTHY, 8080), APP, VER);
        assertEquals(ReadinessHealthVerifier.Outcome.UNREACHABLE, result.getOutcome());
        assertEquals("connection refused", result.getDetail());
    }

    @Test
    public void selfReportedDegradedIsUnhealthyWithoutProbe() {
        FakeProbe probe = new FakeProbe(200);
        ReadinessHealthVerifier.Result result =
                verifierWith(probe).verify(frame(ReadinessHealth.DEGRADED, 8080), APP, VER);
        assertEquals(ReadinessHealthVerifier.Outcome.UNHEALTHY, result.getOutcome());
        assertEquals(0, probe.calls.get());
    }

    @Test
    public void selfReportedUnhealthyIsUnhealthyWithoutProbe() {
        FakeProbe probe = new FakeProbe(200);
        ReadinessHealthVerifier.Result result =
                verifierWith(probe).verify(frame(ReadinessHealth.UNHEALTHY, 8080), APP, VER);
        assertEquals(ReadinessHealthVerifier.Outcome.UNHEALTHY, result.getOutcome());
        assertEquals(0, probe.calls.get());
    }

    @Test
    public void wrongAppIdIsIdentityMismatchWithoutProbe() {
        FakeProbe probe = new FakeProbe(200);
        ReadinessHealthVerifier.Result result =
                verifierWith(probe).verify(frame(ReadinessHealth.HEALTHY, 8080), APP, "0.2.0");
        assertEquals(ReadinessHealthVerifier.Outcome.IDENTITY_MISMATCH, result.getOutcome());
        assertEquals(0, probe.calls.get());
    }

    @Test
    public void rejectsInvalidConstruction() {
        try {
            new ReadinessHealthVerifier(new FakeProbe(200), "no-slash", 1, 1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ReadinessHealthVerifier(null, "/healthz", 1, 1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ReadinessHealthVerifier(new FakeProbe(200), "/healthz", -1, 1);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
