package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link ProotBindMount} validation and argument formatting.
 */
public class ProotBindMountTest {

    @Test
    public void ofCreatesValidatedBindMount() {
        ProotBindMount bind = ProotBindMount.of("/proc", "/proc");
        assertEquals("/proc", bind.getHostPath());
        assertEquals("/proc", bind.getGuestPath());
        assertEquals("/proc:/proc", bind.toBindArgument());
    }

    @Test
    public void ofRejectsRelativeHostPath() {
        try {
            ProotBindMount.of("relative", "/proc");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("hostPath"));
        }
    }

    @Test
    public void ofRejectsTraversalGuestPath() {
        try {
            ProotBindMount.of("/proc", "/proc/../etc");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("guestPath"));
        }
    }

    @Test
    public void ofRejectsNullByteInHostPath() {
        try {
            ProotBindMount.of("/data\0/x", "/data");
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null bytes"));
        }
    }

    @Test
    public void equalsAndHashCodeByPaths() {
        ProotBindMount a = ProotBindMount.of("/proc", "/proc");
        ProotBindMount b = ProotBindMount.of("/proc", "/proc");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
