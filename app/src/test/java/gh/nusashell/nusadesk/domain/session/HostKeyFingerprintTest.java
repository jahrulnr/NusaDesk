package gh.nusashell.nusadesk.domain.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class HostKeyFingerprintTest {
    @Test
    public void acceptsOpenSshFingerprint() {
        HostKeyFingerprint fp = new HostKeyFingerprint("SHA256:abc123+/=");
        assertEquals("SHA256:abc123+/=", fp.getValue());
    }

    @Test
    public void acceptsHexDigestAndNormalisesCase() {
        HostKeyFingerprint lower = new HostKeyFingerprint(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        HostKeyFingerprint upper = new HostKeyFingerprint(
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF");
        assertEquals(lower, upper);
        assertEquals(lower.hashCode(), upper.hashCode());
    }

    @Test
    public void rejectsBlankFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new HostKeyFingerprint("  "));
    }

    @Test
    public void rejectsMalformedFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new HostKeyFingerprint("not-a-fingerprint"));
    }

    @Test
    public void rejectsShortHex() {
        assertThrows(IllegalArgumentException.class, () ->
                new HostKeyFingerprint("0123abcd"));
    }

    @Test
    public void differentFingerprintsAreNotEqual() {
        HostKeyFingerprint a = new HostKeyFingerprint("SHA256:aaaa");
        HostKeyFingerprint b = new HostKeyFingerprint("SHA256:bbbb");
        assertNotEquals(a, b);
    }
}
