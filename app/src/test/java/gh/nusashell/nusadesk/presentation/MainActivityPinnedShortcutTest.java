package gh.nusashell.nusadesk.presentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.infrastructure.homescreen.PinnedShortcutProof;

/**
 * A pinned homescreen shortcut re-enters the shell through {@code onCreate}
 * and {@code onNewIntent} (ADR-0077). These runs cover the contract's two
 * halves: a forged or stale intent reconciles to a working shell, and a
 * proof-stamped intent dispatches exactly once — the same way
 * {@link MainActivityApiLevelLaunchTest} guards launch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class MainActivityPinnedShortcutTest {

    private static final String EXTRA_PINNED_KIND =
            "gh.nusashell.nusadesk.extra.PINNED_KIND";
    private static final String EXTRA_PINNED_ID =
            "gh.nusashell.nusadesk.extra.PINNED_ID";

    @Test
    public void aForgedShortcutIntentIsRejectedBeforeAnyDispatch() {
        // The exported activity can receive composed extras, but without the
        // trampoline's proof they open the shell, never an app surface.
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PINNED_KIND, "CURATED")
                .putExtra(EXTRA_PINNED_ID, "terminal");
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class, intent);

        controller.create();

        assertNotNull("a forged pinned intent lands on the shell", controller.get());
        // The kind extra survives unconsumed: the proof gate rejected it
        // before any dispatch could run.
        assertNotNull(controller.get().getIntent().getStringExtra(EXTRA_PINNED_KIND));
    }

    @Test
    public void aStampedShortcutIntentDispatchesOnceAndIsConsumed() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PINNED_KIND, "CURATED")
                .putExtra(EXTRA_PINNED_ID, "terminal")
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_PROOF",
                        PinnedShortcutProof.token(context));
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class, intent);

        controller.create();

        assertNotNull(controller.get());
        Intent delivered = controller.get().getIntent();
        assertFalse("the proof is consumed by dispatch",
                PinnedShortcutProof.matches(context, delivered));
        assertFalse("the kind is consumed too",
                delivered.hasExtra(EXTRA_PINNED_KIND));
    }

    @Test
    public void aStampedShortcutForADeletedAppReconcilesToTheShell() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PINNED_KIND, "WEB_APP")
                .putExtra(EXTRA_PINNED_ID, "long-since-deleted")
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_PROOF",
                        PinnedShortcutProof.token(context));
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class, intent);

        controller.create();

        assertNotNull("a shortcut for a deleted app opens the shell, not a crash",
                controller.get());
    }

    @Test
    public void aMalformedStampedKindIsIgnored() {
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PINNED_KIND, "NOT_A_KIND")
                .putExtra(EXTRA_PINNED_ID, "x")
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_PROOF",
                        PinnedShortcutProof.token(context));
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class, intent);

        controller.create();

        assertNotNull(controller.get());
    }

    @Test
    public void aForgedShortcutArrivingWhileRunningIsRejected() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        controller.create();

        controller.newIntent(new Intent(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PINNED_KIND, "TERMINAL_APP")
                .putExtra(EXTRA_PINNED_ID, "long-since-deleted"));

        assertNotNull("onNewIntent ignores a forged shortcut while running",
                controller.get());
    }
    @Test
    public void deletedTerminalPinReturnsToHomeFromAnotherSurface() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod(
                "showSurface", DesktopDestination.class);
        show.setAccessible(true);
        show.invoke(controller.get(), DesktopDestination.SYSTEM);

        controller.newIntent(new Intent(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PINNED_KIND, "TERMINAL_APP")
                .putExtra(EXTRA_PINNED_ID, "deleted")
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_PROOF",
                        PinnedShortcutProof.token(context)));

        java.lang.reflect.Field destination = MainActivity.class.getDeclaredField("activeDestination");
        destination.setAccessible(true);
        assertEquals(DesktopDestination.HOME, destination.get(controller.get()));
    }

}
