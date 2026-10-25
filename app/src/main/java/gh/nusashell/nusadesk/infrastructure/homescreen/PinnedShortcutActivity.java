package gh.nusashell.nusadesk.infrastructure.homescreen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import gh.nusashell.nusadesk.presentation.MainActivity;

/**
 * Non-exported trampoline every pinned homescreen shortcut lands on
 * (ADR-0077).
 *
 * <p>A launcher launches a pinned shortcut through the system's shortcut
 * path, which may start unexported activities; an outside app cannot reach
 * this activity directly. The trampoline stamps the unforgeable
 * {@link PinnedShortcutProof} on the forwarded intent, so an
 * {@code ACTION_VIEW} composed straight at the exported shell still fails
 * the proof check. It finishes immediately: recents never hold a second
 * shell entry.</p>
 */
public final class PinnedShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent forward = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW);
            Intent source = getIntent();
            if (source != null && source.getExtras() != null) {
                forward.putExtras(source.getExtras());
            }
            // Stamped last: a forged proof carried by the copied extras is
            // always overwritten with the real one.
            forward.putExtra(PinnedShortcutProof.EXTRA_PROOF,
                    PinnedShortcutProof.token(this));
            startActivity(forward);
        } catch (RuntimeException malformed) {
            // A hostile or malformed bundle settles quietly.
        } finally {
            finish();
        }
    }
}
