package gh.nusashell.nusadesk.infrastructure.webapp;

/**
 * The ICO container half of tile-icon decoding.
 *
 * <p>A web app's icon is allowed to be a Windows {@code .ico}: it is the
 * conventional favicon name, it is what many servers still publish, and whether
 * {@code BitmapFactory} can read one at all depends on which Skia codecs the
 * device's OEM built — the same container decodes on one device and reports
 * "not an image" on another. This class removes that dependency by doing the
 * container half itself: an ICO is only a directory of images, each a PNG or a
 * raw DIB, and the platform decoder already covers both once the container is
 * unwrapped.</p>
 *
 * <p>Pure: no Android type and no I/O. It reads a directory, returns the byte
 * ranges and synthesized BMP bytes, and leaves every decode decision to the
 * caller. Any byte it cannot account for — a truncated directory, a frame that
 * overruns the buffer, a pre-{@code BITMAPINFOHEADER} DIB — means "no frame",
 * never a guess.</p>
 */
public final class IcoImage {

    /** ICONDIR is reserved(2), type(2), count(2). */
    private static final int HEADER_BYTES = 6;
    /** ICONDIRENTRY is 16 bytes. */
    private static final int ENTRY_BYTES = 16;
    /** {@code idType} for an icon; a cursor (2) is not an app icon. */
    private static final int TYPE_ICON = 1;
    /** A directory dimension of 0 encodes 256. */
    private static final int DIMENSION_ZERO_MEANS = 256;
    private static final int BITMAPINFOHEADER_BYTES = 40;
    private static final int BI_BITFIELDS = 3;
    /** BITMAPFILEHEADER, the file part a raw DIB does not carry. */
    private static final int BMP_FILE_HEADER_BYTES = 14;

    private static final int PNG_MAGIC_0 = 0x89;
    private static final int PNG_MAGIC_1 = 0x50;
    private static final int PNG_MAGIC_2 = 0x4E;
    private static final int PNG_MAGIC_3 = 0x47;

    /** One image inside the container, located and classified. */
    public static final class Frame {
        private final int offset;
        private final int length;
        private final int width;
        private final int height;
        private final int bitCount;
        private final boolean png;
        /** DIB-relative offset of the XOR pixels; 0 for a PNG frame. */
        private final int pixelDataOffset;
        /** Absolute offset of the AND transparency mask, or -1 when absent. */
        private final int maskOffset;
        private final int maskRowBytes;

        private Frame(int offset, int length, int width, int height, int bitCount,
                      boolean png, int pixelDataOffset, int maskOffset, int maskRowBytes) {
            this.offset = offset;
            this.length = length;
            this.width = width;
            this.height = height;
            this.bitCount = bitCount;
            this.png = png;
            this.pixelDataOffset = pixelDataOffset;
            this.maskOffset = maskOffset;
            this.maskRowBytes = maskRowBytes;
        }

        /** Start of the frame's bytes in the container. */
        public int getOffset() {
            return offset;
        }

        /** The frame's byte count in the container. */
        public int getLength() {
            return length;
        }

        /** Real image width, after the 0-means-256 convention. */
        public int getWidth() {
            return width;
        }

        /** Real image height, after the 0-means-256 convention. */
        public int getHeight() {
            return height;
        }

        /** True when the frame's payload is a PNG rather than a DIB. */
        public boolean isPng() {
            return png;
        }

        /** Absolute offset of the AND mask in the container, or -1 when absent. */
        public int getMaskOffset() {
            return maskOffset;
        }
    }

    private IcoImage() {
    }

    /**
     * The largest decodable frame, which is the tile's best source.
     *
     * @return the chosen frame, or {@code null} when the bytes are not an ICO
     *         container or hold no decodable frame
     */
    public static Frame bestFrame(byte[] bytes, int length) {
        if (bytes == null || length < HEADER_BYTES || bytes.length < length) {
            return null;
        }
        if (u16(bytes, 0) != 0 || u16(bytes, 2) != TYPE_ICON) {
            return null;
        }
        int count = u16(bytes, 4);
        // A declared count can overstate what fits: only the entries actually
        // inside the buffer are read, so only they bound frame offsets.
        int readable = Math.min(count, (length - HEADER_BYTES) / ENTRY_BYTES);
        long directoryEnd = HEADER_BYTES + (long) readable * ENTRY_BYTES;
        Frame best = null;
        for (int index = 0; index < readable; index++) {
            int entry = HEADER_BYTES + index * ENTRY_BYTES;
            int width = directoryDimension(bytes[entry]);
            int height = directoryDimension(bytes[entry + 1]);
            long size = u32(bytes, entry + 8);
            long offset = u32(bytes, entry + 12);
            // A frame that does not fit inside the buffer — or points back
            // into the directory itself — is skipped rather than followed:
            // the directory is untrusted input.
            if (size <= 0 || offset < directoryEnd || offset + size > length) {
                continue;
            }
            Frame frame = frameAt(bytes, (int) offset, (int) size, width, height);
            if (frame == null) {
                continue;
            }
            if (best == null
                    || area(frame) > area(best)
                    || (area(frame) == area(best) && frame.png && !best.png)) {
                best = frame;
            }
        }
        return best;
    }

