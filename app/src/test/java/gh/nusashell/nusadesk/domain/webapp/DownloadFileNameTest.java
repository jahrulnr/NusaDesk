package gh.nusashell.nusadesk.domain.webapp;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DownloadFileNameTest {
    @Test public void cannotBecomeAPathOrSpecialDirectory() {
        assertEquals("download", DownloadFileName.safe(null));
        assertEquals("download", DownloadFileName.safe(".."));
        assertEquals(".._.._report_.txt", DownloadFileName.safe("../../report\n.txt"));
        assertEquals("a_b.txt", DownloadFileName.safe("a\\b.txt"));
    }

    @Test public void preservesUnicodeAndBoundsEncodedFileName() {
        assertEquals("catatan 日本語.pdf", DownloadFileName.safe("catatan 日本語.pdf"));
        String longName = DownloadFileName.safe("😀".repeat(100));
        assertTrue(longName.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertFalse(Character.isHighSurrogate(longName.charAt(longName.length() - 1)));
    }

    @Test public void collisionSuffixKeepsExtension() {
        assertEquals("report (1).pdf", DownloadFileName.numbered("report.pdf", 1));
        assertEquals("download (2)", DownloadFileName.numbered("download", 2));
        assertEquals(".hidden (1)", DownloadFileName.numbered(".hidden", 1));
    }
}
