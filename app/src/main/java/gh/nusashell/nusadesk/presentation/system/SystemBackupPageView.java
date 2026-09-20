package gh.nusashell.nusadesk.presentation.system;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.backup.BackupProgress;
import gh.nusashell.nusadesk.application.backup.BackupResult;
import gh.nusashell.nusadesk.domain.backup.BackupMode;
import gh.nusashell.nusadesk.domain.backup.BackupScopePolicy;
import gh.nusashell.nusadesk.domain.backup.BackupSelection;
import gh.nusashell.nusadesk.domain.backup.LastBackupRecord;
import gh.nusashell.nusadesk.domain.backup.LastBackupRun;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * System &gt; Settings &gt; Backup &amp; restore page: the three export modes,
 * the CUSTOM folder allowlist, the SAF export/import actions, and the typed
 * status surface.
 *
 * <p>The view only collects a {@link BackupSelection} and renders
 * {@link BackupProgress}/{@link BackupResult}/{@link LastBackupRecord} — the
 * domain validates the selection and the engine produces the typed terminal
 * state, so no archive or runtime policy lives here.</p>
 */
public final class SystemBackupPageView extends ScrollView {

    /** Host callbacks: SAF intents are fired by the Activity, not the view. */
    public interface Listener {
        void onExportRequested(BackupSelection selection);

        void onImportRequested();
    }

    private RadioGroup modeGroup;
    private TextView modeDetail;
    private LinearLayout customPanel;
    private Button exportAction;
    private Button importAction;
    private TextView lastValue;
    private TextView lastRunValue;
    private TextView progressValue;
    private TextView resultValue;
    private Listener listener;
    private boolean busy;

    public SystemBackupPageView(Context context) {
        super(context);
        init();
    }

