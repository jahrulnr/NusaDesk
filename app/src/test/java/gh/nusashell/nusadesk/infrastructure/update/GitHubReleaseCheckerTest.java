package gh.nusashell.nusadesk.infrastructure.update;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.infrastructure.update.GitHubReleaseChecker.UpdateCheckResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The parse-and-compare half of the release check, fed a realistic
 * {@code /releases/latest} fixture (Robolectric supplies the real
 * {@code org.json} implementation). The network half is thin by design —
 * timeouts, one GET, a bounded read — and maps every failure onto the same
 * {@code UNAVAILABLE} kind this test covers for the payload cases.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GitHubReleaseCheckerTest {

    private static final String RELEASE_URL =
            "https://github.com/jahrulnr/NusaDesk/releases/tag/v0.4.0";
    private static final String ASSET_URL =
            "https://github.com/jahrulnr/NusaDesk/releases/download/v0.4.0/NusaDesk-v0.4.0.apk";
    private static final String ASSET_DIGEST =
            "sha256:063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c";
    private static final long ASSET_SIZE = 17_405_756L;

    /** Shape the real GitHub API returns for /releases/latest. */
    private static String fixture(String tag, String htmlUrl) {
        return fixture(tag, htmlUrl, true);
    }

    private static String fixture(String tag, String htmlUrl, boolean usableAsset) {
        return "{"
                + "\"url\":\"https://api.github.com/repos/jahrulnr/NusaDesk/releases/249000001\","
                + "\"assets_url\":\"https://api.github.com/repos/jahrulnr/NusaDesk/releases/249000001/assets\","
                + "\"html_url\":\"" + htmlUrl + "\","
                + "\"id\":249000001,"
                + "\"tag_name\":\"" + tag + "\","
                + "\"target_commitish\":\"main\","
                + "\"name\":\"" + tag + "\","
                + "\"draft\":false,"
                + "\"prerelease\":false,"
                + "\"created_at\":\"2026-09-19T12:00:00Z\","
                + "\"published_at\":\"2026-09-19T12:30:00Z\","
                + "\"assets\":[{"
                + "\"name\":\"app-release.apk\","
                + (usableAsset
                        ? "\"browser_download_url\":\"" + ASSET_URL + "\","
                        + "\"digest\":\"" + ASSET_DIGEST + "\","
                        + "\"size\":" + ASSET_SIZE
                        : "\"browser_download_url\":\"https://github.com/jahrulnr/NusaDesk/releases/download/" + tag + "/app-release.apk\"")
                + "}]}";
    }

    @Test
    public void aNewerTagReportsUpdateAvailableWithTagAndReleaseUrl() {
        UpdateCheckResult result =
                GitHubReleaseChecker.parseRelease("0.3.0", fixture("v0.4.0", RELEASE_URL));

        assertEquals(UpdateCheckResult.Kind.UPDATE_AVAILABLE, result.getKind());
        assertEquals("v0.4.0", result.getTag());
        assertEquals(RELEASE_URL, result.getReleaseUrl());
        assertEquals(ASSET_URL, result.getDownloadUrl());
        assertEquals(ASSET_DIGEST, result.getDigest());
        assertEquals(ASSET_SIZE, result.getSizeBytes());
    }

    @Test
    public void anAssetWithoutAReportedDigestOnlyAllowsTheBrowserHandOff() {
        UpdateCheckResult result = GitHubReleaseChecker.parseRelease("0.3.0",
                fixture("v0.4.0", RELEASE_URL, false));

        assertEquals(UpdateCheckResult.Kind.UPDATE_AVAILABLE, result.getKind());
        assertEquals("v0.4.0", result.getTag());
        assertNull("no digest, no assisted install", result.getDigest());
        assertNull(result.getDownloadUrl());
        assertEquals(-1L, result.getSizeBytes());
    }

    @Test
    public void anAssetUrlOverNonHttpsIsDroppedNotTrusted() {
        String body = fixture("v0.4.0", RELEASE_URL).replace(
                "https://github.com/jahrulnr/NusaDesk/releases/download/v0.4.0/",
                "http://insecure.example/").replace(
                "\"digest\":\"" + ASSET_DIGEST + "\",", "\"digest\":\"sha256:deadbeef\",");

        UpdateCheckResult result = GitHubReleaseChecker.parseRelease("0.3.0", body);

        assertEquals(UpdateCheckResult.Kind.UPDATE_AVAILABLE, result.getKind());
        assertNull("a non-HTTPS asset url is never handed to the installer",
                result.getDownloadUrl());
        assertNull("a malformed digest never passes", result.getDigest());
    }

    @Test
    public void theSameTagIsUpToDate() {
        UpdateCheckResult result =
                GitHubReleaseChecker.parseRelease("0.3.0", fixture("v0.3.0", RELEASE_URL));

        assertEquals(UpdateCheckResult.Kind.UP_TO_DATE, result.getKind());
        assertNull(result.getTag());
        assertNull(result.getReleaseUrl());
    }

    @Test
    public void anOlderTagIsUpToDate() {
        assertEquals(UpdateCheckResult.Kind.UP_TO_DATE,
                GitHubReleaseChecker.parseRelease("0.3.0", fixture("v0.2.9", RELEASE_URL)).getKind());
    }

    @Test
    public void aPrereleaseTagOfTheSameTripleIsUpToDate() {
        assertEquals(UpdateCheckResult.Kind.UP_TO_DATE,
                GitHubReleaseChecker.parseRelease("0.3.0", fixture("v0.3.0-rc1", RELEASE_URL)).getKind());
    }

    @Test
    public void anUnparsableInstalledVersionCannotReportAnUpdate() {
        assertEquals(UpdateCheckResult.Kind.UP_TO_DATE,
                GitHubReleaseChecker.parseRelease("dev", fixture("v0.4.0", RELEASE_URL)).getKind());
    }

    @Test
    public void malformedJsonIsUnavailable() {
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0", "{not json"));
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0", ""));
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0", null));
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0", "[]"));
    }

    @Test
    public void aMissingOrUnparsableTagIsUnavailable() {
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0",
                "{\"html_url\":\"" + RELEASE_URL + "\"}"));
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0",
                fixture("latest", RELEASE_URL)));
    }

    @Test
    public void aMissingOrNonHttpsReleaseUrlIsUnavailable() {
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0",
                "{\"tag_name\":\"v0.4.0\"}"));
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0",
                fixture("v0.4.0", "javascript:alert(1)")));
        assertUnavailable(GitHubReleaseChecker.parseRelease("0.3.0",
                fixture("v0.4.0", "http://github.com/jahrulnr/NusaDesk/releases/tag/v0.4.0")));
    }

    private static void assertUnavailable(UpdateCheckResult result) {
        assertEquals(UpdateCheckResult.Kind.UNAVAILABLE, result.getKind());
        assertNull(result.getTag());
        assertNull(result.getReleaseUrl());
    }
}
