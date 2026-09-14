package gh.nusashell.nusadesk.presentation.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Ubuntu-live-installer setup surface for the single setup pipeline. It renders
 * the combined rootfs + guest-SSH add-on install lifecycle
 * (prepare → download Linux → verify → extract → add terminal component → ready)
 * as one append-only, monospace, dark terminal log panel with a compact
 * progress bar and one thumb-reachable bottom action.
 *
 * <p>Each install phase appends a deduplicated, component-tagged line to the
 * terminal log so the user sees the full history while setup runs. Rootfs
 * phases read {@code download}/{@code verify}/{@code extract}/{@code ready};
 * add-on phases read {@code ssh …}. Download progress is parsed from the
 * snapshot detail and shown as a determinate progress bar; verify and extract
 * show indeterminate activity. The log is bounded to
 * {@link InstallerLog#MAX_LINES} lines and auto-scrolls to keep the latest
 * output visible.</p>
 *
 * <p>It never starts a process; it only reflects snapshots published by the
 * application installer. The log is fed by {@link #appendLog}, which gates
 * appends on an active-install flag so an initial load with the rootfs already
 * active never logs a spurious ready line. The display (title, progress,
 * detail, action) is driven by {@link #showPhase}, which is idempotent and
 * safe to call on every re-render. The single action is Start setup, Retry
 * setup, or a disabled Installing…; there is no second "Next: add terminal
 * component" button.</p>
 */
public final class InstallerWizardView extends LinearLayout {

    private final InstallerLog log = new InstallerLog();

    private TextView titleView;
    private TextView logView;
    private ScrollView logScroll;
    private View requirements;
    private TextView storageRequirement;
    private ProgressBar progress;
    private TextView detailView;
    private Button actionButton;

    public InstallerWizardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.widget_installer_wizard, this, true);
    }

    public InstallerWizardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.widget_installer_wizard, this, true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        titleView = findViewById(R.id.wizard_title);
        logView = findViewById(R.id.wizard_log);
        logScroll = findViewById(R.id.wizard_log_scroll);
        requirements = findViewById(R.id.wizard_requirements);
        storageRequirement = findViewById(R.id.wizard_requirement_storage);
        progress = findViewById(R.id.wizard_progress);
        detailView = findViewById(R.id.wizard_detail);
        actionButton = findViewById(R.id.wizard_action);
        // The terminal log is a live region so screen readers announce new lines
        // as the install progresses, without the user having to focus it.
        logView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    /**
     * States the real download-plus-extracted size instead of a vague "enough
     * storage", so the first-run requirement is checkable by the user.
     */
    public void setStorageRequirement(long totalBytes) {
        if (storageRequirement == null || totalBytes <= 0) {
            return;
        }
        storageRequirement.setText(getContext().getString(
                R.string.wizard_requirement_storage,
                android.text.format.Formatter.formatShortFileSize(getContext(), totalBytes)));
    }

    /** Wires the single primary action (start/retry) to the host install flow. */
    public void setOnActionListener(View.OnClickListener listener) {
        actionButton.setOnClickListener(listener);
    }

    /**
     * Feeds one install phase snapshot into the terminal log, applying the
     * active-install gating, new-attempt clearing, and component-aware
     * deduplication. Call this on every rootfs and add-on snapshot arrival.
     */
    public void appendLog(InstallPhaseSnapshot phase) {
        log.append(phase);
        renderLogText();
    }

    /**
     * Updates the setup surface display — title, requirements, progress,
     * detail, and the single action — for the combined phase. Idempotent and
     * safe to call on every re-render; it does not append to the log.
     */
    public void showPhase(InstallPhaseSnapshot phase, SetupAction action) {
        if (log.isEmpty()) {
            log.prependPrepare(getContext().getString(R.string.wizard_welcome_body));
            renderLogText();
        }
        RuntimeState state = phase.getState();
        titleView.setText(titleFor(phase));
        requirements.setVisibility(
                phase.getComponent() == InstallPhaseSnapshot.Component.ROOTFS
                        && state == RuntimeState.NOT_INSTALLED ? VISIBLE : GONE);
        configureProgress(phase, state);
        detailView.setText(detailFor(state, phase.getDetail()));
        detailView.setVisibility(
                shouldShowDetail(phase, state) ? VISIBLE : GONE);
        configureAction(action);
    }

    private void renderLogText() {
        logView.setText(log.joined());
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int titleFor(InstallPhaseSnapshot phase) {
        if (phase.getComponent() == InstallPhaseSnapshot.Component.ADDON) {
            switch (phase.getState()) {
                case DOWNLOADING:
                    return R.string.wizard_addon_download_title;
                case VERIFYING:
                    return R.string.wizard_addon_verify_title;
                case EXTRACTING:
                    return R.string.wizard_addon_extract_title;
                case READY:
                    return R.string.wizard_ready_title;
                case FAILED:
                    return R.string.wizard_failed_title;
                default:
                    return R.string.wizard_addon_pending_title;
            }
        }
        switch (phase.getState()) {
            case NOT_INSTALLED:
                return R.string.wizard_welcome_title;
            case DOWNLOADING:
                return R.string.wizard_download_title;
            case VERIFYING:
                return R.string.wizard_verify_title;
            case EXTRACTING:
                return R.string.wizard_extract_title;
            case READY:
                // The rootfs is active but the add-on is still pending: setup
                // continues, so the surface states the next phase, not a done
                // state the launcher unlock would contradict.
                return R.string.wizard_addon_pending_title;
            case FAILED:
                return R.string.wizard_failed_title;
            default:
                return R.string.wizard_addon_pending_title;
        }
    }

    private boolean shouldShowDetail(InstallPhaseSnapshot phase, RuntimeState state) {
        if (state == RuntimeState.NOT_INSTALLED || state == RuntimeState.READY) {
            return false;
        }
        if (phase.getComponent() == InstallPhaseSnapshot.Component.ADDON
                && phase.getState() == RuntimeState.NOT_INSTALLED) {
            return false;
        }
        String detail = phase.getDetail();
        return detail != null && !detail.trim().isEmpty();
    }

    private String detailFor(RuntimeState state, String snapshotDetail) {
        if (state == RuntimeState.NOT_INSTALLED || state == RuntimeState.READY) {
            return "";
        }
        return snapshotDetail == null ? "" : snapshotDetail;
    }

    private void configureProgress(InstallPhaseSnapshot phase, RuntimeState state) {
        if (state == RuntimeState.DOWNLOADING) {
            progress.setVisibility(VISIBLE);
            int percent = InstallerLog.parsePercent(phase.getDetail());
            if (percent >= 0) {
                progress.setIndeterminate(false);
                progress.setProgress(percent);
                progress.setContentDescription(
                        getContext().getString(R.string.wizard_progress_desc, percent));
            } else {
                progress.setIndeterminate(true);
                progress.setContentDescription(
                        getContext().getString(R.string.wizard_indeterminate_desc));
            }
            return;
        }
        if (state == RuntimeState.VERIFYING || state == RuntimeState.EXTRACTING) {
            progress.setVisibility(VISIBLE);
            progress.setIndeterminate(true);
            progress.setContentDescription(
                    getContext().getString(R.string.wizard_indeterminate_desc));
            return;
        }
        progress.setVisibility(GONE);
    }

    private void configureAction(SetupAction action) {
        switch (action) {
            case START:
                actionButton.setVisibility(VISIBLE);
                actionButton.setText(R.string.action_begin_install);
                actionButton.setEnabled(true);
                actionButton.setContentDescription(
                        getContext().getString(R.string.action_begin_install));
                break;
            case RETRY:
                actionButton.setVisibility(VISIBLE);
                actionButton.setText(R.string.action_retry_install);
                actionButton.setEnabled(true);
                actionButton.setContentDescription(
                        getContext().getString(R.string.action_retry_install));
                break;
            case INSTALLING:
                actionButton.setVisibility(VISIBLE);
                actionButton.setText(R.string.action_installing);
                actionButton.setEnabled(false);
                actionButton.setContentDescription(
                        getContext().getString(R.string.action_installing));
                break;
            default:
                // Both components are active: the launcher is the surface this
                // wizard lives on, and Linux starts by itself, so there is
                // nothing left to press here.
                actionButton.setVisibility(GONE);
                break;
        }
    }
}
