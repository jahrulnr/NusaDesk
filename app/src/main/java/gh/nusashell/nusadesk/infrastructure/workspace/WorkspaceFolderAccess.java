package gh.nusashell.nusadesk.infrastructure.workspace;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;

/**
 * The Android side of choosing a workspace folder: the all-files access gate,
 * the system folder picker, and the translation of a pick into a bindable path.
 *
 * <p>A Storage Access Framework grant is not enough on its own: it yields a
 * content URI, while the guest mount needs a real path. Android only allows this
 * app to open a shared-storage path directly when the user has granted
 * <em>all-files access</em>, so this class never hands out a path without that
 * grant and without a successful write probe ({@link WorkspaceDirectory}).</p>
 *
 * <p>Introspection note: {@code ACTION_OPEN_DOCUMENT_TREE} cannot select the
 * volume root or {@code Download} on Android 11+, which is why the product
 * suggests {@code Documents/nusadesk} and pre-creates it when it may.</p>
 */
public final class WorkspaceFolderAccess {

    /** System picker request code; the host routes the result back here. */
    public static final int REQUEST_PICK_FOLDER = 0x5702;

    /** Authority of the platform's own external-storage documents provider. */
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    /** The folder this product suggests for a Linux workspace. */
    private static final String SUGGESTED_DOCUMENT_ID = "primary:Documents/nusadesk";

    /** Identifier and folder name of the app's own external workspace. */
    private static final String APP_FOLDER_ID = "app-external-files";
    private static final String APP_FOLDER_NAME = "nusadesk";

    private final Context context;

    public WorkspaceFolderAccess(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Whether this platform can expose a shared-storage folder to the guest at
     * all. All-files access only exists from Android 11 (API 30); on Android 10
     * the app targets API 37, so scoped storage applies and no broad-storage
     * opt-out is available.
     */
    public boolean isSupportedPlatform() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** Whether the user has granted all-files access on this device. */
    public boolean hasAllFilesAccess() {
        if (!isSupportedPlatform()) {
            return false;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (RuntimeException probeFailed) {
            // This is a permission probe on a user-visible screen, so it must
            // never take the app down: the framework call consults system state
            // that has been observed to be incomplete on API 30+ (missing
            // volumes surface as an out-of-bounds read inside the platform
            // method). Reporting "not granted" is the safe, honest answer — the
            // card then offers the Settings step, and the next foreground event
            // re-probes.
            return false;
        }
    }

    /**
     * Settings page where the user can grant all-files access, or null below
     * API 30.
     *
     * <p>Deliberately no {@code resolveActivity()} probe: package visibility on
     * Android 11+ can report "not found" for a screen that would in fact open,
     * so the caller starts the intent and falls back when the platform throws
     * ({@link #genericAllFilesAccessIntent()}).</p>
     */
    public Intent allFilesAccessIntent() {
        if (!isSupportedPlatform()) {
            return null;
        }
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * The generic all-files screen (Settings &gt; Special app access), used when
     * the app-specific page is not published by the platform build. Null below
     * API 30, where the screen and the permission do not exist.
     */
    public Intent genericAllFilesAccessIntent() {
        if (!isSupportedPlatform()) {
            return null;
        }
        return new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
    }

    /**
     * The folder this product suggests, created when the app is allowed to write
     * it. Returns null when the platform cannot host a workspace.
     */
    public WorkspaceFolder suggestWorkspace() {
        if (!isSupportedPlatform()) {
            return null;
        }
        WorkspaceFolder suggested = WorkspaceFolder.fromTreeDocumentId(SUGGESTED_DOCUMENT_ID);
        if (suggested != null && hasAllFilesAccess()) {
            WorkspaceDirectory.ensureExists(suggested.getHostPath());
        }
        return suggested;
    }

    /** System folder picker, opened at the suggested folder when one is known. */
    public Intent folderPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        WorkspaceFolder suggested = suggestWorkspace();
        if (suggested != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                            EXTERNAL_STORAGE_AUTHORITY, suggested.getTreeDocumentId()));
        }
        return intent;
    }

