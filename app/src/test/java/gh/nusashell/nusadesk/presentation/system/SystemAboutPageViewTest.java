package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;

import java.util.concurrent.atomic.AtomicBoolean;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.update.ChangelogSection;
import gh.nusashell.nusadesk.infrastructure.update.GitHubReleaseChecker;

/**
 * The About page's update row and changelog card (ADR-0066): the row's tap
 * reaches the host's manual check, the status line tells the truth for every
 * check kind, the card renders the parsed newest section with an honest
 * fallback, and "Read more" fires a real browser intent at the repository
 * changelog.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 30, 31, 33})
public class SystemAboutPageViewTest {

    private static SystemScreenView screen() {
        Context context = RuntimeEnvironment.getApplication();
        return new SystemScreenView(context);
    }

    @Test
    public void tappingTheUpdateRowReachesTheHost() {
        SystemScreenView screen = screen();
        AtomicBoolean reached = new AtomicBoolean(false);

        screen.setOnCheckUpdateListener(view -> reached.set(true));
        screen.findViewById(R.id.system_update_row).performClick();

        assertTrue("the tapped row must reach the host's manual check", reached.get());
    }

    @Test
    public void theUpdateStatusNamesTheInstalledVersion() {
        SystemScreenView screen = screen();
        TextView status = screen.findViewById(R.id.system_update_status);

        screen.renderInstalledVersion("1.2.3");

        assertTrue(status.getText().toString().contains("1.2.3"));
    }

    @Test
    public void theUpdateStatusReportsEveryCheckKind() {
        SystemScreenView screen = screen();
        TextView status = screen.findViewById(R.id.system_update_status);

        screen.renderUpdateChecking();
        assertEquals(screen.getContext().getString(R.string.system_update_checking),
                status.getText().toString());

        screen.renderUpdateCheck(GitHubReleaseChecker.UpdateCheckResult.upToDate(), "1.2.3");
        assertTrue(status.getText().toString().contains("1.2.3"));

        screen.renderUpdateCheck(GitHubReleaseChecker.UpdateCheckResult.updateAvailable(
                "v9.9.9", "https://example.invalid/r", null, null, -1L), "1.2.3");
        assertTrue(status.getText().toString().contains("v9.9.9"));

        screen.renderUpdateCheck(GitHubReleaseChecker.UpdateCheckResult.unavailable(), "1.2.3");
        assertEquals(screen.getContext().getString(R.string.system_update_failed),
                status.getText().toString());

        // A null check payload is a failed check, not a misleading up-to-date.
        screen.renderUpdateCheck(null, "1.2.3");
        assertEquals(screen.getContext().getString(R.string.system_update_failed),
                status.getText().toString());
    }

    @Test
    public void theChangelogCardRendersTheSectionAndTheFallback() {
        SystemScreenView screen = screen();
        TextView title = screen.findViewById(R.id.system_changelog_title);
        TextView text = screen.findViewById(R.id.system_changelog_text);

        ChangelogSection section = ChangelogSection.parseLatest(
                "## [Unreleased]\n\n## [3.4.5] - 2026-11-02\n\n### Added\n\n- A thing.\n");
        screen.renderChangelog(section);

        assertEquals("3.4.5 — 2026-11-02", title.getText().toString());
        assertTrue(text.getText().toString().contains("A thing."));

        screen.renderChangelog(null);

        assertEquals(View.GONE, title.getVisibility());
        assertEquals(screen.getContext().getString(R.string.system_changelog_unavailable),
                text.getText().toString());
    }

    @Test
    public void readMoreOpensTheRepositoryChangelogInABrowser() {
        SystemScreenView screen = screen();

        screen.findViewById(R.id.system_changelog_more).performClick();

        ShadowApplication shadow = Shadow.extract(
                screen.getContext().getApplicationContext());
        Intent intent = shadow.getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertTrue("the link must point at the repository changelog",
                intent.getData().toString().endsWith("/CHANGELOG.md"));
    }
}
