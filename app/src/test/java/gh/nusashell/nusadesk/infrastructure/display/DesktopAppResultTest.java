package gh.nusashell.nusadesk.infrastructure.display;
import org.junit.Test;
import static org.junit.Assert.*;
public class DesktopAppResultTest {
    @Test public void onlyExplicitCompletionIsNormalEvenIfTheReadyCallbackHasNotRun() {
        assertTrue(DesktopAppResult.closed(true, false, false));
        assertFalse(DesktopAppResult.closed(false, false, false));
        assertFalse(DesktopAppResult.closed(true, false, true));
        assertFalse(DesktopAppResult.closed(true, true, false));
    }
    @Test public void reportsFailureCauseWithoutPresentingTheReadyMarkerAsAnError() {
        String detail = DesktopAppResult.failure("__NUSADESK_GUI_READY__\nwarning\n__NUSADESK_GUI_FAILED__exit status 23\n", "closed");
        assertEquals("exit status 23", detail);
        assertTrue(GuestDesktopCatalog.failureDetail("__NUSADESK_GUI_FAILED__missing libgio", "", "EOF").contains("missing libgio"));
    }
}
