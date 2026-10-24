package gh.nusashell.nusadesk.presentation.display;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput;
import java.util.EnumSet;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DesktopControlsOrbTest {
    private final Fake input = new Fake();
    private DesktopControlsOrb orb;
    static class Fake implements NativeDesktopInput {
        Mode mode = Mode.TOUCH; int zoom = 100, width, keyboard; Key last;
        EnumSet<Key> armed = EnumSet.noneOf(Key.class);
        Runnable changed = () -> { };
        public boolean isKeyboardVisible() { return false; }
        public void toggleKeyboard() { keyboard++; }
        public void press(Key key) { last = key; if (!armed.remove(key)) armed.add(key); }
        public boolean isArmed(Key key) { return armed.contains(key); }
        public void setMode(Mode next) { mode = next; }
        public Mode mode() { return mode; }
        public void setControlsChanged(Runnable callback) { changed = callback; }
        public int zoomPercent() { return zoom; }
        public void setZoomPercent(int value) { zoom = value; changed.run(); }
        public int layoutWidth() { return width; }
        public void setLayoutWidth(int value) { width = value; }
    }
    @Before public void setup() {
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        orb = new DesktopControlsOrb(activity, input);
        activity.setContentView(orb);
        orb.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        orb.layout(0, 0, 1080, 1600);
    }
    private Button button(View node, String text) {
        if (node instanceof Button && ((Button) node).getText().toString().equals(text)) return (Button) node;
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) { Button found = button(group.getChildAt(i), text); if (found != null) return found; }
        }
        return null;
    }
    @Test public void orbOpensControlsAndKeyboardActionClosesThem() {
        Button toggle = button(orb, "⊙"); toggle.performClick();
        assertEquals(View.INVISIBLE, toggle.getVisibility());
        button(orb, "Show keyboard").performClick();
        assertEquals(1, input.keyboard); assertEquals(View.VISIBLE, toggle.getVisibility());
    }
    @Test public void modifierIsDescribedAndModeSelectionClosesPanel() {
        button(orb, "⊙").performClick(); button(orb, "Ctrl").performClick();
        assertEquals(NativeDesktopInput.Key.CTRL, input.last);
        assertTrue(button(orb, "Ctrl •").getContentDescription().toString().contains("next key"));
        button(orb, "Drag").performClick();
        assertEquals(NativeDesktopInput.Mode.DRAG, input.mode);
        assertEquals(View.VISIBLE, button(orb, "⊙").getVisibility());
    }
    @Test public void zoomAndLayoutControlsReachInputPort() {
        button(orb, "+").performClick(); assertEquals(125, input.zoom);
        button(orb, "960 px").performClick(); assertEquals(960, input.width);
    }
    @Test public void draggingBeyondScreenAndResizingKeepsOrbReachable() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Button toggle = button(orb, "⊙");
        touch(toggle, android.view.MotionEvent.ACTION_DOWN, 20, 20);
        touch(toggle, android.view.MotionEvent.ACTION_MOVE, -5000, 5000);
        touch(toggle, android.view.MotionEvent.ACTION_UP, -5000, 5000);
        assertEquals(View.VISIBLE, toggle.getVisibility());
        assertInside(toggle);
        orb.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        orb.layout(0, 0, 360, 480);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertInside(toggle);
        toggle.performClick();
        assertEquals(View.INVISIBLE, toggle.getVisibility());
    }
    private void touch(View view, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(0, 1, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private void assertInside(View view) {
        assertTrue(view.getX() >= 0); assertTrue(view.getY() >= 0);
        assertTrue(view.getX() + view.getWidth() <= orb.getWidth());
        assertTrue(view.getY() + view.getHeight() <= orb.getHeight());
    }
}
