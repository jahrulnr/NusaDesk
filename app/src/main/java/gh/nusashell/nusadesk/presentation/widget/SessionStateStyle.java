package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.presentation.SessionUiState;

/**
 * Shared presentation mapping from a session state to semantic colour
 * resources. Presentation vocabulary only: it never changes the host session
 * state machine, and every consumer also renders the state as text so the
 * meaning is never carried by colour alone.
 */
public final class SessionStateStyle {

    private SessionStateStyle() {
    }

    /** Background tint resource for a session badge. */
    public static int backgroundColor(SessionUiState.Kind kind) {
        switch (kind) {
            case RUNNING:
                return R.color.success_tint;
            case STARTING:
            case STOPPING:
                return R.color.warning_tint;
            case FAILED:
                return R.color.danger_tint;
            default:
                return R.color.surface_subtle;
        }
    }

    /** Foreground (text) colour resource for a session badge. */
    public static int foregroundColor(SessionUiState.Kind kind) {
        switch (kind) {
            case RUNNING:
                return R.color.success;
            case STARTING:
            case STOPPING:
                return R.color.warning;
            case FAILED:
                return R.color.danger;
            default:
                return R.color.ink_secondary;
        }
    }
}
