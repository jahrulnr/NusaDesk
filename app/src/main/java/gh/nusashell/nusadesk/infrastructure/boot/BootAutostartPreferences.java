package gh.nusashell.nusadesk.infrastructure.boot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The single persisted source of the user's "Start Linux at boot" opt-in
 * (ADR-0037).
 *
 * <p>The toggle is wired by the coordinator on the Linux system screen; this
 * class only owns where the answer lives: its own {@code boot_autostart}
 * SharedPreferences file, unrelated to the runtime/session stores. The
 * default is {@code false} — a stock install never wakes at boot — and there
 * is deliberately no other way to set it, so a written {@code true} is always
 * the user's own choice.</p>
 */
public final class BootAutostartPreferences {

    private static final String PREFERENCES = "boot_autostart";
    private static final String KEY_ENABLED = "enabled";

    private final SharedPreferences preferences;

    public BootAutostartPreferences(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Whether the user opted in to "Start Linux at boot". Defaults to {@code false}. */
    public boolean enabled() {
        return preferences.getBoolean(KEY_ENABLED, false);
    }

    /** Persist the user's choice. Called only from the system-screen toggle. */
    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
}
