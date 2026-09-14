package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.R;

/**
 * Top-level destinations of the shell.
 *
 * <p>{@link #HOME} is the launcher and the product's default surface. The other
 * destinations are Linux surfaces: the terminal, the Linux system screen, and
 * the add-web-app form. User-defined web apps are not destinations in this
 * enum — there is one per registered app, so the shell keys those surfaces by
 * their persisted web-app id instead.</p>
 *
 * <p>No destination owns the Linux session lifecycle: Linux is background
 * infrastructure that starts from an Activity foreground event (ADR-0013).</p>
 */
public enum DesktopDestination {
    HOME(R.string.launcher_title),
    TERMINAL(R.string.app_terminal),
    SYSTEM(R.string.app_system),
    ADD_WEB_APP(R.string.webapp_add_title);

    private final int titleRes;

    DesktopDestination(int titleRes) {
        this.titleRes = titleRes;
    }

    /** Title shown in the app surface task bar. */
    public int getTitleRes() {
        return titleRes;
    }

    /** True when the destination is a Linux surface rather than the launcher. */
    public boolean isApp() {
        return this != HOME;
    }

    /**
     * True when the destination is shown with the shared task bar. Everything
     * except the launcher is, so there is exactly one way back to the launcher
     * and no destination can strand the user.
     */
    public boolean usesTaskBar() {
        return this != HOME;
    }
}
