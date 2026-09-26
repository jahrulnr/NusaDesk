package gh.nusashell.nusadesk.presentation.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;
import gh.nusashell.nusadesk.presentation.widget.InstallPhaseSnapshot;

/**
 * Launcher-level coverage for the opt-in toolkits contract (ADR-0055), driven
 * through {@link DesktopHomeView} so the wizard is XML-inflated exactly as in
 * production: the extras card offers the USB/ADB and Termux toolkits as two
 * independent, never-pre-selected checkboxes while a start/retry decision is
 * pending — in either call order — an in-flight toolkit install keeps the
 * setup surface mounted, the mandatory systemctl/Python service bridge gates
 * the launcher unlock until the host reports it ready, and the log's
 * hanging-indent spans always end on paragraph boundaries so a wrapped
 * continuation never throws or reads as a new log event.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31})
public class DesktopHomeViewExtrasTest {


    private static RuntimeSnapshot rootfs(RuntimeState state) {
        return new RuntimeSnapshot("ubuntu-base-arm64", state, "detail", 0, 1_000L);
    }

    private static DesktopHomeView home(Activity activity) {
        DesktopHomeView view = new DesktopHomeView(activity);
        activity.setContentView(view);
        return view;
    }

    private static void coreReady(DesktopHomeView home) {
        home.render(rootfs(RuntimeState.READY));
        home.renderGuestSsh(GuestSshUiState.installed());
        home.setRuntimeServiceReady(true);
    }

    @Test
    public void firstInstallOffersBothToolkitsUnchecked() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        // Production order: the state render runs first, the offer lands after.
        home.render(rootfs(RuntimeState.NOT_INSTALLED));
        home.setOptionalToolsOffered(true, true);

        assertEquals(View.VISIBLE,
                home.findViewById(R.id.setup_runtime_card).getVisibility());
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.wizard_extras).getVisibility());
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.wizard_extra_usb_row).getVisibility());
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.wizard_extra_termux_row).getVisibility());
        CheckBox usb = home.findViewById(R.id.wizard_extra_usb);
        CheckBox termux = home.findViewById(R.id.wizard_extra_termux);
        assertFalse("opt-ins are never pre-selected", usb.isChecked());
        assertFalse("opt-ins are never pre-selected", termux.isChecked());
        assertFalse(home.isUsbAdbSelected());
        assertFalse(home.isTermuxSelected());
    }

    @Test
    public void offerBeforeFirstRenderStillShowsTheCard() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.setOptionalToolsOffered(true, true);

        home.render(rootfs(RuntimeState.NOT_INSTALLED));
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.wizard_extras).getVisibility());
    }

    @Test
    public void aPartialOfferShowsOnlyThatToolkit() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.render(rootfs(RuntimeState.NOT_INSTALLED));
        home.setOptionalToolsOffered(true, false);

        assertEquals(View.VISIBLE,
                home.findViewById(R.id.wizard_extras).getVisibility());
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.wizard_extra_usb_row).getVisibility());
        assertEquals(View.GONE,
                home.findViewById(R.id.wizard_extra_termux_row).getVisibility());
    }

    @Test
    public void eachToolkitSelectsIndependently() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.render(rootfs(RuntimeState.NOT_INSTALLED));
        home.setOptionalToolsOffered(true, true);

        // None selected.
        assertFalse(home.isUsbAdbSelected());
        assertFalse(home.isTermuxSelected());

        // USB only.
        home.findViewById(R.id.wizard_extra_usb_row).performClick();
        assertTrue(home.isUsbAdbSelected());
        assertFalse(home.isTermuxSelected());

        // Both.
        home.findViewById(R.id.wizard_extra_termux_row).performClick();
        assertTrue(home.isUsbAdbSelected());
        assertTrue(home.isTermuxSelected());

        // Termux only.
        ((CheckBox) home.findViewById(R.id.wizard_extra_usb)).setChecked(false);
        assertFalse(home.isUsbAdbSelected());
        assertTrue(home.isTermuxSelected());
    }

    @Test
    public void extrasCardHidesWhileInstallingAndWhenNotOffered() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.setOptionalToolsOffered(true, true);
        home.render(rootfs(RuntimeState.DOWNLOADING));
        assertEquals(View.GONE,
                home.findViewById(R.id.wizard_extras).getVisibility());

        home.setOptionalToolsOffered(false, false);
        home.render(rootfs(RuntimeState.NOT_INSTALLED));
        assertEquals(View.GONE,
                home.findViewById(R.id.wizard_extras).getVisibility());
        assertFalse(home.isUsbAdbSelected());
        assertFalse(home.isTermuxSelected());
    }

    @Test
    public void missingServiceBridgeKeepsSetupMountedAndAppsLocked() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.render(rootfs(RuntimeState.READY));
        home.renderGuestSsh(GuestSshUiState.installed());
        // The mandatory overlay has not been reported ready: rootfs + SSH
        // alone must never unlock the terminal.
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.setup_runtime_card).getVisibility());
        assertEquals(View.GONE,
                home.findViewById(R.id.apps_grid).getVisibility());

        home.setRuntimeServiceReady(true);
        assertEquals(View.GONE,
                home.findViewById(R.id.setup_runtime_card).getVisibility());
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.apps_grid).getVisibility());
    }

    @Test
    public void serviceBridgeFailureKeepsSetupVisibleWithRetry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.render(rootfs(RuntimeState.READY));
        home.renderGuestSsh(GuestSshUiState.installed());
        home.renderAddonPhase(InstallPhaseSnapshot.serviceAddon(
                new RuntimeSnapshot("guest-service-bridge",
                        RuntimeState.FAILED, "checksum mismatch", 0, 1_000L)));

        assertEquals("a failed mandatory overlay keeps setup on screen",
                View.VISIBLE,
                home.findViewById(R.id.setup_runtime_card).getVisibility());
        Button action = home.findViewById(R.id.wizard_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertTrue("the failed core install offers a retry", action.isEnabled());
        assertEquals(home.getContext().getString(R.string.action_retry_install),
                action.getText().toString());
    }

    @Test
    public void setupActionIsReparentedIntoTheBottomFooter() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.render(rootfs(RuntimeState.NOT_INSTALLED));

        Button action = home.findViewById(R.id.wizard_action);
        View parent = (View) action.getParent();
        assertTrue("the action must leave the wizard for the pinned footer",
                parent instanceof FrameLayout);
        assertTrue("the footer must be a direct child of the launcher",
                parent.getParent() == home);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) parent.getLayoutParams();
        assertEquals("the footer must sit at the bottom edge",
                Gravity.BOTTOM, params.gravity);
        assertEquals("a pending setup decision shows the pinned action",
                View.VISIBLE, action.getVisibility());
    }

    @Test
    public void optionalInstallInFlightKeepsSetupSurfaceMounted() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        coreReady(home);
        assertEquals("a fully installed core hides the setup card",
                View.GONE, home.findViewById(R.id.setup_runtime_card).getVisibility());

        home.renderAddonPhase(InstallPhaseSnapshot.usbAdb(
                new RuntimeSnapshot("guest-usb-adb",
                        RuntimeState.DOWNLOADING, "Installing USB / ADB", 0, 1_000L)));
        home.setOptionalInstallActive(true);
        assertEquals(View.VISIBLE,
                home.findViewById(R.id.setup_runtime_card).getVisibility());

        home.setOptionalInstallActive(false);
        assertEquals(View.GONE,
                home.findViewById(R.id.setup_runtime_card).getVisibility());
    }

    @Test
    public void everyLogLineGetsAParagraphAlignedHangingIndent() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DesktopHomeView home = home(activity);
        home.render(rootfs(RuntimeState.NOT_INSTALLED));
        home.render(rootfs(RuntimeState.DOWNLOADING));
        home.render(rootfs(RuntimeState.VERIFYING));
        home.renderAddonPhase(InstallPhaseSnapshot.serviceAddon(
                new RuntimeSnapshot("guest-service-bridge",
                        RuntimeState.FAILED, "network timeout", 0, 1_000L)));
        home.renderAddonPhase(InstallPhaseSnapshot.termux(
                new RuntimeSnapshot("guest-termux",
                        RuntimeState.DOWNLOADING, "Installing Termux commands", 0, 1_000L)));

        CharSequence text =
                ((TextView) home.findViewById(R.id.wizard_log)).getText();
        assertTrue(text instanceof Spanned);
        String plain = text.toString();
        assertTrue(plain.contains("\n"));
        assertTrue("the termux tag reaches the log", plain.contains("termux"));
        LeadingMarginSpan[] spans =
                ((Spanned) text).getSpans(0, text.length(), LeadingMarginSpan.class);
        // prepare + download + verify + svc error + termux — every logged
        // line indents.
        assertEquals(5, spans.length);
        for (LeadingMarginSpan span : spans) {
            int end = ((Spanned) text).getSpanEnd(span);
            assertTrue("span must end on a paragraph boundary",
                    end == plain.length() || plain.charAt(end - 1) == '\n');
            assertEquals("first visual line stays flush left at the tag",
                    0, span.getLeadingMargin(true));
            assertTrue("wrapped continuations indent under the detail",
                    span.getLeadingMargin(false) > 0);
        }
    }
}
