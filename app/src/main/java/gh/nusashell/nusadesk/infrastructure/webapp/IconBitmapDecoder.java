package gh.nusashell.nusadesk.infrastructure.webapp;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import java.io.ByteArrayInputStream;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes static SVG icons through AndroidSVG at tile resolution and raster
 * tile-icon bytes through the platform image decoder, with the ICO
 * container unwrapped by {@link IcoImage} first.
 *
 * <p>{@code BitmapFactory} covers PNG, JPEG, WebP, GIF, and BMP on every API
 * level, but an ICO container is only readable where the OEM's Skia build
 * happened to ship its ICO codec — the platform could decode a PNG inside an
 * ICO and still refuse the container that carries it. Every payload therefore
 * goes through {@link IcoImage#bestFrame}: an ICO's PNG frame is decoded from
 * its byte range directly, a DIB frame is rewritten as a {@code .bmp} file the
 * decoder already reads, and its AND mask is applied afterwards because a BMP
 * conversion loses it. Bytes that are not an ICO fall through to the platform
 * untouched, so every format the platform already decodes keeps working.
 * Bytes that carry the ICO magic but yield no decodable frame are left for
 * the platform too, so a device whose Skia does ship the codec still gets
 * its chance.</p>
 *
 * <p>Used as the production {@link WebAppFaviconFetcher.Decoder} and, through
 * {@link #decodePicked}, as the fallback reader for an image the user picked:
 * the system document picker offers any {@code image/*}, and an {@code .ico}
 * resolves cleanly but draws nothing on a device without the platform codec —
 * the tile would keep its monogram with no sign the pick itself failed.</p>
 */
public final class IconBitmapDecoder implements WebAppFaviconFetcher.Decoder {

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    static {
        // Keep XML entity expansion inside the existing bounded-payload contract.
        SVG.setInternalEntitiesEnabled(false);
    }

    @Override
    public WebAppFaviconFetcher.Size boundsOf(byte[] bytes, int length) {
        IcoImage.Frame frame = IcoImage.bestFrame(bytes, length);
        if (frame == null || frame.isPng()) {
            int offset = frame == null ? 0 : frame.getOffset();
            int frameLength = frame == null ? length : frame.getLength();
            WebAppFaviconFetcher.Size raster = bounds(bytes, offset, frameLength);
            if (raster.getWidth() > 0 && raster.getHeight() > 0) return raster;
            SVG svg = svg(bytes, length);
            return svg == null ? raster : svgSize(svg);
        }
        byte[] bmp = IcoImage.toBmp(bytes, frame);
        return bmp == null ? bounds(bytes, 0, length) : bounds(bmp, 0, bmp.length);
    }

    @Override
    public Bitmap decode(byte[] bytes, int length, int sampleSize) {
        IcoImage.Frame frame = IcoImage.bestFrame(bytes, length);
        if (frame == null || frame.isPng()) {
            int offset = frame == null ? 0 : frame.getOffset();
            int frameLength = frame == null ? length : frame.getLength();
            Bitmap raster = decodeBytes(bytes, offset, frameLength, sampleSize);
            if (raster != null) return raster;
            SVG svg = svg(bytes, length);
            if (svg == null) return null;
            WebAppFaviconFetcher.Size size = svgSize(svg);
            Bitmap image = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
            svg.setDocumentWidth(size.getWidth());
            svg.setDocumentHeight(size.getHeight());
            try {
                svg.renderToCanvas(new Canvas(image));
            } catch (RuntimeException renderFailure) {
                image.recycle();
                return null;
            }
            if (!hasVisiblePixel(image)) {
                // A valid-but-blank SVG must not become an invisible tile icon.
                image.recycle();
                return null;
            }
            return image;
        }
        byte[] bmp = IcoImage.toBmp(bytes, frame);
        Bitmap decoded = bmp == null
                ? decodeBytes(bytes, 0, length, sampleSize)
                : decodeBytes(bmp, 0, bmp.length, sampleSize);
        if (decoded != null && frame.getMaskOffset() >= 0) {
            decoded = applyTransparencyMask(decoded, bytes, frame, sampleSize);
        }
        return decoded;
    }

    /**
     * Reads and decodes a user-picked {@code content://} image, bounded by the
     * same {@link FaviconResponsePolicy} a fetched favicon obeys — a tile icon
     * is bounded by the tile, not by the file the user happened to pick.
     *
     * <p>Runs synchronously: the read and decode happen on the calling thread,
     * which today is the main thread — the same exposure {@code
     * ImageView.setImageURI} already had, bounded to the favicon's 2 MiB cap,
     * and reached only when the platform decode produced nothing.</p>
     *
     * @return the tile image, or {@code null} for anything unreadable,
     *         undecodable, or over budget — exactly like a missing favicon
     */
    public static Bitmap decodePicked(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) {
            return null;
        }
        byte[] bytes;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                return null;
            }
            bytes = readUpTo(stream, FaviconResponsePolicy.MAX_RESPONSE_BYTES + 1);
            if (bytes.length > FaviconResponsePolicy.MAX_RESPONSE_BYTES) {
                return null;
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        try {
            IconBitmapDecoder decoder = new IconBitmapDecoder();
            WebAppFaviconFetcher.Size bounds = decoder.boundsOf(bytes, bytes.length);
            if (bounds == null || !FaviconResponsePolicy.isUsableDimensions(
                    bounds.getWidth(), bounds.getHeight())) {
                return null;
            }
            Bitmap bitmap = decoder.decode(bytes, bytes.length,
                    FaviconResponsePolicy.sampleSizeFor(bounds.getWidth(), bounds.getHeight()));
            if (bitmap != null && !FaviconResponsePolicy.isWithinMemoryBudget(bitmap.getByteCount())) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        } catch (RuntimeException undecodable) { return null; }
    }

    private static SVG svg(byte[] bytes, int length) {
        if (bytes == null || length <= 0 || length > bytes.length
                || length > FaviconResponsePolicy.MAX_RESPONSE_BYTES) return null;
        try {
            return SVG.getFromInputStream(new ByteArrayInputStream(bytes, 0, length));
        } catch (SVGParseException | RuntimeException invalid) {
            return null;
        }
    }

    /** Any pixel with a non-zero alpha channel, i.e. the render drew something. */
    private static boolean hasVisiblePixel(Bitmap image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (Color.alpha(image.getPixel(x, y)) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** SVG coordinates are not source pixels: rasterize at the existing tile budget. */
    private static WebAppFaviconFetcher.Size svgSize(SVG svg) {
        float ratio = svg.getDocumentAspectRatio();
        int longest = FaviconResponsePolicy.TARGET_MAX_DIMENSION;
        if (!Float.isFinite(ratio) || ratio <= 0) return new WebAppFaviconFetcher.Size(longest, longest);
        int width = ratio >= 1 ? longest : Math.max(1, Math.round(longest * ratio));
        int height = ratio >= 1 ? Math.max(1, Math.round(longest / ratio)) : longest;
        return new WebAppFaviconFetcher.Size(width, height);
    }

    private static WebAppFaviconFetcher.Size bounds(byte[] bytes, int offset, int length) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, offset, length, options);
        return new WebAppFaviconFetcher.Size(options.outWidth, options.outHeight);
    }

    private static Bitmap decodeBytes(byte[] bytes, int offset, int length, int sampleSize) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        // Transparency is preserved: icons usually carry an alpha channel, and
        // the tile plate shows through it.
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, offset, length, options);
    }

    /**
     * Applies an ICO frame's AND mask to the decoded image. The BMP rewrite
     * keeps the mask as trailing bytes, so transparency survives only when it
     * is written back onto the pixels here; the mask's row order is bottom-up,
     * which {@link IcoImage#maskedOut} already reverses.
     */
    private static Bitmap applyTransparencyMask(
            Bitmap decoded, byte[] source, IcoImage.Frame frame, int sampleSize) {
        Bitmap mutable = decoded.isMutable()
                ? decoded
                : decoded.copy(Bitmap.Config.ARGB_8888, true);
        if (mutable == null) {
            return decoded;
        }
        if (mutable != decoded) {
            decoded.recycle();
        }
        int sample = Math.max(1, sampleSize);
        int width = mutable.getWidth();
        int height = mutable.getHeight();
        for (int y = 0; y < height; y++) {
            int sourceY = Math.min(y * sample, frame.getHeight() - 1);
            for (int x = 0; x < width; x++) {
                int sourceX = Math.min(x * sample, frame.getWidth() - 1);
                if (IcoImage.maskedOut(source, frame, sourceX, sourceY)) {
                    mutable.setPixel(x, y, Color.TRANSPARENT);
                }
            }
        }
        return mutable;
    }

    /**
     * Drains a stream up to {@code capBytes}.
     *
     * @return the bytes read — possibly overshooting the cap by up to one
     *         buffer, which is how "over the cap" is reported to the caller
     */
    private static byte[] readUpTo(InputStream stream, int capBytes) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            collected.write(buffer, 0, read);
            if (collected.size() >= capBytes) {
                break;
            }
        }
        return collected.toByteArray();
    }
}
