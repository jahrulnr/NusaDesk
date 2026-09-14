package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression guard for the launcher header's count wording, found on the
 * {@code id-ID} device: a one-app grid read "1 apps" because a quantity string
 * resolves through the locale's CLDR plural category, and locales with no plural
 * marking only have the {@code other} category.
 */
public class LauncherHeaderTextTest {

    @Test
    public void oneAppIsStatedInTheSingularOnEveryLocale() {
        assertEquals(R.string.apps_count_singular, LauncherHeaderText.countRes(1));
    }

    @Test
    public void everyOtherCountKeepsThePluralWording() {
        for (int count : new int[]{0, 2, 3, 5, 11, 21}) {
            assertEquals("count " + count,
                    R.string.apps_count_plural, LauncherHeaderText.countRes(count));
        }
    }
}
