package gh.nusashell.nusadesk.presentation.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;

/**
 * One launcher tile: a single focusable accessibility node with an icon plate, a
 * label, and — only when it applies — a status line.
 *
 * <p>Three kinds of entry share this tile, and the differences are stated in
 * words, never implied by colour:</p>
 *
 * <ul>
 *   <li>the {@code Add} action stays enabled even
 *       before Linux is installed, because registering a launcher entry needs no
 *       runtime;</li>
 *   <li>a Linux surface or a user web app is enabled only once the curated
 *       system is installed, and says {@code Locked} with the reason when it is
 *       not;</li>
 *   <li>a web app renders its chosen image when it has one, the favicon its own
 *       endpoint served when it has not, and a monogram from its own name when
 *       it has neither, so a tile is never an empty square.</li>
 * </ul>
 *
 * <p>The icon, label, and status children are excluded from the accessibility
 * tree so TalkBack reads exactly one node per tile.</p>
 */
public final class LauncherTileView extends LinearLayout {

    /** Opacity of the icon plate when the tile cannot be opened. */
    private static final float UNAVAILABLE_ICON_ALPHA = 0.6f;
    /** Corner radius of the icon plate, matching tile_icon_surface. */
    private static final int ICON_CORNER_RADIUS_DP = 14;

    private LinearLayout iconPlate;
    private ImageView imageView;
    private TextView glyphView;
    private TextView labelView;
    private TextView statusView;

    private LauncherEntry entry;

    public LauncherTileView(Context context) {
        super(context);
        init();
    }

    public LauncherTileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutInflater.from(getContext()).inflate(R.layout.widget_launcher_tile, this, true);
        iconPlate = findViewById(R.id.tile_icon);
        imageView = findViewById(R.id.tile_image);
        glyphView = findViewById(R.id.tile_glyph);
        labelView = findViewById(R.id.tile_label);
        statusView = findViewById(R.id.tile_status);

        setFocusable(true);
        setClickable(true);
        int padding = getResources().getDimensionPixelSize(R.dimen.tile_padding);
        setPadding(padding, padding, padding, padding);
        setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.tile_min_height));
    }

    /** The entry this tile currently renders. */
    public LauncherEntry getEntry() {
        return entry;
    }

    /**
     * Renders one launcher entry.
     *
     * @param entry    the entry to render
     * @param unlocked whether the curated Linux system is installed, so an entry
     *                 that needs the runtime can open
     * @param favicon  the image the entry's own endpoint served, or {@code null}
     *                 when it has none; the user's own image always wins over it
     */
    public void bind(LauncherEntry entry, boolean unlocked, Bitmap favicon) {
        this.entry = entry;
        Context context = getContext();
        String label = labelOf(entry);
        boolean openable = entry.getKind() == LauncherEntry.Kind.ADD_APP || unlocked;

        // Every tile shares one borderless surface. The "Add" action used to
        // carry a dashed outline; the product now keeps the grid uniform and
        // lets the tile's own glyph and label say what it does.
        setBackgroundResource(R.drawable.tile_surface);
        labelView.setText(label);
        renderGlyph(entry, label, favicon);
        setEnabled(openable);
        iconPlate.setAlpha(openable ? 1f : UNAVAILABLE_ICON_ALPHA);

        if (!openable) {
            statusView.setVisibility(VISIBLE);
            statusView.setText(R.string.app_locked);
        } else {
            statusView.setVisibility(GONE);
        }
        setContentDescription(descriptionFor(entry, label, openable));
        setTooltipText(label);
    }

    /**
     * The tile's accessible sentence: the app's own name or curated description,
     * plus the gestures this tile really supports. A web app says it can be
     * long-pressed for editing; a curated surface does not promise that.
     */
    private String descriptionFor(LauncherEntry entry, String label, boolean openable) {
        if (entry.isWebApp()) {
            return getContext().getString(openable
                    ? R.string.webapp_tile_open_desc : R.string.webapp_tile_locked_desc, label);
        }
        String detail = entry.getDescriptionRes() != 0
                ? getContext().getString(entry.getDescriptionRes()) : label;
        return getContext().getString(openable
                ? R.string.app_tile_open_desc : R.string.app_tile_locked_desc, detail);
    }

    /** The localised label of an entry: a resource for curated items, user text otherwise. */
    private String labelOf(LauncherEntry entry) {
        return entry.getLabelRes() != 0
                ? getContext().getString(entry.getLabelRes())
                : entry.getLabel();
    }

    /**
     * Renders the icon plate by walking {@link LauncherIconPolicy}'s order — the
     * user's own image, then the app's favicon, then a glyph or a monogram — and
     * stopping at the first source that actually renders.
     */
    private void renderGlyph(LauncherEntry entry, String label, Bitmap favicon) {
        boolean hasFavicon = favicon != null;
        LauncherIconPolicy.Source source =
                LauncherIconPolicy.preferred(entry.getIconUri(), hasFavicon);
        if (source == LauncherIconPolicy.Source.USER_IMAGE) {
            if (showUserImage(entry.getIconUri())) {
                showImage();
                return;
            }
            // The token is still the app's icon, it just cannot be read any more
            // (a revoked permission, a deleted file): fall through rather than
            // leave the plate empty.
            source = LauncherIconPolicy.after(source, hasFavicon);
        }
        if (source == LauncherIconPolicy.Source.FAVICON && showFavicon(favicon)) {
            showImage();
            return;
        }
        imageView.setVisibility(GONE);
        glyphView.setVisibility(VISIBLE);
        showGlyph(entry, label);
    }

    private void showImage() {
        imageView.setVisibility(VISIBLE);
        glyphView.setVisibility(GONE);
    }

    /** Renders the literal glyph a curated entry carries, or a monogram. */
    private void showGlyph(LauncherEntry entry, String label) {
        if (entry.getGlyphRes() != 0) {
            glyphView.setText(entry.getGlyphRes());
        } else if (entry.getGlyph() != null) {
            glyphView.setText(entry.getGlyph());
        } else {
            glyphView.setText(monogram(label));
        }
    }

    /**
     * Loads a stored {@code content://} token into the plate. The token is an
     * opaque reference the user granted read access to; a token that can no
     * longer be resolved falls back to the next source instead of failing the
     * tile.
     */
    private boolean showUserImage(String iconUri) {
        Uri uri;
        try {
            uri = Uri.parse(iconUri);
            applyPlate();
            imageView.setImageURI(uri);
        } catch (RuntimeException unresolved) {
            imageView.setImageDrawable(null);
            return false;
        }
        return imageView.getDrawable() != null;
    }

    /**
     * Shows the image the app's own endpoint served. It is drawn on the same
     * rounded plate as a user image, so the two are visually interchangeable.
     */
    private boolean showFavicon(Bitmap favicon) {
        if (favicon == null) {
            return false;
        }
        applyPlate();
        imageView.setImageBitmap(favicon);
        return true;
    }

    private void applyPlate() {
        imageView.setBackground(roundedPlate());
        imageView.setClipToOutline(true);
    }

    private GradientDrawable roundedPlate() {
        GradientDrawable plate = new GradientDrawable();
        plate.setShape(GradientDrawable.RECTANGLE);
        plate.setCornerRadius(dp(ICON_CORNER_RADIUS_DP));
        plate.setColor(getContext().getColor(android.R.color.transparent));
        return plate;
    }

    /** First character of the app's own name, upper-cased, as a stand-in icon. */
    private static String monogram(String label) {
        if (label == null || label.trim().isEmpty()) {
            return "?";
        }
        String trimmed = label.trim();
        int firstCodePoint = trimmed.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(firstCodePoint)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
