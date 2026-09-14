package gh.nusashell.nusadesk.infrastructure.webapp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The favicon response rules are pure policy, so every rejection the fetch path
 * depends on is asserted without a server, a device, or a bitmap.
 */
public class FaviconResponsePolicyTest {

    @Test
    public void onlyAnOutrightSuccessIsUsable() {
        assertTrue(FaviconResponsePolicy.isUsableStatus(200));
        // A redirect would leave the app's own origin, so it is never followed.
        assertFalse(FaviconResponsePolicy.isUsableStatus(301));
        assertFalse(FaviconResponsePolicy.isUsableStatus(302));
        assertFalse(FaviconResponsePolicy.isUsableStatus(307));
        // An error page is not an image, and its body is never read.
        assertFalse(FaviconResponsePolicy.isUsableStatus(204));
        assertFalse(FaviconResponsePolicy.isUsableStatus(401));
        assertFalse(FaviconResponsePolicy.isUsableStatus(404));
        assertFalse(FaviconResponsePolicy.isUsableStatus(500));
    }

    @Test
    public void aPayloadWithinTheByteCapIsAccepted() {
        assertTrue(FaviconResponsePolicy.isWithinByteBudget(0, 0));
        assertTrue(FaviconResponsePolicy.isWithinByteBudget(1_024, 1_024));
        assertTrue(FaviconResponsePolicy.isWithinByteBudget(
                FaviconResponsePolicy.MAX_RESPONSE_BYTES, FaviconResponsePolicy.MAX_RESPONSE_BYTES));
    }

    @Test
    public void anUnknownDeclaredLengthIsAllowedBecauseTheReadIsStillCapped() {
        assertTrue(FaviconResponsePolicy.isWithinByteBudget(-1, 512));
        assertTrue(FaviconResponsePolicy.isWithinByteBudget(0, 512));
    }

    @Test
    public void aPayloadOverTheByteCapIsRejectedBeforeItIsDecoded() {
        int over = FaviconResponsePolicy.MAX_RESPONSE_BYTES + 1;

        assertFalse("a declared length over the cap is rejected up front",
                FaviconResponsePolicy.isWithinByteBudget(over, 0));
        assertFalse("a body that grew past the cap is rejected after the read",
                FaviconResponsePolicy.isWithinByteBudget(-1, over));
    }

    @Test
    public void bytesThatAreNotAnImageAreRejected() {
        // BitmapFactory reports -1 on both sides when the header is not an image.
        assertFalse(FaviconResponsePolicy.isUsableDimensions(-1, -1));
        assertFalse(FaviconResponsePolicy.isUsableDimensions(0, 0));
        assertFalse(FaviconResponsePolicy.isUsableDimensions(16, 0));
        assertFalse(FaviconResponsePolicy.isUsableDimensions(0, 16));
    }

    @Test
    public void aRealisticFaviconIsAccepted() {
        assertTrue(FaviconResponsePolicy.isUsableDimensions(16, 16));
        assertTrue(FaviconResponsePolicy.isUsableDimensions(32, 32));
        assertTrue(FaviconResponsePolicy.isUsableDimensions(180, 180));
        assertTrue(FaviconResponsePolicy.isUsableDimensions(512, 256));
        assertTrue(FaviconResponsePolicy.isUsableDimensions(
                FaviconResponsePolicy.MAX_SOURCE_DIMENSION,
                FaviconResponsePolicy.MAX_SOURCE_DIMENSION));
    }

    @Test
    public void anOversizedImageIsRejectedBeforeItsPixelsAreDecoded() {
        int tooBig = FaviconResponsePolicy.MAX_SOURCE_DIMENSION + 1;

        assertFalse(FaviconResponsePolicy.isUsableDimensions(tooBig, 16));
        assertFalse(FaviconResponsePolicy.isUsableDimensions(16, tooBig));
        assertFalse(FaviconResponsePolicy.isUsableDimensions(30_000, 30_000));
    }

    @Test
    public void downsamplingKeepsBothSidesWithinTheTileTarget() {
        assertEquals(1, FaviconResponsePolicy.sampleSizeFor(16, 16));
        assertEquals(1, FaviconResponsePolicy.sampleSizeFor(256, 256));
        assertEquals(2, FaviconResponsePolicy.sampleSizeFor(257, 257));
        assertEquals(2, FaviconResponsePolicy.sampleSizeFor(512, 512));
        // 513 / 2 floors to the 256px target, so 2 is already enough.
        assertEquals(2, FaviconResponsePolicy.sampleSizeFor(513, 513));
        assertEquals(4, FaviconResponsePolicy.sampleSizeFor(1_024, 1_024));
    }

    @Test
    public void downsamplingFollowsTheLongestSide() {
        assertEquals(2, FaviconResponsePolicy.sampleSizeFor(512, 16));
        assertEquals(2, FaviconResponsePolicy.sampleSizeFor(16, 512));
        assertEquals(4, FaviconResponsePolicy.sampleSizeFor(1_024, 16));
    }

    @Test
    public void everyAcceptedImageDecodesWithinTheMemoryBudget() {
        int[] sizes = {1, 16, 32, 180, 256, 257, 512, 513, 1_024};

        for (int size : sizes) {
            int sampleSize = FaviconResponsePolicy.sampleSizeFor(size, size);
            int decoded = size / sampleSize;
            assertTrue("decoded " + size + "px must fit the target",
                    decoded <= FaviconResponsePolicy.TARGET_MAX_DIMENSION);
            assertTrue("a " + size + "px image must fit the memory budget",
                    FaviconResponsePolicy.isWithinMemoryBudget(decoded * decoded * 4));
        }
        assertEquals(FaviconResponsePolicy.TARGET_MAX_DIMENSION
                * FaviconResponsePolicy.TARGET_MAX_DIMENSION * 4,
                FaviconResponsePolicy.MAX_BITMAP_BYTES);
    }

    @Test
    public void aBitmapLargerThanTheBudgetIsRejected() {
        assertFalse(FaviconResponsePolicy.isWithinMemoryBudget(0));
        assertFalse(FaviconResponsePolicy.isWithinMemoryBudget(-1));
        assertTrue(FaviconResponsePolicy.isWithinMemoryBudget(
                FaviconResponsePolicy.MAX_BITMAP_BYTES));
        assertFalse(FaviconResponsePolicy.isWithinMemoryBudget(
                FaviconResponsePolicy.MAX_BITMAP_BYTES + 1));
    }

    @Test
    public void theCapsAreTheDeclaredBudgets() {
        assertEquals(64 * 1024, FaviconResponsePolicy.MAX_RESPONSE_BYTES);
        assertEquals(1_024, FaviconResponsePolicy.MAX_SOURCE_DIMENSION);
        assertEquals(256, FaviconResponsePolicy.TARGET_MAX_DIMENSION);
        assertTrue("the source cap must be reachable by the downsample rule",
                FaviconResponsePolicy.MAX_SOURCE_DIMENSION
                        <= FaviconResponsePolicy.TARGET_MAX_DIMENSION * 8);
    }
}
