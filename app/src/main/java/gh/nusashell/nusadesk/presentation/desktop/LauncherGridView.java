package gh.nusashell.nusadesk.presentation.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import gh.nusashell.nusadesk.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The launcher's responsive tile grid.
 *
 * <p>It is one flat grid, not a section list: the {@code Add app} action, the
 * Linux surfaces this build ships, and the user's web apps all live in the same
 * grid, in that order. The column count follows the real available width, so
 * phone portrait, phone landscape, and a tablet all reflow without a separate
 * layout each, and it drops to fewer columns as the system font scale grows so a
 * label never breaks mid-word.</p>
 *
 * <p>Rows are explicit equal-weight columns rather than a {@code GridLayout}:
 * the launcher is a scrolling surface with an unbounded height, and the last row
 * keeps the same tile width as every other row instead of stretching to fill.</p>
 */
public final class LauncherGridView extends LinearLayout {

    /** Width below which a tile column is too narrow to be usable. */
    private static final int MIN_TILE_WIDTH_DP = 96;

    /** Receives a tap on a tile. */
    public interface OpenListener {
        void onEntryOpened(LauncherEntry entry);
    }

    private final List<LauncherTileView> tiles = new ArrayList<>();

    private List<LauncherEntry> entries = Collections.emptyList();
    private boolean unlocked;
    private int columns = 3;
    private OpenListener openListener;
    private OpenListener editListener;

    public LauncherGridView(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    public LauncherGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    /** Receives a tap on an openable tile. */
    public void setOnEntryOpenListener(OpenListener listener) {
        this.openListener = listener;
    }

    /**
     * Receives a long press on a tile that has something to edit. Only web apps
     * do; the gesture is also stated in the tile's content description, so it is
     * never the only way to reach the edit form.
     */
    public void setOnEntryEditListener(OpenListener listener) {
        this.editListener = listener;
    }

    /**
     * Renders the grid.
     *
     * @param entries  the entries in launcher order
     * @param unlocked whether the curated Linux system is installed
     * @param favicons decoded favicons by web-app id; an entry with a user image
     *                 of its own ignores its favicon
     */
    public void setEntries(
            List<LauncherEntry> entries, boolean unlocked, Map<String, Bitmap> favicons) {
        this.entries = entries == null ? Collections.emptyList() : entries;
        this.unlocked = unlocked;
        rebuildTilesIfNeeded();
        // Bind by position, not from the tile's own tag: an edited web app keeps
        // its id but changes its label and icon, and that must reach the tile.
        for (int index = 0; index < tiles.size() && index < this.entries.size(); index++) {
            LauncherEntry entry = this.entries.get(index);
            tiles.get(index).bind(entry, unlocked, faviconFor(entry, favicons));
        }
        post(this::layoutTiles);
    }

    /**
     * The favicon one entry may show. A curated surface has none, and the lookup
     * is by id, so an image fetched for one app can never land on another tile.
     */
    private static Bitmap faviconFor(LauncherEntry entry, Map<String, Bitmap> favicons) {
        if (!entry.isWebApp() || favicons == null) {
            return null;
        }
        return favicons.get(entry.getId());
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int next = columnsFor(width);
        if (next != columns) {
            columns = next;
            // Rebuilding rows inside a layout pass would leave the added views
            // unmeasured, so the reflow is deferred to the next frame.
            post(this::layoutTiles);
        }
    }

    private int columnsFor(int viewportWidth) {
        int available = viewportWidth - getPaddingStart() - getPaddingEnd();
        return Math.max(2, available / minTileWidthPx());
    }

    /**
     * Minimum usable tile width, scaled by the system font scale: a tile has to
     * be wide enough for its label at the size the user chose, otherwise the
     * grid reflows to fewer, wider columns instead of breaking words.
     */
    private int minTileWidthPx() {
        float fontScale = getResources().getConfiguration().fontScale;
        return dp((int) Math.ceil(MIN_TILE_WIDTH_DP * Math.max(1f, fontScale)));
    }

    private void layoutTiles() {
        int visibleColumns = Math.max(2, Math.min(columns, tiles.size()));
        int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);

        for (LauncherTileView tile : tiles) {
            ViewGroup parent = (ViewGroup) tile.getParent();
            if (parent != null) {
                parent.removeView(tile);
            }
        }
        removeAllViews();
        if (tiles.isEmpty()) {
            return;
        }

        LinearLayout row = null;
        for (int index = 0; index < tiles.size(); index++) {
            int column = index % visibleColumns;
            if (column == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = index == 0 ? 0 : gap;
                addView(row, rowParams);
            }
            row.addView(tiles.get(index), tileParams(column, gap));
        }
        // Keep the last row's tiles the same width as every other row.
        if (row != null) {
            for (int column = tiles.size() % visibleColumns; column > 0 && column < visibleColumns;
                 column++) {
                row.addView(new View(getContext()), tileParams(column, gap));
            }
        }
        post(this::equalizeTileHeights);
    }

