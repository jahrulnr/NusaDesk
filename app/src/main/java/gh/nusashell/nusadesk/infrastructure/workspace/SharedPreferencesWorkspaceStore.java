package gh.nusashell.nusadesk.infrastructure.workspace;

import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.application.workspace.WorkspaceStore;
import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;

/**
 * {@link WorkspaceStore} backed by a private {@code SharedPreferences} file.
 *
 * <p>Only the three fields of the user's choice are stored — no URI grant, no
 * path outside that choice. Reads go through
 * {@link WorkspaceFolder#restore}, so a preference edited by hand (or left over
 * from a different build) that does not describe a valid local folder is
 * reported as "no workspace" instead of being bound into the guest.</p>
 */
public final class SharedPreferencesWorkspaceStore implements WorkspaceStore {

    private static final String PREFS_NAME = "linux_wrapper_workspace";
    private static final String KEY_DOCUMENT_ID = "treeDocumentId";
    private static final String KEY_HOST_PATH = "hostPath";
    private static final String KEY_DISPLAY_NAME = "displayName";

    private final SharedPreferences preferences;

    public SharedPreferencesWorkspaceStore(Context context) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public WorkspaceFolder load() {
        String documentId = preferences.getString(KEY_DOCUMENT_ID, null);
        String hostPath = preferences.getString(KEY_HOST_PATH, null);
        if (documentId == null || hostPath == null) {
            return null;
        }
        return WorkspaceFolder.restore(
                documentId, hostPath, preferences.getString(KEY_DISPLAY_NAME, null));
    }

    @Override
    public void save(WorkspaceFolder folder) {
        if (folder == null) {
            clear();
            return;
        }
        preferences.edit()
                .putString(KEY_DOCUMENT_ID, folder.getTreeDocumentId())
                .putString(KEY_HOST_PATH, folder.getHostPath())
                .putString(KEY_DISPLAY_NAME, folder.getDisplayName())
                .apply();
    }

    @Override
    public void clear() {
        preferences.edit().clear().apply();
    }
}
