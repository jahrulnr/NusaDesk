package gh.nusashell.nusadesk.infrastructure.homescreen;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * The unforgeable half of the pinned-shortcut contract (ADR-0077).
 *
 * <p>The shell's exported activity can be handed a composed intent, so the
 * pinned shortcut carries. The proof closes that gap: a random token lives
 * only in app-private storage, and the non-exported trampoline stamps it on
 * the intent it forwards. An outside caller never sees the value, so it can
 * never present a matching one.</p>
 */
public final class PinnedShortcutProof {

    private static final String PREFERENCES = "pinned_shortcut";
    private static final String KEY_TOKEN = "token";
    /** Extra the trampoline stamps on the intent it forwards to the shell. */
    static final String EXTRA_PROOF = "gh.nusashell.nusadesk.extra.PINNED_PROOF";

    private PinnedShortcutProof() {
    }

    /** The stored proof, creating it on first use. */
    public static String token(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        String token = preferences.getString(KEY_TOKEN, null);
        if (token == null) {
            token = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    /**
     * True when the intent carries the stamped proof — and consumes it, so a
     * re-delivered intent cannot dispatch twice. False for every intent an
     * outside app can compose, since the token never leaves private storage.
     */
    public static boolean matches(Context context, Intent intent) {
        if (intent == null) {
            return false;
        }
        String proof = intent.getStringExtra(EXTRA_PROOF);
        if (proof == null) {
            return false;
        }
        intent.removeExtra(EXTRA_PROOF);
        return token(context).equals(proof);
    }
}
