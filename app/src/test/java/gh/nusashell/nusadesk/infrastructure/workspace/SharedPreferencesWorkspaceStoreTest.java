package gh.nusashell.nusadesk.infrastructure.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;

/**
 * The stored workspace round-trips through SharedPreferences, including the
 * synthetic id the in-app browser returns (ADR-0047). Regression: a stored pick
 * used to be unreadable because {@code restore} only understood tree document
 * ids, so the card fell back to the app folder after every reload.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SharedPreferencesWorkspaceStoreTest {

    private static SharedPreferencesWorkspaceStore store() {
        return new SharedPreferencesWorkspaceStore(RuntimeEnvironment.getApplication());
    }

    @Test
    public void aPickedPathRoundTrips() {
        SharedPreferencesWorkspaceStore store = store();
        WorkspaceFolder picked = WorkspaceFolder.ofHostPath(WorkspaceFolder.PICKED_PATH_ID,
                "/storage/emulated/0/Android/media/pkg/nusadesk/projects", "projects");

        store.save(picked);

        WorkspaceFolder loaded = store.load();
        assertNotNull("a path the browser returned must stay readable", loaded);
        assertEquals(WorkspaceFolder.PICKED_PATH_ID, loaded.getTreeDocumentId());
        assertEquals(picked.getHostPath(), loaded.getHostPath());
        assertEquals("projects", loaded.getDisplayName());
    }

    @Test
    public void aTreeDocumentIdStillRoundTrips() {
        SharedPreferencesWorkspaceStore store = store();
        WorkspaceFolder picked =
                WorkspaceFolder.fromTreeDocumentId("primary:Documents/nusadesk");

        store.save(picked);

        WorkspaceFolder loaded = store.load();
        assertNotNull(loaded);
        assertEquals("primary:Documents/nusadesk", loaded.getTreeDocumentId());
        assertEquals(picked.getHostPath(), loaded.getHostPath());
    }

    @Test
    public void aHandEditedPathOutsideTheRulesReadsAsNoWorkspace() {
        SharedPreferencesWorkspaceStore store = store();
        store.save(WorkspaceFolder.ofHostPath(
                WorkspaceFolder.PICKED_PATH_ID, "/storage/emulated/0/Download", "Download"));
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("linux_wrapper_workspace", Context.MODE_PRIVATE)
                .edit().putString("hostPath", "relative/path").apply();

        assertNull("an unusable stored path must not be bound", store.load());
    }

    @Test
    public void clearRemovesTheChoice() {
        SharedPreferencesWorkspaceStore store = store();
        store.save(WorkspaceFolder.ofHostPath(
                WorkspaceFolder.PICKED_PATH_ID, "/storage/emulated/0/Download", "Download"));

        store.clear();

        assertNull(store.load());
    }
}
