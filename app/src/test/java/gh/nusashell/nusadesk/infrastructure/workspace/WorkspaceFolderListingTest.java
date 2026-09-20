package gh.nusashell.nusadesk.infrastructure.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-app browser's listing rules (ADR-0047): directories only, sorted,
 * never outside the root, and the parent row disappears at the root. These are
 * the rules that keep the Android 10 browser inside the app's own media tree
 * and the Android 11+ browser inside shared storage.
 */
public class WorkspaceFolderListingTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private File root() throws IOException {
        return temp.newFolder("nusadesk");
    }

    @Test
    public void onlyDirectoriesAreListedInNameOrder() throws Exception {
        File root = root();
        new File(root, "zeta").mkdirs();
        new File(root, "Alpha").mkdirs();
        new File(root, "notes.txt").createNewFile();
        new File(root, ".hidden").mkdirs();

        List<String> names = new ArrayList<>();
        for (File folder : WorkspaceFolderListing.childFolders(root)) {
            names.add(folder.getName());
        }

        assertEquals("[Alpha, zeta]", names.toString());
    }

    @Test
    public void containmentDecidesWhatTheBrowserMayWalk() throws Exception {
        File root = root();
        File inside = new File(root, "projects/deep");
        assertTrue(inside.mkdirs());

        assertTrue(WorkspaceFolderListing.isInside(root, root));
        assertTrue(WorkspaceFolderListing.isInside(root, inside));
        assertFalse(WorkspaceFolderListing.isInside(root, new File(root.getParentFile(), "other")));
        assertFalse(WorkspaceFolderListing.isInside(root, null));
    }

    @Test
    public void theParentRowStopsAtTheRoot() throws Exception {
        File root = root();
        File child = new File(root, "projects");
        assertTrue(child.mkdirs());

        assertEquals(root, WorkspaceFolderListing.parentWithin(root, child));
        assertNull("the browser never walks above its root",
                WorkspaceFolderListing.parentWithin(root, root));
        assertNull(WorkspaceFolderListing.parentWithin(root, new File(root.getParentFile(), "x")));
    }

    @Test
    public void anUnlistableDirectoryYieldsNoRows() {
        assertTrue(WorkspaceFolderListing.childFolders(null).isEmpty());
        assertTrue(WorkspaceFolderListing.childFolders(new File("/does/not/exist")).isEmpty());
    }
}
