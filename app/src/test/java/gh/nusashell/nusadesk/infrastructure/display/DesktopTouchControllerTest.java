package gh.nusashell.nusadesk.infrastructure.display;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput.Mode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DesktopTouchControllerTest {
    private final List<String> events = new ArrayList<>();
    private float lastScrollX, lastScrollY;
    private DesktopTouchController touch;
    @Before public void setup() {
        touch = new DesktopTouchController(new View(RuntimeEnvironment.getApplication()), new DesktopTouchController.Sink() {
            public void point(float x, float y) { events.add("point"); }
            public void relative(float x, float y) { events.add("relative"); }
            public void button(int b, boolean down) { events.add(b + ":" + down); }
            public void scroll(float x, float y) { events.add("scroll"); lastScrollX = x; lastScrollY = y; }
            public void targetChanged() { }
        });
    }
    private void event(int action, int t, float x, float y) {
        MotionEvent event = MotionEvent.obtain(100, 100 + t, action, x, y, 0);
        touch.touch(event); event.recycle();
    }
    private void multiEvent(int action, int actionIndex, int t, float[][] points) {
        int count = points.length;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0]; coords[i].y = points[i][1];
        }
        int full = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        MotionEvent event = MotionEvent.obtain(100, 100 + t, full, count, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        touch.touch(event); event.recycle();
    }
    @Test public void fingerScrollNeverPressesOrClicksMouse() {
        event(MotionEvent.ACTION_DOWN, 0, 10, 200);
        event(MotionEvent.ACTION_MOVE, 30, 10, 80);
        event(MotionEvent.ACTION_UP, 60, 10, 50);
        assertTrue(events.contains("scroll"));
        assertFalse(events.contains("1:true"));
    }
    @Test public void tapClicksOnlyOnRelease() {
        event(MotionEvent.ACTION_DOWN, 0, 10, 10); assertFalse(events.contains("1:true"));
        event(MotionEvent.ACTION_UP, 50, 10, 10);
        assertTrue(events.contains("1:true")); assertTrue(events.contains("1:false"));
    }
    @Test public void dragModeReleasesWhenCancelledOrModeChanges() {
        touch.setMode(Mode.DRAG); event(MotionEvent.ACTION_DOWN, 0, 10, 10);
        assertTrue(events.contains("1:true"));
        touch.setMode(Mode.TOUCH); assertEquals("1:false", events.get(events.size() - 1));
    }
    @Test public void pointerMovesRelativeWithoutClicking() {
        touch.setMode(Mode.POINTER); event(MotionEvent.ACTION_DOWN, 0, 10, 200);
        event(MotionEvent.ACTION_MOVE, 30, 80, 80); event(MotionEvent.ACTION_UP, 60, 80, 80);
        assertTrue(events.contains("relative")); assertFalse(events.contains("1:true"));
    }
    @Test public void liftingAFingerRebaselinesTheScrollCenterSoTheNextMoveDoesNotJump() {
        multiEvent(MotionEvent.ACTION_DOWN, 0, 0, new float[][]{{100, 100}});
        multiEvent(MotionEvent.ACTION_POINTER_DOWN, 1, 10, new float[][]{{100, 100}, {200, 100}});
        multiEvent(MotionEvent.ACTION_POINTER_DOWN, 2, 20, new float[][]{{100, 100}, {200, 100}, {300, 100}});
        multiEvent(MotionEvent.ACTION_MOVE, 0, 30, new float[][]{{100, 200}, {200, 200}, {300, 200}});
        assertEquals(0f, lastScrollX, 0.001f); assertEquals(-100f, lastScrollY, 0.001f);
        // One finger lifts; the remaining two keep scrolling from their own center.
        multiEvent(MotionEvent.ACTION_POINTER_UP, 0, 40, new float[][]{{100, 200}, {200, 200}, {300, 200}});
        multiEvent(MotionEvent.ACTION_MOVE, 0, 50, new float[][]{{200, 300}, {300, 300}});
        assertEquals(0f, lastScrollX, 0.001f); assertEquals(-100f, lastScrollY, 0.001f);
    }
}
