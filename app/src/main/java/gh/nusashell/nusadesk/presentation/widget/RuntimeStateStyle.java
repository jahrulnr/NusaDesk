package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Shared presentation mapping from a runtime state to semantic color resources.
 * This is presentation vocabulary only; it never changes the domain state machine.
 */
public final class RuntimeStateStyle {
    private RuntimeStateStyle() {
    }

    /** Background tint resource for a state badge or surface. */
    public static int backgroundColor(RuntimeState state) {
        switch (state) {
            case NOT_INSTALLED:
                return R.color.info_tint;
            case DOWNLOADING:
            case VERIFYING:
            case EXTRACTING:
            case STARTING:
            case STOPPING:
            case RECOVERING:
                return R.color.warning_tint;
            case READY:
            case RUNNING:
                return R.color.success_tint;
            case STOPPED:
                return R.color.surface_subtle;
            case FAILED:
                return R.color.danger_tint;
            default:
                throw new AssertionError("Unhandled runtime state: " + state);
        }
    }

    /** Foreground (text/icon) color resource for a state badge. */
    public static int foregroundColor(RuntimeState state) {
        switch (state) {
            case NOT_INSTALLED:
                return R.color.info;
            case DOWNLOADING:
            case VERIFYING:
            case EXTRACTING:
            case STARTING:
            case STOPPING:
            case RECOVERING:
                return R.color.warning;
            case READY:
            case RUNNING:
                return R.color.success;
            case STOPPED:
                return R.color.ink_secondary;
            case FAILED:
                return R.color.danger;
            default:
                throw new AssertionError("Unhandled runtime state: " + state);
        }
    }
}
