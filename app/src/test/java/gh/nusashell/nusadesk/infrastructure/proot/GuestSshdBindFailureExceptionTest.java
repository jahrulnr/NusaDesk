package gh.nusashell.nusadesk.infrastructure.proot;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshEndpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the typed fixed-port bind failure: the reason is deterministic,
 * names the endpoint, and distinguishes "something already answers there" from
 * "the port looked free but the daemon still failed". In both cases the host
 * publishes no endpoint.
 */
public class GuestSshdBindFailureExceptionTest {

    @Test
    public void reasonNamesTheFixedEndpointAndRefusesAForeignListener() {
        GuestSshdBindFailureException failure =
                new GuestSshdBindFailureException(LocalSshEndpoint.endpoint(), true);

        assertEquals(LocalSshEndpoint.HOST, failure.getEndpoint().getHost());
        assertEquals(LocalSshEndpoint.PORT, failure.getEndpoint().getPort());
        assertTrue(failure.isListenerAlreadyPresent());
        String reason = failure.getReason();
        assertEquals(failure.getMessage(), reason);
        assertTrue(reason, reason.contains("127.0.0.1:22022"));
        assertTrue(reason, reason.contains("never attaches to a listener it did not start"));
    }

    @Test
    public void reasonWithoutAListenerSaysThePortIsInUse() {
        GuestSshdBindFailureException failure =
                new GuestSshdBindFailureException(new RuntimePort("127.0.0.1", 22_022), false);

        assertFalse(failure.isListenerAlreadyPresent());
        String reason = failure.getReason();
        assertTrue(reason, reason.contains("127.0.0.1:22022"));
        assertTrue(reason, reason.contains("already in use"));
        assertFalse(reason, reason.contains("another listener"));
    }

    @Test
    public void reasonIsDeterministicForTheSameInputs() {
        RuntimePort endpoint = LocalSshEndpoint.endpoint();

        assertEquals(new GuestSshdBindFailureException(endpoint, true).getReason(),
                new GuestSshdBindFailureException(endpoint, true).getReason());
        assertEquals(new GuestSshdBindFailureException(endpoint, false).getReason(),
                new GuestSshdBindFailureException(endpoint, false).getReason());
    }

    @Test
    public void rejectsANullEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new GuestSshdBindFailureException(null, false));
    }
}
