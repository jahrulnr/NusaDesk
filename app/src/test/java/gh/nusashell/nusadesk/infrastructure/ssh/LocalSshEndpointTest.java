package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the fixed local SSH endpoint contract (ADR-0013): one documented
 * loopback port, below the ephemeral range, and nothing else.
 */
public class LocalSshEndpointTest {

    @Test
    public void endpointIsTheDocumentedFixedLoopbackPort() {
        assertEquals("127.0.0.1", LocalSshEndpoint.HOST);
        assertEquals(22_022, LocalSshEndpoint.PORT);

        RuntimePort endpoint = LocalSshEndpoint.endpoint();
        assertEquals("127.0.0.1", endpoint.getHost());
        assertEquals(22_022, endpoint.getPort());
        assertEquals("127.0.0.1:22022", LocalSshEndpoint.HOST_KEY_SCOPE);
        assertEquals("127.0.0.1:22022", LocalSshEndpoint.label());
    }

    @Test
    public void fixedPortStaysOutsideTheLinuxEphemeralRange() {
        // An outgoing connection cannot occupy a port below the ephemeral range,
        // so the guest's listener never races with a client socket.
        assertTrue("fixed port must stay below the ephemeral range",
                LocalSshEndpoint.PORT < 32_768);
        assertTrue("fixed port must not be a privileged port",
                LocalSshEndpoint.PORT > 1_024);
    }

    @Test
    public void endpointIsALoopbackHostOnly() {
        RuntimePort endpoint = LocalSshEndpoint.endpoint();
        assertTrue(endpoint.getHost().equals("127.0.0.1"));
        assertFalse(endpoint.getHost().equals("0.0.0.0"));
        assertEquals("http://127.0.0.1:22022", endpoint.toUrl("http"));
        // The domain value rejects anything that is not loopback, so a
        // non-loopback endpoint cannot be constructed here at all.
        assertThrows(IllegalArgumentException.class, () -> new RuntimePort("0.0.0.0", 22_022));
    }

    /**
     * Cross-slice consistency: the user-defined web-app port policy reserves the
     * same port the guest SSH service owns. A divergence would let a web app
     * claim the SSH port inside the guest, so the two constants must agree even
     * though the domain layer cannot depend on this infrastructure class.
     */
    @Test
    public void webAppPortPolicyReservesThisEndpoint() {
        assertEquals(LocalSshEndpoint.PORT, GuestPortPolicy.RESERVED_GUEST_SSH_PORT);
        assertTrue(GuestPortPolicy.isReserved(LocalSshEndpoint.PORT));
        assertFalse(GuestPortPolicy.isAllowed(LocalSshEndpoint.PORT));
    }
}
