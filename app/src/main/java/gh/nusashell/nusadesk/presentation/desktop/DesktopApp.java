package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.presentation.DesktopDestination;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Linux surfaces the launcher ships itself.
 *
 * <p>Two entries, because two things are real: the terminal (the in-app shell)
 * and the Linux system screen (install state, technical detail, and the product
 * contract). Everything else in the launcher is either the "Add app" action or a
 * web app the user registered — the product does not fake a built-in desktop,
 * file browser, or app catalogue it cannot open.</p>
 *
 * <p>This is presentation vocabulary, not a runtime registry: nothing here
 * starts a process or claims a desktop that does not exist.</p>
 */
public enum DesktopApp {

    TERMINAL("terminal", R.string.app_terminal, R.drawable.ic_launcher_terminal,
            R.string.app_terminal_desc, DesktopDestination.TERMINAL),

    SYSTEM("system", R.string.app_system, R.drawable.ic_launcher_system,
            R.string.app_system_desc, DesktopDestination.SYSTEM);

    private final String id;
    private final int labelRes;
    private final int iconRes;
    private final int descriptionRes;
    private final DesktopDestination destination;

    DesktopApp(String id, int labelRes, int iconRes, int descriptionRes,
               DesktopDestination destination) {
        this.id = id;
        this.labelRes = labelRes;
        this.iconRes = iconRes;
        this.descriptionRes = descriptionRes;
        this.destination = destination;
    }

    /** Stable identifier of this surface. */
    public String getId() {
        return id;
    }

    public int getLabelRes() {
        return labelRes;
    }

    /**
     * Drawable resource for the tile icon. The icon is a bundled vector asset,
     * not a font glyph: a glyph's shape depends on the OEM system font and can
     * degrade to a missing-glyph square, while a packaged vector renders the
     * same on every device.
     */
    public int getIconRes() {
        return iconRes;
    }

    /** Accessible one-sentence description of what the app is. */
    public int getDescriptionRes() {
        return descriptionRes;
    }

    /** Destination this entry opens. */
    public DesktopDestination getDestination() {
        return destination;
    }

    /** The curated surfaces in launcher order. */
    public static List<DesktopApp> curated() {
        return Collections.unmodifiableList(Arrays.asList(values()));
    }

    /** Resolves a stable id, or {@code null} when it is not a curated surface. */
    public static DesktopApp fromId(String id) {
        if (id == null) {
            return null;
        }
        for (DesktopApp app : values()) {
            if (app.id.equals(id)) {
                return app;
            }
        }
        return null;
    }
}
