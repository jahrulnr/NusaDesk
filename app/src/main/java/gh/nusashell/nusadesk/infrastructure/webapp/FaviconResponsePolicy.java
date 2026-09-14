package gh.nusashell.nusadesk.infrastructure.webapp;

/**
 * What a favicon response must satisfy before it may become a launcher tile
 * icon.
 *
 * <p>A favicon is fetched from a server the app does not control, so every
 * property of the response is treated as hostile input: the status must be an
 * outright success (a redirect is never followed, because following one would
 * leave the app's own origin), the payload must fit a byte budget, and the
 * image itself must fit a pixel budget so decoding it cannot allocate an
 * unbounded bitmap. A response that fails any of these is simply "no favicon";
 * the tile keeps its monogram and the user is shown no error, because a missing
 * favicon is not a failure they can act on.</p>
 *
 * <p>The checks are staged — status before the body is read, length before it is
 * decoded, dimensions before its pixels are decoded — so a hostile or broken
 * response is rejected as early as possible.</p>
 *
 * <p>Pure: no Android type, no I/O. The caller performs the request and the
 * decode and consults these rules.</p>
 */
public final class FaviconResponsePolicy {

    /** Largest favicon payload accepted, in bytes. A favicon is not a photo. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** Largest source image accepted, in pixels on either side. */
    public static final int MAX_SOURCE_DIMENSION = 1024;
    /** Largest decoded image kept, in pixels on either side; the tile plate at 4x. */
    public static final int TARGET_MAX_DIMENSION = 256;
    /**
     * Memory a decoded tile image may occupy: an ARGB_8888 bitmap of
     * {@link #TARGET_MAX_DIMENSION} square. Downsampling to the target is what
     * keeps a favicon's cost independent of the size the server chose.
     */
    public static final int MAX_BITMAP_BYTES = TARGET_MAX_DIMENSION * TARGET_MAX_DIMENSION * 4;

    private static final int HTTP_OK = 200;

    private FaviconResponsePolicy() {
    }

    /**
     * True only for {@code 200 OK}.
     *
     * <p>A redirect, a {@code 404}, and a {@code 5xx} are all the same answer
     * here: this endpoint has no favicon to offer. The body of a non-200
     * response is never read, so an error page can never be mistaken for an
     * image.</p>
     */
    public static boolean isUsableStatus(int httpStatus) {
        return httpStatus == HTTP_OK;
    }

    /**
     * True when a declared or received payload fits {@link #MAX_RESPONSE_BYTES}.
     *
     * @param declaredLength the {@code Content-Length} header, or a negative
     *                       value when the server declared none
     * @param receivedBytes  bytes read so far; {@code 0} before the body is read
     */
    public static boolean isWithinByteBudget(long declaredLength, int receivedBytes) {
        return declaredLength <= MAX_RESPONSE_BYTES && receivedBytes <= MAX_RESPONSE_BYTES;
    }

    /**
     * True when the payload's source pixels can be bounded to a tile-sized
     * bitmap.
     *
     * <p>A dimension that is not positive means the bytes are not an image at
     * all; a dimension beyond {@link #MAX_SOURCE_DIMENSION} means the "favicon"
     * is not one, and is rejected before its pixels are decoded.</p>
     */
    public static boolean isUsableDimensions(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false;
        }
        return sourceWidth <= MAX_SOURCE_DIMENSION && sourceHeight <= MAX_SOURCE_DIMENSION;
    }

    /**
     * The largest power-of-two downsample that keeps both sides of an image
     * within {@link #TARGET_MAX_DIMENSION}, so the decoded bitmap's memory is
     * bounded by the target rather than by the source.
     *
     * @return a sample size of at least {@code 1}
     */
    public static int sampleSizeFor(int width, int height) {
        int longest = Math.max(width, height);
        int sampleSize = 1;
        while (longest / sampleSize > TARGET_MAX_DIMENSION) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /**
     * True when a decoded bitmap fits {@link #MAX_BITMAP_BYTES}. Belt and braces
     * for a decoder that returns something larger than it was asked for.
     */
    public static boolean isWithinMemoryBudget(int byteCount) {
        return byteCount > 0 && byteCount <= MAX_BITMAP_BYTES;
    }
}
