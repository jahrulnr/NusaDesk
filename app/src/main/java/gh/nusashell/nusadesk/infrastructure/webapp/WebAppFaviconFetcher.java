package gh.nusashell.nusadesk.infrastructure.webapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One bounded fetch of a registered web app's own favicon, made before the
 * launcher tile renders it.
 *
 * <p>The request goes to exactly one URL — {@link WebAppFaviconEndpoint} derives
 * it from the definition's generated origin — with explicit connect and read
 * timeouts, no redirect following, no cache, and {@code Connection: close}. The
 * body is read through a hard byte cap, and the bytes only become a tile image
 * if {@link FaviconResponsePolicy} accepts the response and
 * {@link BitmapFactory} actually decodes it, downsampled so the bitmap's memory
 * is bounded by the tile rather than by whatever the server sent.</p>
 *
 * <p>Every failure — unreachable, timed out, redirected, wrong status, too
 * large, not an image, undecodable — is the same answer: {@code null}. A tile
 * then keeps the monogram it already had, because a missing favicon is not an
 * error the user can act on and must never be reported as one. Nothing is
 * persisted: the bytes live in memory for the length of the call, and the caller
 * owns any cache.</p>
 *
 * <p>Blocking: the caller runs it off the main thread and owns cancellation.</p>
 */
public final class WebAppFaviconFetcher {

    /** Connect timeout used by the no-argument constructor. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_000;
    /** Read timeout used by the no-argument constructor. */
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 1_500;

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    /**
     * Reads the payload's pixel dimensions and decodes its pixels.
     *
     * <p>Split behind an interface for one reason: a JVM unit test cannot produce
     * a real {@link Bitmap}, so the fetch decision above this seam — status, byte
     * budget, dimension budget, downsampling — is tested against a real loopback
     * server with a recording fake instead of going unverified.</p>
     */
    public interface Decoder {
        /**
         * Reads an image header without decoding its pixels.
         *
         * @return the source size, or {@code null} when the bytes are not an image
         */
        Size boundsOf(byte[] bytes, int length);

        /**
         * Decodes the payload downsampled by {@code sampleSize}.
         *
         * @return the tile image, or {@code null} when the bytes cannot be decoded
         */
        Bitmap decode(byte[] bytes, int length, int sampleSize);
    }

    /** Source pixel dimensions read from an image header. */
    public static final class Size {
        private final int width;
        private final int height;

        public Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    private final Decoder decoder;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /** Fetches through {@link BitmapFactory} with the default timeouts. */
    public WebAppFaviconFetcher() {
        this(new BitmapFactoryDecoder(),
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /**
     * @param decoder              reads the image header and decodes the pixels
     * @param connectTimeoutMillis connect timeout; non-negative
     * @param readTimeoutMillis    read timeout; non-negative
     */
    public WebAppFaviconFetcher(
            Decoder decoder, int connectTimeoutMillis, int readTimeoutMillis) {
        if (decoder == null) {
            throw new IllegalArgumentException("decoder must not be null");
        }
        if (connectTimeoutMillis < 0 || readTimeoutMillis < 0) {
            throw new IllegalArgumentException("timeouts must not be negative");
        }
        this.decoder = decoder;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /**
     * Fetches one app's favicon once.
     *
     * @param definition the registered app; its port is already validated
     * @return the decoded, dimension-bounded image, or {@code null} when the
     *         endpoint has no usable favicon
     * @throws IllegalArgumentException when no definition is given
     */
    public Bitmap fetch(WebAppDefinition definition) {
        String url = WebAppFaviconEndpoint.forDefinition(definition);
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            int status = connection.getResponseCode();
            if (!FaviconResponsePolicy.isUsableStatus(status)) {
                return null;
            }
            long declaredLength = connection.getContentLengthLong();
            if (!FaviconResponsePolicy.isWithinByteBudget(declaredLength, 0)) {
                return null;
            }
            byte[] bytes = readBounded(connection);
            if (bytes == null
                    || !FaviconResponsePolicy.isWithinByteBudget(declaredLength, bytes.length)) {
                return null;
            }
            Size bounds = decoder.boundsOf(bytes, bytes.length);
            if (bounds == null || !FaviconResponsePolicy.isUsableDimensions(
                    bounds.getWidth(), bounds.getHeight())) {
                return null;
            }
            return withinMemoryBudget(decoder.decode(bytes, bytes.length,
                    FaviconResponsePolicy.sampleSizeFor(bounds.getWidth(), bounds.getHeight())));
        } catch (IOException | RuntimeException failure) {
            // Unreachable, timed out, malformed, or not an image: the tile keeps
            // the monogram it already had, and nothing is reported to the user.
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        // A redirect would leave the app's own origin; it is never followed, and
        // a 3xx is simply not a favicon.
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Connection", "close");
        connection.setUseCaches(false);
        return connection;
    }

    /**
     * Reads the body through the byte cap.
     *
     * @return the payload, or {@code null} when the server sent more than
     *         {@link FaviconResponsePolicy#MAX_RESPONSE_BYTES}
     */
    private static byte[] readBounded(HttpURLConnection connection) throws IOException {
        try (InputStream stream = connection.getInputStream()) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (collected.size() + read > FaviconResponsePolicy.MAX_RESPONSE_BYTES) {
                    return null;
                }
                collected.write(buffer, 0, read);
            }
            return collected.toByteArray();
        }
    }

    private static Bitmap withinMemoryBudget(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        if (!FaviconResponsePolicy.isWithinMemoryBudget(bitmap.getByteCount())) {
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    /** Decodes through the platform image decoder, downsampled to the tile. */
    private static final class BitmapFactoryDecoder implements Decoder {

        @Override
        public Size boundsOf(byte[] bytes, int length) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, length, bounds);
            return new Size(bounds.outWidth, bounds.outHeight);
        }

        @Override
        public Bitmap decode(byte[] bytes, int length, int sampleSize) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            // Transparency is preserved: favicons are usually PNG or ICO with an
            // alpha channel, and the tile plate shows through it.
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(bytes, 0, length, options);
        }
    }
}