    /**
     * Translate a picker result into a workspace, taking the persistable grant so
     * the choice keeps resolving after a restart.
     *
     * @return the chosen workspace, or null when the result is missing, the grant
     *         cannot be persisted, or the folder is not a local storage folder
     */
    public WorkspaceFolder resolvePickedFolder(Intent data) {
        if (data == null || data.getData() == null) {
            return null;
        }
        Uri treeUri = data.getData();
        ContentResolver resolver = context.getContentResolver();
        try {
            resolver.takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException notPersistable) {
            // A grant that cannot be persisted would stop resolving after the
            // next restart, so it is refused now rather than stored.
            return null;
        }
        String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException notATree) {
            return null;
        }
        return WorkspaceFolder.fromTreeDocumentId(documentId);
    }

    /**
     * The workspace on platforms that cannot bind a shared folder: this app's own
     * external media folder, {@code Android/media/<package>/nusadesk}.
     *
     * <p>No permission is involved — the directory belongs to the app — and it
     * is the app-owned tree that stays reachable for the user: on Android 10 a
     * file manager can browse it, and on Android 11+ the media tree is still
     * visible over MTP and to file managers, unlike {@code Android/data}. That
     * reachability is the point: the guest backup excludes the workspace bind,
     * so this folder is what the user copies out themselves.</p>
     */
    public WorkspaceFolder appFolderWorkspace() {
        File root = primaryAppExternalRoot();
        if (root == null) {
            return null;
        }
        File workspace = new File(root, APP_FOLDER_NAME);
        return WorkspaceFolder.ofHostPath(
                APP_FOLDER_ID, workspace.getAbsolutePath(), APP_FOLDER_NAME);
    }

    /**
     * The root the built-in folder picker may browse on this platform: the whole
     * shared volume on Android 11+ (the app holds all-files access there, so raw
     * paths are real), and this app's own media folder on Android 10, where a
     * shared folder could never be bound.
     *
     * @return the browsable root, or null when the device exposes no storage
     */
    public WorkspaceFolder pickerRoot() {
        if (!isSupportedPlatform()) {
            // Android 10: only this app's own trees are raw-accessible, and the
            // media tree is the one a file manager and MTP still show. Rooting at
            // the tree (not at the workspace folder inside it) lets the user pick
            // the folder itself or any subfolder of it.
            File ownRoot = primaryAppExternalRoot();
            return ownRoot == null
                    ? null
                    : WorkspaceFolder.ofHostPath(
                            WorkspaceFolder.PICKED_PATH_ID, ownRoot.getAbsolutePath(), ownRoot.getName());
        }
        File root = Environment.getExternalStorageDirectory();
        return root == null
                ? null
                : WorkspaceFolder.ofHostPath(WorkspaceFolder.PICKED_PATH_ID, root.getAbsolutePath(), "Storage");
    }

    /**
     * Wraps a raw path the built-in picker returned.
     *
     * <p>Accepted are absolute paths without traversal segments that either sit
     * inside this app's own external directories (no grant needed) or, on
     * Android 11+, inside shared storage (usable only while the all-files grant
     * is in place — {@link #isUsable} is the second gate). Everything else is
     * refused rather than bound.</p>
     *
     * @return the workspace, or null when the path cannot be bound here
     */
    public WorkspaceFolder pickedPathWorkspace(String absolutePath) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) {
            return null;
        }
        String path = absolutePath.trim();
        if (!path.startsWith("/") || path.contains("..") || path.indexOf('\0') >= 0) {
            return null;
        }
        if (!isInsideAppExternalStorage(path)
                && !(isSupportedPlatform() && path.startsWith("/storage/"))) {
            return null;
        }
        File folder = new File(path);
        String name = folder.getName();
        // The app's own media tree is labelled like the app folder state rather
        // than with the package name it happens to carry.
        for (File root : appExternalRoots()) {
            if (root.getAbsolutePath().equals(path)) {
                name = APP_FOLDER_NAME;
                break;
            }
        }
        return WorkspaceFolder.ofHostPath(
                WorkspaceFolder.PICKED_PATH_ID, path, name.isEmpty() ? path : name);
    }

    /**
     * Creates the picker root when the platform's own tree does not exist yet
     * (the app media folder on Android 10 is created on first use). Failures are
     * ignored: the browser then reports an unusable root instead of pretending.
     */
    public void preparePickerRoot() {
        WorkspaceFolder root = pickerRoot();
        if (root != null) {
            WorkspaceDirectory.ensureExists(root.getHostPath());
        }
    }

    /** Whether a path lives inside one of this app's own external directories. */
    public boolean isInsideAppExternalStorage(String path) {
        if (path == null) {
            return false;
        }
        for (File root : appExternalRoots()) {
            String prefix = root.getAbsolutePath();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private File primaryAppExternalRoot() {
        File[] media = context.getExternalMediaDirs();
        if (media != null && media.length > 0 && media[0] != null) {
            return media[0];
        }
        return context.getExternalFilesDir(null);
    }

    private List<File> appExternalRoots() {
        List<File> roots = new ArrayList<>();
        File files = context.getExternalFilesDir(null);
        if (files != null) {
            roots.add(files);
        }
        File[] media = context.getExternalMediaDirs();
        if (media != null) {
            for (File directory : media) {
                if (directory != null) {
                    roots.add(directory);
                }
            }
        }
        return roots;
    }

    /**
     * Whether the folder can really be used right now: a folder outside this
     * app's own external directories still needs the platform grant, and in
     * every case a probe write into the folder must succeed.
     */
    public boolean isUsable(WorkspaceFolder folder) {
        if (folder == null) {
            return false;
        }
        if (!isInsideAppExternalStorage(folder.getHostPath())
                && !(isSupportedPlatform() && hasAllFilesAccess())) {
            return false;
        }
        return WorkspaceDirectory.ensureUsable(folder.getHostPath());
    }
}
