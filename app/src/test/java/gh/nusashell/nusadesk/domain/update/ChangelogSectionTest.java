package gh.nusashell.nusadesk.domain.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The About page's changelog card shows only the newest released section of
 * the bundled changelog, flattened to plain text (ADR-0066).
 */
public class ChangelogSectionTest {

    private static final String DOC = "# Changelog\n\n"
            + "## [Unreleased]\n\n"
            + "## [1.2.3] - 2026-10-21\n\n"
            + "### Fixed\n\n"
            + "- **Keyboard** no longer rides\n"
            + "  across surfaces.\n\n"
            + "### Added\n\n"
            + "- Cache actions.\n\n"
            + "## [1.2.2] - 2026-09-01\n\n"
            + "- Older entry.\n";

    @Test
    public void latestSectionCarriesVersionDateAndFlattenedBody() {
        ChangelogSection section = ChangelogSection.parseLatest(DOC);
        assertEquals("1.2.3", section.getVersion());
        assertEquals("2026-10-21", section.getDate());
        String body = section.getBody();
        assertTrue(body.contains("Fixed"));
        assertTrue(body.contains("\u2022 Keyboard no longer rides"));
        assertTrue("markdown emphasis is flattened", !body.contains("**"));
        assertTrue("older sections stay out", !body.contains("Older entry."));
    }

    @Test
    public void missingUnreleasedStillFindsTheFirstRelease() {
        ChangelogSection section = ChangelogSection.parseLatest(
                "# C\n\n## [2.0.0] - 2027-01-01\n\n- One.\n");
        assertEquals("2.0.0", section.getVersion());
    }

    @Test
    public void crlfDocumentsAndDatelessHeadersParse() {
        ChangelogSection section = ChangelogSection.parseLatest(
                "## [Unreleased]\r\n\r\n## [2.0.0]\r\n\r\n- One.\r\n");
        assertEquals("2.0.0", section.getVersion());
        assertEquals("", section.getDate());
        assertTrue(section.getBody().contains("One."));
    }

    @Test
    public void emptyOrReleaselessDocumentsYieldNull() {
        assertNull(ChangelogSection.parseLatest(null));
        assertNull(ChangelogSection.parseLatest(""));
        assertNull(ChangelogSection.parseLatest("# C\n\n## [Unreleased]\n\n- todo\n"));
    }

    @Test
    public void longBodiesAreCappedOnALineBoundary() {
        StringBuilder doc = new StringBuilder("# C\n\n## [9.9.9] - 2030-01-01\n\n");
        for (int i = 0; i < 200; i++) {
            doc.append("- entry ").append(i).append(" is here\n");
        }
        ChangelogSection section = ChangelogSection.parseLatest(doc.toString());
        assertTrue(section.getBody().length()
                <= ChangelogSection.MAX_BODY_CHARS + 20);
        assertTrue(section.getBody().endsWith("\u2026"));
    }
}
