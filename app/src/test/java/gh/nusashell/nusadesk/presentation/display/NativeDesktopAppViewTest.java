package gh.nusashell.nusadesk.presentation.display;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import gh.nusashell.nusadesk.application.display.NativeDesktopPort;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NativeDesktopAppViewTest {
    static class Port implements NativeDesktopPort {
        Listener listener; int opens;
        public void open() { opens++; }
        public void subscribe(Listener value) { listener = value; value.onStatus(State.STARTING, "Waiting for Linux"); }
        public void unsubscribe(Listener value) { listener = null; }
    }
    private <T extends View> T find(View view, Class<T> kind) {
        if (kind.isInstance(view)) return kind.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), kind); if (found != null) return found;
        }
        return null;
    }
    @Test public void startupFailureRetryAndRunningUseRealPublishedState() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Port port = new Port();
        NativeDesktopAppView view = new NativeDesktopAppView(activity, port, new View(activity), new DesktopControlsOrbTest.Fake());
        activity.setContentView(view);
        assertTrue(view.ownsTextInput()); assertEquals(1, port.opens);
        assertEquals(View.VISIBLE, find(view, ProgressBar.class).getVisibility());
        assertEquals(View.GONE, find(view, DesktopControlsOrb.class).getVisibility());
        port.listener.onStatus(NativeDesktopPort.State.FAILED, "Display failed");
        assertEquals(View.GONE, find(view, ProgressBar.class).getVisibility());
        // The retry button lives in the status card after the orb overlay.
        ViewGroup card = (ViewGroup) view.getChildAt(2);
        Button retry = (Button) card.getChildAt(3);
        assertEquals(View.VISIBLE, retry.getVisibility()); retry.performClick(); assertEquals(2, port.opens);
        port.listener.onStatus(NativeDesktopPort.State.RUNNING, "");
        assertEquals(View.GONE, card.getVisibility());
        assertEquals(View.VISIBLE, find(view, DesktopControlsOrb.class).getVisibility());
        activity.setContentView(new View(activity)); assertNull(port.listener);
    }
}
