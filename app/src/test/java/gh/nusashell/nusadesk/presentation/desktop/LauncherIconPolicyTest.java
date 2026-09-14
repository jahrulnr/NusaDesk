package gh.nusashell.nusadesk.presentation.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The tile's icon priority is pure policy, so the promise "the user's image
 * always wins, then the app's favicon, then a monogram" is asserted without a
 * device.
 */
public class LauncherIconPolicyTest {

    private static final String USER_ICON = "content://media/external/images/1";

    @Test
    public void aUserImageAlwaysWinsEvenWhenAFaviconExists() {
        assertEquals(LauncherIconPolicy.Source.USER_IMAGE,
                LauncherIconPolicy.preferred(USER_ICON, true));
        assertEquals(LauncherIconPolicy.Source.USER_IMAGE,
                LauncherIconPolicy.preferred(USER_ICON, false));
    }

    @Test
    public void withoutAUserImageAFaviconIsUsed() {
        assertEquals(LauncherIconPolicy.Source.FAVICON,
                LauncherIconPolicy.preferred(null, true));
    }

    @Test
    public void withNeitherSourceTheMonogramStands() {
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.preferred(null, false));
    }

    @Test
    public void anUnrenderableUserImageFallsThroughToTheFaviconThenTheMonogram() {
        assertEquals(LauncherIconPolicy.Source.FAVICON,
                LauncherIconPolicy.after(LauncherIconPolicy.Source.USER_IMAGE, true));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(LauncherIconPolicy.Source.USER_IMAGE, false));
    }

    @Test
    public void theMonogramIsTheEndOfTheOrder() {
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(LauncherIconPolicy.Source.FAVICON, true));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(LauncherIconPolicy.Source.MONOGRAM, true));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(LauncherIconPolicy.Source.MONOGRAM, false));
    }

    @Test
    public void everySourceAlwaysEndsSomewhereRenderable() {
        LauncherIconPolicy.Source[] sources = LauncherIconPolicy.Source.values();

        for (LauncherIconPolicy.Source source : sources) {
            LauncherIconPolicy.Source next = LauncherIconPolicy.after(source, true);
            assertEquals("a source must never fall back to itself",
                    next == source, source == LauncherIconPolicy.Source.MONOGRAM);
        }
        assertEquals(3, sources.length);
    }
}
