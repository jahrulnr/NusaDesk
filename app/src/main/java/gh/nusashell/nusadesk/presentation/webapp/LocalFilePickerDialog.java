package gh.nusashell.nusadesk.presentation.webapp;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.files.LocalFileListing;

/**
 * The built-in real-path file picker (ADR-0059), offered next to the system
 * document picker as "NusaDesk files".
 *
 * <p>The system picker can only hand back {@code content://} URIs, and the
 * document providers behind them decide what exists — which is exactly the
 * wrong view for this product: the files a Linux session works with (the active
 * rootfs behind the guest's {@code /}, the workspace's real storage, anything
 * else the app itself may read) are ordinary paths, and PRoot binds them as
 * such. This browser therefore walks the real filesystem from {@code /}, one
 * directory at a time, and returns the chosen absolute paths.</p>
 *
 * <p>It is deliberately small: a path line, the directory's children, and a
 * name field for a new file. A directory the process cannot list is shown as
 * unreadable instead of as an empty folder, and a name that cannot be created
 * keeps the dialog open with the reason. When the current directory cannot be
 * listed at all, the picker also offers the readable roots — starting at the
 * filesystem {@code /} is what this picker promises, but an Android app may not
 * list {@code /}, {@code /storage}, or {@code /data} at all (SELinux denies the
 * app domain), so those shortcuts are what make the promise usable.</p>
 */
public final class LocalFilePickerDialog {

    /** What a pick returns. */
    public enum Selection {
        /** One existing file; a tap chooses and closes. */
        SINGLE_FILE,
        /** Zero or more existing files; the positive button returns them. */
        MULTIPLE_FILES,
        /** A file that may not exist yet; the dialog creates it before returning. */
        NEW_FILE
    }

    /** Receives the chosen files; never called on cancel. */
    public interface Listener {
        void onFilesChosen(List<File> files);
    }

    private enum Kind { PARENT, DIRECTORY, FILE, UNREADABLE, SHORTCUT }

    private static final class Row {
        private final Kind kind;
        private final File target;
        private final String label;

        private Row(Kind kind, File target, String label) {
            this.kind = kind;
            this.target = target;
            this.label = label;
        }
    }

    private LocalFilePickerDialog() {
    }

    /**
     * Shows the picker rooted at the filesystem {@code /}.
     *
     * @param context       an Activity context; the dialog needs one
     * @param start         the directory to start in
     * @param selection     what a pick returns
     * @param suggestedName pre-filled name in {@link Selection#NEW_FILE}, or null
     * @param listener      receives the chosen files
     * @return the shown dialog, or {@code null} when it could not be shown; the
     *         caller may dismiss it when the surface behind it goes away
     */
    public static AlertDialog show(
            Context context,
            File start,
            Selection selection,
            String suggestedName,
            Listener listener) {
        if (context == null || start == null || selection == null || listener == null) {
            return null;
        }
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_local_file_picker, null);
        TextView pathView = content.findViewById(R.id.local_picker_path);
        TextView statusView = content.findViewById(R.id.local_picker_status);
        EditText filenameView = content.findViewById(R.id.local_picker_filename);
        ListView listView = content.findViewById(R.id.local_picker_list);

