package gh.nusashell.nusadesk.presentation.terminal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import gh.nusashell.nusadesk.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The terminal's mobile accessory key row: ESC, CTRL, ALT, TAB, the arrows,
 * HOME, END, PGUP, and PGDN.
 *
 * <p>It is a keyboard extension, not a second terminal protocol. Every press is
 * encoded by {@link TerminalKeySequences} and handed to the surface's single
 * input path — the same stdin path the page's own {@code INPUT} messages use —
 * so nothing here can invent a session or reach the guest another way. There is
 * deliberately no JavaScript interface involved.</p>
 *
 * <p>CTRL and ALT are sticky: arming one applies it to the next key press or
 * the next typed character and then clears. Their armed state is a real state,
 * so it is rendered as a filled, bordered key and described in words for
 * accessibility rather than by colour alone.</p>
 *
 * <p>The keys wrap into as many rows as the available width needs: two compact
 * rows on a phone, one on a wide landscape surface, and more only when the
 * system font scale makes the key caps wider. The row never claims a fixed
 * fraction of the terminal, and it stays out of the soft keyboard's way because
 * the shell applies the IME inset to the whole surface.</p>
 */
public final class TerminalKeyRowView extends LinearLayout {

    /** Receives the encoded bytes of one key press. */
    public interface Listener {
        void onKeyInput(String sequence);
    }

    private final List<Button> keys = new ArrayList<>();
    private Button ctrlButton;
    private Button altButton;

    private Listener listener;
    private boolean ctrlArmed;
    private boolean altArmed;
    private boolean shellAttached;
    private int laidOutWidth = -1;

    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable repeatTick = new Runnable() {
        @Override
        public void run() {
            fireRepeatTick();
        }
    };
    private TerminalKeyRepeat activeRepeat;
    private TerminalKey activeRepeatKey;
    private Button activeRepeatButton;

    public TerminalKeyRowView(Context context) {
        super(context);
        init();
    }