    public SystemBackupPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext())
                .inflate(R.layout.widget_system_backup_page, this, true);
        ((TextView) findViewById(R.id.system_page_title))
                .setText(R.string.system_backup_title);
        modeGroup = findViewById(R.id.system_backup_mode_group);
        modeDetail = findViewById(R.id.system_backup_mode_detail);
        customPanel = findViewById(R.id.system_backup_custom_panel);
        exportAction = findViewById(R.id.system_backup_export_action);
        importAction = findViewById(R.id.system_backup_import_action);
        lastValue = findViewById(R.id.system_backup_last_value);
        lastRunValue = findViewById(R.id.system_backup_last_run_value);
        progressValue = findViewById(R.id.system_backup_progress_value);
        resultValue = findViewById(R.id.system_backup_result_value);

        for (String root : BackupScopePolicy.allowedCustomRoots()) {
            CheckBox box = new CheckBox(getContext());
            box.setText(root);
            box.setTag(root);
            box.setTextColor(getResources().getColor(R.color.ink));
            box.setTextSize(14);
            box.setMinHeight(getResources().getDimensionPixelSize(R.dimen.touch_target));
            customPanel.addView(box);
        }
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> renderModeDetail());
        exportAction.setOnClickListener(view -> {
            BackupSelection selection = currentSelection();
            if (listener != null && selection != null) {
                listener.onExportRequested(selection);
            }
        });
        importAction.setOnClickListener(view -> {
            if (listener != null) {
                listener.onImportRequested();
            }
        });
        renderModeDetail();
    }

    /** Wires the back row that returns to the System hub. */
    public void setOnBackListener(OnClickListener listener) {
        findViewById(R.id.system_page_back).setOnClickListener(listener);
    }

    /** Wires the page's two actions; the host owns the SAF round-trips. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * The selection the current controls describe, or {@code null} when CUSTOM
     * is chosen with no folder checked — the export action stays inert then.
     */
    public BackupSelection currentSelection() {
        int checked = modeGroup.getCheckedRadioButtonId();
        if (checked == R.id.system_backup_mode_home) {
            return BackupSelection.home();
        }
        if (checked == R.id.system_backup_mode_custom) {
            List<String> roots = new ArrayList<>();
            for (int i = 0; i < customPanel.getChildCount(); i++) {
                View child = customPanel.getChildAt(i);
                if (child instanceof CheckBox && ((CheckBox) child).isChecked()) {
                    roots.add((String) child.getTag());
                }
            }
            try {
                return BackupSelection.custom(roots);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return BackupSelection.full();
    }

    /** Renders the persisted last-backup record, or the empty state. */
    public void renderLastBackup(LastBackupRecord record) {
        if (record == null) {
            lastValue.setText(R.string.system_backup_last_none);
            return;
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(record.getCreatedAtEpochMs()));
        lastValue.setText(getContext().getString(R.string.system_backup_last_value,
                modeLabel(record.getMode()), when, record.getDisplayName()));
    }

    /** Renders the persisted last-run outcome, or hides the line when absent. */
    public void renderLastRun(LastBackupRun run) {
        if (run == null) {
            lastRunValue.setVisibility(GONE);
            return;
        }
        lastRunValue.setVisibility(VISIBLE);
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(run.getFinishedAtEpochMs()));
        int operationRes = LastBackupRun.OPERATION_RESTORE.equals(run.getOperation())
                ? R.string.system_backup_op_restore : R.string.system_backup_op_export;
        String outcome = run.isSuccess()
                ? getContext().getString(R.string.system_backup_outcome_ok)
                : getContext().getString(R.string.system_backup_outcome_failed,
                        run.getFailureCode());
        lastRunValue.setText(getContext().getString(R.string.system_backup_run_value,
                getContext().getString(operationRes), outcome, when));
    }

    /**
     * The picker returned without a document: render the cancelled terminal
     * state instead of leaving the page looking mid-operation.
     */
    public void renderCancelled() {
        setBusy(false);
        progressValue.setVisibility(GONE);
        resultValue.setVisibility(VISIBLE);
        resultValue.setTextColor(getResources().getColor(R.color.ink_secondary));
        resultValue.setText(R.string.system_backup_cancelled);
    }

    /** Renders one progress tick while an export/import runs. */
    public void renderProgress(BackupProgress progress) {
        if (progress == null) {
            return;
        }
        progressValue.setVisibility(VISIBLE);
        int entries = (int) Math.min(progress.getEntries(), Integer.MAX_VALUE);
        progressValue.setText(getContext().getResources().getQuantityString(
                R.plurals.system_backup_progress, entries,
                progress.getDetail(), progress.getEntries(), formatBytes(progress.getBytes())));
    }

    /** Renders the terminal state and re-enables the actions. */
    public void renderResult(BackupResult result) {
        if (result == null) {
            return;
        }
        setBusy(false);
        progressValue.setVisibility(GONE);
        resultValue.setVisibility(VISIBLE);
        if (result.isReady()) {
            resultValue.setTextColor(getResources().getColor(R.color.success));
            int entries = (int) Math.min(result.getEntries(), Integer.MAX_VALUE);
            resultValue.setText(getContext().getResources().getQuantityString(
                    R.plurals.system_backup_done, entries,
                    result.getEntries(), formatBytes(result.getBytes())));
        } else {
            resultValue.setTextColor(getResources().getColor(R.color.danger));
            String detail = result.getDetail();
            resultValue.setText(getContext().getString(R.string.system_backup_failed,
                    result.getFailure() == null ? "" : result.getFailure().getCode(),
                    detail));
        }
    }

    /** Disables both actions while a backup operation runs. */
    public void setBusy(boolean busy) {
        this.busy = busy;
        exportAction.setEnabled(!busy);
        importAction.setEnabled(!busy);
        if (busy) {
            progressValue.setVisibility(VISIBLE);
            progressValue.setText(R.string.system_backup_working);
            resultValue.setVisibility(GONE);
        }
    }

    public boolean isBusy() {
        return busy;
    }

    private void renderModeDetail() {
        int checked = modeGroup.getCheckedRadioButtonId();
        int detailRes;
        if (checked == R.id.system_backup_mode_home) {
            detailRes = R.string.system_backup_mode_home_detail;
        } else if (checked == R.id.system_backup_mode_custom) {
            detailRes = R.string.system_backup_mode_custom_detail;
        } else {
            detailRes = R.string.system_backup_mode_full_detail;
        }
        modeDetail.setText(detailRes);
        customPanel.setVisibility(
                checked == R.id.system_backup_mode_custom ? VISIBLE : GONE);
    }

    private String modeLabel(BackupMode mode) {
        int res;
        switch (mode) {
            case HOME:
                res = R.string.system_backup_mode_home;
                break;
            case CUSTOM:
                res = R.string.system_backup_mode_custom;
                break;
            default:
                res = R.string.system_backup_mode_full;
                break;
        }
        return getContext().getString(res);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
