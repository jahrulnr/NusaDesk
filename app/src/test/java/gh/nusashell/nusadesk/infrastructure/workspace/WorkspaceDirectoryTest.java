package gh.nusashell.nusadesk.infrastructure.workspace;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The workspace mount is only as good as the folder behind it: a missing,
 * read-only, or blocked directory would leave the guest with a mount point that
 * fails on the first write. These cases pin the probe that decides whether a
 * chosen folder may be bound, and that it leaves nothing behind in the user's
 * folder.
 *
 * <p>Plain {@code java.io} work, so this runs on the JVM without a device.</p>
 */
public class WorkspaceDirectoryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsAnExistingWritableFolder() throws Exception {
        File folder = temporaryFolder.newFolder("Documents", "nusadesk");

        assertTrue(WorkspaceDirectory.ensureUsable(folder.getAbsolutePath()));
    }

    @Test
    public void createsAMissingFolderAndLeavesNoProbeBehind() throws Exception {
        File nested = new File(temporaryFolder.getRoot(), "Documents/nusadesk");

        assertTrue(WorkspaceDirectory.ensureUsable(nested.getAbsolutePath()));
        assertTrue(nested.isDirectory());
        String[] leftovers = nested.list();
        assertTrue("the probe file must be removed again",
                leftovers != null && leftovers.length == 0);
    }

    @Test
    public void refusesAPathWhoseParentIsAFile() throws Exception {
        File file = temporaryFolder.newFile("not-a-directory.txt");
        Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File impossible = new File(file, "nusadesk");

        assertFalse(WorkspaceDirectory.ensureUsable(impossible.getAbsolutePath()));
    }

    @Test
    public void refusesBlankPaths() {
        assertFalse(WorkspaceDirectory.ensureUsable(null));
        assertFalse(WorkspaceDirectory.ensureUsable(""));
        assertFalse(WorkspaceDirectory.ensureExists(null));
    }

    @Test
    public void ensureExistsCreatesWithoutProbing() throws Exception {
        File nested = new File(temporaryFolder.getRoot(), "Documents/nusadesk");

        assertTrue(WorkspaceDirectory.ensureExists(nested.getAbsolutePath()));
        assertTrue(nested.isDirectory());
        String[] leftovers = nested.list();
        assertTrue("ensureExists must not write a probe file",
                leftovers == null || leftovers.length == 0);
    }
}
