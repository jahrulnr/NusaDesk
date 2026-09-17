package gh.nusashell.nusadesk.domain.compose;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ComposeVolumeMappingTest {

    @Test
    public void buildsAHostPathMount() {
        ComposeVolumeMapping volume = new ComposeVolumeMapping("./site", "/srv/www", false);
        assertEquals("./site", volume.getSource());
        assertEquals("/srv/www", volume.getTarget());
        assertFalse(volume.isReadOnly());
    }

    @Test
    public void buildsAReadOnlyNamedVolumeMount() {
        ComposeVolumeMapping volume = new ComposeVolumeMapping("app-data", "/data", true);
        assertEquals("app-data", volume.getSource());
        assertEquals("/data", volume.getTarget());
        assertTrue(volume.isReadOnly());
    }

    @Test
    public void rejectsNullBlankAndNulSources() {
        assertRejectedSource(null);
        assertRejectedSource("");
        assertRejectedSource("   ");
        assertRejectedSource("data\0vol");
    }

    @Test
    public void keepsTheSourceVerbatim() {
        // No canonicalisation: relative, absolute, and named sources are stored
        // exactly as declared; interpreting them is the adapter's job.
        assertEquals("./a//b", new ComposeVolumeMapping("./a//b", "/x", false).getSource());
        assertEquals("/abs/path/",
                new ComposeVolumeMapping("/abs/path/", "/x", false).getSource());
    }

    @Test
    public void rejectsNonAbsoluteTargets() {
        assertRejectedTarget(null);
        assertRejectedTarget("");
        assertRejectedTarget("   ");
        assertRejectedTarget("data");
        assertRejectedTarget("./data");
    }

    @Test
    public void rejectsTraversalAndNulInTargets() {
        assertRejectedTarget("/a/../b");
        assertRejectedTarget("/../b");
        assertRejectedTarget("/a/b\0c");
    }

    @Test
    public void acceptsAbsoluteTargetsVerbatim() {
        assertEquals("/data", new ComposeVolumeMapping("s", "/data", false).getTarget());
        assertEquals("/", new ComposeVolumeMapping("s", "/", false).getTarget());
        assertEquals("/a/b.c-d_e", new ComposeVolumeMapping("s", "/a/b.c-d_e", false).getTarget());
    }

    @Test
    public void equalityIsByValue() {
        ComposeVolumeMapping first = new ComposeVolumeMapping("vol", "/data", true);
        ComposeVolumeMapping same = new ComposeVolumeMapping("vol", "/data", true);
        ComposeVolumeMapping writable = new ComposeVolumeMapping("vol", "/data", false);
        ComposeVolumeMapping otherTarget = new ComposeVolumeMapping("vol", "/data2", true);
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, writable);
        assertNotEquals(first, otherTarget);
    }

    @Test
    public void toStringRendersTheComposeShortForm() {
        assertEquals("vol:/data",
                new ComposeVolumeMapping("vol", "/data", false).toString());
        assertEquals("./site:/srv/www:ro",
                new ComposeVolumeMapping("./site", "/srv/www", true).toString());
    }

    private static void assertRejectedSource(String source) {
        try {
            new ComposeVolumeMapping(source, "/data", false);
            fail("expected rejection of source: " + source);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedTarget(String target) {
        try {
            new ComposeVolumeMapping("vol", target, false);
            fail("expected rejection of target: " + target);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
