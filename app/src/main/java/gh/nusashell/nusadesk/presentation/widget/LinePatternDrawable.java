package gh.nusashell.nusadesk.presentation.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Launcher backdrop texture: an infinite Truchet arc tiling, drawn as code so it
 * repeats seamlessly at any size or density and needs no image asset.
 *
 * <p>A Truchet tile is one square carrying two quarter-circle arcs of radius
 * half the tile edge, each arc running from one edge midpoint to the next, so
 * every edge midpoint carries exactly one arc endpoint. Because neighbouring
 * tiles therefore always meet arc-to-arc, the tiling connects no matter which
 * orientation each tile takes — the seamless continuity is a property of the
 * geometry, not something the drawing code enforces (Weisstein, "Truchet
 * Tiling", MathWorld; Pickover's arc variant of Truchet's 1704 diagonal tile).
 * Each arc is drawn twice at a small radius offset, which turns the strokes into
 * a woven ribbon.</p>
 *
 * <p>The tile orientation comes from a coordinate hash rather than a random
 * generator, so the field is deterministic, stable while the user scrolls, and
 * unbounded — there is no fixed pattern image to size, repeat, or memory-map.
 * Color arrives from the caller as a themed resource with a low alpha, so the
 * texture stays faint and identical in structure in both themes.</p>
 */
public final class LinePatternDrawable extends Drawable {

    /** One tile, in density-independent pixels. */
    private static final float CELL_DP = 88f;
    /** Stroke width relative to the tile edge. */
    private static final float STROKE_RATIO = 0.022f;
    /** Radius offset of the inner ribbon stroke, relative to the tile edge. */
    private static final float RIBBON_OFFSET_RATIO = 0.16f;
    /** Hash constants for the deterministic per-tile orientation. */
    private static final int HASH_X = 73856093;
    private static final int HASH_Y = 19349663;
    private static final int HASH_SEED = 0x5BF03635;
    /** Sweep of one quarter arc, in degrees. */
    private static final float QUARTER_SWEEP = 90f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final float cell;
    private final float radius;
    private final float ribbonRadius;
    private final int baseColor;

    public LinePatternDrawable(Context context, int colorRes) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        float density = context.getResources().getDisplayMetrics().density;
        this.cell = CELL_DP * density;
        this.radius = cell / 2f;
        this.ribbonRadius = radius - RIBBON_OFFSET_RATIO * cell;
        this.baseColor = context.getColor(colorRes);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, STROKE_RATIO * cell));
        paint.setColor(baseColor);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || Color.alpha(paint.getColor()) == 0 || cell <= 0f) {
            return;
        }
        // Start one tile before the visible area so edge tiles are drawn whole.
        int firstColumn = (int) Math.floor(bounds.left / cell) - 1;
        int firstRow = (int) Math.floor(bounds.top / cell) - 1;
        int lastColumn = (int) Math.ceil(bounds.right / cell) + 1;
        int lastRow = (int) Math.ceil(bounds.bottom / cell) + 1;

        int checkpoint = canvas.save();
        canvas.clipRect(bounds);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                drawTile(canvas, column, row);
            }
        }
        canvas.restoreToCount(checkpoint);
    }

    private void drawTile(Canvas canvas, int column, int row) {
        float originX = column * cell;
        float originY = row * cell;
        boolean flipped = tileOrientation(column, row);
        if (flipped) {
            drawCornerArc(canvas, originX, originY, 0f);
            drawCornerArc(canvas, originX + cell, originY + cell, 180f);
        } else {
            drawCornerArc(canvas, originX + cell, originY, 90f);
            drawCornerArc(canvas, originX, originY + cell, 270f);
        }
    }

    /**
     * Draws one corner of a tile: the quarter arc centred on that corner that
     * curves into the tile, twice, so the stroke reads as a ribbon.
     *
     * @param cornerX  x of the tile corner the arc is centred on
     * @param cornerY  y of the tile corner the arc is centred on
     * @param startAngle canvas angle of the first edge midpoint, clockwise from
     *                   the positive x axis (y grows downward)
     */
    private void drawCornerArc(Canvas canvas, float cornerX, float cornerY, float startAngle) {
        drawArc(canvas, cornerX, cornerY, radius, startAngle);
        drawArc(canvas, cornerX, cornerY, ribbonRadius, startAngle);
    }

    private void drawArc(Canvas canvas, float cornerX, float cornerY, float arcRadius,
                         float startAngle) {
        if (arcRadius <= 0f) {
            return;
        }
        arcBounds.set(cornerX - arcRadius, cornerY - arcRadius,
                cornerX + arcRadius, cornerY + arcRadius);
        canvas.drawArc(arcBounds, startAngle, QUARTER_SWEEP, false, paint);
    }

    /**
     * Deterministic tile orientation from its grid coordinates.
     *
     * <p>Exposed to tests so the pattern's stability (same tile for the same
     * coordinate on every draw) and its variety (both orientations actually
     * occur, so the field never degenerates into a plain grid) are asserted
     * without a device.</p>
     */
    static boolean tileOrientation(int column, int row) {
        int hash = (column * HASH_X) ^ (row * HASH_Y) ^ HASH_SEED;
        return ((hash >>> 7) & 1) == 1;
    }

    @Override
    public void setAlpha(int alpha) {
        if (Color.alpha(paint.getColor()) == alpha) {
            return;
        }
        paint.setColor(Color.argb(alpha, Color.red(baseColor),
                Color.green(baseColor), Color.blue(baseColor)));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