    public TerminalKeyRowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(getResources().getColor(R.color.terminal_surface, null));
        int gap = getResources().getDimensionPixelSize(R.dimen.terminal_key_gap);
        setPadding(gap, gap, gap, gap);
        buildKeys();
        renderEnabledState();
    }

    /** Wires the row to the surface's stdin path. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Enables the keys only while a shell is actually attached. Disabled keys
     * stay visible and readable; they are inert, not hidden, so the row does not
     * appear and disappear as the session changes.
     */
    public void setShellAttached(boolean attached) {
        if (shellAttached == attached) {
            return;
        }
        shellAttached = attached;
        if (!attached) {
            cancelRepeat();
            clearModifiers();
        }
        renderEnabledState();
    }

    /**
     * Applies and clears the sticky modifiers for input typed on the soft
     * keyboard. Returns the input unchanged when no modifier is armed.
     */
    public String applyPendingModifiers(String typed) {
        if (!ctrlArmed && !altArmed) {
            return typed;
        }
        String encoded = TerminalKeySequences.forTypedInput(typed, ctrlArmed, altArmed);
        clearModifiers();
        return encoded;
    }

    // ---- Key construction ----

    private void buildKeys() {
        for (TerminalKey key : TerminalKey.row()) {
            Button button = newKey();
            button.setText(key.getLabelRes());
            button.setContentDescription(getContext().getString(
                    R.string.terminal_key_desc, getContext().getString(key.getLabelRes())));
            button.setOnClickListener(view -> onKeyPressed(key));
            if (TerminalKeyRepeat.isRepeating(key)) {
                attachHoldRepeat(button, key);
            }
            keys.add(button);
            if (key == TerminalKey.TAB) {
                ctrlButton = newModifier(R.string.terminal_key_ctrl, true);
                altButton = newModifier(R.string.terminal_key_alt, false);
            }
        }
    }

    private Button newKey() {
        return (Button) LayoutInflater.from(getContext())
                .inflate(R.layout.widget_terminal_key, this, false);
    }

    private Button newModifier(int labelRes, boolean ctrl) {
        Button button = newKey();
        button.setText(labelRes);
        button.setOnClickListener(view -> toggleModifier(ctrl));
        // The armed/unarmed description is set here as well as on every toggle:
        // a key that is never toggled still has to explain itself.
        renderModifier(button, false, labelRes);
        keys.add(button);
        return button;
    }

    private void onKeyPressed(TerminalKey key) {
        if (!shellAttached || listener == null) {
            return;
        }
        String sequence = TerminalKeySequences.forKey(key, ctrlArmed, altArmed);
        clearModifiers();
        listener.onKeyInput(sequence);
    }

    private void toggleModifier(boolean ctrl) {
        if (!shellAttached) {
            return;
        }
        if (ctrl) {
            ctrlArmed = !ctrlArmed;
        } else {
            altArmed = !altArmed;
        }
        renderModifier(ctrlButton, ctrlArmed, R.string.terminal_key_ctrl);
        renderModifier(altButton, altArmed, R.string.terminal_key_alt);
    }

    private void clearModifiers() {
        ctrlArmed = false;
        altArmed = false;
        renderModifier(ctrlButton, false, R.string.terminal_key_ctrl);
        renderModifier(altButton, false, R.string.terminal_key_alt);
    }

    private void renderModifier(Button button, boolean armed, int labelRes) {
        if (button == null) {
            return;
        }
        button.setSelected(armed);
        button.setContentDescription(getContext().getString(
                armed ? R.string.terminal_key_modifier_armed_desc : R.string.terminal_key_modifier_desc,
                getContext().getString(labelRes)));
    }

    // ---- Arrow key hold-to-repeat ----

    /**
     * Wires hold-to-repeat for one arrow key. A tap sends exactly one key (the
     * immediate press); a held press repeats after the initial delay and then
     * at a fixed interval. Release, cancellation, or sliding the finger off the
     * key stops the repeat at once. The {@code OnClickListener} is kept for
     * accessibility (TalkBack) and is not re-triggered by a real touch.
     */
    @SuppressLint("ClickableViewAccessibility")
    private void attachHoldRepeat(Button button, TerminalKey key) {
        button.setOnTouchListener((v, event) -> {
            if (!shellAttached) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelRepeat();
                    activeRepeatButton = button;
                    activeRepeatKey = key;
                    activeRepeat = new TerminalKeyRepeat();
                    emitRepeat(key, activeRepeat.press(SystemClock.uptimeMillis()));
                    v.setPressed(true);
                    scheduleRepeat();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (activeRepeatButton == button
                            && outsideBounds(v, event.getX(), event.getY())) {
                        cancelRepeat();
                        v.setPressed(false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (activeRepeatButton == button) {
                        cancelRepeat();
                    }
                    v.setPressed(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (activeRepeatButton == button) {
                        cancelRepeat();
                    }
                    v.setPressed(false);
                    return true;
                default:
                    return false;
            }
        });
    }

    private static boolean outsideBounds(View v, float x, float y) {
        return x < 0 || y < 0 || x > v.getWidth() || y > v.getHeight();
    }

    private void scheduleRepeat() {
        if (activeRepeat == null) {
            return;
        }
        long delay = Math.max(1L, activeRepeat.nextEmitAt() - SystemClock.uptimeMillis());
        repeatHandler.postDelayed(repeatTick, delay);
    }

    private void fireRepeatTick() {
        TerminalKeyRepeat repeat = activeRepeat;
        if (repeat == null || !repeat.isPressed()) {
            return;
        }
        if (activeRepeatButton == null || !activeRepeatButton.isAttachedToWindow()) {
            cancelRepeat();
            return;
        }
        long now = SystemClock.uptimeMillis();
        long count = repeat.tick(now);
        if (count > 0) {
            emitRepeat(activeRepeatKey, count);
        }
        if (repeat.isPressed()) {
            long delay = Math.max(1L, repeat.nextEmitAt() - now);
            repeatHandler.postDelayed(repeatTick, delay);
        }
    }

    private void emitRepeat(TerminalKey key, long count) {
        for (long i = 0; i < count; i++) {
            onKeyPressed(key);
        }
    }

    private void cancelRepeat() {
        repeatHandler.removeCallbacks(repeatTick);
        activeRepeat = null;
        activeRepeatKey = null;
        activeRepeatButton = null;
    }

    private void renderEnabledState() {
        for (Button key : keys) {
            key.setEnabled(shellAttached);
        }
        setContentDescription(shellAttached
                ? getContext().getString(R.string.terminal_key_row_desc)
                : getContext().getString(R.string.terminal_key_row_desc)
                        + " " + getContext().getString(R.string.terminal_key_no_shell));
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelRepeat();
        super.onDetachedFromWindow();
    }

    // ---- Wrapping layout ----

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width != laidOutWidth) {
            laidOutWidth = width;
            // Re-parenting keys inside a layout pass would leave them unmeasured.
            post(this::layoutKeys);
        }
    }

    /**
     * Rebuilds the rows so every key keeps a full touch target: keys are laid
     * out with their measured width, and a key that no longer fits the current
     * width starts the next row instead of being squeezed.
     *
     * <p>The keys are then spread evenly over the rows the width allows — twelve
     * keys in a wide landscape surface stay on one row, and a narrow portrait
     * surface gets two balanced rows instead of eleven keys plus one orphan.</p>
     */
    private void layoutKeys() {
        int available = getWidth() - getPaddingStart() - getPaddingEnd();
        if (available <= 0) {
            return;
        }
        int gap = getResources().getDimensionPixelSize(R.dimen.terminal_key_gap);
        // Row order: the fixed keys plus the two sticky modifiers, inserted
        // after TAB by buildKeys().
        List<Button> keysInOrder = keys;
        for (Button key : keysInOrder) {
            ViewGroup parent = (ViewGroup) key.getParent();
            if (parent != null) {
                parent.removeView(key);
            }
        }
        removeAllViews();

        int[] widths = new int[keysInOrder.size()];
        int safetyMargin = Math.max(1, dp(1));
        for (int index = 0; index < keysInOrder.size(); index++) {
            Button key = keysInOrder.get(index);
            key.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            widths[index] = Math.min(key.getMeasuredWidth() + safetyMargin, available);
        }

        List<List<Integer>> rows = wrap(keysInOrder.size(), widths, available, gap, 0);
        int rowCount = rows.size();
        if (rowCount > 1) {
            // Spread the keys evenly over the same number of rows. A balanced
            // row never holds more keys than the greedy pass put in its widest
            // row, so it still fits the available width.
            int perRow = (keysInOrder.size() + rowCount - 1) / rowCount;
            rows = wrap(keysInOrder.size(), widths, available, gap, perRow);
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Integer> rowKeys = rows.get(rowIndex);
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(HORIZONTAL);
            rowView.setBaselineAligned(false);
            int rowWidth = Math.max(0, rowKeys.size() - 1) * gap;
            for (int keyIndex : rowKeys) {
                rowWidth += widths[keyIndex];
            }
            // Every key is at least as wide as its own label needs; the space
            // the row has left over is shared equally, so a row is never
            // half-empty and a key cap never wraps mid-word.
            int extra = Math.max(0, (available - rowWidth) / rowKeys.size());
            for (int position = 0; position < rowKeys.size(); position++) {
                int keyIndex = rowKeys.get(position);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        widths[keyIndex] + extra, LayoutParams.WRAP_CONTENT);
                if (position > 0) {
                    params.setMarginStart(gap);
                }
                rowView.addView(keysInOrder.get(keyIndex), params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (rowIndex > 0) {
                rowParams.topMargin = gap;
            }
            addView(rowView, rowParams);
        }
    }

    /**
     * Greedy row fill, returning key indexes per row. {@code maxPerRow} of zero
     * means "as many as fit"; a positive value additionally caps each row so the
     * keys can be balanced.
     */
    private List<List<Integer>> wrap(
            int keyCount, int[] widths, int available, int gap, int maxPerRow) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        int used = 0;
        for (int index = 0; index < keyCount; index++) {
            int needed = row.isEmpty() ? widths[index] : gap + widths[index];
            boolean rowFull = maxPerRow > 0 && row.size() >= maxPerRow;
            if (!row.isEmpty() && (rowFull || used + needed > available)) {
                rows.add(row);
                row = new ArrayList<>();
                used = widths[index];
            } else {
                used += needed;
            }
            row.add(index);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
