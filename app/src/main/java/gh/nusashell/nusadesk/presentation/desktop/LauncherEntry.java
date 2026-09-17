package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;

import java.util.Objects;

/**
 * One item in the launcher grid.
 *
 * <p>The launcher shows three kinds of item in one grid: the {@code Add app}
 * action, the curated Linux surfaces, and the web apps the user registered. They
 * are modelled as one immutable type so the grid, the search filter, and the
 * tile renderer each have exactly one input shape instead of three parallel code
 * paths that could disagree.</p>
 *
 * <p>A web-app entry keeps its {@link WebAppDefinition}, so the tile can show
 * the app's own name and icon and the shell can open the exact loopback origin
 * the definition generates. This object never builds a URL, probes a port, or
 * starts anything; those are the surface's job after the user opens it.</p>
 */
public final class LauncherEntry {

    /** What an entry is, which is what decides how a tile renders and opens. */
    public enum Kind {
        /** The "Add" action tile that opens the new-app form. Always enabled. */
        ADD_APP,
        /** A Linux surface the app ships itself. */
        CURATED,
        /** A user-registered local web app. */
        WEB_APP
    }

    /** Stable id of the "Add app" action. */
    public static final String ADD_APP_ID = "add-app";

    private static final LauncherEntry ADD_APP = new LauncherEntry(
            Kind.ADD_APP, ADD_APP_ID, R.string.launcher_add_label, null,
            R.drawable.ic_launcher_add, R.string.webapp_add_desc, null, null);

    private final Kind kind;
    private final String id;
    private final int labelRes;
    private final String label;
    private final int iconRes;
    private final int descriptionRes;
    private final String iconUri;
    private final WebAppDefinition webApp;

    private LauncherEntry(
            Kind kind, String id, int labelRes, String label,
            int iconRes, int descriptionRes,
            String iconUri, WebAppDefinition webApp) {
        this.kind = kind;
        this.id = id;
        this.labelRes = labelRes;
        this.label = label;
        this.iconRes = iconRes;
        this.descriptionRes = descriptionRes;
        this.iconUri = iconUri;
        this.webApp = webApp;
    }

    /** The single "Add app" action tile. */
    public static LauncherEntry addApp() {
        return ADD_APP;
    }

    /** A curated Linux surface. */
    public static LauncherEntry curated(DesktopApp app) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        return new LauncherEntry(Kind.CURATED, app.getId(), app.getLabelRes(), null,
                app.getIconRes(), app.getDescriptionRes(), null, null);
    }

    /** A registered web app. */
    public static LauncherEntry webApp(WebAppDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        // No resource description: a web app's own name is what describes it, and
        // the tile composes its accessible sentence from that name.
        return new LauncherEntry(Kind.WEB_APP, definition.getId().value(),
                0, definition.getDisplayName(), 0,
                0, definition.getIconUri(), definition);
    }

    public Kind getKind() {
        return kind;
    }

    /** Stable identity: {@link #ADD_APP_ID}, a curated id, or a web-app id. */
    public String getId() {
        return id;
    }

    /** String resource for the label, or {@code 0} when the label is user text. */
    public int getLabelRes() {
        return labelRes;
    }

    /** User-supplied label, or {@code null} when the label is a resource. */
    public String getLabel() {
        return label;
    }

    /**
     * Drawable resource for the bundled vector icon, or {@code 0} when the
     * entry's icon comes from elsewhere: a web app renders its own image,
     * favicon, or monogram instead of a bundled asset.
     */
    public int getIconRes() {
        return iconRes;
    }

    /** Accessible description resource for this entry. */
    public int getDescriptionRes() {
        return descriptionRes;
    }

    /** The web app's icon token, or {@code null} when it has none. */
    public String getIconUri() {
        return iconUri;
    }

    /** The registered definition, or {@code null} for a non-web-app entry. */
    public WebAppDefinition getWebApp() {
        return webApp;
    }

    /** True when tapping this entry can open a surface. */
    public boolean isOpenable() {
        return kind != Kind.ADD_APP;
    }

    /** True when this entry is a web app the user registered. */
    public boolean isWebApp() {
        return kind == Kind.WEB_APP;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LauncherEntry)) {
            return false;
        }
        LauncherEntry that = (LauncherEntry) other;
        return kind == that.kind && id.equals(that.id)
                && labelRes == that.labelRes && Objects.equals(label, that.label)
                && iconRes == that.iconRes
                && descriptionRes == that.descriptionRes
                && Objects.equals(iconUri, that.iconUri)
                && Objects.equals(webApp, that.webApp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, id, labelRes, label, iconRes,
                descriptionRes, iconUri, webApp);
    }

    @Override
    public String toString() {
        return "LauncherEntry{" + kind + " " + id + "}";
    }
}
