package gh.nusashell.nusadesk.domain.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Table-driven contract for the release-version comparator (ADR-0038):
 * forgiving shape ({@code v} prefix, missing components), strict content
 * (garbage never parses), and the rule that a prerelease is never newer than
 * the same numeric triple.
 */
public class ReleaseVersionTest {

    @Test
    public void parsesTheTagShapesTheChannelProduces() {
        ReleaseVersion v = ReleaseVersion.parseOrNull("v0.3.0");
        assertEquals(0, v.getMajor());
        assertEquals(3, v.getMinor());
        assertEquals(0, v.getPatch());
        assertFalse(v.isPrerelease());
        assertEquals("0.3.0", v.toString());
        assertEquals("v0.3.0", v.getRaw());
    }

    @Test
    public void missingComponentsCountAsZero() {
        assertEquals(ReleaseVersion.parseOrNull("0.3.0"), ReleaseVersion.parseOrNull("0.3"));
        ReleaseVersion bare = ReleaseVersion.parseOrNull("2");
        assertEquals(2, bare.getMajor());
        assertEquals(0, bare.getMinor());
        assertEquals(0, bare.getPatch());
        assertFalse(ReleaseVersion.parseOrNull("0.3.0").isNewerThan(
                ReleaseVersion.parseOrNull("0.3")));
        assertFalse(ReleaseVersion.parseOrNull("0.3").isNewerThan(
                ReleaseVersion.parseOrNull("0.3.0")));
    }

    @Test
    public void anyNonEmptySuffixMarksAPrerelease() {
        assertTrue(ReleaseVersion.parseOrNull("0.3.0-rc1").isPrerelease());
        assertTrue(ReleaseVersion.parseOrNull("v1.0.0-beta.2").isPrerelease());
        assertTrue(ReleaseVersion.parseOrNull("1.0.0+build5").isPrerelease());
        ReleaseVersion snapshot = ReleaseVersion.parseOrNull("1.2-SNAPSHOT.3");
        assertTrue(snapshot.isPrerelease());
        assertEquals("1.2.0", snapshot.toString());
    }

    @Test
    public void garbageNeverParses() {
        assertNull(ReleaseVersion.parseOrNull(null));
        assertNull(ReleaseVersion.parseOrNull(""));
        assertNull(ReleaseVersion.parseOrNull("   "));
        assertNull(ReleaseVersion.parseOrNull("v"));
        assertNull(ReleaseVersion.parseOrNull("abc"));
        assertNull(ReleaseVersion.parseOrNull("1.x"));
        assertNull(ReleaseVersion.parseOrNull("1.2.3.4"));
        assertNull(ReleaseVersion.parseOrNull("0.3.0rc1"));
        assertNull(ReleaseVersion.parseOrNull("latest"));
    }

    @Test
    public void numericTripleOrdering() {
        assertNewer("v0.4.0", "0.3.0");
        assertNewer("0.3.1", "0.3.0");
        assertNewer("0.4", "0.3.9");
        assertNewer("1.0.0", "0.9.9");
        assertNewer("2.0.0", "1.9.9");
        assertNotNewer("0.3.0", "0.4.0");
        assertNotNewer("0.3.0", "0.3.1");
    }

    @Test
    public void equalVersionsAreNeverNewer() {
        assertNotNewer("0.3.0", "0.3.0");
        assertNotNewer("v0.3.0", "0.3.0");
        assertNotNewer("0.3", "0.3.0");
    }

    @Test
    public void prereleaseIsNeverNewerThanTheSameTriple() {
        assertNotNewer("0.3.0-rc1", "0.3.0");
        assertNotNewer("0.3.0-rc2", "0.3.0-rc1");
        assertNewer("0.3.0", "0.3.0-rc1");
        // A prerelease of a higher triple still counts as newer.
        assertNewer("0.4.0-rc1", "0.3.0");
        assertNotNewer("0.4.0-rc1", "0.4.0");
    }

    @Test
    public void comparingAgainstNullIsNeverNewer() {
        assertFalse(ReleaseVersion.parseOrNull("9.9.9").isNewerThan(null));
    }

    @Test
    public void surroundingWhitespaceIsIgnored() {
        assertEquals(ReleaseVersion.parseOrNull("0.3.0"),
                ReleaseVersion.parseOrNull("  v0.3.0  "));
    }

    private static void assertNewer(String candidate, String current) {
        ReleaseVersion parsed = ReleaseVersion.parseOrNull(candidate);
        assertTrue("expected newer: " + candidate + " > " + current,
                parsed != null && parsed.isNewerThan(ReleaseVersion.parseOrNull(current)));
    }

    private static void assertNotNewer(String candidate, String current) {
        ReleaseVersion parsed = ReleaseVersion.parseOrNull(candidate);
        assertTrue("expected not newer: " + candidate + " <= " + current,
                parsed == null || !parsed.isNewerThan(ReleaseVersion.parseOrNull(current)));
    }
}
