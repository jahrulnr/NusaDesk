package gh.nusashell.nusadesk.presentation.widget;

import android.content.Context;
import android.text.SpannableString;
import android.text.style.LeadingMarginSpan;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Ubuntu-live-installer setup surface for the single setup pipeline. It renders
 * the core lifecycle (prepare → download Linux → verify → extract → add
 * terminal component → add core services → ready) plus the two independent
 * opt-in toolkits (USB/ADB driver, Termux command compatibility — ADR-0057)
 * as one append-only, monospace, dark terminal log panel with a compact
 * progress bar and one thumb-reachable bottom action.
 *
 * <p>Each install phase appends a deduplicated, component-tagged line to the
 * terminal log so the user sees the full history while setup runs. Rootfs
 * phases read {@code download}/{@code verify}/{@code extract}/{@code ready};
 * add-on phases read {@code ssh …}; the core services overlay reads
 * {@code svc …}; optional toolkit installs read {@code usb …} and
 * {@code termux …}. Wrapped continuations indent under the detail column so a
 * wrapped line never reads as a new log event. Download progress is parsed
 * from the snapshot detail and shown as a determinate progress bar; verify
 * and extract show indeterminate activity. The log is bounded to
 * {@link InstallerLog#MAX_LINES} lines and auto-scrolls to keep the latest
 * output visible.</p>
 *
 * <p>It never starts a process; it only reflects snapshots published by the
 * application installer. The log is fed by {@link #appendLog}, which gates
 * appends on an active-install flag so an initial load with the rootfs already
 * active never logs a spurious ready line. The display (progress, detail,
 * action) is driven by {@link #showPhase}, which is idempotent and
 * safe to call on every re-render. The single action is Start setup, Retry
 * setup, or a disabled Installing…; there is no second "Next: add terminal
 * component" button. While the action is a user choice (start/retry), two
 * unchecked-by-default checkboxes offer the optional toolkits — each is
 * installed only when the user ticks it or installs it later from System &gt;
 * One-click install.</p>
 */
public final class InstallerWizardView extends LinearLayout {

    private final InstallerLog log = new InstallerLog();

    private TextView logView;
    private ScrollView logScroll;
    private ProgressBar progress;
    private TextView detailView;
    private View extrasCard;
    private View usbExtraRow;
    private CheckBox usbExtra;
    private View termuxExtraRow;
    private CheckBox termuxExtra;
    private Button actionButton;
    private boolean usbOffered;
    private boolean termuxOffered;
    /** Last action passed to {@link #showPhase}; starts hidden so a card set
     *  before the first phase render stays out of the way. */
    private SetupAction lastAction = SetupAction.HIDDEN;

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
        logView = findViewById(R.id.wizard_log);
        logScroll = findViewById(R.id.wizard_log_scroll);
        progress = findViewById(R.id.wizard_progress);
        detailView = findViewById(R.id.wizard_detail);
        extrasCard = findViewById(R.id.wizard_extras);
        usbExtraRow = findViewById(R.id.wizard_extra_usb_row);
        usbExtra = findViewById(R.id.wizard_extra_usb);
        termuxExtraRow = findViewById(R.id.wizard_extra_termux_row);
        termuxExtra = findViewById(R.id.wizard_extra_termux);
        actionButton = findViewById(R.id.wizard_action);
        // A tap anywhere on a toolkit row toggles its opt-in; each checkbox
        // itself stays the announced control for accessibility.
        usbExtraRow.setOnClickListener(view -> usbExtra.toggle());
        termuxExtraRow.setOnClickListener(view -> termuxExtra.toggle());
        // The terminal log is a live region so screen readers announce new lines
        // as the install progresses, without the user having to focus it.
        logView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    /** Wires the single primary action (start/retry) to the host install flow. */
    public void setOnActionListener(View.OnClickListener listener) {
        actionButton.setOnClickListener(listener);
    }

    /**
     * Offers — or withdraws — each optional toolkit independently
     * (ADR-0057). Offered toolkits render an unchecked checkbox; they only
     * render while the bottom action is a user choice (start/retry), so an
     * in-flight pipeline never surfaces a mid-install toggle. The host
     * decides each offer from disk truth: an already-installed toolkit is
     * never offered again.
     */
    public void setOptionalToolsOffered(boolean usbOffered, boolean termuxOffered) {
        this.usbOffered = usbOffered;
        this.termuxOffered = termuxOffered;
        updateExtrasVisibility();
    }

    /** Whether the user ticked the USB / ADB driver toolkit for this run. */
    public boolean isUsbAdbSelected() {
        return usbOffered && usbExtra.isChecked();
    }

    /** Whether the user ticked the Termux commands toolkit for this run. */
    public boolean isTermuxSelected() {
        return termuxOffered && termuxExtra.isChecked();
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
     * Updates the setup surface display — progress, detail, and the single
     * action — for the combined phase. Idempotent and safe to call on every
     * re-render; it does not append to the log.
     */
    public void showPhase(InstallPhaseSnapshot phase, SetupAction action) {
        if (log.isEmpty()) {
            log.prependPrepare(getContext().getString(R.string.wizard_welcome_body));
            renderLogText();
        }
        RuntimeState state = phase.getState();
        configureProgress(phase, state);
        detailView.setText(detailFor(state, phase.getDetail()));
        detailView.setVisibility(
                shouldShowDetail(phase, state) ? VISIBLE : GONE);
        configureAction(action);
    }

    /**
     * Renders the log so a wrapped continuation can never be mistaken for a
     * new log event: every logical line carries a {@code "tag  "} prefix
     * ({@code prepare}, {@code download}, {@code ssh}, {@code svc},
     * {@code usb}, {@code termux}, …) and a hanging indent aligns the wrapped
     * text under the detail column while real newlines still start flush left
     * at the tag.
     */
    private void renderLogText() {
        String text = log.joined();
        SpannableString rendered = new SpannableString(text);
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            int tagEnd = line.indexOf("  ");
            if (tagEnd > 0 && !line.isEmpty()) {
                int indent = (int) logView.getPaint()
                        .measureText(line.substring(0, tagEnd + 2));
                // LeadingMarginSpan is a ParagraphStyle: the span must cover
                // the whole paragraph, so it ends on the newline (or the end
                // of the text for the last line), never mid-paragraph.
                int end = Math.min(text.length(), offset + line.length() + 1);
                rendered.setSpan(new LeadingMarginSpan.Standard(0, indent),
                        offset, end, 0);
            }
            offset += line.length() + 1;
        }
        logView.setText(rendered);
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private boolean shouldShowDetail(InstallPhaseSnapshot phase, RuntimeState state) {
        if (state == RuntimeState.NOT_INSTALLED || state == RuntimeState.READY) {
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

    /**
     * An opt-in toolkit is a decision the user makes with the action, so the
     * offered rows render exactly while a decision is pending — never while
     * an install runs and never after setup has finished.
     */
    private void updateExtrasVisibility() {
        boolean choicePending =
                lastAction == SetupAction.START || lastAction == SetupAction.RETRY;
        boolean showExtras = choicePending && (usbOffered || termuxOffered);
        extrasCard.setVisibility(showExtras ? VISIBLE : GONE);
        usbExtraRow.setVisibility(showExtras && usbOffered ? VISIBLE : GONE);
        termuxExtraRow.setVisibility(showExtras && termuxOffered ? VISIBLE : GONE);
    }

    private void configureAction(SetupAction action) {
        lastAction = action;
        updateExtrasVisibility();
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
                // The core pipeline is active: the launcher is the surface
                // this wizard lives on, and Linux starts by itself, so there
                // is nothing left to press here.
                actionButton.setVisibility(GONE);
                break;
        }
    }
}
