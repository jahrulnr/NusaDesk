package gh.nusashell.nusadesk.presentation.system;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.update.ChangelogSection;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.update.GitHubReleaseChecker;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;
import gh.nusashell.nusadesk.presentation.RuntimeStateDescriptor;
import gh.nusashell.nusadesk.presentation.SessionUiState;
import gh.nusashell.nusadesk.presentation.widget.StateBadgeView;

/**
 * System hub &gt; About NusaDesk page: the runtime install state, the
 * technical details a support conversation needs (session, terminal
 * component, web apps, loopback endpoint, runtime profile and version), the
 * GitHub link, and the "How it works" contract disclosure (ADR-0043).
 *
 * <p>The page reports state and never offers a session start/stop control:
 * Linux starts from an app launch and is stopped from the platform's own
 * foreground-service notification (ADR-0013).</p>
 */
public final class SystemAboutPageView extends ScrollView {

    private static final String GITHUB_URL = "https://github.com/jahrulnr/NusaDesk";
    private static final String CHANGELOG_URL =
            "https://github.com/jahrulnr/NusaDesk/blob/master/CHANGELOG.md";

    private StateBadgeView stateBadge;
    private TextView stateSummary;
    private TextView stateDetail;
    private TextView sessionValue;
    private TextView serviceValue;
    private TextView webAppsValue;
    private TextView endpointValue;
    private TextView profileValue;
    private TextView versionValue;
    private TextView updateStatus;
    private TextView changelogTitle;
    private TextView changelogText;

    public SystemAboutPageView(Context context) {
        super(context);
        init();
    }

