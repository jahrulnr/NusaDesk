package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

/**
 * Regression for editing a combined app back to web-only: re-saving a record
 * must erase the stored {@code startupCommand} key, or the next load would
 * resurrect a command the user already cleared.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SharedPreferencesWebAppStoreTest {

    private static SharedPreferencesWebAppStore store() {
        return new SharedPreferencesWebAppStore(RuntimeEnvironment.getApplication());
    }

    @Test
    public void savingWithoutAStartupCommandDropsTheStoredOne() {
        SharedPreferencesWebAppStore store = store();
        WebAppId id = WebAppId.of("app-1");
        store.save(new WebAppDefinition(id, "Server", null, 4050,
                1_000L, 1_000L, 0, "python3 -m http.server 4050"));

        store.save(new WebAppDefinition(id, "Server", null, 4050,
                1_000L, 2_000L, 0, null));

        List<WebAppDefinition> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertFalse("an edit to web-only must not resurrect the cleared command",
                loaded.get(0).hasStartupCommand());
    }

    @Test
    public void aStartupCommandRoundTripsWhileItExists() {
        SharedPreferencesWebAppStore store = store();
        store.save(new WebAppDefinition(WebAppId.of("app-2"), "Server", null, 4050,
                1_000L, 1_000L, 0, "python3 -m http.server 4050"));

        List<WebAppDefinition> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("python3 -m http.server 4050",
                loaded.get(0).getStartupCommand().value());
    }
}
