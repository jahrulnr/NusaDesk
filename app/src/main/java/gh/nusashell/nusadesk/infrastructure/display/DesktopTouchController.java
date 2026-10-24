package gh.nusashell.nusadesk.infrastructure.display;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput.Mode;

/** Android gesture recognizer; a scrolling finger never presses the Linux mouse button. */
final class DesktopTouchController {
    interface Sink {
        void point(float x, float y);
        void relative(float x, float y);
        void button(int button, boolean down);
        void scroll(float x, float y);
        void targetChanged();
        default void twoFingerScroll(float x, float y) { scroll(x, y); }
    }
    private final Sink sink;
    private final GestureDetector detector;
    private final float slop;
    private Mode mode = Mode.TOUCH;
    private boolean dragging, multi, moved;
    private float lastX, lastY, travel;

    DesktopTouchController(View view, Sink sink) {
        this.sink = sink;
        slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                if (mode == Mode.TOUCH) sink.point(event.getX(), event.getY());
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent event) { click(1); view.performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent event) { return true; }
            @Override public boolean onDoubleTapEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) click(1);
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                click(3);
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (mode == Mode.POINTER) sink.relative(-dx, -dy);
                else sink.scroll(dx, dy);
                return true;
            }
        });
    }
    void setMode(Mode next) { cancel(); mode = java.util.Objects.requireNonNull(next); }
    Mode mode() { return mode; }
    void cancel() {
        if (dragging) sink.button(1, false);
        dragging = false;
        multi = false;
        MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        detector.onTouchEvent(cancel); cancel.recycle();
    }
    private void click(int button) {
        sink.targetChanged(); sink.button(button, true); sink.button(button, false);
    }
    boolean touch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (mode == Mode.DRAG) {
            if (action == MotionEvent.ACTION_DOWN) {
                sink.targetChanged(); sink.point(event.getX(), event.getY());
                sink.button(1, true); dragging = true;
            } else if (action == MotionEvent.ACTION_MOVE && dragging) sink.point(event.getX(), event.getY());
            else if (action == MotionEvent.ACTION_UP && dragging) { sink.button(1, false); dragging = false; }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) { multi = false; moved = false; travel = 0; }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
            detector.onTouchEvent(cancel); cancel.recycle();
            multi = true; lastX = center(event, true); lastY = center(event, false);
        } else if (multi) {
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() > 1) {
                float x = center(event, true), y = center(event, false);
                float dx = lastX - x, dy = lastY - y;
                travel += Math.abs(dx) + Math.abs(dy);
                if (travel > slop) moved = true;
                if (moved) sink.twoFingerScroll(dx, dy);
                lastX = x; lastY = y;
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                // Re-baseline on the pointers that stay down, or the next MOVE delta jumps.
                lastX = centerExcluding(event, event.getActionIndex(), true);
                lastY = centerExcluding(event, event.getActionIndex(), false);
            } else if (action == MotionEvent.ACTION_UP && !moved) click(3);
        } else detector.onTouchEvent(event);
        return true;
    }
    private static float center(MotionEvent event, boolean horizontal) {
        return centerExcluding(event, -1, horizontal);
    }
    private static float centerExcluding(MotionEvent event, int excluded, boolean horizontal) {
        float total = 0;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i == excluded) continue;
            total += horizontal ? event.getX(i) : event.getY(i);
            count++;
        }
        return count == 0 ? center(event, horizontal) : total / count;
    }
}
