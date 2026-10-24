package qa.nusadesk.gestures;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
public class GestureDriver extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle bundle) { super.onCreate(bundle); args = bundle; start(); }
    public void onStart() {
        Bundle result = new Bundle();
        try {
            requireNusaDesk();
            long down = SystemClock.uptimeMillis();
            String mode = args.getString("mode", "zigzag");
            if (mode.equals("hold")) {
                send(down, MotionEvent.ACTION_DOWN, 540, 800);
                SystemClock.sleep(1500);
                send(down, MotionEvent.ACTION_UP, 540, 800);
            } else if (mode.equals("zigzag") || mode.equals("long")) {
                int steps = 180;
                send(down, MotionEvent.ACTION_DOWN, 540, mode.equals("long") ? 500 : 800);
                for (int i=1; i<=steps; i++) {
                    if (i % 20 == 0) requireNusaDesk();
                    SystemClock.sleep(20);
                    float y;
                    if (mode.equals("long")) y = 500 + i * 3;
                    else if (i<=60) y=800 + i*5;
                    else if (i<=120) y=1100 - (i-60)*10;
                    else y=500 + (i-120)*5;
                    send(down, MotionEvent.ACTION_MOVE, 540, y);
                }
                send(down, MotionEvent.ACTION_UP, 540, mode.equals("long")?1040:800);
            } else {
                throw new IllegalArgumentException("unknown QA gesture");
            }
            result.putString("result", "injected " + mode); finish(-1, result);
        } catch (Throwable error) { result.putString("error", error.toString()); finish(0, result); }
    }
    private void requireNusaDesk() {
        android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        for (int retry = 0; root == null && retry < 30; retry++) {
            SystemClock.sleep(50);
            root = getUiAutomation().getRootInActiveWindow();
        }
        if (root == null) throw new IllegalStateException("No active window; gesture cancelled");
        String owner = String.valueOf(root.getPackageName());
        root.recycle();
        if (!"gh.nusashell.nusadesk".equals(owner)) throw new IllegalStateException("NusaDesk is not the active app; gesture cancelled");
    }
    private void send(long down, int action, float x, float y) {
        MotionEvent.PointerProperties[] p = {new MotionEvent.PointerProperties()};
        p[0].id = 0; p[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] c = {new MotionEvent.PointerCoords()};
        c[0].x = x; c[0].y = y; c[0].pressure = 1; c[0].size = 1;
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, p, c, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        if (!getUiAutomation().injectInputEvent(event, true)) throw new IllegalStateException("injection refused");
        event.recycle();
    }
}
