package gh.nusashell.nusadesk.infrastructure.webapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * One bounded fetch of a registered web app's own favicon, made before the
 * launcher tile renders it.
 *
 * <p>Every request stays on the app's generated origin: the app's own document
 * is read once, bounded, for the icon URLs it declares
 * ({@link FaviconLinkParser}, same-origin only), those candidates are tried in
 * declaration order, and {@link WebAppFaviconEndpoint#forDefinition} remains the
 * conventional fallback — so an app whose icon lives at a declared path is no
 * longer missed just because {@code /favicon.ico} is absent (ADR-0048). Each
 * request uses explicit connect and read timeouts, no redirect following, no
 * cache, and {@code Connection: close}. Every body is read through a hard byte
 * cap, and the bytes only become a tile image if {@link FaviconResponsePolicy}
 * accepts the response and {@link BitmapFactory} actually decodes it,
 * downsampled so the bitmap's memory is bounded by the tile rather than by
 * whatever the server sent.</p>
 *
 * <p>An app may protect its own endpoint with HTTP Basic auth (ADR-0058).
 * {@link #fetch(WebAppDefinition, WebAppSignInCredential)} then attaches the
 * stored sign-in pair — the same pair the WebView answers that challenge with —
 * as an {@code Authorization} header, so a protected app still gets its own
 * icon instead of staying a monogram. The pair is only ever attached to
 * requests on the app's own generated {@code 127.0.0.1} endpoint and must
 * belong to that app's id, so it can never be offered to another origin or
 * another app. Without a pair, a {@code 401} is just another unusable
 * response — the same answer as a {@code 404}.</p>
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
     * <p>Two sources, in order: the icon URLs the app's own document declares
     * ({@code <link rel="icon">} and friends, same-origin only — the convention
     * {@code /favicon.ico} is often absent, which is what kept real tiles on
     * their monogram), then {@code /favicon.ico} itself as the conventional
     * fallback. Every attempt obeys the same policy; the first image that
     * decodes wins, and a document that cannot be read simply means the fallback
     * is tried.</p>
     *
     * <p>Equivalent to {@link #fetch(WebAppDefinition, WebAppSignInCredential)}
     * with no stored pair.</p>
     *
     * @param definition the registered app; its port is already validated
     * @return the decoded, dimension-bounded image, or {@code null} when neither
     *         source has a usable favicon
     * @throws IllegalArgumentException when no definition is given
     */
    public Bitmap fetch(WebAppDefinition definition) {
        return fetch(definition, null);
    }

    /**
     * Fetches one app's favicon once, offering the app's stored sign-in pair
     * when the app protects its own endpoint with HTTP Basic auth.
     *
     * <p>{@code credential} is the same stored pair the WebView uses to answer
     * the app's own auth challenge (ADR-0058): one user sign-in unlocks both
     * the surface and the tile icon. It is sent as an {@code Authorization:
     * Basic} header on every request this fetch makes, and every request stays
     * on the app's generated {@code 127.0.0.1} endpoint, so the pair is only
     * ever offered to the app it belongs to — a pair stored under a different
     * app id is refused outright rather than sent.</p>
     *
     * <p>A {@code null} credential is exactly the anonymous fetch: no header
     * is added, and a {@code 401} — like any other non-200 — is simply "no
     * favicon", so the tile keeps its monogram. A pair the server rejects is
     * the same silent {@code null}; a stale or wrong secret must never become
     * a user-visible error. The pair is never logged here.</p>
     *
     * @param definition the registered app; its port is already validated
     * @param credential the sign-in pair stored for this app, or {@code null}
     * @return the decoded, dimension-bounded image, or {@code null} when
     *         neither source has a usable favicon
     * @throws IllegalArgumentException when no definition is given, or when
     *         {@code credential} belongs to a different app
     */
    public Bitmap fetch(WebAppDefinition definition, WebAppSignInCredential credential) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (credential != null && !credential.getWebAppId().equals(definition.getId())) {
            throw new IllegalArgumentException(
                    "credential must belong to the app being fetched");
        }
        String authorization = credential == null
                ? null
                : basicAuthorizationHeader(credential.getUsername(), credential.getPassword());
        String origin = definition.getEndpointUrl();
        for (String declared : declaredIconUrls(origin, authorization)) {
            Bitmap image = fetchImage(declared, authorization);
            if (image != null) {
                return image;
            }
        }
        return fetchImage(WebAppFaviconEndpoint.forDefinition(definition), authorization);
    }

    /**
     * Reads the app's own document and returns the same-origin icon URLs it
     * declares. A document that is unreachable, non-200, oversized, or without a
     * usable declaration yields no candidates — never an error.
     *
     * @param authorization the {@code Authorization} header value to attach, or
     *                      {@code null} for an anonymous request
     */
    private List<String> declaredIconUrls(String origin, String authorization) {
        HttpURLConnection connection = null;
        try {
            connection = open(origin, authorization);
            if (!FaviconResponsePolicy.isUsableStatus(connection.getResponseCode())) {
                return Collections.emptyList();
            }
            byte[] document = readBounded(connection, FaviconResponsePolicy.MAX_DOCUMENT_BYTES);
            return document == null
                    ? Collections.emptyList()
                    : FaviconLinkParser.iconUrls(
                            new String(document, StandardCharsets.UTF_8), origin);
        } catch (IOException | RuntimeException failure) {
            return Collections.emptyList();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * One bounded image fetch: status, byte cap, dimensions, and decode.
     *
     * @param authorization the {@code Authorization} header value to attach, or
     *                      {@code null} for an anonymous request
     */
    private Bitmap fetchImage(String url, String authorization) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, authorization);
            int status = connection.getResponseCode();
            if (!FaviconResponsePolicy.isUsableStatus(status)) {
                return null;
            }
            long declaredLength = connection.getContentLengthLong();
            if (!FaviconResponsePolicy.isWithinByteBudget(declaredLength, 0)) {
                return null;
            }
            byte[] bytes = readBounded(connection, FaviconResponsePolicy.MAX_RESPONSE_BYTES);
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

    private HttpURLConnection open(String url, String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        // A redirect would leave the app's own origin; it is never followed, and
        // a 3xx is simply not a favicon.
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Connection", "close");
        connection.setUseCaches(false);
        // The stored sign-in pair answers the app's own Basic challenge and is
        // attached only here — every URL handed in is on the app's generated
        // 127.0.0.1 endpoint. It is never logged and never written anywhere.
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        return connection;
    }

    /**
     * Builds the {@code Authorization} header value for one stored pair:
     * {@code Basic} followed by the Base64 of {@code username + ":" + password}
     * in UTF-8 (RFC 7617).
     *
     * <p>{@code java.util.Base64} rather than {@code android.util.Base64}: the
     * project's other encoders already use it, the minSdk (29) is well above
     * the API 26 that added it, and it runs on a plain JVM — which is the only
     * reason this encoding can be pinned byte-for-byte in a unit test. That
     * matters because a silent encoding mistake turns a stored sign-in into a
     * permanent 401 that looks exactly like a wrong password.</p>
     *
     * <p>The returned value is a secret in transit: callers attach it to the
     * request and never log, persist, or reflect it.</p>
     *
     * @throws IllegalArgumentException when either part is missing
     */
    static String basicAuthorizationHeader(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("username and password must not be null");
        }
        String pair = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(
                pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the body through the byte cap.
     *
     * @return the payload, or {@code null} when the server sent more than
     *         {@code capBytes}
     */
    private static byte[] readBounded(HttpURLConnection connection, int capBytes) throws IOException {
        try (InputStream stream = connection.getInputStream()) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (collected.size() + read > capBytes) {
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
