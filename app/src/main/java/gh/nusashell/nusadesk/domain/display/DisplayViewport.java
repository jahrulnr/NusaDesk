package gh.nusashell.nusadesk.domain.display;

/** Immutable mapping from Android renderer content bounds to guest pixels. */
public final class DisplayViewport {
    private final float x, y, width, height, sourceX, sourceY, sourceWidth, sourceHeight;

    public DisplayViewport(float x, float y, float width, float height,
            float sourceX, float sourceY, float sourceWidth, float sourceHeight) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width)
                || !Float.isFinite(height) || !Float.isFinite(sourceX) || !Float.isFinite(sourceY)
                || !Float.isFinite(sourceWidth) || !Float.isFinite(sourceHeight)
                || width <= 0 || height <= 0 || sourceWidth < 1 || sourceHeight < 1) {
            throw new IllegalArgumentException("display geometry must be finite and usable");
        }
        this.x = x; this.y = y; this.width = width; this.height = height;
        this.sourceX = sourceX; this.sourceY = sourceY;
        this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
    }

    /** Minimum 640 guest pixels accommodates the selected GTK file dialog on a portrait phone. */
    public static int logicalWidth(int surfaceWidth) {
        if (surfaceWidth <= 0) throw new IllegalArgumentException("surface width must be positive");
        // Halving keeps guest text legible; the renderer only needs the width 8-aligned.
        return Math.max(640, surfaceWidth / 2) / 8 * 8;
    }

    public static int logicalHeight(int surfaceWidth, int surfaceHeight) {
        if (surfaceHeight <= 0) throw new IllegalArgumentException("surface height must be positive");
        return logicalHeight(logicalWidth(surfaceWidth), surfaceWidth, surfaceHeight);
    }

    /** Same proportional height for an explicit logical width; the floor still applies. */
    public static int logicalHeight(int guestWidth, int surfaceWidth, int surfaceHeight) {
        if (surfaceWidth <= 0) throw new IllegalArgumentException("surface width must be positive");
        // The 240-pixel floor keeps even a shallow letterboxed surface usable.
        return Math.max(240, Math.round((float) guestWidth * surfaceHeight / surfaceWidth));
    }

    public float guestDeltaX(float surfaceDelta) { return surfaceDelta * sourceWidth / width; }
    public float guestDeltaY(float surfaceDelta) { return surfaceDelta * sourceHeight / height; }

    public float guestX(float surfaceX) {
        return sourceX + Math.max(0, Math.min(sourceWidth - 1, (surfaceX - x) * sourceWidth / width));
    }

    public float guestY(float surfaceY) {
        return sourceY + Math.max(0, Math.min(sourceHeight - 1, (surfaceY - y) * sourceHeight / height));
    }
}