        final File[] current = {start};
        final Set<File> selected = new LinkedHashSet<>();
        final List<Row> rows = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_list_item_1, new ArrayList<String>());
        listView.setAdapter(adapter);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(R.string.webapp_local_picker_title)
                .setView(content)
                .setNegativeButton(R.string.webapp_local_picker_cancel, null);
        if (selection != Selection.SINGLE_FILE) {
            builder.setPositiveButton(selection == Selection.NEW_FILE
                    ? R.string.webapp_local_picker_save
                    : R.string.webapp_local_picker_select, null);
        }
        AlertDialog dialog = builder.create();

        Runnable render = () -> {
            rows.clear();
            LocalFileListing.Listing listing = LocalFileListing.list(current[0]);
            File parent = LocalFileListing.parentOf(current[0]);
            if (parent != null) {
                rows.add(new Row(Kind.PARENT, parent,
                        context.getString(R.string.webapp_local_picker_parent)));
            }
            for (File directory : listing.getDirectories()) {
                rows.add(new Row(Kind.DIRECTORY, directory, directory.getName() + "/"));
            }
            for (File file : listing.getFiles()) {
                String label = selected.contains(file)
                        ? context.getString(R.string.webapp_local_picker_selected_entry,
                                file.getName())
                        : file.getName();
                rows.add(new Row(Kind.FILE, file, label));
            }
            if (!listing.isReadable()) {
                rows.add(new Row(Kind.UNREADABLE, null,
                        context.getString(R.string.webapp_local_picker_unreadable)));
                for (Object[] shortcut : readableRoots(context)) {
                    rows.add(new Row(Kind.SHORTCUT, new File((String) shortcut[0]),
                            context.getString((Integer) shortcut[1])));
                }
            }
            pathView.setText(current[0].getAbsolutePath());
            adapter.clear();
            for (Row row : rows) {
                adapter.add(row.label);
            }
            adapter.notifyDataSetChanged();
            statusView.setVisibility(View.GONE);
        };

        Runnable updateActions = () -> {
            if (selection != Selection.MULTIPLE_FILES) {
                return;
            }
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive == null) {
                return;
            }
            positive.setEnabled(!selected.isEmpty());
            positive.setText(context.getString(
                    R.string.webapp_local_picker_select_count, selected.size()));
        };

        listView.setOnItemClickListener((parentView, view, position, id) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            Row row = rows.get(position);
            switch (row.kind) {
                case PARENT:
                case DIRECTORY:
                case SHORTCUT:
                    current[0] = row.target;
                    render.run();
                    break;
                case FILE:
                    if (selection == Selection.MULTIPLE_FILES) {
                        if (!selected.remove(row.target)) {
                            selected.add(row.target);
                        }
                        render.run();
                        updateActions.run();
                    } else if (selection == Selection.NEW_FILE) {
                        filenameView.setText(row.target.getName());
                    } else {
                        listener.onFilesChosen(Collections.singletonList(row.target));
                        dialog.dismiss();
                    }
                    break;
                default:
                    // An unreadable directory offers nothing to open.
                    break;
            }
        });

        dialog.setOnShowListener(shown -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive == null) {
                return;
            }
            positive.setOnClickListener(view -> {
                if (selection == Selection.NEW_FILE) {
                    saveInto(context, current[0], filenameView, statusView, listener, dialog);
                } else if (!selected.isEmpty()) {
                    listener.onFilesChosen(new ArrayList<>(selected));
                    dialog.dismiss();
                }
            });
            updateActions.run();
        });

        filenameView.setVisibility(selection == Selection.NEW_FILE ? View.VISIBLE : View.GONE);
        if (suggestedName != null) {
            filenameView.setText(suggestedName);
        }
        render.run();
        dialog.show();
        return dialog;
    }

    /**
     * The roots an app on this platform can actually read: shared storage
     * (reachable because this product holds the all-files grant), the app's own
     * files directory — where the Linux runtime lives, the guest's {@code /} —
     * and the read-only system tree. The literal {@code /} is not among them
     * because the app domain cannot list it.
     *
     * @return {@code {absolutePath, labelRes}} pairs, in the order they are shown
     */
    private static List<Object[]> readableRoots(Context context) {
        List<Object[]> roots = new ArrayList<>();
        roots.add(new Object[]{
                Environment.getExternalStorageDirectory().getAbsolutePath(),
                R.string.webapp_local_picker_shared_storage});
        roots.add(new Object[]{
                context.getFilesDir().getAbsolutePath(),
                R.string.webapp_local_picker_app_files});
        roots.add(new Object[]{"/system", R.string.webapp_local_picker_system});
        return roots;
    }

    /**
     * Creates the named file in {@code directory} and returns it, or keeps the
     * dialog open with the reason it could not be created. The page writes
     * through the returned URI, so the file has to exist first — the same thing
     * {@code ACTION_CREATE_DOCUMENT} does for a document provider.
     */
    private static void saveInto(
            Context context,
            File directory,
            EditText filenameView,
            TextView statusView,
            Listener listener,
            AlertDialog dialog) {
        String name = filenameView.getText() == null
                ? "" : filenameView.getText().toString().trim();
        if (name.isEmpty() || name.contains("/") || ".".equals(name) || "..".equals(name)) {
            statusView.setText(R.string.webapp_local_picker_name_required);
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        File target = new File(directory, name);
        boolean usable;
        try {
            usable = !target.isDirectory()
                    && (target.exists() ? target.canWrite() : target.createNewFile());
        } catch (IOException | SecurityException failed) {
            usable = false;
        }
        if (!usable) {
            statusView.setText(R.string.webapp_local_picker_write_failed);
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        listener.onFilesChosen(Collections.singletonList(target));
        dialog.dismiss();
    }
}
