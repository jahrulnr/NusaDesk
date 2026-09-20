package gh.nusashell.nusadesk.domain.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Table tests for the fail-closed digest comparison (assisted update install). */
public class ApkDigestTest {

    private static final String SHA =
            "063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c";

    @Test
    public void matchesRecordedAndComputedDigests() {
        assertTrue(ApkDigest.matches("sha256:" + SHA, SHA));
        // Case and surrounding space are normalization, not a difference.
        assertTrue(ApkDigest.matches("SHA256:" + SHA.toUpperCase(), " " + SHA + " "));
    }

    @Test
    public void failsClosedOnMissingOrMalformedRecordedDigest() {
        assertFalse(ApkDigest.matches(null, SHA));
        assertFalse(ApkDigest.matches("", SHA));
        assertFalse(ApkDigest.matches(SHA, SHA));
        assertFalse(ApkDigest.matches("md5:" + SHA, SHA));
        assertFalse(ApkDigest.matches("sha256:  ", SHA));
        assertFalse(ApkDigest.matches("sha256:" + SHA, null));
        assertFalse(ApkDigest.matches("sha256:" + SHA, ""));
    }

    @Test
    public void normalizeKeepsOnlyTheHexPart() {
        assertEquals(SHA, ApkDigest.normalize("sha256:" + SHA));
        assertEquals(SHA, ApkDigest.normalize(" SHA256:  " + SHA.toUpperCase() + " "));
        assertNull(ApkDigest.normalize(null));
        assertNull(ApkDigest.normalize("plain"));
        assertNull(ApkDigest.normalize("sha256:"));
    }
}
