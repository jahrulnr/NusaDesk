package gh.nusashell.nusadesk.presentation.system;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.presentation.GuestSshStatusAware;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;
import gh.nusashell.nusadesk.presentation.RuntimeStateDescriptor;
import gh.nusashell.nusadesk.presentation.ScreenView;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.SessionUiState;
import gh.nusashell.nusadesk.presentation.widget.StateBadgeView;

/**
 * Linux system screen: install state, session and terminal-component status, the
 * technical details that a support conversation needs, and the product contract.
 *
 * <p>It reports state and never offers a session start/stop control: Linux starts
 * from an app launch and is stopped from the platform's own foreground-service
 * notification (ADR-0013). Transport detail that would be noise on a primary
 * surface (the loopback endpoint, the runtime profile and version) lives here
 * instead of on the launcher or an app surface.</p>
 */
public final class SystemScreenView extends ScrollView
        implements ScreenView, SessionStatusAware, GuestSshStatusAware {

    private StateBadgeView stateBadge;
    private TextView stateSummary;
    private TextView stateDetail;
    private TextView sessionValue;
    private TextView serviceValue;
    private TextView webAppsValue;
    private TextView endpointValue;
    private TextView profileValue;
    private TextView versionValue;

    private final RuntimeStatusBus.Listener statusListener = this::renderSessionStatus;

    public SystemScreenView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.widget_system_screen, this, true);
        initViews();
    }

    public SystemScreenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.widget_system_screen, this, true);
        initViews();
    }

    private void initViews() {
        stateBadge = findViewById(R.id.system_state_badge);
        stateSummary = findViewById(R.id.system_state_summary);
        stateDetail = findViewById(R.id.system_state_detail);
        sessionValue = findViewById(R.id.system_session_value);
        serviceValue = findViewById(R.id.system_service_value);
        webAppsValue = findViewById(R.id.system_webapps_value);
        endpointValue = findViewById(R.id.system_endpoint_value);
        profileValue = findViewById(R.id.system_profile_value);
        versionValue = findViewById(R.id.system_version_value);
        endpointValue.setText(R.string.system_detail_endpoint_none);
        sessionValue.setText(SessionUiState.unknown().getBadgeRes());
        serviceValue.setText(R.string.system_service_missing);
        setWebAppCount(0);
    }

    /** Wires the "How it works" contract disclosure. */
    public void setOnHowItWorksListener(OnClickListener listener) {
        findViewById(R.id.system_how_button).setOnClickListener(listener);
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RuntimeStatusBus.getInstance().register(statusListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        RuntimeStatusBus.getInstance().unregister(statusListener);
        super.onDetachedFromWindow();
    }

    @Override
    public void render(RuntimeSnapshot snapshot) {
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

    @Override
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

    @Override
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
