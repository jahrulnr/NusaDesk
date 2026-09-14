package gh.nusashell.nusadesk.presentation.widget;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.presentation.RuntimeStateDescriptor;
import gh.nusashell.nusadesk.presentation.SessionUiState;

/**
 * Reusable pill badge that renders one state with a text label, semantic
 * colours, and an accessible content description.
 *
 * <p>It renders either the Linux system's install state or the live session
 * state — never both at once — so a surface can never show an install badge
 * that contradicts the session statement next to it. State is never conveyed by
 * colour alone: the label text is always present.</p>
 */
public final class StateBadgeView extends TextView {
    public StateBadgeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Renders a Linux system install state from a domain state. */
    public void render(RuntimeState state) {
        RuntimeStateDescriptor descriptor = RuntimeStateDescriptor.forState(state);
        renderRuntime(descriptor.getLabel(), state, descriptor.getSummary());
    }

    /** Renders a snapshot, using its actionable detail in the content description. */
    public void render(RuntimeSnapshot snapshot) {
        RuntimeStateDescriptor descriptor = RuntimeStateDescriptor.forState(snapshot.getState());
        renderRuntime(descriptor.getLabel(), snapshot.getState(), snapshot.getDetail());
    }

    /** Renders the live session state using the shared session vocabulary. */
    public void renderSession(SessionUiState state) {
        setText(state.getBadgeRes());
        setTextColor(getContext().getColor(SessionStateStyle.foregroundColor(state.getKind())));
        setBackground(createBadgeBackground(
                getContext().getColor(SessionStateStyle.backgroundColor(state.getKind()))));
        setContentDescription(getContext().getString(
                R.string.status_content_description,
                getContext().getString(state.getBadgeRes()),
                getContext().getString(state.getDetailRes())));
    }

    private void renderRuntime(String label, RuntimeState state, String description) {
        setText(label);
        setTextColor(getContext().getColor(RuntimeStateStyle.foregroundColor(state)));
        setBackground(createBadgeBackground(
                getContext().getColor(RuntimeStateStyle.backgroundColor(state))));
        setContentDescription(getContext().getString(
                R.string.status_content_description, label, description));
    }

    private GradientDrawable createBadgeBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(40));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
