package gh.nusashell.nusadesk.infrastructure.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The real-path listing rules of the built-in picker (ADR-0059): directories
 * first, both groups sorted case-insensitively, hidden entries kept, and an
 * unlistable directory reported as unreadable rather than as empty.
 */
public class LocalFileListingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void directoriesComeFirstAndEachGroupIsSortedByName() throws Exception {
        File root = folder.newFolder("root");
        file(root, "zeta.txt");
        file(root, "Alpha.txt");
        folderIn(root, "work");
        folderIn(root, ".config");

        LocalFileListing.Listing listing = LocalFileListing.list(root);

        assertTrue(listing.isReadable());
        assertEquals(names(listing.getDirectories()), List.of(".config", "work"));
        assertEquals(names(listing.getFiles()), List.of("Alpha.txt", "zeta.txt"));
    }

    @Test
    public void hiddenEntriesAreKeptBecauseTheLinuxPathsNeedThem() throws Exception {
        File root = folder.newFolder("hidden");
        file(root, ".bashrc");

        LocalFileListing.Listing listing = LocalFileListing.list(root);

        assertEquals(names(listing.getFiles()), List.of(".bashrc"));
    }

    @Test
    public void aMissingOrNonDirectoryPathIsUnreadableAndEmpty() throws Exception {
        File file = folder.newFile("plain.txt");

        LocalFileListing.Listing missing =
                LocalFileListing.list(new File(folder.getRoot(), "nope"));
        LocalFileListing.Listing notADirectory = LocalFileListing.list(file);

        assertFalse(missing.isReadable());
        assertTrue(missing.getDirectories().isEmpty());
        assertTrue(missing.getFiles().isEmpty());
        assertFalse(notADirectory.isReadable());
        assertTrue(notADirectory.getFiles().isEmpty());
        assertFalse(LocalFileListing.list(null).isReadable());
    }

    @Test
    public void parentOfTheFilesystemRootIsNull() {
        assertNull(LocalFileListing.parentOf(new File("/")));
    }

    @Test
    public void parentOfAChildIsItsDirectory() throws Exception {
        File root = folder.newFolder("parent");
        File child = folderIn(root, "child");

        assertEquals(root.getAbsolutePath(),
                LocalFileListing.parentOf(child).getAbsolutePath());
    }

    private static List<String> names(List<File> entries) {
        List<String> names = new ArrayList<>();
        for (File entry : entries) {
            names.add(entry.getName());
        }
        return names;
    }

    private static void file(File directory, String name) throws Exception {
        Files.write(new File(directory, name).toPath(), "x".getBytes(StandardCharsets.UTF_8));
    }

    private static File folderIn(File directory, String name) {
        File child = new File(directory, name);
        assertTrue(child.mkdir());
        return child;
    }
}
