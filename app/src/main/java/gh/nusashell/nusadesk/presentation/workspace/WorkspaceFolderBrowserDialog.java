package gh.nusashell.nusadesk.presentation.workspace;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.workspace.WorkspaceFolderListing;

/**
 * The in-app workspace folder browser (ADR-0047).
 *
 * <p>Written here instead of using a picker library: the guest mount needs a
 * real host path, this app already owns the storage grant it needs, and a
 * third-party dialog would bring its own permission gate — the library
 * evaluated for this slice demanded {@code READ_EXTERNAL_STORAGE} below API 30,
 * a broader grant than the feature needs and one the workspace storage guard
 * forbids. The browser therefore walks exactly the tree the caller passes in
 * ({@code WorkspaceFolderAccess.pickerRoot()}): shared storage on Android 11+,
 * the app's own media folder on Android 10.</p>
 *
 * <p>The positive button applies to the folder currently shown, which is the
 * natural "use this folder" semantics; the caller still validates and
 * write-probes the path before it is stored.</p>
 */
public final class WorkspaceFolderBrowserDialog {

    /** Receives the chosen folder's absolute path. */
    public interface Listener {
        void onFolderChosen(String absolutePath);
    }

    private WorkspaceFolderBrowserDialog() {
    }

    /**
     * Shows the browser rooted at {@code rootPath}, starting at
     * {@code startPath} when that path is inside the root.
     *
     * @param listener receives the chosen folder; never called on cancel
     */
    public static void show(Context context, String rootPath, String startPath, Listener listener) {
        if (context == null || rootPath == null || listener == null) {
            return;
        }
        File root = new File(rootPath);
        if (!root.isDirectory()) {
            return;
        }
        File start = root;
        if (startPath != null && WorkspaceFolderListing.isInside(root, new File(startPath))) {
            start = new File(startPath);
        }

        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_workspace_folder, null);
        TextView pathView = content.findViewById(R.id.workspace_browser_path);
        ListView listView = content.findViewById(R.id.workspace_browser_list);

        final File[] current = {start};
        final List<File> targets = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listView.setAdapter(adapter);

        final Runnable render = () -> {
            targets.clear();
            List<String> labels = new ArrayList<>();
            File parent = WorkspaceFolderListing.parentWithin(root, current[0]);
            if (parent != null) {
                targets.add(parent);
                labels.add(context.getString(R.string.system_workspace_picker_parent));
            }
            for (File folder : WorkspaceFolderListing.childFolders(current[0])) {
                targets.add(folder);
                labels.add(folder.getName());
            }
            pathView.setText(current[0].getAbsolutePath());
            adapter.clear();
            adapter.addAll(labels);
            adapter.notifyDataSetChanged();
        };
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < targets.size()) {
                current[0] = targets.get(position);
                render.run();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.system_workspace_picker_title)
                .setView(content)
                .setPositiveButton(R.string.system_workspace_picker_select, null)
                .setNegativeButton(R.string.system_workspace_picker_cancel, null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    listener.onFolderChosen(current[0].getAbsolutePath());
                    dialog.dismiss();
                }));
        render.run();
        dialog.show();
    }
}
