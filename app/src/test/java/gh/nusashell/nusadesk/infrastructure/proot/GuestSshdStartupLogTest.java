package gh.nusashell.nusadesk.infrastructure.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Pure-JVM tests for the daemon-reported bind channel: only the daemon's own
 * startup lines may be accepted as a bind outcome.
 */
public class GuestSshdStartupLogTest {

    @Test
    public void parsesListeningLineWithLoopbackEndpoint() {
        GuestSshdStartupLog.Event event =
                GuestSshdStartupLog.parse("Server listening on 127.0.0.1 port 44007.");
        assertNotNull(event);
        assertEquals(GuestSshdStartupLog.Kind.LISTENING, event.getKind());
        assertEquals("127.0.0.1", event.getHost());
        assertEquals(44007, event.getPort());
    }

    @Test
    public void parsesListeningLineWithoutTrailingPeriodAndWithSurroundingSpace() {
        GuestSshdStartupLog.Event event =
                GuestSshdStartupLog.parse("  Server listening on 127.0.0.1 port 65535 \n");
        assertEquals(GuestSshdStartupLog.Kind.LISTENING, event.getKind());
        assertEquals(65535, event.getPort());
    }

    @Test
    public void parsesBothBindFailureForms() {
        assertEquals(GuestSshdStartupLog.Kind.BIND_FAILED,
                GuestSshdStartupLog.parse(
                        "Bind to port 44007 on 127.0.0.1 failed: Address already in use.").getKind());
        assertEquals(GuestSshdStartupLog.Kind.BIND_FAILED,
                GuestSshdStartupLog.parse("Cannot bind any address.").getKind());
    }

    @Test
    public void ignoresUnrelatedDaemonNoise() {
        String[] noise = {
                "Connection from 127.0.0.1 port 52791 on 127.0.0.1 port 44007 rdomain \"\"",
                "kex_exchange_identification: Connection closed by remote host",
                "proot info: pid 1234: terminated with signal 15",
                "Server listening on port 22",
                "Server listening on 127.0.0.1 port 0.",
                "Server listening on 127.0.0.1 port 70000.",
                "Bind to port 44007 on 127.0.0.1 failed",
                "",
        };
        for (String line : noise) {
            assertEquals("must be ignored: " + line,
                    GuestSshdStartupLog.Kind.IGNORED, GuestSshdStartupLog.parse(line).getKind());
        }
    }

    @Test
    public void nullLineIsIgnored() {
        assertEquals(GuestSshdStartupLog.Kind.IGNORED, GuestSshdStartupLog.parse(null).getKind());
    }
}
