package gh.nusashell.nusadesk.presentation.system;

import android.content.Context;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.presentation.workspace.WorkspaceUiState;

/**
 * System hub &gt; Settings page: the control rows the single System scroll used
 * to host — the workspace folder, battery optimization, boot start, and the
 * app-settings shortcut. The page renders state and forwards the four action
 * taps to the host; it owns no policy of its own (ADR-0043).
 */
public final class SystemSettingsPageView extends ScrollView {

    private TextView workspaceValue;
    private TextView workspaceDetail;
    private Button workspaceAction;
    private Button permissionsAction;
    private TextView batteryValue;
    private TextView batteryDetail;
    private Button batteryAction;
    private TextView bootValue;
    private TextView bootDetail;
    private Button bootAction;

    public SystemSettingsPageView(Context context) {
        super(context);
        init();
    }

    public SystemSettingsPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext())
                .inflate(R.layout.widget_system_settings_page, this, true);
        ((TextView) findViewById(R.id.system_page_title))
                .setText(R.string.system_settings_title);
        workspaceValue = findViewById(R.id.system_workspace_value);
        workspaceDetail = findViewById(R.id.system_workspace_detail);
        workspaceAction = findViewById(R.id.system_workspace_action);
        permissionsAction = findViewById(R.id.system_permissions_action);
        batteryValue = findViewById(R.id.system_battery_value);
        batteryDetail = findViewById(R.id.system_battery_detail);
        batteryAction = findViewById(R.id.system_battery_action);
        bootValue = findViewById(R.id.system_boot_value);
        bootDetail = findViewById(R.id.system_boot_detail);
        bootAction = findViewById(R.id.system_boot_action);
        workspaceValue.setText(R.string.system_workspace_value_none);
        workspaceDetail.setText(R.string.system_workspace_detail_none);
        workspaceAction.setVisibility(GONE);
        batteryAction.setVisibility(GONE);
    }

    /** Wires the back row that returns to the System hub. */
    public void setOnBackListener(OnClickListener listener) {
        findViewById(R.id.system_page_back).setOnClickListener(listener);
    }

    /**
     * Wires the single workspace action. Which action it is depends on the state
     * rendered by {@link #renderWorkspace(WorkspaceUiState)} — allow access,
     * choose a folder, or change it — so the host handles them in one place.
     */
    public void setOnWorkspaceActionListener(OnClickListener listener) {
        workspaceAction.setOnClickListener(listener);
    }

    /**
     * Wires the app-permissions shortcut. The button opens this app's Android
     * App Info page via {@link Settings#ACTION_APPLICATION_DETAILS_SETTINGS},
     * where the platform manages camera, microphone, location, contacts, SMS and
     * other permission switches; NusaDesk never requests them itself.
     */
    public void setOnOpenAppSettingsListener(OnClickListener listener) {
        permissionsAction.setOnClickListener(listener);
    }

    /** Wires the battery card's single action (request exemption). */
    public void setOnBatteryActionListener(OnClickListener listener) {
        batteryAction.setOnClickListener(listener);
    }

    /** Wires the boot card's single action (flip the opt-in, ADR-0037). */
    public void setOnBootActionListener(OnClickListener listener) {
        bootAction.setOnClickListener(listener);
    }

    /**
     * Renders the workspace folder state. The screen reports what is possible on
     * this device; it never offers an action the platform cannot honour, and it
     * never widens storage access on its own.
     */
    public void renderWorkspace(WorkspaceUiState state) {
        WorkspaceUiState current = state == null ? WorkspaceUiState.notChosen() : state;
        int valueRes = current.getValueRes();
        if (valueRes == 0) {
            workspaceValue.setText(current.getFolderLabel());
        } else {
            workspaceValue.setText(valueRes);
        }
        workspaceDetail.setText(current.getDetailArg() == null
                ? getContext().getString(current.getDetailRes())
                : getContext().getString(current.getDetailRes(), current.getDetailArg()));
        if (current.hasAction()) {
            workspaceAction.setText(current.getActionRes());
            workspaceAction.setVisibility(VISIBLE);
        } else {
            workspaceAction.setVisibility(GONE);
        }
    }

    /**
     * Renders the battery-optimization card. The host reads the permission-free
     * PowerManager state and re-reads it on every foreground event; the request
     * itself is made only from the action tap, so an exempt device needs no
     * button and the card only says how to revoke.
     */
    public void renderBattery(boolean exempt) {
        if (exempt) {
            batteryValue.setText(R.string.system_battery_value_exempt);
            batteryDetail.setText(R.string.system_battery_detail_exempt);
            batteryAction.setVisibility(GONE);
        } else {
            batteryValue.setText(R.string.system_battery_value_not_exempt);
            batteryDetail.setText(R.string.system_battery_detail_not_exempt);
            batteryAction.setText(R.string.system_battery_action_request);
            batteryAction.setVisibility(VISIBLE);
        }
    }

    /**
     * Renders the boot-start card. Off is the product default and the receiver
     * stays inert until the user opts in here; the one action flips the
     * setting, so its text names what the next tap does.
     */
    public void renderBoot(boolean enabled) {
        bootValue.setText(enabled
                ? R.string.system_boot_value_enabled
                : R.string.system_boot_value_disabled);
        bootDetail.setText(enabled
                ? R.string.system_boot_detail_enabled
                : R.string.system_boot_detail_disabled);
        bootAction.setText(enabled
                ? R.string.system_boot_action_disable
                : R.string.system_boot_action_enable);
    }
}
