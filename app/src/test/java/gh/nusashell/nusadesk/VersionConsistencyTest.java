package gh.nusashell.nusadesk;

import org.junit.Test;

import gh.nusashell.nusadesk.domain.update.ChangelogSection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * One release bump touches several files at once: the {@code VERSION} marker,
 * the changelog section, and the document naming the current version. The
 * APK's {@code versionName}/{@code versionCode} are derived from {@code
 * VERSION} inside {@code app/build.gradle} (ADR-0067), so this guard pins the
 * derivation contract instead of a literal that can drift, and fails the
 * build rather than shipping an inconsistent release.
 */
public class VersionConsistencyTest {

    /** The last hand-edited versionCode; derived codes must stay above it. */
    private static final int LAST_MANUAL_VERSION_CODE = 25;

    @Test
    public void versionMarkerGradleAndChangelogAgree() throws Exception {
        String version = read(repoPath("VERSION")).trim();
        String gradle = read(repoPath("app/build.gradle"));
        String changelog = read(repoPath("CHANGELOG.md"));
        String security = read(repoPath("SECURITY.md"));

        assertTrue("VERSION must be a plain semantic version, not: " + version,
                version.matches("\\d+\\.\\d+\\.\\d+"));

        // The build derives both version fields from the VERSION marker; a
        // hand-edited literal sneaking back in fails here.
        assertTrue("app/build.gradle must derive the version from the VERSION file",
                gradle.contains("rootProject.file('VERSION')"));
        assertTrue("app/build.gradle must not carry a hand-edited versionName",
                !gradle.matches("(?s).*versionName\\s+['\"]\\d+\\.\\d+\\.\\d+['\"].*"));

        // The versionCode mapping reserves three digits per field and must
        // stay monotonic above the last hand-edited code, or sideload updates
        // silently stop installing.
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        assertTrue("VERSION fields must stay under 1000 for the versionCode mapping",
                major < 1000 && minor < 1000 && patch < 1000);
        int derivedCode = major * 1_000_000 + minor * 1_000 + patch;
        assertTrue("derived versionCode " + derivedCode
                        + " must stay above the last manual code " + LAST_MANUAL_VERSION_CODE,
                derivedCode > LAST_MANUAL_VERSION_CODE);

        assertTrue("CHANGELOG must carry a section for " + version,
                changelog.contains("## [" + version + "]"));
        assertTrue("CHANGELOG must keep an [Unreleased] section at the top",
                changelog.contains("## [Unreleased]"));
        // The newest released section — the same one the About card renders —
        // has to be this version, so a forgotten changelog section fails here.
        ChangelogSection latest = ChangelogSection.parseLatest(changelog);
        assertNotNull("the changelog must carry a released section", latest);
        assertEquals("the newest changelog section must be " + version,
                version, latest.getVersion());

        assertTrue("SECURITY.md must name the current version",
                security.contains("`" + version + "`"));
    }

    @Test
    public void theVersionMarkerIsAReleaseValueNotACacheKey() throws Exception {
        // A synthetic name would break the guest document footer and the
        // release notes, so the marker stays a plain version string.
        String version = read(repoPath("VERSION")).trim();
        assertTrue("no build metadata, no qualifier, no whitespace",
                version.matches("[0-9]+(\\.[0-9]+){2}"));
    }

    private static String read(Path path) throws Exception {
        assertTrue("missing file: " + path, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * Resolve a repository-relative path from either launch directory: unit
     * tests normally run with the module directory as the working directory,
     * but a repo-root launch must work too.
     */
    private static Path repoPath(String relative) {
        Path workingDir = Paths.get("").toAbsolutePath();
        Path direct = workingDir.resolve(relative);
        return Files.isRegularFile(direct)
                ? direct : workingDir.resolve("..").resolve(relative).normalize();
    }
}
