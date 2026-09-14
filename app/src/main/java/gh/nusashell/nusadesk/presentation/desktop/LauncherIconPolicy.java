package gh.nusashell.nusadesk.presentation.desktop;

/**
 * What a launcher tile's icon plate shows, and in what order.
 *
 * <p>Three sources can fill the plate, and the order is a product promise rather
 * than a rendering detail: the image the user chose for the app always wins,
 * then the favicon the app's own loopback endpoint served, then a monogram of
 * the app's own name. A favicon can therefore never replace a choice the user
 * made, and a tile always has something to show.</p>
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

    /** One of the three sources a tile plate can show. */
    public enum Source {
        /** The image the user picked for the app. */
        USER_IMAGE,
        /** The favicon the app's own endpoint served. */
        FAVICON,
        /** A monogram of the app's own name; always renderable. */
        MONOGRAM
    }

    private LauncherIconPolicy() {
    }

    /**
     * The first source to try for one tile.
     *
     * @param userIconUri the entry's stored {@code content://} token, or
     *                    {@code null} when the user chose no image
     * @param hasFavicon  whether a decoded favicon is available for this entry
     */
    public static Source preferred(String userIconUri, boolean hasFavicon) {
        if (userIconUri != null) {
            return Source.USER_IMAGE;
        }
        return hasFavicon ? Source.FAVICON : Source.MONOGRAM;
    }

    /**
     * The source to try when {@code current} could not be rendered. A favicon is
     * the only step between the user's image and the monogram, and the monogram
     * is the end of the order.
     */
    public static Source after(Source current, boolean hasFavicon) {
        if (current == Source.USER_IMAGE && hasFavicon) {
            return Source.FAVICON;
        }
        return Source.MONOGRAM;
    }
}
