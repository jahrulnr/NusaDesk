package gh.nusashell.nusadesk.infrastructure.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Environment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;

/**
 * The workspace access policy across API levels (ADR-0023, ADR-0047): where the
 * in-app browser may browse, which picked paths may become a workspace, and
 * which of them need storage access.
 *
 * <p>Both API levels now browse shared storage — Android 11+ through the
 * all-files grant, Android 10 through the platform's legacy model and its two
 * runtime permissions (measured on the S7 Edge) — while the app's own media
 * folder stays the default workspace until the user picks something.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WorkspaceFolderAccessTest {

    private static WorkspaceFolderAccess access() {
        return new WorkspaceFolderAccess(RuntimeEnvironment.getApplication());
    }

    /** A context whose legacy storage grants the test decides. */
    private static Context contextWithLegacyGrants(boolean granted) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public int checkSelfPermission(String permission) {
                return granted
                        ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED;
            }
        };
    }

    @Test
    public void everyPlatformBrowsesSharedStorage() {
        WorkspaceFolder root = access().pickerRoot();

        assertNotNull(root);
        assertEquals(Environment.getExternalStorageDirectory().getAbsolutePath(),
                root.getHostPath());
        assertFalse("shared storage is not the app's own tree",
                access().isInsideAppExternalStorage(root.getHostPath()));
    }

    @Test
    public void theAppMediaFolderStaysTheDefaultWorkspace() {
        WorkspaceFolderAccess access = access();
        WorkspaceFolder appFolder = access.appFolderWorkspace();

        assertNotNull(appFolder);
        assertTrue("the default lives inside the app's own tree",
                access.isInsideAppExternalStorage(appFolder.getHostPath()));
        assertTrue("the folder is named after the workspace",
                appFolder.getHostPath().endsWith("/nusadesk"));
    }

    @Test
    public void aPathInsideTheAppTreeNeedsNoGrantAndIsAccepted() {
        WorkspaceFolderAccess access = access();
        File inside = new File(access.appFolderWorkspace().getHostPath(), "projects");

        WorkspaceFolder picked = access.pickedPathWorkspace(inside.getAbsolutePath());

        assertNotNull("the app's own tree needs no storage grant", picked);
        assertEquals(inside.getAbsolutePath(), picked.getHostPath());
        assertEquals("projects", picked.getDisplayName());
        assertTrue("the folder is created and probed before it is stored",
                access.isUsable(picked));
    }

    @Test
    public void androidTenNeedsTheLegacyGrantsForASharedPath() {
        WorkspaceFolderAccess denied = new WorkspaceFolderAccess(contextWithLegacyGrants(false));

        assertFalse("without the legacy grants a shared path cannot be bound",
                denied.hasLegacyStorageAccess());
        assertNull(denied.pickedPathWorkspace("/storage/emulated/0/Documents/nusadesk"));

        WorkspaceFolderAccess granted = new WorkspaceFolderAccess(contextWithLegacyGrants(true));

        assertTrue("the legacy grants are the platform's door on API 29 (ADR-0047)",
                granted.hasLegacyStorageAccess());
        WorkspaceFolder picked =
                granted.pickedPathWorkspace("/storage/emulated/0/Documents/nusadesk");
        assertNotNull("with them a shared folder is accepted, as on Android 11+", picked);
        assertEquals("/storage/emulated/0/Documents/nusadesk", picked.getHostPath());
        assertEquals("the app folder is no longer the only choice",
                "nusadesk", picked.getDisplayName());
    }

    @Test
    public void traversalAndForeignPathsAreRefused() {
        WorkspaceFolderAccess access = access();

        assertNull(access.pickedPathWorkspace("/storage/emulated/0/../etc"));
        assertNull(access.pickedPathWorkspace("relative/path"));
        assertNull(access.pickedPathWorkspace("/etc"));
        assertNull(access.pickedPathWorkspace(""));
        assertNull(access.pickedPathWorkspace(null));
    }

    /** Android 11+: the same root, but a shared path needs the all-files grant. */
    @Test
    @Config(sdk = 31)
    public void androidElevenNeedsTheAllFilesGrantForASharedPath() {
        WorkspaceFolderAccess access = access();

        assertEquals(Environment.getExternalStorageDirectory().getAbsolutePath(),
                access.pickerRoot().getHostPath());
        assertFalse("Robolectric never holds the all-files grant",
                access.hasAllFilesAccess());
        assertNull("without the grant a shared path is not accepted",
                access.pickedPathWorkspace("/storage/emulated/0/Documents/nusadesk"));
        assertNotNull("the app's own tree still works without any grant",
                access.pickedPathWorkspace(
                        new File(access.appFolderWorkspace().getHostPath(), "projects")
                                .getAbsolutePath()));
    }
}
