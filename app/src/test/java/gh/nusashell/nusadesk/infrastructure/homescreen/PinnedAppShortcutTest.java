package gh.nusashell.nusadesk.infrastructure.homescreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowShortcutManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * The pinned-shortcut icon contract (ADR-0077): the source order a tile uses —
 * user image, favicon, bundled vector, monogram — and the launcher-badge
 * composition. {@code ShortcutManager} runs against Robolectric's shadow, so
 * the unsupported/supported pin paths are covered without a launcher.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PinnedAppShortcutTest {

    private final Context context = RuntimeEnvironment.getApplication();

    private static Bitmap red(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        return bitmap;
    }

    @Test
    public void theFaviconWinsOverTheBundledVector() {
        Bitmap favicon = red(32);

        Bitmap icon = PinnedAppShortcut.baseIcon(context, null, favicon,
                gh.nusashell.nusadesk.R.drawable.ic_launcher_terminal, "T");

        assertSame(favicon, icon);
    }

    @Test
    public void aBundledVectorRasterizesWhenNothingElseExists() {
        Bitmap icon = PinnedAppShortcut.baseIcon(context, null, null,
                gh.nusashell.nusadesk.R.drawable.ic_launcher_terminal, "T");

        assertNotNull(icon);
        assertTrue(icon.getWidth() > 0);
        assertEquals(icon.getWidth(), icon.getHeight());
        // A bundled vector paints somewhere — the glyph itself may leave the
        // exact centre transparent, so scan for any painted pixel.
        boolean painted = false;
        for (int y = 0; y < icon.getHeight() && !painted; y += 4) {
            for (int x = 0; x < icon.getWidth(); x += 4) {
                if (Color.alpha(icon.getPixel(x, y)) > 0) {
                    painted = true;
                    break;
                }
            }
        }
        assertTrue("rasterized vector must paint something", painted);
    }

    @Test
    public void theMonogramPaintsThePlateAndALetter() {
        Bitmap icon = PinnedAppShortcut.baseIcon(context, null, null, 0, "Notebook");

        assertNotNull(icon);
        int centre = icon.getPixel(icon.getWidth() / 2, icon.getHeight() / 2);
        assertTrue("monogram must paint a glyph at its centre",
                Color.alpha(centre) > 0);
    }

    @Test
    public void aReadableUserImageWinsOverEverything() throws Exception {
        File image = new File(context.getCacheDir(), "pinned-icon.png");
        FileOutputStream out = new FileOutputStream(image);
        red(8).compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();

        Bitmap icon = PinnedAppShortcut.baseIcon(context,
                image.getAbsolutePath(), null,
                gh.nusashell.nusadesk.R.drawable.ic_launcher_terminal, "T");

        assertNotNull(icon);
        assertEquals(8, icon.getWidth());
    }

    @Test
    public void anUnreadableUserImageFallsThroughToTheNextSource() {
        Bitmap favicon = red(32);

        Bitmap icon = PinnedAppShortcut.baseIcon(context,
                "/does/not/exist.png", favicon, 0, "T");

        assertSame("a stale image token must not mask the favicon", favicon, icon);
    }

    @Test
    public void theBadgedIconIsSquareAtLauncherSize() {
        Bitmap badged = PinnedAppShortcut.badgedIcon(context, red(64));

        assertNotNull(badged);
        assertEquals(badged.getWidth(), badged.getHeight());
        // The badge is painted: a pixel inside the white plate (but outside
        // the mark's inset) can no longer be the base bitmap's flat red.
        int size = badged.getWidth();
        float badge = size * 0.42f;
        int plate = badged.getPixel(Math.round(size - badge * 0.9f),
                Math.round(size - badge * 0.5f));
        assertEquals(Color.WHITE, plate);
    }

    @Test
    public void pinRefusesWhenTheLauncherCannotPin() {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(false);

        boolean pinned = PinnedAppShortcut.pin(context, "x", sources("X"),
                new Intent(Intent.ACTION_VIEW));

        assertFalse(pinned);
        assertFalse(PinnedAppShortcut.isSupported(context));
    }

    @Test
    public void pinReportsTheLaunchersAnswer() {
        // The shadow's support flag is static state, so a directly constructed
        // shadow flips what the framework-bound one reads (Shadows.shadowOf is
        // unusable here — it references FingerprintManager, which the mockable
        // android.jar does not contain).
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);

        boolean pinned = PinnedAppShortcut.pin(context, "y", sources("Yapp"),
                new Intent(Intent.ACTION_VIEW));

        assertTrue(pinned);
        assertTrue(PinnedAppShortcut.isSupported(context));
    }

    @Test
    public void reconcileDisablesThePinOfADeletedEntry() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        assertTrue(PinnedAppShortcut.pin(context, "gone", sources("Gone"),
                new Intent(Intent.ACTION_VIEW)));
        assertEquals(1, context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().size());

        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap());

        assertTrue("a deleted entry's pin is disabled, not deleted",
                disabledPins().containsKey("nusadesk-entry-gone"));
    }

    @Test
    public void reconcileReenablesThePinOfAReinstalledEntry() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        assertTrue(PinnedAppShortcut.pin(context, "back", sources("Back"),
                new Intent(Intent.ACTION_VIEW)));
        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap());
        assertTrue(disabledPins().containsKey("nusadesk-entry-back"));

        PinnedAppShortcut.reconcilePins(context,
                Collections.singletonMap("back", sources("Back")));

        assertTrue("a returning entry's pin is enabled again",
                activePins().containsKey("nusadesk-entry-back"));
    }

    @Test
    public void reconcileLeavesShortcutsItDidNotPinAlone() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        ShortcutInfo foreign = new ShortcutInfo.Builder(context, "foreign")
                .setShortLabel("Foreign")
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .build();
        context.getSystemService(ShortcutManager.class)
                .requestPinShortcut(foreign, null);

        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap());

        assertTrue("only nusadesk-entry-* pins are reconciled",
                activePins().containsKey("foreign"));
    }

    @Test
    public void reconcilePushesAnIconChangeToTheLauncher() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        assertTrue(PinnedAppShortcut.pin(context, "app", sources("Old Name"),
                new Intent(Intent.ACTION_VIEW)));

        // The favicon was re-fetched (a fresh bitmap) and the app renamed:
        // the fingerprint moves, so the launcher's copy is updated.
        PinnedAppShortcut.reconcilePins(context,
                Collections.singletonMap("app", sources("New Name")));

        ShortcutInfo pinned = context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().get(0);
        assertEquals("the pinned label follows the rename", "New Name",
                pinned.getShortLabel().toString());
    }

    @Test
    public void reconcileSkipsTheUpdateWhenSourcesDidNotChange() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        // The same bitmap instance across renders — as the favicon map holds —
        // keeps the generation id, and so the fingerprint, stable.
        Bitmap favicon = red(16);
        assertTrue(PinnedAppShortcut.pin(context, "app",
                new PinnedAppShortcut.EntryIcon(null, favicon, 0, "Same"),
                new Intent(Intent.ACTION_VIEW)));
        ShortcutInfo before = context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().get(0);

        PinnedAppShortcut.reconcilePins(context,
                Collections.singletonMap("app",
                        new PinnedAppShortcut.EntryIcon(null, favicon, 0, "Same")));

        assertSame("steady-state render must not re-push the icon",
                before, context.getSystemService(ShortcutManager.class)
                        .getPinnedShortcuts().get(0));
    }

    @Test
    public void constructingHomeDoesNotTreatUnloadedRegistrationsAsDeleted() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        PinnedAppShortcut.pin(context, "saved-app", sources("Saved"),
                new Intent(Intent.ACTION_VIEW));
        gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView home =
                new gh.nusashell.nusadesk.presentation.desktop.DesktopHomeView(context);
        assertTrue("construction precedes loading the registries",
                activePins().containsKey("nusadesk-entry-saved-app"));
        home.setApps(Collections.emptyList(), Collections.emptyList());
        assertTrue("an authoritative empty registry does disable it",
                disabledPins().containsKey("nusadesk-entry-saved-app"));
    }

    @Test
    public void pinUsesClearTopSoEachTapDeliversItsIntent() {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        PinnedAppShortcut.pin(context, "repeat", sources("Repeat"),
                new Intent(context, PinnedShortcutActivity.class).setAction(Intent.ACTION_VIEW));
        Intent intent = context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().get(0).getIntent();
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
    }

    @Test
    public void oldPinLaunchFlagsMigrateEvenBeforeLinuxCatalogLoads() {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        manager.requestPinShortcut(new ShortcutInfo.Builder(context, "nusadesk-entry-linux:legacy.desktop")
                .setShortLabel("Legacy")
                .setIntent(new Intent(context, PinnedShortcutActivity.class).setAction(Intent.ACTION_VIEW))
                .build(), null);
        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap(), false);
        Intent intent = manager.getPinnedShortcuts().get(0).getIntent();
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
    }

    @Test
    public void unloadedLinuxCatalogDoesNotDisableItsPins() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        PinnedAppShortcut.pin(context, "linux:mousepad.desktop", sources("Mousepad"),
                new Intent(Intent.ACTION_VIEW));
        PinnedAppShortcut.pin(context, "deleted-web", sources("Deleted"),
                new Intent(Intent.ACTION_VIEW));

        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap(), false);

        assertTrue(activePins().containsKey("nusadesk-entry-linux:mousepad.desktop"));
        assertTrue(disabledPins().containsKey("nusadesk-entry-deleted-web"));
        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap(), true);
        assertTrue(disabledPins().containsKey("nusadesk-entry-linux:mousepad.desktop"));
    }

    @Test
    public void revivedPinReceivesCurrentLabelInTheSameReconciliation() {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        PinnedAppShortcut.pin(context, "back", sources("Old label"),
                new Intent(Intent.ACTION_VIEW));
        PinnedAppShortcut.reconcilePins(context, Collections.emptyMap());

        PinnedAppShortcut.reconcilePins(context,
                Collections.singletonMap("back", sources("New label")));

        assertEquals("New label", context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().get(0).getShortLabel().toString());
    }

    @Test
    public void replacingAnIconAtTheSameFilePathRefreshesItsPin() throws Exception {
        new ShadowShortcutManager().setIsRequestPinShortcutSupported(true);
        File image = new File(context.getCacheDir(), "replace-icon.png");
        try (FileOutputStream out = new FileOutputStream(image)) {
            red(8).compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        PinnedAppShortcut.EntryIcon source = new PinnedAppShortcut.EntryIcon(
                image.toURI().toString(), null, 0, "Same");
        PinnedAppShortcut.pin(context, "image", source, new Intent(Intent.ACTION_VIEW));
        ShortcutInfo before = context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().get(0);
        long oldTime = image.lastModified();
        try (FileOutputStream out = new FileOutputStream(image)) {
            red(32).compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        assertTrue(image.setLastModified(oldTime + 2000));

        PinnedAppShortcut.reconcilePins(context, Collections.singletonMap("image", source));

        assertNotSame(before, context.getSystemService(ShortcutManager.class)
                .getPinnedShortcuts().get(0));
    }

    @Test
    public void extremelyWideFileIsSampledByItsLongestDimension() throws Exception {
        File image = new File(context.getCacheDir(), "wide-icon.png");
        try (FileOutputStream out = new FileOutputStream(image)) {
            redWide().compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        Bitmap icon = PinnedAppShortcut.baseIcon(context, image.getAbsolutePath(),
                null, 0, "Wide");
        assertTrue("a short side must not keep the long side at full resolution",
                icon.getWidth() < 1024);
    }

    private static Bitmap redWide() {
        Bitmap bitmap = Bitmap.createBitmap(8192, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        return bitmap;
    }

    private static PinnedAppShortcut.EntryIcon sources(String label) {
        return new PinnedAppShortcut.EntryIcon(null, red(16), 0, label);
    }

    @Test
    public void theProofRoundTripsAndConsumesItself() {
        Intent intent = new Intent(Intent.ACTION_VIEW).putExtra(
                "gh.nusashell.nusadesk.extra.PINNED_PROOF",
                PinnedShortcutProof.token(context));

        assertTrue(PinnedShortcutProof.matches(context, intent));
        assertFalse("a consumed proof never matches twice",
                PinnedShortcutProof.matches(context, intent));
    }

    @Test
    public void theProofRejectsStrangers() {
        assertFalse(PinnedShortcutProof.matches(context, null));
        assertFalse(PinnedShortcutProof.matches(context,
                new Intent(Intent.ACTION_VIEW)));
        assertFalse(PinnedShortcutProof.matches(context,
                new Intent(Intent.ACTION_VIEW).putExtra(
                        "gh.nusashell.nusadesk.extra.PINNED_PROOF", "guess")));
    }

    /**
     * The shadow keeps enabled/disabled pins in two static maps rather than
     * flipping {@code ShortcutInfo.isEnabled()}, so reconcile assertions read
     * the maps directly. White-box, but stable across the shadow's API.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, ShortcutInfo> shadowMap(String fieldName)
            throws Exception {
        Field field = ShadowShortcutManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, ShortcutInfo>) field.get(null);
    }

    private static Map<String, ShortcutInfo> activePins() throws Exception {
        return shadowMap("activePinnedShortcuts");
    }

    private static Map<String, ShortcutInfo> disabledPins() throws Exception {
        return shadowMap("disabledPinnedShortcuts");
    }
}
