package gh.nusashell.nusadesk.presentation.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The tile's icon priority is pure policy, so the promise "the user's image
 * always wins, then the app's favicon, then the bundled vector, then a
 * monogram" is asserted without a device.
 */
public class LauncherIconPolicyTest {

    private static final String USER_ICON = "content://media/external/images/1";
    /** Any non-zero resource id stands in for a bundled vector icon. */
    private static final int BUNDLED_ICON = 42;
    private static final int NO_BUNDLED_ICON = 0;

    @Test
    public void aUserImageAlwaysWinsEvenWhenAFaviconOrVectorExists() {
        assertEquals(LauncherIconPolicy.Source.USER_IMAGE,
                LauncherIconPolicy.preferred(USER_ICON, true, NO_BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.USER_IMAGE,
                LauncherIconPolicy.preferred(USER_ICON, false, NO_BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.USER_IMAGE,
                LauncherIconPolicy.preferred(USER_ICON, true, BUNDLED_ICON));
    }

    @Test
    public void withoutAUserImageAFaviconIsUsed() {
        assertEquals(LauncherIconPolicy.Source.FAVICON,
                LauncherIconPolicy.preferred(null, true, NO_BUNDLED_ICON));
        // A favicon still outranks a bundled vector.
        assertEquals(LauncherIconPolicy.Source.FAVICON,
                LauncherIconPolicy.preferred(null, true, BUNDLED_ICON));
    }

    @Test
    public void aBundledVectorStandsBetweenTheFaviconAndTheMonogram() {
        assertEquals(LauncherIconPolicy.Source.VECTOR,
                LauncherIconPolicy.preferred(null, false, BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.VECTOR,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.USER_IMAGE, false, BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.VECTOR,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.FAVICON, true, BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.VECTOR,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.FAVICON, false, BUNDLED_ICON));
    }

    @Test
    public void withNeitherSourceTheMonogramStands() {
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.preferred(null, false, NO_BUNDLED_ICON));
    }

    @Test
    public void anUnrenderableUserImageFallsThroughToTheFaviconThenTheMonogram() {
        assertEquals(LauncherIconPolicy.Source.FAVICON,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.USER_IMAGE, true, NO_BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.USER_IMAGE, false, NO_BUNDLED_ICON));
    }

    @Test
    public void theMonogramIsTheEndOfTheOrder() {
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.FAVICON, true, NO_BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.VECTOR, true, BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.MONOGRAM, true, NO_BUNDLED_ICON));
        assertEquals(LauncherIconPolicy.Source.MONOGRAM,
                LauncherIconPolicy.after(
                        LauncherIconPolicy.Source.MONOGRAM, false, BUNDLED_ICON));
    }

    @Test
    public void everySourceAlwaysEndsSomewhereRenderable() {
        LauncherIconPolicy.Source[] sources = LauncherIconPolicy.Source.values();

        for (LauncherIconPolicy.Source source : sources) {
            LauncherIconPolicy.Source next =
                    LauncherIconPolicy.after(source, true, BUNDLED_ICON);
            assertEquals("a source must never fall back to itself",
                    next == source, source == LauncherIconPolicy.Source.MONOGRAM);
        }
        assertEquals(4, sources.length);
    }
}
