package gh.nusashell.nusadesk.domain.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RuntimePortTest {
    @Test
    public void createsLoopbackUrl() {
        RuntimePort port = new RuntimePort("127.0.0.1", 10994);

        assertEquals("http://127.0.0.1:10994", port.toUrl("http"));
    }

    @Test
    public void createsIpv6LoopbackUrl() {
        RuntimePort port = new RuntimePort("::1", 10994);

        assertEquals("http://[::1]:10994", port.toUrl("http"));
    }

    @Test
    public void rejectsUnsupportedScheme() {
        RuntimePort port = new RuntimePort("localhost", 10994);

        assertThrows(IllegalArgumentException.class, () -> port.toUrl("file"));
    }

    @Test
    public void rejectsNonLoopbackHost() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePort("0.0.0.0", 10994));
    }

    @Test
    public void rejectsEphemeralPortAsPublishedEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimePort("localhost", 0));
    }
}
