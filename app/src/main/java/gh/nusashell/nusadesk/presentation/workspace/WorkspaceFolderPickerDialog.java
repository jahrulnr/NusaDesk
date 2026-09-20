package gh.nusashell.nusadesk.presentation.workspace;

import android.content.Context;

import com.developer.filepicker.model.DialogConfigs;
import com.developer.filepicker.model.DialogProperties;
import com.developer.filepicker.view.FilePickerDialog;

import java.io.File;

import gh.nusashell.nusadesk.R;

/**
 * The built-in workspace folder picker (ADR-0047).
 *
 * <p>A path-based browser instead of the system document picker: the guest mount
 * needs a real host path, and on Android 11+ this app already holds all-files
 * access, so browsing raw paths is exactly the capability it has. The root comes
 * from the caller ({@code WorkspaceFolderAccess.pickerRoot()}), so on Android 10
 * the browser can only ever offer the app's own media folder — the one tree that
 * is both bindable and reachable for the user — while Android 11+ browses the
 * whole shared volume.</p>
 *
 * <p>The dialog itself comes from the vetted FilePicker library (Apache-2.0,
 * pure Java, empty manifest); this class owns the product contract around it:
 * the root, single-folder selection, and the labels.</p>
 */
public final class WorkspaceFolderPickerDialog {

    /** Receives the chosen folder's absolute path. */
    public interface Listener {
        void onFolderChosen(String absolutePath);
    }

    private WorkspaceFolderPickerDialog() {
    }

    /**
     * Shows the picker rooted at {@code rootPath}.
     *
     * @param rootPath absolute directory the browser starts in and may not leave
     * @param listener receives the chosen folder; never called on cancel
     */
    public static void show(Context context, String rootPath, Listener listener) {
        if (context == null || rootPath == null || listener == null) {
            return;
        }
        DialogProperties properties = new DialogProperties();
        properties.selection_mode = DialogConfigs.SINGLE_MODE;
        properties.selection_type = DialogConfigs.DIR_SELECT;
        properties.root = new File(rootPath);
        properties.offset = new File(rootPath);
        properties.error_dir = new File(rootPath);
        properties.extensions = null;
        properties.show_hidden_files = false;
        // The library gates its own show() on storage access: treat this app's
        // all-files grant as that access on Android 11+, and report a missing
        // grant with its settings trip instead of a silent no-op. On Android 10
        // the gate can only be satisfied by the platform read permission, which
        // the host asks for before it opens this dialog (ADR-0047).
        properties.allow_manage_external_storage = true;
        properties.show_permission_error_toast = true;

        FilePickerDialog dialog = new FilePickerDialog(context, properties);
        dialog.setTitle(context.getString(R.string.system_workspace_picker_title));
        dialog.setPositiveBtnName(context.getString(R.string.system_workspace_picker_select));
        dialog.setNegativeBtnName(context.getString(R.string.system_workspace_picker_cancel));
        dialog.setDialogSelectionListener(files -> {
            if (files != null && files.length > 0 && files[0] != null) {
                listener.onFolderChosen(files[0]);
            }
        });
        dialog.show();
    }
}
