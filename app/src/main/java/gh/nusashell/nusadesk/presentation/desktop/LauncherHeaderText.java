package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;

/**
 * Wording for the launcher header's app count.
 *
 * <p>The count uses an explicit singular/plural pair instead of a quantity
 * string. The launcher ships one default copy, and the platform picks a plural
 * category from the device locale: locales with no plural marking (Indonesian,
 * Chinese, Japanese, Korean) have only the {@code other} category, so a
 * quantity string renders "1 apps" on an {@code id-ID} device. Choosing the
 * form from the count states one app in the singular everywhere, while every
 * other count keeps the plural the launcher already used.</p>
 */
final class LauncherHeaderText {

    private LauncherHeaderText() {
    }

    /** The count wording for a grid holding {@code count} apps. */
    static int countRes(int count) {
        return count == 1 ? R.string.apps_count_singular : R.string.apps_count_plural;
    }
}