    public SystemAboutPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext())
                .inflate(R.layout.widget_system_about_page, this, true);
        ((TextView) findViewById(R.id.system_page_title))
                .setText(R.string.system_about_title);
        stateBadge = findViewById(R.id.system_state_badge);
        stateSummary = findViewById(R.id.system_state_summary);
        stateDetail = findViewById(R.id.system_state_detail);
        sessionValue = findViewById(R.id.system_session_value);
        serviceValue = findViewById(R.id.system_service_value);
        webAppsValue = findViewById(R.id.system_webapps_value);
        endpointValue = findViewById(R.id.system_endpoint_value);
        profileValue = findViewById(R.id.system_profile_value);
        versionValue = findViewById(R.id.system_version_value);
        updateStatus = findViewById(R.id.system_update_status);
        changelogTitle = findViewById(R.id.system_changelog_title);
        changelogText = findViewById(R.id.system_changelog_text);
        endpointValue.setText(R.string.system_detail_endpoint_none);
        sessionValue.setText(SessionUiState.unknown().getBadgeRes());
        serviceValue.setText(R.string.system_service_missing);
        setWebAppCount(0);
        findViewById(R.id.system_github_row).setOnClickListener(
                view -> openExternal(GITHUB_URL, R.string.system_github_unavailable));
        findViewById(R.id.system_changelog_more).setOnClickListener(
                view -> openExternal(CHANGELOG_URL,
                        R.string.system_changelog_unavailable_link));
    }

    /** Wires the back row that returns to the System hub. */
    public void setOnBackListener(OnClickListener listener) {
        findViewById(R.id.system_page_back).setOnClickListener(listener);
    }

    /** Wires the "How it works" contract disclosure. */
    public void setOnHowItWorksListener(OnClickListener listener) {
        findViewById(R.id.system_how_button).setOnClickListener(listener);
    }

    /** Wires the update row's explicit re-check tap (ADR-0066). */
    public void setOnCheckUpdateListener(OnClickListener listener) {
        findViewById(R.id.system_update_row).setOnClickListener(listener);
    }

    /**
     * States the installed app version under the update row, so the check has
     * something to compare against at a glance. A {@code null} version leaves
     * the descriptive default.
     */
    public void renderInstalledVersion(String installedVersion) {
        if (installedVersion != null && !installedVersion.trim().isEmpty()) {
            updateStatus.setText(getContext().getString(
                    R.string.system_update_installed, installedVersion));
        }
    }

    /** Marks a manual check as in flight. */
    public void renderUpdateChecking() {
        updateStatus.setText(R.string.system_update_checking);
    }

    /**
     * Renders one completed check under the row. The status always reports
     * the truth of the check — including an update the user dismissed on the
     * launcher banner — while the banner/notification hand-off itself stays
     * with the host's shared completion path (ADR-0066).
     */
    public void renderUpdateCheck(GitHubReleaseChecker.UpdateCheckResult result,
            String installedVersion) {
        if (result == null || installedVersion == null) {
            updateStatus.setText(R.string.system_update_failed);
            return;
        }
        switch (result.getKind()) {
            case UP_TO_DATE:
                updateStatus.setText(getContext().getString(
                        R.string.system_update_up_to_date, installedVersion));
                break;
            case UPDATE_AVAILABLE:
                updateStatus.setText(getContext().getString(
                        R.string.system_update_available, result.getTag()));
                break;
            default:
                updateStatus.setText(R.string.system_update_failed);
                break;
        }
    }

    /**
     * Renders the newest released changelog section on the card; a
     * {@code null} section — a build whose bundled asset could not be read
     * or parsed — gets the honest unavailable text (ADR-0066).
     */
    public void renderChangelog(ChangelogSection section) {
        if (section == null) {
            changelogTitle.setVisibility(GONE);
            changelogText.setText(R.string.system_changelog_unavailable);
            return;
        }
        changelogTitle.setVisibility(VISIBLE);
        String title = section.getDate().isEmpty()
                ? section.getVersion()
                : section.getVersion() + " — " + section.getDate();
        changelogTitle.setText(title);
        changelogText.setText(section.getBody());
    }

    /**
     * Opens an external page in the system browser — a plain VIEW intent, the
     * same pattern the update banner's release page uses. A device with no
     * browser gets the honest unavailable toast instead of a dead row.
     */
    private void openExternal(String url, int failureRes) {
        try {
            getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException noBrowser) {
            Toast.makeText(getContext(), failureRes, Toast.LENGTH_LONG).show();
        }
    }

    /** States how many user-defined web apps are registered, from the registry truth. */
    public void setWebAppCount(int count) {
        webAppsValue.setText(getContext().getString(
                count == 1 ? R.string.apps_count_singular : R.string.apps_count_plural,
                Math.max(0, count)));
    }

    /** Shows which curated runtime profile and version this build expects. */
    public void setRuntimeProfile(String appId, String version) {
        profileValue.setText(appId == null ? "" : appId);
        versionValue.setText(version == null ? "" : version);
    }

    /** Renders the runtime install state: badge, summary, and detail. */
    public void renderState(RuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        RuntimeStateDescriptor descriptor = RuntimeStateDescriptor.forState(snapshot.getState());
        stateBadge.render(snapshot);
        stateSummary.setText(descriptor.getSummary());
        String detail = snapshot.getDetail();
        stateDetail.setText(detail == null || detail.trim().isEmpty()
                ? descriptor.getDetail()
                : detail);
    }

    /** Renders the live session row and the endpoint the session reported. */
    public void renderSessionStatus(HostRuntimeStatus status) {
        SessionUiState session = status == null
                ? SessionUiState.unknown()
                : SessionUiState.from(status.getState(), status.getFailureReason());
        sessionValue.setText(session.getBadgeRes());
        SessionSnapshot snapshot = status == null ? null : status.getSnapshot();
        if (snapshot != null && snapshot.getEndpoint() != null) {
            endpointValue.setText(getContext().getString(R.string.system_detail_endpoint_value,
                    snapshot.getEndpoint().getHost(), snapshot.getEndpoint().getPort()));
        } else {
            endpointValue.setText(R.string.system_detail_endpoint_none);
        }
    }

    /** Renders the terminal-component (guest sshd) install row. */
    public void renderGuestSsh(GuestSshUiState state) {
        GuestSshUiState current = state == null ? GuestSshUiState.missing() : state;
        switch (current.getKind()) {
            case INSTALLED:
                serviceValue.setText(R.string.system_service_installed);
                break;
            case INSTALLING:
                serviceValue.setText(R.string.system_service_installing);
                break;
            case FAILED:
                String reason = current.getDetail();
                serviceValue.setText(getContext().getString(R.string.system_service_failed,
                        reason == null || reason.trim().isEmpty() ? "" : reason));
                break;
            default:
                serviceValue.setText(R.string.system_service_missing);
                break;
        }
    }
}