    /**
     * Gives every tile in a row the same height. A tile's natural height depends
     * on how its label and status wrap, which changes with the system font
     * scale, so the tallest tile in the row wins and the measured height is only
     * applied when it actually differs.
     */
    private void equalizeTileHeights() {
        for (int rowIndex = 0; rowIndex < getChildCount(); rowIndex++) {
            ViewGroup row = (ViewGroup) getChildAt(rowIndex);
            int tallest = 0;
            for (int index = 0; index < row.getChildCount(); index++) {
                View tile = row.getChildAt(index);
                if (tile.getWidth() <= 0) {
                    continue;
                }
                tile.measure(
                        MeasureSpec.makeMeasureSpec(tile.getWidth(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                tallest = Math.max(tallest, tile.getMeasuredHeight());
            }
            if (tallest == 0) {
                continue;
            }
            for (int index = 0; index < row.getChildCount(); index++) {
                View tile = row.getChildAt(index);
                ViewGroup.LayoutParams params = tile.getLayoutParams();
                if (params.height != tallest) {
                    params.height = tallest;
                    tile.setLayoutParams(params);
                }
            }
        }
    }

    private LinearLayout.LayoutParams tileParams(int column, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 0, 1f);
        params.width = 0;
        params.height = LayoutParams.WRAP_CONTENT;
        if (column > 0) {
            params.setMarginStart(gap);
        }
        return params;
    }

    /**
     * Replaces the tile views when the entry set actually changed; a plain
     * re-render (unlock or status update) only re-binds the existing tiles, which
     * keeps tile identity stable while the user is looking at them.
     */
    private void rebuildTilesIfNeeded() {
        List<String> current = new ArrayList<>(tiles.size());
        for (LauncherTileView tile : tiles) {
            current.add(tile.getEntry().getId());
        }
        List<String> next = new ArrayList<>(entries.size());
        for (LauncherEntry entry : entries) {
            next.add(entry.getId());
        }
        if (current.equals(next)) {
            return;
        }
        tiles.clear();
        for (LauncherEntry entry : entries) {
            LauncherTileView tile = new LauncherTileView(getContext());
            tile.setOnClickListener(view -> open((LauncherTileView) view));
            if (entry.isWebApp()) {
                tile.setOnLongClickListener(view -> {
                    if (editListener != null) {
                        editListener.onEntryOpened(((LauncherTileView) view).getEntry());
                    }
                    return true;
                });
            }
            tiles.add(tile);
        }
    }

    private void open(LauncherTileView tile) {
        LauncherEntry entry = tile.getEntry();
        if (entry == null || openListener == null) {
            return;
        }
        if (entry.getKind() != LauncherEntry.Kind.ADD_APP && !unlocked) {
            return;
        }
        openListener.onEntryOpened(entry);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
