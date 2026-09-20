package gh.nusashell.nusadesk.infrastructure.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
 * built-in picker may browse, which picked paths may become a workspace, and
 * which of them need the all-files grant.
 *
 * <p>The point of the split is that Android 10 can never bind a shared folder:
 * there the picker is rooted inside the app's own media folder — bindable and
 * still reachable for the user — while Android 11+ browses the whole shared
 * volume and a picked path is only usable while the grant is in place.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WorkspaceFolderAccessTest {

    private static WorkspaceFolderAccess access() {
        return new WorkspaceFolderAccess(RuntimeEnvironment.getApplication());
    }

    @Test
    public void androidTenRootsThePickerInsideTheAppMediaFolder() {
        WorkspaceFolderAccess access = access();
        WorkspaceFolder root = access.pickerRoot();
        WorkspaceFolder appFolder = access.appFolderWorkspace();

        assertNotNull(root);
        assertNotNull(appFolder);
        assertTrue("Android 10 browses only the app's own tree",
                access.isInsideAppExternalStorage(root.getHostPath()));
        assertTrue("the app folder lives inside that root",
                appFolder.getHostPath().startsWith(root.getHostPath() + "/"));
        assertTrue("the folder is named after the workspace",
                appFolder.getHostPath().endsWith("/nusadesk"));
    }

    @Test
    public void androidTenRefusesASharedStoragePathItCouldNeverBind() {
        WorkspaceFolderAccess access = access();

        assertNull("a shared folder is not bindable below API 30",
                access.pickedPathWorkspace("/storage/emulated/0/Documents/nusadesk"));
        assertFalse(access.isUsable(
                WorkspaceFolder.ofHostPath("picked-path", "/storage/emulated/0/Documents/x", "x")));
    }

    @Test
    public void aPathInsideTheAppTreeNeedsNoGrantAndIsAccepted() {
        WorkspaceFolderAccess access = access();
        File root = new File(access.pickerRoot().getHostPath());
        File inside = new File(root, "projects");

        WorkspaceFolder picked = access.pickedPathWorkspace(inside.getAbsolutePath());

        assertNotNull(picked);
        assertEquals(inside.getAbsolutePath(), picked.getHostPath());
        assertEquals("projects", picked.getDisplayName());
        assertTrue("the folder is created and probed before it is stored",
                access.isUsable(picked));
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

    /** Android 11+: the picker browses shared storage, gated by the grant. */
    @Test
    @Config(sdk = 31)
    public void androidElevenRootsThePickerAtSharedStorage() {
        WorkspaceFolderAccess access = access();
        WorkspaceFolder root = access.pickerRoot();

        assertNotNull(root);
        assertEquals(Environment.getExternalStorageDirectory().getAbsolutePath(),
                root.getHostPath());
        assertFalse("shared storage is not the app's own tree",
                access.isInsideAppExternalStorage(root.getHostPath()));

        WorkspaceFolder picked =
                access.pickedPathWorkspace("/storage/emulated/0/Documents/nusadesk");
        assertNotNull("a shared path is accepted on API 30+", picked);
        assertFalse("but it is only usable with the all-files grant in place",
                access.isUsable(picked));
    }
}