    /**
     * Wraps a DIB frame as a complete {@code .bmp} file, which the platform
     * decoder reads natively. A raw DIB lacks the 14-byte file header and its
     * {@code biHeight} counts the XOR pixels plus the AND mask, so both are
     * corrected here; the mask bytes trail the file harmlessly.
     *
     * @return BMP file bytes, or {@code null} when the frame is not a DIB
     */
    public static byte[] toBmp(byte[] source, Frame frame) {
        if (source == null || frame == null || frame.png) {
            return null;
        }
        long xorRowBytes = alignedRowBytes((long) frame.width * frame.bitCount);
        long xorBytes = xorRowBytes * frame.height;
        byte[] bmp = new byte[BMP_FILE_HEADER_BYTES + frame.length];
        bmp[0] = 'B';
        bmp[1] = 'M';
        put32(bmp, 2, bmp.length);
        put32(bmp, 10, BMP_FILE_HEADER_BYTES + frame.pixelDataOffset);
        System.arraycopy(source, frame.offset, bmp, BMP_FILE_HEADER_BYTES, frame.length);
        // The DIB's biHeight counts image and mask together; the file must
        // name the image alone, and biSizeImage is the XOR region only.
        put32(bmp, BMP_FILE_HEADER_BYTES + 8, frame.height);
        put32(bmp, BMP_FILE_HEADER_BYTES + 20, (int) Math.min(xorBytes, Integer.MAX_VALUE));
        return bmp;
    }

    /**
     * True when the AND mask marks source pixel ({@code x}, {@code y})
     * transparent. Mask rows are stored bottom-up, one bit per pixel,
     * most-significant bit first.
     */
    public static boolean maskedOut(byte[] source, Frame frame, int x, int y) {
        if (source == null || frame == null || frame.maskOffset < 0
                || x < 0 || y < 0 || x >= frame.width || y >= frame.height) {
            return false;
        }
        int row = frame.height - 1 - y;
        int index = frame.maskOffset + row * frame.maskRowBytes + (x >> 3);
        if (index < 0 || index >= source.length) {
            return false;
        }
        return ((source[index] >> (7 - (x & 7))) & 1) == 1;
    }

    private static Frame frameAt(byte[] bytes, int offset, int size, int width, int height) {
        if (size >= 4
                && (bytes[offset] & 0xFF) == PNG_MAGIC_0
                && (bytes[offset + 1] & 0xFF) == PNG_MAGIC_1
                && (bytes[offset + 2] & 0xFF) == PNG_MAGIC_2
                && (bytes[offset + 3] & 0xFF) == PNG_MAGIC_3) {
            return new Frame(offset, size, width, height, 0, true, 0, -1, 0);
        }
        // DIB frame. Only BITMAPINFOHEADER-or-later is supported: the 12-byte
        // BITMAPCOREHEADER form has a different field layout and 3-byte
        // palette entries, and predates the web favicons this path serves.
        if (size < BITMAPINFOHEADER_BYTES) {
            return null;
        }
        long headerSize = u32(bytes, offset);
        long compression = u32(bytes, offset + 16);
        int bitCount = u16(bytes, offset + 14);
        if (headerSize < BITMAPINFOHEADER_BYTES || headerSize > size
                || bitCount <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        long paletteBytes = paletteBytes(bytes, offset, bitCount);
        // With a plain BITMAPINFOHEADER the BI_BITFIELDS masks live between
        // the header and the palette; from V2 on they are inside the header.
        long bitfieldsBytes =
                compression == BI_BITFIELDS && headerSize == BITMAPINFOHEADER_BYTES ? 12 : 0;
        long pixelDataOffset = headerSize + bitfieldsBytes + paletteBytes;
        if (pixelDataOffset >= size) {
            return null;
        }
        long xorBytes = alignedRowBytes((long) width * bitCount) * height;
        long maskRowBytes = alignedRowBytes(width);
        long maskBytes = maskRowBytes * height;
        long pixelsEnd = pixelDataOffset + xorBytes;
        // The spec stores the 1-bpp AND mask after the XOR pixels, but some
        // encoders write none: it counts only when the frame's own bytes
        // actually contain it whole.
        int maskOffset = pixelsEnd < size && pixelsEnd + maskBytes <= size
                ? offset + (int) pixelsEnd
                : -1;
        return new Frame(offset, size, width, height, bitCount,
                false, (int) pixelDataOffset, maskOffset, (int) maskRowBytes);
    }

    /**
     * The palette's byte size in a DIB: RGBQUAD entries, mandatory below
     * 8 bpp, still legal above it when {@code biClrUsed} declares them.
     */
    private static long paletteBytes(byte[] bytes, int offset, int bitCount) {
        long declared = u32(bytes, offset + 32);
        long entries;
        if (bitCount <= 8) {
            entries = declared > 0 ? Math.min(declared, 1L << bitCount) : (1L << bitCount);
        } else {
            entries = Math.min(declared, 256);
        }
        return entries * 4;
    }

    /** A directory dimension byte: 0 encodes 256, anything else is itself. */
    private static int directoryDimension(byte field) {
        int value = field & 0xFF;
        return value == 0 ? DIMENSION_ZERO_MEANS : value;
    }

    /** A DIB/BMP row is padded out to a 4-byte boundary. */
    private static long alignedRowBytes(long bits) {
        return (bits + 31) / 32 * 4;
    }

    private static long area(Frame frame) {
        return (long) frame.width * frame.height;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
