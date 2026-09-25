package gh.nusashell.nusadesk.presentation.desktop;

/**
 * What a launcher tile's icon plate shows, and in what order.
 *
 * <p>Four sources can fill the plate, and the order is a product promise rather
 * than a rendering detail: the image the user chose for the app always wins,
 * then the favicon the app's own loopback endpoint served, then the vector icon
 * bundled with the build for kinds that ship one, then a monogram of the app's
 * own name. A favicon or a bundled glyph can therefore never replace a choice
 * the user made, and a tile always has something to show.</p>
 *
 * <p>A source that cannot actually be rendered — a revoked picker permission, a
 * favicon that has gone — falls through to the next one instead of leaving an
 * empty plate, which is why the order is asked for one step at a time rather
 * than decided once.</p>
 *
 * <p>Pure: it decides which source to try and which to fall back to. It loads,
 * fetches, and draws nothing.</p>
 */
public final class LauncherIconPolicy {

    /** One of the four sources a tile plate can show. */
    public enum Source {
        /** The image the user picked for the app. */
        USER_IMAGE,
        /** The favicon the app's own endpoint served. */
        FAVICON,
        /** The vector icon bundled with the build for this kind of entry. */
        VECTOR,
        /** A monogram of the app's own name; always renderable. */
        MONOGRAM
    }

    private LauncherIconPolicy() {
    }

    /**
     * The first source to try for one tile.
     *
     * @param userIconUri    the entry's stored {@code content://} token, or
     *                       {@code null} when the user chose no image
     * @param hasFavicon     whether a decoded favicon is available for this
     *                       entry
     * @param bundledIconRes the entry's bundled vector resource, or {@code 0}
     *                       when the kind ships none
     */
    public static Source preferred(
            String userIconUri, boolean hasFavicon, int bundledIconRes) {
        if (userIconUri != null) {
            return Source.USER_IMAGE;
        }
        if (hasFavicon) {
            return Source.FAVICON;
        }
        return bundledIconRes != 0 ? Source.VECTOR : Source.MONOGRAM;
    }

    /**
     * The source to try when {@code current} could not be rendered. The steps
     * walk the same order as {@link #preferred} — favicon, then the bundled
     * vector when one exists — and the monogram is the end of the order.
     */
    public static Source after(Source current, boolean hasFavicon, int bundledIconRes) {
        switch (current) {
            case USER_IMAGE:
                if (hasFavicon) {
                    return Source.FAVICON;
                }
                // No favicon: fall through to the same step a favicon takes.
            case FAVICON:
                return bundledIconRes != 0 ? Source.VECTOR : Source.MONOGRAM;
            default:
                return Source.MONOGRAM;
        }
    }
}
