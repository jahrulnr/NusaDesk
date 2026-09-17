package gh.nusashell.nusadesk;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One release bump has to touch several files at once: the {@code VERSION}
 * marker, the Gradle {@code versionName}/{@code versionCode} that the APK and
 * every guest document actually carry, the changelog section, and the document
 * naming the current version. They have drifted apart before, so this guard
 * fails the build instead of shipping an inconsistent release.
 */
public class VersionConsistencyTest {

    @Test
    public void versionMarkerGradleAndChangelogAgree() throws Exception {
        String version = read(repoPath("VERSION")).trim();
        String gradle = read(repoPath("app/build.gradle"));
        String changelog = read(repoPath("CHANGELOG.md"));
        String security = read(repoPath("SECURITY.md"));

        assertTrue("VERSION must be a plain semantic version, not: " + version,
                version.matches("\\d+\\.\\d+\\.\\d+"));
        assertEquals("app/build.gradle versionName must match VERSION",
                version, match(gradle, "versionName\\s+'([^']+)'"));
        int versionCode = Integer.parseInt(match(gradle, "versionCode\\s+(\\d+)"));
        assertTrue("versionCode must be positive", versionCode > 0);

        assertTrue("CHANGELOG must carry a section for " + version,
                changelog.contains("## [" + version + "]"));
        // The newest released section (the first one after Unreleased) has to
        // be this version, so a forgotten changelog section fails here.
        int unreleased = changelog.indexOf("## [Unreleased]");
        assertTrue("CHANGELOG must keep an [Unreleased] section at the top",
                unreleased >= 0);
        int newest = changelog.indexOf("## [", unreleased + 1);
        String expectedHeader = "## [" + version + "]";
        assertEquals("the newest changelog section must be " + version,
                expectedHeader, changelog.substring(newest, newest + expectedHeader.length()));

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

    private static String match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue("no match for " + regex, matcher.find());
        return matcher.group(1);
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
