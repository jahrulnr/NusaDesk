package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import gh.nusashell.nusadesk.R;

/**
 * The built-in real-path picker's contract (ADR-0059): what a tap returns, how
 * a multi-selection is confirmed, and that a new file exists before the page is
 * told about it. The listing rules themselves are covered by
 * {@code LocalFileListingTest}; this test drives the dialog.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class LocalFilePickerDialogTest {

    private static final int ROW_PARENT = 0;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Activity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void oneFileIsChosenByTappingItsRow() throws Exception {
        File root = folder.newFolder("single");
        File note = touch(root, "note.txt");
        List<File> chosen = new ArrayList<>();

        AlertDialog dialog = showDialog(
                root, LocalFilePickerDialog.Selection.SINGLE_FILE, null, chosen);

        assertNotNull(dialog);
        list(dialog).performItemClick(null, ROW_PARENT + 1, ROW_PARENT + 1);
        assertEquals(List.of(note.getAbsolutePath()), paths(chosen));
        assertFalse("a single choice closes the picker", dialog.isShowing());
    }

    @Test
    public void multipleSelectionIsConfirmedWithThePositiveButton() throws Exception {
        File root = folder.newFolder("multi");
        File alpha = touch(root, "alpha.txt");
        File beta = touch(root, "beta.txt");
        List<File> chosen = new ArrayList<>();

        AlertDialog dialog = showDialog(
                root, LocalFilePickerDialog.Selection.MULTIPLE_FILES, null, chosen);

        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        assertFalse("nothing is marked yet", positive.isEnabled());

        list(dialog).performItemClick(null, ROW_PARENT + 1, ROW_PARENT + 1);
        list(dialog).performItemClick(null, ROW_PARENT + 2, ROW_PARENT + 2);

        assertTrue(positive.isEnabled());
        assertEquals(activity.getString(R.string.webapp_local_picker_select_count, 2),
                positive.getText().toString());
        positive.performClick();
        assertEquals(List.of(alpha.getAbsolutePath(), beta.getAbsolutePath()), paths(chosen));
    }

    @Test
    public void aNewNameCreatesTheFileBeforeThePageHearsAboutIt() throws Exception {
        File root = folder.newFolder("save");
        List<File> chosen = new ArrayList<>();

        AlertDialog dialog = showDialog(
                root, LocalFilePickerDialog.Selection.NEW_FILE, "draft.txt", chosen);
        EditText name = dialog.findViewById(R.id.local_picker_filename);
        assertEquals("the page's suggested name is offered",
                "draft.txt", name.getText().toString());
        name.setText("notes.txt");

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        assertEquals(1, chosen.size());
        assertEquals("notes.txt", chosen.get(0).getName());
        assertTrue("the file exists because the page writes through it",
                chosen.get(0).isFile());
        assertEquals(root.getAbsolutePath(), chosen.get(0).getParentFile().getAbsolutePath());
        assertFalse(dialog.isShowing());
    }

    @Test
    public void aNameThatCannotBeCreatedKeepsThePickerOpenWithTheReason() throws Exception {
        File root = folder.newFolder("refuse");
        List<File> chosen = new ArrayList<>();

        AlertDialog dialog = showDialog(
                root, LocalFilePickerDialog.Selection.NEW_FILE, null, chosen);
        ((EditText) dialog.findViewById(R.id.local_picker_filename)).setText("../escape");

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        assertTrue(chosen.isEmpty());
        assertTrue(dialog.isShowing());
        TextView status = dialog.findViewById(R.id.local_picker_status);
        assertEquals(View.VISIBLE, status.getVisibility());
    }

    @Test
    public void walkingIntoAFolderShowsItsChildren() throws Exception {
        File root = folder.newFolder("nav");
        File sub = folderIn(root, "sub");
        File inner = touch(sub, "inner.txt");
        List<File> chosen = new ArrayList<>();

        AlertDialog dialog = showDialog(
                root, LocalFilePickerDialog.Selection.SINGLE_FILE, null, chosen);

        list(dialog).performItemClick(null, ROW_PARENT + 1, ROW_PARENT + 1);
        TextView path = dialog.findViewById(R.id.local_picker_path);
        assertEquals(sub.getAbsolutePath(), path.getText().toString());

        list(dialog).performItemClick(null, ROW_PARENT + 1, ROW_PARENT + 1);
        assertEquals(List.of(inner.getAbsolutePath()), paths(chosen));
    }

    /**
     * Shows the picker and runs the dialog's show message, which is where it
     * attaches the positive button's behavior — the same order a real device
     * executes.
     */
    private AlertDialog showDialog(
            File start,
            LocalFilePickerDialog.Selection selection,
            String suggestedName,
            List<File> chosen) {
        AlertDialog dialog = LocalFilePickerDialog.show(
                activity, start, selection, suggestedName, chosen::addAll);
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    @Test
    public void anUnlistableStartOffersTheReadableRoots() {
        File missing = new File(folder.getRoot(), "does-not-exist");
        List<File> chosen = new ArrayList<>();

        AlertDialog dialog = showDialog(
                missing, LocalFilePickerDialog.Selection.SINGLE_FILE, null, chosen);

        ListView list = list(dialog);
        assertEquals("the parent row, the notice, and the three readable roots",
                5, list.getAdapter().getCount());
        assertEquals(activity.getString(R.string.webapp_local_picker_unreadable),
                list.getAdapter().getItem(1));
        assertEquals(activity.getString(R.string.webapp_local_picker_shared_storage),
                list.getAdapter().getItem(2));

        list.performItemClick(null, 3, 3);

        TextView path = dialog.findViewById(R.id.local_picker_path);
        assertEquals("App files jumps to the tree the Linux session lives in",
                activity.getFilesDir().getAbsolutePath(), path.getText().toString());
    }

    private static ListView list(AlertDialog dialog) {
        ListView listView = dialog.findViewById(R.id.local_picker_list);
        assertNotNull(listView);
        return listView;
    }

    private static List<String> paths(List<File> files) {
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.getAbsolutePath());
        }
        return paths;
    }

    private static File touch(File directory, String name) throws Exception {
        File file = new File(directory, name);
        Files.write(file.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static File folderIn(File directory, String name) {
        File child = new File(directory, name);
        assertTrue(child.mkdir());
        return child;
    }
}
