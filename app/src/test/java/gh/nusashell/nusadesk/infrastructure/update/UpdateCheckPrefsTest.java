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
 * Throttle and bookkeeping defaults for the update-check store (ADR-0038):
 * due before the first check and again only after the 24 h interval, tags
 * round-trip with {@code null} defaults, and every value lives in the
 * dedicated {@code "update_check"} file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class UpdateCheckPrefsTest {

    private static final long NOW = 1_758_000_000_000L; // 2026 epoch millis

    @Test
    public void freshStoreIsDueWithNoTags() {
        UpdateCheckPrefs prefs = prefs();

        assertTrue("the first check must always be due", prefs.isDue(NOW));
        assertNull(prefs.lastSeenTag());
        assertNull(prefs.dismissedTag());
    }

    @Test
    public void aRecordedCheckIsNotDueAgainUntilTheIntervalPasses() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, "v0.4.0");

        assertFalse(prefs.isDue(NOW));
        assertFalse(prefs.isDue(NOW + UpdateCheckPrefs.MIN_INTERVAL_MILLIS - 1));
        assertTrue(prefs.isDue(NOW + UpdateCheckPrefs.MIN_INTERVAL_MILLIS));
        assertEquals("v0.4.0", prefs.lastSeenTag());
    }

    @Test
    public void aClockReadBeforeTheRecordIsNotDue() {
        UpdateCheckPrefs prefs = prefs();
        prefs.recordCheck(NOW, null);

        assertFalse(prefs.isDue(NOW - 1));
        assertNull("a failed check clears the seen tag", prefs.lastSeenTag());
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
        new UpdateCheckPrefs(context).recordCheck(NOW, "v0.4.0");
        new UpdateCheckPrefs(context).setDismissed("v0.4.0");
        new UpdateCheckPrefs(context).recordAsset(
                "https://github.com/jahrulnr/NusaDesk/releases/download/v0.4.0/NusaDesk-v0.4.0.apk",
                "sha256:063758633392fcebc07b5f197db978708a88bf377398a3e660bdad0b29b8375c",
                17_405_756L);

        SharedPreferences file =
                context.getSharedPreferences("update_check", Context.MODE_PRIVATE);
        assertEquals(NOW, file.getLong("last_check_at", -1L));
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
