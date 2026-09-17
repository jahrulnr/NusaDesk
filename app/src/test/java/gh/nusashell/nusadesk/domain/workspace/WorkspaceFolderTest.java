package gh.nusashell.nusadesk.domain.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The workspace folder is the single place a picked Storage Access Framework
 * tree id becomes the host path PRoot binds. A wrong translation would mount an
 * unintended directory into the guest, and an unvalidated one could mount the
 * whole shared volume or escape it, so both the accepted forms and every refusal
 * are pinned here.
 */
public class WorkspaceFolderTest {

    @Test
    public void translatesPrimaryStorageFolder() {
        WorkspaceFolder folder = WorkspaceFolder.fromTreeDocumentId("primary:Documents/nusadesk");

        assertNotNull(folder);
        assertEquals("/storage/emulated/0/Documents/nusadesk", folder.getHostPath());
        assertEquals("Documents/nusadesk", folder.getDisplayName());
        assertEquals("primary:Documents/nusadesk", folder.getTreeDocumentId());
    }

    @Test
    public void translatesPrimaryVolumeRoot() {
        WorkspaceFolder folder = WorkspaceFolder.fromTreeDocumentId("primary:");

        assertNotNull(folder);
        assertEquals("/storage/emulated/0", folder.getHostPath());
        assertEquals("primary", folder.getDisplayName());
    }

    @Test
    public void translatesRemovableVolumeFolder() {
        WorkspaceFolder folder = WorkspaceFolder.fromTreeDocumentId("1a2b-3c4d:Projects/nusadesk");

        assertNotNull(folder);
        assertEquals("/storage/1A2B-3C4D/Projects/nusadesk", folder.getHostPath());
    }

    @Test
    public void translatesRawPathId() {
        WorkspaceFolder folder = WorkspaceFolder.fromTreeDocumentId("raw:/storage/9C33-6BBD/Docs");

        assertNotNull(folder);
        assertEquals("/storage/9C33-6BBD/Docs", folder.getHostPath());
    }

    @Test
    public void refusesIdsThatAreNotLocalStorageFolders() {
        assertNull(WorkspaceFolder.fromTreeDocumentId(null));
        assertNull(WorkspaceFolder.fromTreeDocumentId(""));
        assertNull(WorkspaceFolder.fromTreeDocumentId("no-colon"));
        assertNull(WorkspaceFolder.fromTreeDocumentId(":Documents"));
        assertNull("a cloud provider is not a bindable host path",
                WorkspaceFolder.fromTreeDocumentId("com.google.drive:some-folder"));
    }

    @Test
    public void refusesTraversalAndAbsoluteRelativePaths() {
        assertNull(WorkspaceFolder.fromTreeDocumentId("primary:../etc"));
        assertNull(WorkspaceFolder.fromTreeDocumentId("primary:Documents/../../etc"));
        assertNull(WorkspaceFolder.fromTreeDocumentId("primary:/etc"));
        assertNull(WorkspaceFolder.fromTreeDocumentId("primary:a//b"));
        assertNull(WorkspaceFolder.fromTreeDocumentId("primary:./a"));
        assertNull(WorkspaceFolder.fromTreeDocumentId("raw:relative/path"));
        assertNull(WorkspaceFolder.fromTreeDocumentId("raw:/storage/../data"));
    }

    @Test
    public void restoreKeepsAStoredChoiceWhenItStillMatches() {
        WorkspaceFolder restored = WorkspaceFolder.restore(
                "primary:Documents/nusadesk",
                "/storage/emulated/0/Documents/nusadesk",
                "Documents/nusadesk");

        assertNotNull(restored);
        assertEquals("/storage/emulated/0/Documents/nusadesk", restored.getHostPath());
    }

    @Test
    public void restoreRefusesAStoredChoiceThatNoLongerMatches() {
        assertNull("a preference edited to another path must not be trusted",
                WorkspaceFolder.restore(
                        "primary:Documents/nusadesk",
                        "/storage/emulated/0/DCIM",
                        "Documents/nusadesk"));
        assertNull(WorkspaceFolder.restore("primary:Documents/nusadesk", "relative/path", null));
    }

    @Test
    public void restoreFallsBackToTheParsedLabel() {
        WorkspaceFolder restored = WorkspaceFolder.restore(
                "primary:Documents/nusadesk",
                "/storage/emulated/0/Documents/nusadesk",
                "   ");

        assertNotNull(restored);
        assertEquals("Documents/nusadesk", restored.getDisplayName());
    }

    @Test
    public void guestMountPointIsTheDocumentedWorkspacePath() {
        assertEquals("/root/nusadesk", WorkspaceFolder.GUEST_MOUNT_PATH);
    }

    @Test
    public void acceptsAComputedAbsoluteFolder() {
        WorkspaceFolder folder = WorkspaceFolder.ofHostPath(
                "app-external-files",
                "/storage/emulated/0/Android/data/gh.nusashell.nusadesk/files/nusadesk",
                "nusadesk");

        assertNotNull(folder);
        assertEquals("nusadesk", folder.getDisplayName());
        assertEquals("/storage/emulated/0/Android/data/gh.nusashell.nusadesk/files/nusadesk",
                folder.getHostPath());
    }

    @Test
    public void refusesAComputedFolderThatIsNotAnAbsoluteSafePath() {
        assertNull(WorkspaceFolder.ofHostPath("app-external-files", "relative/nusadesk", null));
        assertNull(WorkspaceFolder.ofHostPath("app-external-files", "/a/../b", null));
        assertNull(WorkspaceFolder.ofHostPath("", "/absolute/path", null));
        assertNull(WorkspaceFolder.ofHostPath(null, "/absolute/path", null));
    }

    @Test
    public void computedFolderFallsBackToThePathAsItsLabel() {
        WorkspaceFolder folder = WorkspaceFolder.ofHostPath("app-external-files", "/tmp/ws", "  ");

        assertNotNull(folder);
        assertEquals("/tmp/ws", folder.getDisplayName());
    }
}
