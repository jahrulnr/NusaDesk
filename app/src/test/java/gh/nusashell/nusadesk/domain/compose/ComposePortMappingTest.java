package gh.nusashell.nusadesk.domain.compose;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ComposePortMappingTest {

    @Test
    public void buildsAMinimalTcpMapping() {
        ComposePortMapping mapping = new ComposePortMapping(8080, 80, "tcp", null);
        assertEquals(8080, mapping.getHostPort());
        assertEquals(80, mapping.getContainerPort());
        assertEquals("tcp", mapping.getProtocol());
        assertNull(mapping.getHostAddress());
    }

    @Test
    public void nullProtocolMeansTheComposeTcpDefault() {
        assertEquals("tcp", new ComposePortMapping(8080, 80, null, null).getProtocol());
    }

    @Test
    public void protocolIsStoredLowerCase() {
        assertEquals("udp", new ComposePortMapping(53, 53, " UDP ", null).getProtocol());
    }

    @Test
    public void acceptsBoundaryPortNumbers() {
        new ComposePortMapping(1, 1, "tcp", null);
        new ComposePortMapping(65535, 65535, "tcp", null);
    }

    @Test
    public void rejectsOutOfRangePorts() {
        assertRejectedPorts(0, 80);
        assertRejectedPorts(-1, 80);
        assertRejectedPorts(65536, 80);
        assertRejectedPorts(8080, 0);
        assertRejectedPorts(8080, -1);
        assertRejectedPorts(8080, 65536);
    }

    @Test
    public void rejectsBlankAndUnsupportedProtocols() {
        assertRejectedProtocol("");
        assertRejectedProtocol("   ");
        assertRejectedProtocol("sctp");
        assertRejectedProtocol("tcp/udp");
    }

    @Test
    public void acceptsNumericHostAddresses() {
        assertEquals("127.0.0.1",
                new ComposePortMapping(80, 80, "tcp", "127.0.0.1").getHostAddress());
        assertEquals("0.0.0.0",
                new ComposePortMapping(80, 80, "tcp", "0.0.0.0").getHostAddress());
        assertEquals("::1", new ComposePortMapping(80, 80, "tcp", "::1").getHostAddress());
        assertEquals("fe80::1",
                new ComposePortMapping(80, 80, "tcp", "fe80::1").getHostAddress());
        assertEquals("::ffff:192.0.2.1",
                new ComposePortMapping(80, 80, "tcp", "::ffff:192.0.2.1").getHostAddress());
        assertEquals("1:2:3:4:5:6:7:8",
                new ComposePortMapping(80, 80, "tcp", "1:2:3:4:5:6:7:8").getHostAddress());
    }

    @Test
    public void blankHostAddressMeansAllLocalAddresses() {
        assertNull(new ComposePortMapping(80, 80, "tcp", "   ").getHostAddress());
    }

    @Test
    public void rejectsHostnamesBecauseTheyWouldNeedDns() {
        assertRejectedHostAddress("localhost");
        assertRejectedHostAddress("example.com");
        assertRejectedHostAddress("host.lan");
    }

    @Test
    public void rejectsMalformedIpv4Literals() {
        assertRejectedHostAddress("256.1.1.1");
        assertRejectedHostAddress("1.2.3");
        assertRejectedHostAddress("1.2.3.4.5");
        assertRejectedHostAddress("01.2.3.4");
        assertRejectedHostAddress("1.2.3.4a");
    }

    @Test
    public void rejectsMalformedIpv6Literals() {
        assertRejectedHostAddress("1:2:3:4:5:6:7:8:9");
        assertRejectedHostAddress("1:2:3:4:5:6:7:8::");
        assertRejectedHostAddress("1::2::3");
        assertRejectedHostAddress(":::");
        assertRejectedHostAddress("12345::");
        assertRejectedHostAddress("fe80::1%eth0");
        assertRejectedHostAddress("::gggg");
    }

    @Test
    public void equalityIsByValue() {
        ComposePortMapping first = new ComposePortMapping(8080, 80, "tcp", "127.0.0.1");
        ComposePortMapping same = new ComposePortMapping(8080, 80, "TCP", "127.0.0.1");
        ComposePortMapping otherProtocol = new ComposePortMapping(8080, 80, "udp", "127.0.0.1");
        ComposePortMapping noAddress = new ComposePortMapping(8080, 80, "tcp", null);
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherProtocol);
        assertNotEquals(first, noAddress);
    }

    @Test
    public void toStringRendersTheComposeShortForm() {
        assertEquals("8080:80/tcp",
                new ComposePortMapping(8080, 80, "tcp", null).toString());
        assertEquals("127.0.0.1:8080:80/udp",
                new ComposePortMapping(8080, 80, "udp", "127.0.0.1").toString());
        assertEquals("[::1]:8080:80/tcp",
                new ComposePortMapping(8080, 80, "tcp", "::1").toString());
    }

    private static void assertRejectedPorts(int hostPort, int containerPort) {
        try {
            new ComposePortMapping(hostPort, containerPort, "tcp", null);
            fail("expected rejection of " + hostPort + ":" + containerPort);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedProtocol(String protocol) {
        try {
            new ComposePortMapping(8080, 80, protocol, null);
            fail("expected rejection of protocol: " + protocol);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedHostAddress(String hostAddress) {
        try {
            new ComposePortMapping(8080, 80, "tcp", hostAddress);
            fail("expected rejection of hostAddress: " + hostAddress);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
