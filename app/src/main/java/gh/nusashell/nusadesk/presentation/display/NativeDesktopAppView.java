package gh.nusashell.nusadesk.presentation.display;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.display.NativeDesktopPort;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput;
import gh.nusashell.nusadesk.presentation.SoftKeyboardAware;

/** Retained native app area, with an assistive orb and explicit startup/retry states. */
@SuppressLint("ViewConstructor")
public final class NativeDesktopAppView extends FrameLayout implements SoftKeyboardAware {
    private final NativeDesktopPort port;
    private final LinearLayout statusCard;
    private final TextView title, status;
    private final Button retry;
    private final ProgressBar progress;
    private final DesktopControlsOrb controls;
    private final NativeDesktopPort.Listener listener = this::render;
    private Runnable appClosed = () -> { };
    private Runnable disposed = () -> { };

    public NativeDesktopAppView(Context context, NativeDesktopPort port, View output, NativeDesktopInput input) {
        super(context);
        this.port = port;
        setBackgroundColor(getResources().getColor(R.color.surface, null));
        addView(output, new LayoutParams(-1, -1));
        controls = new DesktopControlsOrb(context, input);
        addView(controls, new LayoutParams(-1, -1));
        statusCard = new LinearLayout(context);
        statusCard.setOrientation(LinearLayout.VERTICAL); statusCard.setGravity(Gravity.CENTER);
        int pad = dp(20); statusCard.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(getResources().getColor(R.color.surface_elevated, null));
        background.setCornerRadius(dp(16));
        statusCard.setBackground(background);
        progress = new ProgressBar(context);
        statusCard.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        title = new TextView(context); title.setTextSize(18); title.setGravity(Gravity.CENTER);
        title.setTextColor(getResources().getColor(R.color.ink, null));
        statusCard.addView(title, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(context); status.setTextSize(14); status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, dp(12));
        status.setTextColor(getResources().getColor(R.color.ink_secondary, null));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusCard.addView(status, new LinearLayout.LayoutParams(-1, -2));
        retry = new Button(context); retry.setText(R.string.native_gui_open); retry.setAllCaps(false);
        retry.setOnClickListener(view -> port.open());
        statusCard.addView(retry, new LinearLayout.LayoutParams(-1, dp(48)));
        LayoutParams card = new LayoutParams(-1, -2, Gravity.CENTER);
        card.setMargins(dp(24), dp(16), dp(24), dp(16));
        addView(statusCard, card);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public boolean ownsTextInput() { return true; }
    public void setOnAppClosed(Runnable callback) { appClosed = callback; }
    public void setOnDisposed(Runnable callback) { disposed = callback; }
    private void render(NativeDesktopPort.State state, String detail) {
        if (state == NativeDesktopPort.State.APP_CLOSED) { appClosed.run(); return; }
        boolean ready = state == NativeDesktopPort.State.RUNNING;
        boolean starting = state == NativeDesktopPort.State.STARTING;
        statusCard.setVisibility(ready ? GONE : VISIBLE);
        controls.setVisibility(ready ? VISIBLE : GONE);
        if (!ready) controls.close();
        progress.setVisibility(starting ? VISIBLE : GONE);
        retry.setVisibility(starting ? GONE : VISIBLE);
        // A stopped app is opened; a failed or incomplete setup is tried again.
        retry.setText(state == NativeDesktopPort.State.STOPPED
                ? R.string.native_gui_open_app : R.string.native_gui_open);
        title.setText(starting ? R.string.native_display_starting
                : state == NativeDesktopPort.State.MISSING_COMPONENTS ? R.string.native_display_missing
                : state == NativeDesktopPort.State.FAILED ? R.string.native_display_failed : R.string.native_display_stopped);
        status.setText(detail);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); port.subscribe(listener); port.open();
    }
    @Override protected void onDetachedFromWindow() {
        port.unsubscribe(listener); disposed.run(); super.onDetachedFromWindow();
    }
}
