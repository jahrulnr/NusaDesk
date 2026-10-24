package gh.nusashell.nusadesk.presentation.display;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.EnumMap;
import java.util.Locale;
import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput.Key;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput.Mode;

/** Draggable, in-app assistive controls; never a system overlay or global input capture. */
@SuppressLint("ViewConstructor")
final class DesktopControlsOrb extends FrameLayout {
    private final NativeDesktopInput input;
    private final Button orb;
    private final ScrollView panel;
    private final View dismiss;
    private final EnumMap<Key, Button> keys = new EnumMap<>(Key.class);
    private final EnumMap<Mode, Button> modes = new EnumMap<>(Mode.class);
    private float downX, downY, originX, originY;
    private boolean moved;
    private Button zoomLabel, keyboard;
    private float verticalFraction = .55f;
    private boolean right = true;

    // drag() explicitly invokes performClick() for a tap; movement is not a click.
    @SuppressLint("ClickableViewAccessibility")
    DesktopControlsOrb(Context context, NativeDesktopInput input) {
        super(context);
        this.input = input;
        dismiss = new View(context);
        dismiss.setContentDescription(context.getString(R.string.native_controls_close));
        dismiss.setOnClickListener(view -> close());
        dismiss.setVisibility(GONE);
        addView(dismiss, new LayoutParams(-1, -1));
        panel = new ScrollView(context);
        panel.setFillViewport(false);
        panel.setElevation(dp(12));
        panel.setBackground(plate(R.color.surface_elevated, R.color.border, 16));
        panel.setVisibility(GONE);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = new TextView(context);
        title.setText(R.string.native_controls_title);
        title.setTextColor(color(R.color.ink)); title.setTextSize(16);
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        Button hide = button("×"); hide.setContentDescription(context.getString(R.string.native_controls_close));
        hide.setOnClickListener(view -> close()); heading.addView(hide, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(heading);
        keyboard = button(context.getString(R.string.native_keyboard_show));
        keyboard.setOnClickListener(view -> { close(); input.toggleKeyboard(); });
        content.addView(keyboard, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView hint = new TextView(context);
        hint.setText(R.string.native_controls_gestures);
        hint.setTextColor(color(R.color.ink_secondary)); hint.setTextSize(12);
        hint.setPadding(0, dp(4), 0, dp(8));

        LinearLayout modeRow = new LinearLayout(context);
        for (Mode mode : Mode.values()) {
            Button key = button(modeLabel(mode)); modes.put(mode, key);
            key.setOnClickListener(view -> { input.setMode(mode); render(); close(); });
            modeRow.addView(key, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        content.addView(modeRow);
        LinearLayout zoomRow = new LinearLayout(context);
        Button smaller = button("−"); smaller.setContentDescription(context.getString(R.string.native_zoom_out));
        smaller.setOnClickListener(view -> input.setZoomPercent(input.zoomPercent() - 25));
        zoomRow.addView(smaller, new LinearLayout.LayoutParams(0, dp(48), 1));
        zoomLabel = button("Fit"); zoomLabel.setOnClickListener(view -> input.setZoomPercent(100));
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(0, dp(48), 2));
        Button larger = button("+"); larger.setContentDescription(context.getString(R.string.native_zoom_in));
        larger.setOnClickListener(view -> input.setZoomPercent(input.zoomPercent() + 25));
        zoomRow.addView(larger, new LinearLayout.LayoutParams(0, dp(48), 1));

        LinearLayout layoutRow = new LinearLayout(context);
        for (int width : new int[]{0, 960, 1280}) {
            Button layout = button(width == 0 ? getContext().getString(R.string.native_layout_fit) : width + " px");
            layout.setContentDescription(width == 0 ? getContext().getString(R.string.native_layout_fit) : getContext().getString(R.string.native_layout_width, width));
            layout.setOnClickListener(view -> { input.setLayoutWidth(width); close(); });
            layoutRow.addView(layout, new LinearLayout.LayoutParams(0, dp(48), 1));
        }

        GridLayout grid = new GridLayout(context); grid.setColumnCount(4);
        Key[] order = {Key.ESC, Key.TAB, Key.CTRL, Key.ALT, Key.SHIFT, Key.HOME, Key.UP, Key.END,
                Key.PAGE_UP, Key.LEFT, Key.DOWN, Key.RIGHT, Key.PAGE_DOWN, Key.BACKSPACE, Key.ENTER};
        for (Key key : order) {
            Button button = button(label(key));
            button.setContentDescription(description(key));
            button.setOnClickListener(view -> { input.press(key); render(); });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0; params.height = dp(48);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            grid.addView(button, params); keys.put(key, button);
        }
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        content.addView(zoomRow);
        content.addView(layoutRow);
        content.addView(hint);
        panel.addView(content);
        addView(panel);
        orb = button("⊙");
        orb.setTextSize(30); orb.setElevation(dp(8));
        orb.setBackground(plate(R.color.primary_tint, R.color.primary, 28));
        orb.setContentDescription(context.getString(R.string.native_controls_open));
        orb.setOnClickListener(view -> { if (panel.getVisibility() == VISIBLE) close(); else open(); });
        orb.setOnTouchListener(this::drag);
        addView(orb, new LayoutParams(dp(48), dp(48)));
        input.setControlsChanged(() -> post(this::render));
        render();
    }
    private Button button(String title) {
        Button button = new Button(getContext());
        button.setText(title); button.setTextSize(12); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setTextColor(color(R.color.ink));
        button.setBackgroundTintList(ColorStateList.valueOf(color(R.color.surface_subtle)));
        // Deliberately not focusable: on a hardware keyboard every key —
        // arrows, Tab, Enter — belongs to the guest session, and a focusable
        // control would steal that input the moment the panel opens. The
        // panel's key buttons exist for touch users who lack the real keys.
        button.setFocusable(false);
        return button;
    }
    private GradientDrawable plate(int background, int border, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color(background)); result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), color(border)); return result;
    }
    private int color(int id) { return getResources().getColor(id, null); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String modeLabel(Mode mode) {
        return getContext().getString(mode == Mode.TOUCH ? R.string.native_mode_touch
                : mode == Mode.DRAG ? R.string.native_mode_drag : R.string.native_mode_pointer);
    }
    private String label(Key key) {
        switch (key) {
            case LEFT: return "←"; case RIGHT: return "→"; case UP: return "↑"; case DOWN: return "↓";
            case PAGE_UP: return "PgUp"; case PAGE_DOWN: return "PgDn";
            case BACKSPACE: return "⌫"; case ENTER: return "↵";
            default: String text = key.name().toLowerCase(Locale.ROOT); return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
    }
    private String description(Key key) {
        return key.name().replace('_', ' ') + (input.isArmed(key) ? getContext().getString(R.string.native_key_armed) : "");
    }
    private void render() {
        if (keyboard != null) keyboard.setText(input.isKeyboardVisible() ? R.string.native_keyboard_hide : R.string.native_keyboard_show);
        if (zoomLabel != null) {
            zoomLabel.setText(getContext().getString(R.string.native_zoom_fit_value, input.zoomPercent()));
            zoomLabel.setContentDescription(getContext().getString(R.string.native_zoom_reset, input.zoomPercent()));
        }
        for (Key key : keys.keySet()) {
            Button button = keys.get(key);
            boolean armed = input.isArmed(key);
            button.setText(armed ? label(key) + " •" : label(key));
            button.setContentDescription(description(key));
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(armed ? R.color.primary_tint : R.color.surface_subtle)));
        }
        for (Mode mode : modes.keySet()) {
            Button button = modes.get(mode);
            button.setText(mode == input.mode() ? getContext().getString(R.string.native_mode_active, modeLabel(mode)) : modeLabel(mode));
            button.setContentDescription(modeLabel(mode) + (mode == input.mode() ? getContext().getString(R.string.native_mode_selected) : ""));
        }
    }
    private void open() {
        panel.setVisibility(VISIBLE); dismiss.setVisibility(VISIBLE); orb.setVisibility(INVISIBLE);
        render(); place(); orb.setContentDescription(getContext().getString(R.string.native_controls_close));
    }
    void close() {
        panel.setVisibility(GONE); dismiss.setVisibility(GONE); orb.setVisibility(VISIBLE);
        orb.setContentDescription(getContext().getString(R.string.native_controls_open));
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && panel.getVisibility() == VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @SuppressLint("ClickableViewAccessibility")
    private boolean drag(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY(); originX = orb.getX(); originY = orb.getY(); moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
                if (moved) {
                    close(); orb.setX(clamp(originX + dx, getWidth() - orb.getWidth()));
                    orb.setY(clamp(originY + dy, getHeight() - orb.getHeight()));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) orb.performClick();
                else {
                    right = orb.getX() + orb.getWidth() / 2f >= getWidth() / 2f;
                    verticalFraction = orb.getY() / Math.max(1, getHeight() - orb.getHeight());
                    place();
                }
                return true;
            case MotionEvent.ACTION_CANCEL: place(); return true;
            default: return true;
        }
    }
    private float clamp(float value, int max) { return Math.max(0, Math.min(Math.max(0, max), value)); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); post(this::place);
    }
    private void place() {
        if (getWidth() == 0 || getHeight() == 0) return;
        int size = dp(48), margin = dp(8);
        orb.setX(right ? Math.max(0, getWidth() - size - margin) : margin);
        orb.setY(clamp(verticalFraction * Math.max(0, getHeight() - size), getHeight() - size));
        LayoutParams params = new LayoutParams(Math.min(dp(320), Math.max(1, getWidth() - margin * 2)), Math.max(1, Math.min(dp(600), getHeight() - margin * 2)));
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.leftMargin = margin; params.rightMargin = margin;
        params.topMargin = Math.round(clamp(orb.getY() - dp(200), getHeight() - params.height - margin));
        panel.setLayoutParams(params);

    }
    @Override protected void onDetachedFromWindow() { close(); super.onDetachedFromWindow(); }
}
