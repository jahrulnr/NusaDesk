package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure half of ICO support: directory parsing, frame choice, the DIB-to-BMP
 * rewrite, and the AND-mask lookup. What happens to the decoded pixels is the
 * Android decoder's business and is covered by the fetch test; the byte-level
 * container logic asserted here never reaches Android code.
 */
public class IcoImageTest {

    @Test
    public void bytesThatAreNotAnIcoYieldNoFrame() {
        assertNull(IcoImage.bestFrame(null, 0));
        assertNull(IcoImage.bestFrame(new byte[10], 10));           // too short
        assertNull(IcoImage.bestFrame(new byte[64], 64));           // zeros
        // A PNG is not a container.
        assertNull(IcoImage.bestFrame(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0, 0, 0, 0}, 8));
        // A cursor (idType 2) is not an app icon.
        assertNull(IcoImage.bestFrame(new byte[] {0, 0, 2, 0, 1, 0}, 6));
    }

    @Test
    public void aPngFrameIsLocatedAndReportedWhole() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5};
        byte[] ico = ico(new byte[][] {png}, 32, 32);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull(frame);
        assertTrue(frame.isPng());
        assertEquals(32, frame.getWidth());
        assertEquals(32, frame.getHeight());
        // The payload follows the 6-byte directory and the 16-byte entry.
        assertEquals(22, frame.getOffset());
        assertEquals(png.length, frame.getLength());
        assertEquals(-1, frame.getMaskOffset());
    }

    @Test
    public void theLargestFrameWinsAndPngBreaksATie() {
        byte[] small = dib(16, 16, 24, false);
        byte[] large = dib(48, 48, 24, false);
        byte[] ico = ico(new byte[][] {small, large}, 16, 16, 48, 48);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull(frame);
        assertEquals(48, frame.getWidth());

        // Same area twice, DIB first: the PNG is picked because its alpha is
        // real, not a 1-bit mask.
        byte[] png32 = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 9, 9, 9};
        byte[] dib32 = dib(32, 32, 24, false);
        byte[] tied = ico(new byte[][] {dib32, png32}, 32, 32, 32, 32);

        IcoImage.Frame tieFrame = IcoImage.bestFrame(tied, tied.length);
        assertNotNull(tieFrame);
        assertTrue(tieFrame.isPng());
    }

    @Test
    public void aFrameThatOverrunsTheBufferIsSkipped() {
        byte[] good = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 7};
        // A real second frame exists in the layout; its entry is then
        // corrupted to claim far more bytes than the file holds.
        byte[] bad = dib(64, 64, 24, false);
        byte[] ico = ico(new byte[][] {good, bad}, 32, 32, 64, 64);
        put32(ico, 6 + 16 + 8, ico.length);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull("the valid frame still wins", frame);
        assertEquals(32, frame.getWidth());
        assertTrue(frame.isPng());
    }

    @Test
    public void aTruncatedDirectoryStopsAtWhatFits() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1};
        byte[] ico = ico(new byte[][] {png}, 16, 16);
        ico[4] = 9; // claims nine entries; only one fits

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull(frame);
        assertTrue(frame.isPng());
    }

    @Test
    public void aDirectoryDimensionOfZeroMeans256() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1};
        byte[] ico = ico(new byte[][] {png}, 0, 0);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull(frame);
        assertEquals(256, frame.getWidth());
        assertEquals(256, frame.getHeight());
    }

    @Test
    public void aDibFrameIsRewrittenAsACompleteBmpFile() {
        // 4x4, 24 bpp: XOR is 12 bytes/row, mask is 4 bytes/row.
        byte[] dib = dib(4, 4, 24, true);
        byte[] ico = ico(new byte[][] {dib}, 4, 4);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);
        assertNotNull(frame);
        assertFalse(frame.isPng());
        assertTrue("the mask fits, so it is located", frame.getMaskOffset() >= 0);

        byte[] bmp = IcoImage.toBmp(ico, frame);
        assertNotNull(bmp);
        assertEquals('B', bmp[0]);
        assertEquals('M', bmp[1]);
        assertEquals(14 + dib.length, u32(bmp, 2));   // bfSize
        // 24 bpp has no palette: pixels start right after the 40-byte header.
        assertEquals(14 + 40, u32(bmp, 10));          // bfOffBits
        assertEquals(4, u32(bmp, 14 + 8));            // biHeight: image only
        assertEquals(48, u32(bmp, 14 + 20));          // biSizeImage: 12*4 XOR
        // The DIB itself is copied verbatim past the file header.
        assertEquals(dib[0], bmp[14]);
        assertEquals(4, u32(bmp, 14 + 4));            // biWidth survives
    }

    @Test
    public void anEightBitFrameOffsetsItsPixelsPastThePalette() {
        // 8 bpp implies a 256-entry RGBQUAD palette when biClrUsed is 0.
        byte[] dib = dib(4, 4, 8, true);
        byte[] ico = ico(new byte[][] {dib}, 4, 4);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);
        byte[] bmp = IcoImage.toBmp(ico, frame);

        assertNotNull(bmp);
        assertEquals(14 + 40 + 256 * 4, u32(bmp, 10));
    }

    @Test
    public void theAndMaskIsReadBottomUpOneBitPerPixel() {
        byte[] dib = dib(8, 4, 24, true);
        byte[] ico = ico(new byte[][] {dib}, 8, 4);
        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);
        assertNotNull(frame);
        assertTrue(frame.getMaskOffset() >= 0);

        // Set the first mask bit of the stored first row: that row is the
        // image's BOTTOM row, so the top-left pixel stays opaque and the
        // bottom-left pixel goes transparent.
        int maskStart = frame.getMaskOffset();
        ico[maskStart] = (byte) 0x80;

        assertFalse(IcoImage.maskedOut(ico, frame, 0, 0));
        assertTrue(IcoImage.maskedOut(ico, frame, 0, 3));
        assertFalse(IcoImage.maskedOut(ico, frame, 1, 3));
        // Out-of-range coordinates are never transparent.
        assertFalse(IcoImage.maskedOut(ico, frame, -1, 0));
        assertFalse(IcoImage.maskedOut(ico, frame, 0, -1));
        assertFalse(IcoImage.maskedOut(ico, frame, 8, 0));
        assertFalse(IcoImage.maskedOut(ico, frame, 0, 4));
    }

    @Test
    public void aFrameWithoutAMaskReportsNoTransparency() {
        byte[] dib = dib(4, 4, 32, false); // no AND mask stored
        byte[] ico = ico(new byte[][] {dib}, 4, 4);

        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull(frame);
        assertEquals(-1, frame.getMaskOffset());
        assertFalse(IcoImage.maskedOut(ico, frame, 0, 0));
    }

    @Test
    public void toBmpRefusesAPngFrame() {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1};
        byte[] ico = ico(new byte[][] {png}, 8, 8);
        IcoImage.Frame frame = IcoImage.bestFrame(ico, ico.length);

        assertNotNull(frame);
        assertNull(IcoImage.toBmp(ico, frame));
    }

    // ---- Synthetic ICO construction ----

    /**
     * Builds a one-or-more-frame ICO container. Directory entries are written
     * at their declared dimensions; payloads are appended in order.
     *
     * @param declaredDims pairs of (width,height) per frame, flattened
     */
    private static byte[] ico(byte[][] frames, int... declaredDims) {
        int offset = 6 + frames.length * 16;
        int total = offset;
        for (byte[] frame : frames) {
            total += frame.length;
        }
        byte[] ico = new byte[total];
        put16(ico, 0, 0);
        put16(ico, 2, 1);
        put16(ico, 4, frames.length);
        int data = offset;
        for (int i = 0; i < frames.length; i++) {
            int entry = 6 + i * 16;
            ico[entry] = (byte) declaredDims[i * 2];
            ico[entry + 1] = (byte) declaredDims[i * 2 + 1];
            put16(ico, entry + 4, 1);
            put16(ico, entry + 6, 32);
            put32(ico, entry + 8, frames[i].length);
            put32(ico, entry + 12, data);
            System.arraycopy(frames[i], 0, ico, data, frames[i].length);
            data += frames[i].length;
        }
        return ico;
    }

    /**
     * Builds a minimal DIB frame: BITMAPINFOHEADER with the doubled height,
     * a palette when the bit depth needs one, XOR pixels, and — only when
     * {@code withMask} — the AND mask.
     */
    private static byte[] dib(int width, int height, int bitCount, boolean withMask) {
        int palette = bitCount <= 8 ? (1 << bitCount) * 4 : 0;
        int xorRow = (int) (((long) width * bitCount + 31) / 32) * 4;
        int xor = xorRow * height;
        int maskRow = (width + 31) / 32 * 4;
        int mask = withMask ? maskRow * height : 0;
        byte[] dib = new byte[40 + palette + xor + mask];
        put32(dib, 0, 40);              // biSize
        put32(dib, 4, width);           // biWidth
        put32(dib, 8, height * 2);      // biHeight: image + mask, per ICO
        put16(dib, 12, 1);              // biPlanes
        put16(dib, 14, bitCount);       // biBitCount
        put32(dib, 16, 0);              // BI_RGB
        put32(dib, 20, xor);            // biSizeImage
        return dib;
    }

    private static int u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
