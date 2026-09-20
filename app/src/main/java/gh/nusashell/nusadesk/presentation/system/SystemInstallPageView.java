package gh.nusashell.nusadesk.presentation.system;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;

/**
 * System hub &gt; One-click install page: the curated templates list
 * (ADR-0043). Today's entries are built into every session — the USB/ADB
 * driver (ADR-0042) and the Termux command compatibility layer (ADR-0036) —
 * so they render their state as a pill instead of a fake Install button. The
 * trailing card explains that installable add-ons arrive with the
 * provisioning slice; there is no arbitrary package install.
 */
public final class SystemInstallPageView extends ScrollView {

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
        styleStatePill((TextView) findViewById(R.id.system_install_usb_state));
        styleStatePill((TextView) findViewById(R.id.system_install_termux_state));
    }

    /** Wires the back row that returns to the System hub. */
    public void setOnBackListener(OnClickListener listener) {
        findViewById(R.id.system_page_back).setOnClickListener(listener);
    }

    /**
     * Gives a state line the same pill treatment a {@code StateBadgeView}
     * would: the success tint surface plus the success ink. The text carries
     * the meaning; the tint is only reinforcement.
     */
    private void styleStatePill(TextView pill) {
        pill.setTextColor(getContext().getColor(R.color.success));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getContext().getColor(R.color.success_tint));
        background.setCornerRadius(
                40f * getResources().getDisplayMetrics().density);
        pill.setBackground(background);
    }
}
