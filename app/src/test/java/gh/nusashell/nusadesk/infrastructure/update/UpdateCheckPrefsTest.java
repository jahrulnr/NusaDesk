package gh.nusashell.nusadesk.infrastructure.update;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Throttle and bookkeeping defaults for the update-check store (ADR-0038;
 * cadence amended by ADR-0046): due before the first check, due immediately
 * when the installed version changed since the last attempt or when a fresh
 * process asks, and otherwise due only after the 15 minute floor — for a
 * successful and a failed attempt alike. Tags round-trip with {@code null}
 * defaults and every value lives in the dedicated {@code "update_check"} file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class UpdateCheckPrefsTest {

    private static final long NOW = 1_758_000_000_000L; // 2026 epoch millis
    private static final String VERSION = "0.5.0";

    @Test
    public void freshStoreIsDueWithNoTags() {
        UpdateCheckPrefs prefs = prefs();

        assertTrue("the first check must always be due", prefs.isDue(NOW, VERSION, false));
        assertNull(prefs.lastSeenTag());
        assertNull(prefs.dismissedTag());
    }

    @Test
    public void aRecordedCheckIsNotDueAgainUntilTheIntervalPasses() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, "v0.4.0", VERSION);

        assertFalse(prefs.isDue(NOW, VERSION, false));
        assertFalse(prefs.isDue(NOW + UpdateCheckPrefs.MIN_INTERVAL_MILLIS - 1, VERSION, false));
        assertTrue(prefs.isDue(NOW + UpdateCheckPrefs.MIN_INTERVAL_MILLIS, VERSION, false));
        assertEquals("v0.4.0", prefs.lastSeenTag());
    }

    /**
     * The reported defect this rule answers: a launch after a reboot or a
     * force-stop answered with the stored banner and no check, because the
     * floor still held — so a fresh process now always asks once (ADR-0046
     * amendment, 2026-09-21).
     */
    @Test
    public void aFreshProcessIsDueEvenBeforeTheFloorIsOut() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, "v0.4.0", VERSION);

        assertTrue("a launch of a new process must ask, whatever the store says",
                prefs.isDue(NOW + 60_000L, VERSION, true));
        assertFalse("the same moment without the fresh-process rule stays throttled",
                prefs.isDue(NOW + 60_000L, VERSION, false));
    }

    /** The floor stays inside the 5-60 minute window the cadence promises. */
    @Test
    public void theFloorStaysInsideTheRequestedWindow() {
        assertTrue("a release must never wait longer than an hour to be seen",
                UpdateCheckPrefs.MIN_INTERVAL_MILLIS <= 60L * 60 * 1000);
        assertTrue("the floor must not turn the check into a per-open scan",
                UpdateCheckPrefs.MIN_INTERVAL_MILLIS >= 5L * 60 * 1000);
    }

    @Test
    public void aClockReadBeforeTheRecordIsNotDue() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, null, VERSION);

        assertFalse(prefs.isDue(NOW - 1, VERSION, false));
        assertNull("a failed check clears the seen tag", prefs.lastSeenTag());
    }

    /**
     * The regression this cadence exists for: a check that ran under a
     * different installed version — a sideloaded release the user just
     * installed — must run again immediately instead of waiting out the
     * floor, so the banner can never be stale after an update.
     */
    @Test
    public void aChangedInstalledVersionIsDueImmediately() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, null, "0.4.0");

        assertFalse(prefs.isDue(NOW, "0.4.0", false));
        assertTrue("an install from outside the app is a new question",
                prefs.isDue(NOW, "0.5.0", false));
    }

    /** A store written before the version key existed counts as a change. */
    @Test
    public void aStoreWithoutTheVersionKeyIsDueImmediately() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("update_check", Context.MODE_PRIVATE)
                .edit().putLong("last_check_at", NOW).apply();

        assertTrue(new UpdateCheckPrefs(context).isDue(NOW, VERSION, false));
    }

    /**
     * An inconclusive attempt (offline, GitHub's rate limit) records the
     * attempt but must be retried after the same short floor — never muted
     * for a day, which is the reported bug this change fixes.
     */
    @Test
    public void aFailedAttemptRetriesAfterTheSameFloor() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, null, VERSION);

        assertFalse(prefs.isDue(NOW + 60_000L, VERSION, false));
        assertTrue(prefs.isDue(NOW + UpdateCheckPrefs.MIN_INTERVAL_MILLIS, VERSION, false));
        assertTrue("the floor is measured in minutes, not a day",
                UpdateCheckPrefs.MIN_INTERVAL_MILLIS <= 60L * 60 * 1000);
    }

    /** An unknown installed version cannot prove a change, so it cannot skip the floor. */
    @Test
    public void anUnknownInstalledVersionFallsBackToTheFloor() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, null, VERSION);

        assertFalse(prefs.isDue(NOW + 60_000L, null, false));
        assertTrue(prefs.isDue(NOW + UpdateCheckPrefs.MIN_INTERVAL_MILLIS, null, false));
    }

    @Test
    public void theDismissedTagRoundTrips() {
        UpdateCheckPrefs prefs = prefs();
        prefs.setDismissed("v0.4.0");

        assertEquals("v0.4.0", prefs.dismissedTag());
    }

    @Test
    public void theAssetBookkeepingRoundTrips() {
        UpdateCheckPrefs prefs = prefs();

        assertNull("no asset before the first reported one", prefs.assetUrl());
        assertNull(prefs.assetDigest());
        assertEquals(-1L, prefs.assetSizeBytes());

        prefs.recordAsset(
                "https://github.com/jahrulnr/NusaDesk/releases/download/v0.4.0/NusaDesk-v0.4.0.apk",
                "sha256:063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c",
                17_405_756L);

        assertEquals("NusaDesk-v0.4.0.apk",
                prefs.assetUrl().substring(prefs.assetUrl().lastIndexOf('/') + 1));
        assertEquals("sha256:063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c",
                prefs.assetDigest());
        assertEquals(17_405_756L, prefs.assetSizeBytes());
    }

    @Test
    public void everythingLivesInTheDedicatedUpdateCheckFile() {
        Context context = RuntimeEnvironment.getApplication();
        new UpdateCheckPrefs(context).recordCheck(NOW, "v0.4.0", VERSION);
        new UpdateCheckPrefs(context).setDismissed("v0.4.0");
        new UpdateCheckPrefs(context).recordAsset(
                "https://github.com/jahrulnr/NusaDesk/releases/download/v0.4.0/NusaDesk-v0.4.0.apk",
                "sha256:063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c",
                17_405_756L);

        SharedPreferences file =
                context.getSharedPreferences("update_check", Context.MODE_PRIVATE);
        assertEquals(NOW, file.getLong("last_check_at", -1L));
        assertEquals(VERSION, file.getString("last_check_version", null));
        assertEquals("v0.4.0", file.getString("last_seen_tag", null));
        assertEquals("v0.4.0", file.getString("dismissed_tag", null));
        assertEquals("sha256:063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c",
                file.getString("asset_digest", null));
        assertEquals(17_405_756L, file.getLong("asset_size", -1L));
    }

    private static UpdateCheckPrefs prefs() {
        return new UpdateCheckPrefs(RuntimeEnvironment.getApplication());
    }
}
