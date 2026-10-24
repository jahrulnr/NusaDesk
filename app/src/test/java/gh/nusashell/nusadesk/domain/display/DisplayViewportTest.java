package gh.nusashell.nusadesk.domain.display;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class DisplayViewportTest {
    @Test public void mapsLetterboxedSurfaceToGuestCoordinates() {
        DisplayViewport viewport = new DisplayViewport(0, 100, 640, 360, 0, 0, 1280, 720);
        assertEquals(640f, viewport.guestX(320), 0.01f);
        assertEquals(360f, viewport.guestY(280), 0.01f);
    }
    @Test public void clampsTouchesOutsideContentToLastGuestPixel() {
        DisplayViewport viewport = new DisplayViewport(20, 50, 640, 360, 0, 0, 1280, 720);
        assertEquals(0f, viewport.guestX(-10), 0.01f);
        assertEquals(1279f, viewport.guestX(900), 0.01f);
        assertEquals(719f, viewport.guestY(900), 0.01f);
    }
    @Test public void portraitCanvasFitsTheSelectedGtkDialogAndKeepsAspectRatio() {
        assertEquals(640, DisplayViewport.logicalWidth(1080));
        assertEquals(1125, DisplayViewport.logicalHeight(1080, 1899));
        assertEquals(1136, DisplayViewport.logicalWidth(2280));
        assertEquals(438, DisplayViewport.logicalHeight(2280, 880));
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnusableGeometry() {
        new DisplayViewport(0, 0, 0, 100, 0, 0, 1280, 720);
    }
    @Test public void relativePointerAndScrollDeltasPreserveDirectionAtScaledViewport() {
        DisplayViewport viewport = new DisplayViewport(20, 50, 640, 360, 0, 0, 1280, 720);
        assertEquals(20, viewport.guestDeltaX(10), 0.01);
        assertEquals(-20, viewport.guestDeltaX(-10), 0.01);
        assertEquals(40, viewport.guestDeltaY(20), 0.01);
        assertEquals(-40, viewport.guestDeltaY(-20), 0.01);
    }
}
