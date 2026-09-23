package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.app.Activity;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundActivity;

import static org.junit.Assert.assertTrue;

/**
 * The dialog shell's space contract: the scrolling content slot is the only
 * child allowed to shrink, so the action row keeps its 48dp touch target when
 * the window is resized shorter than the content (e.g. the IME shrinking a
 * fixed-height window — the collapse that made the buttons a sliver). The
 * slot yields through layout_weight on a wrap_content ScrollView — not 0dp,
 * which would measure 0 under the pre-show AT_MOST pass.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DialogShellMeasureTest {

    @Test
    public void resizedWindowShrinksContentSlotNotActionRow() {
        Activity activity = Robolectric.buildActivity(
                CapabilityForegroundActivity.class).create().get();
        Dialog dialog = new Dialog(activity, R.style.NusaDeskDialogTheme);
        dialog.setContentView(R.layout.nusadesk_dialog_shell);
        View root = dialog.findViewById(R.id.nusadesk_dialog_shell);
        LinearLayout buttons = dialog.findViewById(R.id.nusadesk_dialog_buttons);
        View positive = dialog.findViewById(R.id.nusadesk_dialog_positive);
        View negative = dialog.findViewById(R.id.nusadesk_dialog_negative);
        ViewGroup content = dialog.findViewById(R.id.nusadesk_dialog_content);
        LayoutInflater.from(dialog.getContext())
                .inflate(R.layout.nusadesk_dialog_message, content, true);
        View scroll = (View) content.getParent();
        int min = activity.getResources().getDimensionPixelSize(R.dimen.touch_target);

        int width = 400;
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
        int naturalRoot = root.getMeasuredHeight();
        int naturalButtons = buttons.getMeasuredHeight();
        int naturalScroll = scroll.getMeasuredHeight();
        assertTrue("precondition: positive " + positive.getMeasuredHeight() + " < " + min,
                positive.getMeasuredHeight() >= min);
        assertTrue("precondition: negative " + negative.getMeasuredHeight() + " < " + min,
                negative.getMeasuredHeight() >= min);
        assertTrue("precondition: no scrollable slack " + naturalScroll,
                naturalScroll > 0);

        // Re-measure as a resized window shorter than the content but by less
        // than the scrollable slack, like an adjustResize pass under the IME:
        // the content slot must yield while the action row keeps its height.
        int shrunk = naturalRoot - (naturalScroll / 2);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(shrunk, View.MeasureSpec.EXACTLY));
        System.out.println("natural root=" + naturalRoot + " scroll=" + naturalScroll
                + " buttons=" + naturalButtons
                + " | shrunk root=" + shrunk
                + " scroll=" + scroll.getMeasuredHeight()
                + " buttons=" + buttons.getMeasuredHeight()
                + " pos=" + positive.getMeasuredHeight()
                + " neg=" + negative.getMeasuredHeight());

        assertTrue("positive height " + positive.getMeasuredHeight()
                        + " collapsed below " + min + " under a " + shrunk + "px window",
                positive.getMeasuredHeight() >= min);
        assertTrue("negative height " + negative.getMeasuredHeight()
                        + " collapsed below " + min + " under a " + shrunk + "px window",
                negative.getMeasuredHeight() >= min);
        assertTrue("action row measured " + buttons.getMeasuredHeight()
                        + " < its natural " + naturalButtons,
                buttons.getMeasuredHeight() >= naturalButtons);
        assertTrue("content slot did not yield (" + scroll.getMeasuredHeight()
                        + " >= natural " + naturalScroll + ")",
                scroll.getMeasuredHeight() < naturalScroll);
    }
}
