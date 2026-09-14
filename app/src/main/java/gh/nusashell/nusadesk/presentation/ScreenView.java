package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;

/**
 * A shell screen that renders the current runtime snapshot. The host owns the
 * snapshot and navigation; screens only translate state into accessible UI.
 */
public interface ScreenView {
    void render(RuntimeSnapshot snapshot);
}
