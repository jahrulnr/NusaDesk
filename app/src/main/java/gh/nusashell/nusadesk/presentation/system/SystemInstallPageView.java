package gh.nusashell.nusadesk.presentation.system;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.proot.GuestOptionalTools;

/**
 * System hub &gt; One-click install page: the curated components list
 * (ADR-0043, amended by ADR-0057). The first two cards are the mandatory
 * core — the service bridge (systemctl + Python) and the base extras
 * (CA certificates + OpenSSL) that setup itself installs and retries — so
 * they report their state and never carry an Install action. The USB/ADB
 * driver (ADR-0042), Termux command compatibility (ADR-0036), and systemd
 * D-Bus compatibility cards are independent opt-ins: each has its own real
 * Install / Try again action and renders its own
 * installed/installing/failed truth. The trailing card states there is no
 * arbitrary package install.
 */
public final class SystemInstallPageView extends ScrollView {

    private TextView servicesState;
    private TextView baseState;
    private TextView usbState;
    private TextView usbDetail;
    private Button usbAction;
    private TextView termuxState;
    private TextView termuxDetail;
    private Button termuxAction;
    private TextView dbusState;
    private TextView dbusDetail;
    private Button dbusAction;

    public SystemInstallPageView(Context context) {
        super(context);
        init();
    }

    public SystemInstallPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext())
                .inflate(R.layout.widget_system_install_page, this, true);
        ((TextView) findViewById(R.id.system_page_title))
                .setText(R.string.system_install_title);
        servicesState = findViewById(R.id.system_install_services_state);
        baseState = findViewById(R.id.system_install_base_state);
        usbState = findViewById(R.id.system_install_usb_state);
        usbDetail = findViewById(R.id.system_install_usb_detail);
        usbAction = findViewById(R.id.system_install_usb_action);
        termuxState = findViewById(R.id.system_install_termux_state);
        termuxDetail = findViewById(R.id.system_install_termux_detail);
        termuxAction = findViewById(R.id.system_install_termux_action);
        dbusState = findViewById(R.id.system_install_dbus_state);
        dbusDetail = findViewById(R.id.system_install_dbus_detail);
        dbusAction = findViewById(R.id.system_install_dbus_action);
    }

    /** Wires the back row that returns to the System hub. */
    public void setOnBackListener(OnClickListener listener) {
        findViewById(R.id.system_page_back).setOnClickListener(listener);
    }

    /**
     * Wires one optional toolkit's Install / Try again action to the host.
     * Each toolkit owns its own action — a tap only ever installs that
     * toolkit.
     */
    public void setOnToolkitInstallListener(
            GuestOptionalTools.Kind kind, OnClickListener listener) {
        if (kind == GuestOptionalTools.Kind.USB_ADB) {
            usbAction.setOnClickListener(listener);
        } else if (kind == GuestOptionalTools.Kind.TERMUX) {
            termuxAction.setOnClickListener(listener);
        }
    }

    /**
     * Wires the systemd D-Bus compatibility overlay's Install / Try again
     * action to the host. The overlay is opt-in, so a tap only ever
     * installs that add-on — nothing schedules it.
     */
    public void setOnDbusFaceInstallListener(OnClickListener listener) {
        dbusAction.setOnClickListener(listener);
    }

    /**
     * Renders the mandatory service bridge row (ADR-0057). It is core —
     * installed and retried by setup itself — so this card carries a
     * truthful state pill and no action: a missing overlay is setup's job,
     * not a button here.
     */
    public void renderRequiredService(boolean installed) {
        renderRequiredPill(servicesState, installed);
    }

    /**
     * Renders the mandatory base-extras row (CA certificates + OpenSSL).
     * Same contract as the service bridge: core tier, so state only.
     */
    public void renderBaseExtras(boolean installed) {
        renderRequiredPill(baseState, installed);
    }

    private void renderRequiredPill(TextView pill, boolean installed) {
        if (installed) {
            pill.setText(R.string.system_install_state_installed);
            stylePill(pill, R.color.success, R.color.success_tint);
        } else {
            pill.setText(R.string.system_install_state_not_installed);
            stylePill(pill, R.color.ink_secondary, R.color.surface_subtle);
        }
    }

    /**
     * Renders one optional toolkit's row from its own derived state —
     * installed/installing/available/failed — with the action enabled only
     * while the serialized pipeline is idle. One toolkit's state never
     * bleeds into the other row.
     */
    public void renderToolkit(GuestOptionalTools.Kind kind, ServicesExtraState state) {
        TextView pill;
        TextView detail;
        Button action;
        if (kind == GuestOptionalTools.Kind.USB_ADB) {
            pill = usbState;
            detail = usbDetail;
            action = usbAction;
        } else if (kind == GuestOptionalTools.Kind.TERMUX) {
            pill = termuxState;
            detail = termuxDetail;
            action = termuxAction;
        } else {
            return;
        }
        renderExtraRow(pill, detail, action, state);
    }

    /**
     * Renders the D-Bus face's row from its own derived state — same
     * installed/installing/available/failed vocabulary as the toolkits.
     */
    public void renderDbusFace(ServicesExtraState state) {
        renderExtraRow(dbusState, dbusDetail, dbusAction, state);
    }

    /**
     * Renders one optional-extra row: the pill reports state, the action
     * appears only for an installable outcome and is enabled only while
     * the serialized pipeline is idle, and the detail line appears only
     * when the snapshot carries text.
     */
    private void renderExtraRow(
            TextView pill, TextView detail, Button action, ServicesExtraState state) {
        switch (state.getKind()) {
            case INSTALLED:
                pill.setText(R.string.system_install_state_installed);
                stylePill(pill, R.color.success, R.color.success_tint);
                action.setVisibility(GONE);
                renderDetail(detail, null);
                break;
            case INSTALLING:
                pill.setText(R.string.system_install_state_installing);
                stylePill(pill, R.color.info, R.color.info_tint);
                action.setVisibility(GONE);
                renderDetail(detail, state.getDetail());
                break;
            case FAILED:
                pill.setText(R.string.system_install_state_failed);
                stylePill(pill, R.color.danger, R.color.danger_tint);
                action.setVisibility(VISIBLE);
                action.setText(R.string.system_install_action_retry);
                action.setEnabled(state.isActionEnabled());
                renderDetail(detail, state.getDetail());
                break;
            default:
                pill.setText(R.string.system_install_state_not_installed);
                stylePill(pill, R.color.ink_secondary, R.color.surface_subtle);
                action.setVisibility(VISIBLE);
                action.setText(R.string.system_install_action_install);
                action.setEnabled(state.isActionEnabled());
                renderDetail(detail, null);
                break;
        }
    }

    private void renderDetail(TextView detailView, String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            detailView.setVisibility(GONE);
            return;
        }
        detailView.setText(detail);
        detailView.setVisibility(VISIBLE);
    }

    /**
     * Gives a state line the same pill treatment a {@code StateBadgeView}
     * would: a tinted surface plus the matching ink. The text carries the
     * meaning; the tint is only reinforcement.
     */
    private void stylePill(TextView pill, int inkRes, int tintRes) {
        pill.setTextColor(getContext().getColor(inkRes));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getContext().getColor(tintRes));
        background.setCornerRadius(
                40f * getResources().getDisplayMetrics().density);
        pill.setBackground(background);
    }
}
