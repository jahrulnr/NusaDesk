package gh.nusashell.nusadesk.infrastructure.homescreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

import gh.nusashell.nusadesk.presentation.MainActivity;

/**
 * The unexported trampoline (ADR-0077): whatever the launcher delivers is
 * re-stamped with the unforgeable proof and forwarded to the shell — a
 * forged proof travelling with the extras is overwritten, never trusted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class PinnedShortcutActivityTest {

    @Test
    public void forwardsThePayloadStampedWithTheRealProof() {
        Intent source = new Intent(Intent.ACTION_VIEW)
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_KIND", "CURATED")
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_ID", "terminal")
                // A forged stamp travelling with the payload.
                .putExtra("gh.nusashell.nusadesk.extra.PINNED_PROOF", "forged");
        ActivityController<PinnedShortcutActivity> controller =
                Robolectric.buildActivity(PinnedShortcutActivity.class, source);

        Activity activity = controller.create().get();

        Intent forwarded = Shadow.<ShadowActivity>extract(activity)
                .getNextStartedActivity();
        assertNotNull("the trampoline always forwards to the shell", forwarded);
        assertEquals(MainActivity.class.getName(),
                forwarded.getComponent().getClassName());
        assertEquals("CURATED", forwarded.getStringExtra(
                "gh.nusashell.nusadesk.extra.PINNED_KIND"));
        assertEquals("terminal", forwarded.getStringExtra(
                "gh.nusashell.nusadesk.extra.PINNED_ID"));
        assertTrue("the stamp is the real proof, not the forged one",
                PinnedShortcutProof.matches(
                        RuntimeEnvironment.getApplication(), forwarded));
        assertTrue("the trampoline does not linger",
                activity.isFinishing() || activity.isDestroyed());
    }

    @Test
    public void finishesQuietlyOnAnEmptyIntent() {
        ActivityController<PinnedShortcutActivity> controller =
                Robolectric.buildActivity(PinnedShortcutActivity.class);

        Activity activity = controller.create().get();

        // Even with no payload the shell opens: a blank trampoline tap is
        // still a way in, and it must never crash.
        Intent forwarded = Shadow.<ShadowActivity>extract(activity)
                .getNextStartedActivity();
        assertNotNull(forwarded);
        assertTrue(PinnedShortcutProof.matches(
                RuntimeEnvironment.getApplication(), forwarded));
        assertTrue(activity.isFinishing() || activity.isDestroyed());
    }
}
