package gh.nusashell.nusadesk.infrastructure.webapp;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconBitmapDecoderTest {
    private final IconBitmapDecoder decoder = new IconBitmapDecoder();

    @Test public void viewBoxOnlySvgRendersColoredPixelsAtTileResolution() {
        byte[] bytes = ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
                + "<rect width='32' height='32' fill='#0b0d0c'/>"
                + "<path d='M10 22 V10 L22 22 V10' stroke='#c8f169' stroke-width='2.6' fill='none'/>"
                + "</svg>").getBytes(StandardCharsets.UTF_8);
        WebAppFaviconFetcher.Size bounds = decoder.boundsOf(bytes, bytes.length);
        assertNotNull(bounds);
        assertEquals(256, bounds.getWidth()); assertEquals(256, bounds.getHeight());
        Bitmap image = decoder.decode(bytes, bytes.length, 1);
        assertNotNull(image); assertEquals(256, image.getWidth());
        assertEquals(Color.rgb(11, 13, 12), image.getPixel(20, 20));
        assertEquals(Color.rgb(200, 241, 105), image.getPixel(80, 128));
        assertTrue(FaviconResponsePolicy.isWithinMemoryBudget(image.getByteCount()));
    }

    @Test public void nonSquareSvgKeepsItsAspectRatio() {
        byte[] bytes = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'><rect width='100' height='50' fill='red'/></svg>".getBytes(StandardCharsets.UTF_8);
        Bitmap image = decoder.decode(bytes, bytes.length, 1);
        assertNotNull(image); assertEquals(256, image.getWidth()); assertEquals(128, image.getHeight());
    }

    @Test public void malformedSvgDoesNotBecomeAnIcon() {
        byte[] bytes = "<svg broken".getBytes(StandardCharsets.UTF_8);
        assertNull(decoder.decode(bytes, bytes.length, 1));
    }

    @Test public void blankSvgDoesNotBecomeAnInvisibleIcon() {
        byte[] bytes = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertNull("a valid-but-fully-transparent render is not an icon",
                decoder.decode(bytes, bytes.length, 1));
    }

    @Test public void rasterPngStillDecodesAndDownsamples() {
        Bitmap original = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888);
        original.eraseColor(Color.BLUE);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        original.compress(Bitmap.CompressFormat.PNG, 100, output);
        byte[] bytes = output.toByteArray();
        assertEquals(512, decoder.boundsOf(bytes, bytes.length).getWidth());
        Bitmap decoded = decoder.decode(bytes, bytes.length, 2);
        assertEquals(256, decoded.getWidth()); assertEquals(128, decoded.getHeight());
        assertEquals(Color.BLUE, decoded.getPixel(0, 0));
    }
}
