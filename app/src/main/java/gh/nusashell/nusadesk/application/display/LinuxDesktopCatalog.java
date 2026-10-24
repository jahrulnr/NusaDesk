package gh.nusashell.nusadesk.application.display;

import java.util.List;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;

/** Refreshes installed GUI applications from the existing guest; no second Linux session. */
public interface LinuxDesktopCatalog {
    interface Listener { void onApps(List<LinuxDesktopApp> apps, String error); }
    /**
     * Requests a fresh scan. While the runtime is not running the request is
     * dropped without a callback; callers re-issue it on the next runtime-running signal.
     */
    void refresh(Listener listener);
    void close();
}
