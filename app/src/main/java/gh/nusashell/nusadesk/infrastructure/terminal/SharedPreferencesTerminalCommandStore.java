package gh.nusashell.nusadesk.infrastructure.terminal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.application.terminal.TerminalCommandStore;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;

import java.util.List;
import java.util.Map;

/**
 * App-private persistence of the user-defined launcher terminal-command apps.
 *
 * <p>Each app owns a {@code <id>.<field>} key prefix in one private preferences
 * file, so a corrupt entry can only ever cost that one app. "Defensive" means:</p>
 *
 * <ul>
 *   <li>writes are synchronous ({@link SharedPreferences.Editor#commit()}), so a
 *       crash mid-save cannot leave a half-written record that a later read
 *       mistakes for a real app;</li>
 *   <li>reads re-validate every stored value through the domain constructor, and
 *       a record that cannot be read back as a complete app is pruned from
 *       storage rather than surfaced as a launcher tile;</li>
 *   <li>deletion removes every key under the app's prefix, including keys this
 *       version no longer writes.</li>
 * </ul>
 *
 * <p>The preferences file is private to the app and holds no host-side secrets:
 * a display name, an opaque {@code content://} icon token, a user-authored
 * command line, and timestamps. The icon token is a reference, never image
 * data; the command is stored verbatim and is never executed by the host.</p>
 */
public final class SharedPreferencesTerminalCommandStore implements TerminalCommandStore {
    private static final String PREFERENCES = "terminal_apps";

    private final SharedPreferences preferences;
    private final TerminalCommandRecordCodec codec = new TerminalCommandRecordCodec();

    public SharedPreferencesTerminalCommandStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override
    public List<TerminalCommandApp> loadAll() {
        TerminalCommandRecordCodec.ParseResult parsed = codec.parseAll(preferences.getAll());
        for (String corruptId : parsed.getCorruptIds()) {
            deleteByPrefix(corruptId);
        }
        return parsed.getApps();
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void save(TerminalCommandApp app) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        String prefix = TerminalCommandRecordCodec.keyPrefix(app.getId());
        Map<String, String> fields = codec.toFields(app);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            // A null icon value removes the key, which reads back as "no icon".
            editor.putString(prefix + field.getKey(), field.getValue());
        }
        if (!editor.commit()) {
            throw new IllegalStateException(
                    "could not persist terminal-command app " + app.getId());
        }
    }

    @Override
    public void delete(TerminalCommandAppId appId) {
        if (appId == null) {
            throw new IllegalArgumentException("appId must not be null");
        }
        deleteByPrefix(appId.value());
    }

    @SuppressLint("ApplySharedPref")
    private void deleteByPrefix(String rawId) {
        String prefix = rawId + TerminalCommandRecordCodec.SEPARATOR;
        SharedPreferences.Editor editor = preferences.edit();
        boolean removed = false;
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
                removed = true;
            }
        }
        if (removed) {
            editor.commit();
        }
    }
}
