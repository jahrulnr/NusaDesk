package gh.nusashell.nusadesk.domain.network;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure tests for {@link ResolvConf}: nameserver validation, dedup, bounding,
 * and {@code nameserver <ip>} formatting. No Android, no filesystem.
 */
public class ResolvConfTest {

    @Test
    public void formatsValidIpv4Nameservers() {
        ResolvConf conf = ResolvConf.of(Arrays.asList("192.168.1.1", "8.8.8.8"));
        assertEquals(Arrays.asList("192.168.1.1", "8.8.8.8"), conf.nameservers());
        assertEquals("nameserver 192.168.1.1\nnameserver 8.8.8.8\n", conf.toText());
    }

    @Test
    public void formatsValidIpv6Nameservers() {
        ResolvConf conf = ResolvConf.of(Collections.singletonList("2001:4860:4860::8888"));
        assertEquals(Collections.singletonList("2001:4860:4860::8888"), conf.nameservers());
        assertEquals("nameserver 2001:4860:4860::8888\n", conf.toText());
    }

    @Test
    public void mixesIpv4AndIpv6() {
        ResolvConf conf = ResolvConf.of(Arrays.asList("192.168.1.1", "2001:db8::1"));
        assertEquals("nameserver 192.168.1.1\nnameserver 2001:db8::1\n", conf.toText());
    }

    @Test
    public void boundsNameserverCountToMax() {
        ResolvConf conf = ResolvConf.of(Arrays.asList("1.1.1.1", "8.8.8.8", "9.9.9.9", "4.4.4.4"));
        assertEquals(ResolvConf.MAX_NAMESERVERS, conf.nameservers().size());
        assertEquals(Arrays.asList("1.1.1.1", "8.8.8.8", "9.9.9.9"), conf.nameservers());
    }

    @Test
    public void deduplicatesNameservers() {
        ResolvConf conf = ResolvConf.of(Arrays.asList("8.8.8.8", "8.8.8.8", "1.1.1.1"));
        assertEquals(Arrays.asList("8.8.8.8", "1.1.1.1"), conf.nameservers());
    }

    @Test
    public void trimsWhitespaceAroundCandidates() {
        ResolvConf conf = ResolvConf.of(Collections.singletonList("  8.8.8.8  "));
        assertEquals(Collections.singletonList("8.8.8.8"), conf.nameservers());
    }

    @Test
    public void rejectsHostnames() {
        assertNull(ResolvConf.of(Collections.singletonList("dns.google")));
    }

    @Test
    public void rejectsPortsAndBrackets() {
        assertNull(ResolvConf.of(Collections.singletonList("8.8.8.8:53")));
        assertNull(ResolvConf.of(Collections.singletonList("[2001:db8::1]")));
        assertNull(ResolvConf.of(Collections.singletonList("[2001:db8::1]:53")));
    }

    @Test
    public void rejectsZoneScopedIpv6() {
        assertNull(ResolvConf.of(Collections.singletonList("fe80::1%wlan0")));
    }

    @Test
    public void rejectsIpv4LinkLocal() {
        // 169.254.0.0/16 is unroutable without a scope; Android may report the
        // router's link-local IPv4 as a DNS candidate and it must not reach
        // the guest resolver.
        assertNull(ResolvConf.of(Collections.singletonList("169.254.1.1")));
    }

    @Test
    public void rejectsIpv6LinkLocal() {
        // fe80::/10 is link-local and unroutable without an interface. Android
        // reports fe80::1%wlan0; the source strips the scope, leaving fe80::1,
        // which the validator must reject.
        assertNull(ResolvConf.of(Collections.singletonList("fe80::1")));
        assertNull(ResolvConf.of(Collections.singletonList("fe80::1:2:3:4:5:6")));
        assertNull(ResolvConf.of(Collections.singletonList("feb0::1")));
        // febf::/10 is the top of the link-local range.
        assertNull(ResolvConf.of(Collections.singletonList("febf::1")));
    }

    @Test
    public void acceptsNonLinkLocalIpv6() {
        // fec0::/10 and ff00::/8 are NOT link-local; keep them usable.
        ResolvConf fec0 = ResolvConf.of(Collections.singletonList("fec0::1"));
        assertEquals(Collections.singletonList("fec0::1"), fec0.nameservers());
        ResolvConf ff00 = ResolvConf.of(Collections.singletonList("ff00::1"));
        assertEquals(Collections.singletonList("ff00::1"), ff00.nameservers());
    }

    @Test
    public void dropsLinkLocalAndKeepsValid() {
        ResolvConf conf = ResolvConf.of(Arrays.asList("fe80::1", "192.168.1.1", "169.254.1.1"));
        assertEquals(Collections.singletonList("192.168.1.1"), conf.nameservers());
    }

    @Test
    public void rejectsInvalidIpv4() {
        assertNull(ResolvConf.of(Collections.singletonList("256.1.1.1")));
        assertNull(ResolvConf.of(Collections.singletonList("1.2.3")));
        assertNull(ResolvConf.of(Collections.singletonList("1.2.3.4.5")));
        assertNull(ResolvConf.of(Collections.singletonList("a.b.c.d")));
    }

    @Test
    public void rejectsInvalidIpv6() {
        assertNull(ResolvConf.of(Collections.singletonList("2001:db8::1::2")));
        assertNull(ResolvConf.of(Collections.singletonList("1:2:3:4:5:6:7")));
        assertNull(ResolvConf.of(Collections.singletonList("2001:db8:gggg::1")));
    }

    @Test
    public void acceptsFullIpv8AndCompression() {
        ResolvConf full = ResolvConf.of(Collections.singletonList("2001:db8:0:0:0:0:0:1"));
        assertEquals(Collections.singletonList("2001:db8:0:0:0:0:0:1"), full.nameservers());
        ResolvConf compressed = ResolvConf.of(Collections.singletonList("::1"));
        assertEquals(Collections.singletonList("::1"), compressed.nameservers());
    }

    @Test
    public void returnsNullForEmptyOrNull() {
        assertNull(ResolvConf.of(null));
        assertNull(ResolvConf.of(Collections.emptyList()));
        assertNull(ResolvConf.of(Collections.singletonList("")));
    }

    @Test
    public void dropsInvalidEntriesAndKeepsValid() {
        List<String> mixed = Arrays.asList("dns.google", "8.8.8.8", null, "256.0.0.1", "1.1.1.1");
        ResolvConf conf = ResolvConf.of(mixed);
        assertEquals(Arrays.asList("8.8.8.8", "1.1.1.1"), conf.nameservers());
    }

    @Test
    public void rejectsNullBytes() {
        assertNull(ResolvConf.of(Collections.singletonList("8.8.8.8\0evil")));
    }

    @Test
    public void isValidIpLiteralChecks() {
        assertTrue(ResolvConf.isValidIpLiteral("8.8.8.8"));
        assertTrue(ResolvConf.isValidIpLiteral("2001:db8::1"));
        assertFalse(ResolvConf.isValidIpLiteral("dns.google"));
        assertFalse(ResolvConf.isValidIpLiteral(""));
        assertFalse(ResolvConf.isValidIpLiteral(null));
    }
}
