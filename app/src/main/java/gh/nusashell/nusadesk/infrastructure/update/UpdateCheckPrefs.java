package gh.nusashell.nusadesk.infrastructure.update;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted bookkeeping for the foreground update check (ADR-0038; cadence
 * amended by ADR-0046).
 *
 * <p>Uses its own {@code "update_check"} preferences file — never a shared
 * store — so the throttle cannot be corrupted by or leak into session or
 * workspace state. Five keys: {@code last_check_at} (epoch millis of the
 * last attempt, throttling the network call), {@code last_check_version}
 * (the installed {@code versionName} that attempt ran under),
 * {@code last_seen_tag} (the newest tag a check reported, for diagnostics
 * and the banner), {@code dismissed_tag} (the tag whose banner the user
 * dismissed), and the reported asset triple.</p>
 *
 * <p>Writes use {@code apply()}: losing a throttle timestamp or a dismissal
 * on process death only re-runs a harmless check, so the durability cost of
 * {@code commit()} is not warranted here.</p>
 */
public final class UpdateCheckPrefs {

    /**
     * Minimum spacing between two network checks: 30 minutes.
     *
     * <p>One floor for every outcome (ADR-0046): a release is discovered
     * within one floor of the next app open, and an inconclusive attempt —
     * offline, GitHub's rate limit, a malformed body — is retried after the
     * same floor instead of muting the feature for a day. The call is one
     * bounded GET on a fixed endpoint, fired only from a foreground event, so
     * the tightened floor stays far inside GitHub's unauthenticated budget.</p>
     */
    public static final long MIN_INTERVAL_MILLIS = 30L * 60 * 1000;

    private static final String PREFERENCES = "update_check";
    private static final String KEY_LAST_CHECK_AT = "last_check_at";
    private static final String KEY_LAST_CHECK_VERSION = "last_check_version";
    private static final String KEY_LAST_SEEN_TAG = "last_seen_tag";
    private static final String KEY_DISMISSED_TAG = "dismissed_tag";
    private static final String KEY_ASSET_URL = "asset_url";
    private static final String KEY_ASSET_DIGEST = "asset_digest";
    private static final String KEY_ASSET_SIZE = "asset_size";

    private final SharedPreferences preferences;

    public UpdateCheckPrefs(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * True when a check may run: always before the first recorded check,
     * whenever the installed version differs from the one the last check ran
     * under — a sideloaded or assisted install is a new question, and a store
     * written before this key existed counts as different — and otherwise
     * only once the last attempt is at least {@link #MIN_INTERVAL_MILLIS}
     * old. A clock reading before the recorded timestamp is simply not due.
     *
     * @param installedVersionName the installed app's {@code versionName};
     *                             {@code null} cannot prove a change, so it
     *                             falls back to the time floor alone
     */
    public boolean isDue(long now, String installedVersionName) {
        if (!preferences.contains(KEY_LAST_CHECK_AT)) {
            return true;
        }
        String checkedVersion = preferences.getString(KEY_LAST_CHECK_VERSION, null);
        if (installedVersionName != null && !installedVersionName.equals(checkedVersion)) {
            return true;
        }
        return now - preferences.getLong(KEY_LAST_CHECK_AT, 0L) >= MIN_INTERVAL_MILLIS;
    }

    /**
     * Records a completed check attempt — whatever its outcome — the tag it
     * reported ({@code null} for up-to-date or unavailable results), and the
     * installed {@code versionName} it ran under.
     */
    public void recordCheck(long now, String tag, String installedVersionName) {
        preferences.edit()
                .putLong(KEY_LAST_CHECK_AT, now)
                .putString(KEY_LAST_SEEN_TAG, tag)
                .putString(KEY_LAST_CHECK_VERSION, installedVersionName)
                .apply();
    }

    /** The newest tag a check has reported, or {@code null}. */
    public String lastSeenTag() {
        return preferences.getString(KEY_LAST_SEEN_TAG, null);
    }

    /**
     * Records the release asset behind the reported tag: the download URL,
     * the channel-computed {@code sha256:<hex>} digest, and the size in
     * bytes. The assisted install flow reads these after a restart, which is
     * how a cached-but-cancelled install can retry without downloading.
     */
    public void recordAsset(String url, String digest, long sizeBytes) {
        preferences.edit()
                .putString(KEY_ASSET_URL, url)
                .putString(KEY_ASSET_DIGEST, digest)
                .putLong(KEY_ASSET_SIZE, sizeBytes)
                .apply();
    }

    /** The release asset's download URL, or {@code null} when none was reported. */
    public String assetUrl() {
        return preferences.getString(KEY_ASSET_URL, null);
    }

    /** The recorded {@code sha256:<hex>} asset digest, or {@code null}. */
    public String assetDigest() {
        return preferences.getString(KEY_ASSET_DIGEST, null);
    }

    /** The recorded asset size in bytes, or {@code -1} when unknown. */
    public long assetSizeBytes() {
        return preferences.getLong(KEY_ASSET_SIZE, -1L);
    }

    /** Remembers the tag whose update banner the user dismissed. */
    public void setDismissed(String tag) {
        preferences.edit().putString(KEY_DISMISSED_TAG, tag).apply();
    }

    /** The dismissed tag, or {@code null} when the user dismissed nothing. */
    public String dismissedTag() {
        return preferences.getString(KEY_DISMISSED_TAG, null);
    }
}
