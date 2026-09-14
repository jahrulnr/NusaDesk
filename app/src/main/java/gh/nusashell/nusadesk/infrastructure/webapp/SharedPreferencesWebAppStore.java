package gh.nusashell.nusadesk.infrastructure.webapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.application.webapp.WebAppStore;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import java.util.List;
import java.util.Map;

/**
 * App-private persistence of the user-defined launcher web apps.
 *
 * <p>Each app owns a {@code <id>.<field>} key prefix in one private preferences
 * file, so a corrupt entry can only ever cost that one app. "Defensive" means:</p>
 *
 * <ul>
 *   <li>writes are synchronous ({@link SharedPreferences.Editor#commit()}), so a
 *       crash mid-save cannot leave a half-written record that a later read
 *       mistakes for a real app;</li>
 *   <li>reads re-validate every stored value through the domain constructor, and
 *       a record that cannot be read back as a complete definition is pruned from
 *       storage rather than surfaced as a launcher tile;</li>
 *   <li>deletion removes every key under the app's prefix, including keys this
 *       version no longer writes.</li>
 * </ul>
 *
 * <p>The preferences file is private to the app and holds no secrets: a display
 * name, an opaque {@code content://} icon token, a port, and timestamps. The
 * icon token is a reference, never image data.</p>
 */
public final class SharedPreferencesWebAppStore implements WebAppStore {
    private static final String PREFERENCES = "web_apps";

    private final SharedPreferences preferences;
    private final WebAppRecordCodec codec = new WebAppRecordCodec();

    public SharedPreferencesWebAppStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override
    public List<WebAppDefinition> loadAll() {
        WebAppRecordCodec.ParseResult parsed = codec.parseAll(preferences.getAll());
        for (String corruptId : parsed.getCorruptIds()) {
            deleteByPrefix(corruptId);
        }
        return parsed.getDefinitions();
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void save(WebAppDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        String prefix = WebAppRecordCodec.keyPrefix(definition.getId());
        Map<String, String> fields = codec.toFields(definition);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            // A null icon value removes the key, which reads back as "no icon".
            editor.putString(prefix + field.getKey(), field.getValue());
        }
        if (!editor.commit()) {
            throw new IllegalStateException(
                    "could not persist web app " + definition.getId());
        }
    }

    @Override
    public void delete(WebAppId webAppId) {
        if (webAppId == null) {
            throw new IllegalArgumentException("webAppId must not be null");
        }
        deleteByPrefix(webAppId.value());
    }

    @SuppressLint("ApplySharedPref")
    private void deleteByPrefix(String rawId) {
        String prefix = rawId + WebAppRecordCodec.SEPARATOR;
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
