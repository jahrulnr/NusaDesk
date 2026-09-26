package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.infrastructure.proot.GuestOptionalTools;

/**
 * The One-click install page renders each curated component truthfully
 * (ADR-0055): the mandatory service bridge reports its state with no
 * Install action, while the USB/ADB and Termux toolkits each carry their
 * own independent Install / Try again action, state pill, and detail line —
 * one toolkit's state never bleeds into the other's. Driven through
 * {@link SystemScreenView} so the host-facing delegation is covered too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31})
public class SystemInstallPageViewTest {


    private static SystemScreenView screen() {
        return new SystemScreenView(RuntimeEnvironment.getApplication());
    }

    private static ServicesExtraState toolkit(RuntimeState state,
            boolean installed, boolean busy) {
        RuntimeSnapshot snapshot = state == null ? null
                : new RuntimeSnapshot("toolkit", state, "install detail", 0, 1_000L);
        return ServicesExtraState.of(snapshot, installed, busy);
    }

    private static boolean containsButton(View root) {
        if (root instanceof Button) {
            return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsButton(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void theCoreServiceRowNeverOffersAnAction() {
        SystemScreenView screen = screen();

        screen.renderRequiredService(true);
        assertEquals("Installed",
                ((TextView) screen.findViewById(R.id.system_install_services_state))
                        .getText().toString());
        assertFalse("a core component has no Install button",
                containsButton(screen.findViewById(R.id.system_install_services_card)));

        screen.renderRequiredService(false);
        assertEquals("Not installed",
                ((TextView) screen.findViewById(R.id.system_install_services_state))
                        .getText().toString());
        assertFalse(containsButton(
                screen.findViewById(R.id.system_install_services_card)));
    }

    @Test
    public void toolkitRowsRenderIndependently() {
        SystemScreenView screen = screen();
        screen.renderToolkit(GuestOptionalTools.Kind.USB_ADB,
                toolkit(null, true, false));
        screen.renderToolkit(GuestOptionalTools.Kind.TERMUX,
                toolkit(null, false, false));

        assertEquals("Installed",
                ((TextView) screen.findViewById(R.id.system_install_usb_state))
                        .getText().toString());
        assertEquals(View.GONE,
                screen.findViewById(R.id.system_install_usb_action).getVisibility());

        assertEquals("Not installed",
                ((TextView) screen.findViewById(R.id.system_install_termux_state))
                        .getText().toString());
        Button termuxAction = screen.findViewById(R.id.system_install_termux_action);
        assertEquals(View.VISIBLE, termuxAction.getVisibility());
        assertTrue(termuxAction.isEnabled());
        assertEquals("Install", termuxAction.getText().toString());
    }

    @Test
    public void aFailedToolkitShowsRetryAndItsReason() {
        SystemScreenView screen = screen();
        screen.renderToolkit(GuestOptionalTools.Kind.USB_ADB,
                toolkit(RuntimeState.FAILED, false, false));

        assertEquals("Install failed",
                ((TextView) screen.findViewById(R.id.system_install_usb_state))
                        .getText().toString());
        Button action = screen.findViewById(R.id.system_install_usb_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertTrue("a failed optional toolkit offers retry", action.isEnabled());
        assertEquals("Try again", action.getText().toString());
        TextView detail = screen.findViewById(R.id.system_install_usb_detail);
        assertEquals(View.VISIBLE, detail.getVisibility());
        assertEquals("install detail", detail.getText().toString());
    }

    @Test
    public void aBusyPipelineDisablesTheToolkitActions() {
        SystemScreenView screen = screen();
        screen.renderToolkit(GuestOptionalTools.Kind.USB_ADB,
                toolkit(null, false, true));
        screen.renderToolkit(GuestOptionalTools.Kind.TERMUX,
                toolkit(null, false, true));

        assertFalse(((Button) screen.findViewById(R.id.system_install_usb_action))
                .isEnabled());
        assertFalse(((Button) screen.findViewById(R.id.system_install_termux_action))
                .isEnabled());
    }

    @Test
    public void eachToolkitInstallButtonReachesItsOwnListener() {
        SystemScreenView screen = screen();
        AtomicBoolean usbTapped = new AtomicBoolean();
        AtomicBoolean termuxTapped = new AtomicBoolean();
        screen.setOnToolkitInstallListener(GuestOptionalTools.Kind.USB_ADB,
                view -> usbTapped.set(true));
        screen.setOnToolkitInstallListener(GuestOptionalTools.Kind.TERMUX,
                view -> termuxTapped.set(true));
        screen.renderToolkit(GuestOptionalTools.Kind.USB_ADB,
                toolkit(null, false, false));
        screen.renderToolkit(GuestOptionalTools.Kind.TERMUX,
                toolkit(null, false, false));

        screen.findViewById(R.id.system_install_usb_action).performClick();
        assertTrue(usbTapped.get());
        assertFalse(termuxTapped.get());

        screen.findViewById(R.id.system_install_termux_action).performClick();
        assertTrue(termuxTapped.get());
    }

    @Test
    public void anInstallingToolkitHidesItsActionAndShowsProgress() {
        SystemScreenView screen = screen();
        screen.renderToolkit(GuestOptionalTools.Kind.TERMUX,
                toolkit(RuntimeState.DOWNLOADING, false, false));

        assertEquals("Installing…",
                ((TextView) screen.findViewById(R.id.system_install_termux_state))
                        .getText().toString());
        assertEquals(View.GONE,
                screen.findViewById(R.id.system_install_termux_action).getVisibility());
        assertEquals(View.VISIBLE,
                screen.findViewById(R.id.system_install_termux_detail).getVisibility());
    }
}
