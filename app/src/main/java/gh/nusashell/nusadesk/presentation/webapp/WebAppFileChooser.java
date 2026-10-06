package gh.nusashell.nusadesk.presentation.webapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.webapp.WebAppUploadRequest;
import gh.nusashell.nusadesk.infrastructure.webapp.CameraCapture;
import gh.nusashell.nusadesk.infrastructure.webapp.CameraCaptureProvider;
import gh.nusashell.nusadesk.infrastructure.webapp.PlatformMimeTypes;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppUploadIntents;

/**
 * The file chooser of one web-app surface (ADR-0059).
 *
 * <p>A page's {@code <input type="file">} — and, since WebView enabled the File
 * System Access API for apps on the 2026 platform release, {@code
 * showOpenFilePicker}/{@code showSaveFilePicker}/{@code showDirectoryPicker} —
 * reaches the surface through {@link WebChromeClient#onShowFileChooser}. The
 * platform answers nothing by itself, so this collaborator owns the whole
 * exchange: it asks the user which source to pick from, launches it, and
 * delivers exactly one answer to the page's callback.</p>
 *
 * <p>Sources, because this product's files are not all in the platform's
 * document providers (the rows carry a mark and a plain name — never a scheme
 * or a path):</p>
 * <ul>
 *   <li><b>Android files</b> — the system picker
 *       ({@link WebAppUploadIntents}), which returns {@code content://} URIs
 *       owned by the provider the user chose.</li>
 *   <li><b>NusaDesk files</b> — {@link LocalFilePickerDialog}, the built-in
 *       real-path browser, which returns {@code file://} URIs for paths the app
 *       itself may read. That is the view the Linux session works in: the active
 *       rootfs behind the guest's {@code /}, the workspace's real storage, and
 *       everything else PRoot binds by path.</li>
 *   <li><b>Camera</b> — the device camera ({@link CameraCapture}), offered when
 *       the page's accept can be satisfied by a photo; the capture lands in the
 *       app cache and is served back through {@link CameraCaptureProvider}.</li>
 * </ul>
 *
 * <p>Recording is not offered: the device-verified Samsung camcorder ignores an
 * app-owned {@code EXTRA_OUTPUT} and returns nothing, so a "Video" source would
 * silently answer the page with nothing (see {@link CameraCapture}).</p>
 *
 * <p>The camera needs the {@code CAMERA} grant: this app's manifest declares the
 * permission for the guest capability bridge, and the platform refuses a capture
 * intent to an app that declares it without holding it. The grant is asked for on
 * first use, and a refusal answers the page with "nothing chosen" like any other
 * cancel.</p>
 *
 * <p>Invariants the page depends on:</p>
 * <ul>
 *   <li>A request that returned {@code true} from {@code onShowFileChooser} is
 *       answered exactly once — a cancelled dialog, a cancelled picker, and a
 *       picker that cannot be launched all deliver {@code null}, which is the
 *       documented "nothing chosen" answer. An unanswered callback leaves the
 *       page's input waiting forever and blocks every later file input.</li>
 *   <li>One chooser per surface: a second request cancels the first, the same
 *       way the platform's own adapter does.</li>
 *   <li>The activity result is read here, not through
 *       {@code FileChooserParams.parseResult}: that helper reads only
 *       {@code Intent.getData()} and drops a multi-selection, which arrives as
 *       {@link ClipData}.</li>
 * </ul>
 *
 * <p>UI thread only: the WebView calls in on its own thread, the dialogs are
 * shown on it, and the activity result is delivered on it.</p>
 */
public final class WebAppFileChooser {

    /** Request code the host forwards from {@code onActivityResult}. */
    public static final int REQUEST_PICK_UPLOAD = 0x5707;
    /** Request code of the still-camera capture. */
    public static final int REQUEST_CAPTURE_PHOTO = 0x5708;
    /** Request code the host forwards from {@code onRequestPermissionsResult}. */
    public static final int REQUEST_CAMERA_PERMISSION = 0x5709;

    /** API level that introduced the writable/folder file-chooser contract. */
    private static final int FILE_SYSTEM_ACCESS_API = 37;

    private final Context context;
    private Pending pending;
    /** A dialog this chooser opened and still owns, if any. */
    private AlertDialog openDialog;

    /**
     * @param context the surface's context; a chooser can only act when it
     *                reaches an Activity, which this resolves at use time
     */
    public WebAppFileChooser(Context context) {
        this.context = context;
    }

    /**
     * Starts the chooser for one page request.
     *
     * @return {@code true} when the page's callback will be answered (this call
     *         or a later activity result); {@code false} when this surface
     *         cannot show a chooser at all, in which case the callback is left
     *         untouched for the platform to fail
     */
    public boolean show(
            WebChromeClient.FileChooserParams params, ValueCallback<Uri[]> callback) {
        Activity activity = activityOf(context);
        if (activity == null || params == null || callback == null) {
            return false;
        }
        finish(null);
        WebAppUploadRequest request = requestOf(params);
        pending = new Pending(callback);
        if (request.getMode() == WebAppUploadRequest.Mode.OPEN_FOLDER) {
            // A folder request is a document-tree request: only the system
            // picker can express it, so the source choice is skipped.
            startSystemPicker(activity, request);
            return true;
        }
        showSourceChooser(activity, request);
        return true;
    }

    /**
     * Delivers a picker's result to the request that launched it.
     *
     * <p>Only a request whose picker is really in front owns a result: a
     * superseded request (a second file input tapped while the dialog was open,
     * or a surface reloaded under it) must not have a stale pick — the wrong
     * file — handed to its callback.</p>
     *
     * @param requestCode the code the surface was started with
     * @return {@code true} when this chooser was waiting for the result
     */
    public boolean deliver(int requestCode, int resultCode, Intent data) {
        if (pending == null) {
            return false;
        }
        if (requestCode == REQUEST_CAPTURE_PHOTO) {
            finish(captureResult(resultCode, data));
            return true;
        }
        if (!pending.systemPickerLaunched) {
            return false;
        }
        finish(parseResult(resultCode, data));
        return true;
    }

    /**
     * Delivers the camera-permission outcome to the request that asked for it.
     *
     * @return {@code true} when this chooser was waiting for the grant
     */
    public boolean deliverCameraPermissionResult(boolean granted) {
        Pending current = pending;
        if (current == null || !current.awaitingCapture) {
            return false;
        }
        current.awaitingCapture = false;
        Activity activity = activityOf(context);
        if (granted && activity != null) {
            launchCapture(activity);
        } else {
            finish(null);
        }
        return true;
    }

    /**
     * Drops a pending request without answering it. Only for surface teardown:
     * the WebView that asked is being destroyed with the page, so there is no
     * input left to answer, and the renderer rejects a second answer anyway. A
     * dialog this chooser opened is dismissed with it, so nothing is left on
     * screen for a surface that is gone.
     */
    public void cancel() {
        dismissOpenDialog();
        pending = null;
    }

    private void showSourceChooser(Activity activity, WebAppUploadRequest request) {
        List<SourceRow> sources = sourcesFor(request);
        try {
            openDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.webapp_upload_source_title)
                    .setAdapter(new SourceAdapter(activity, sources), (dialog, which) -> {
                        if (which < 0 || which >= sources.size()) {
                            finish(null);
                            return;
                        }
                        switch (sources.get(which).kind) {
                            case ANDROID:
                                startSystemPicker(activity, request);
                                break;
                            case NUSADESK:
                                startLocalPicker(activity, request);
                                break;
                            case CAMERA:
                            default:
                                startCapture(activity);
                                break;
                        }
                    })
                    .setOnCancelListener(dialog -> finish(null))
                    .show();
        } catch (RuntimeException windowUnavailable) {
            // A window that cannot be shown (an activity that is finishing)
            // must not crash the renderer's callback: the page gets "nothing
            // chosen" instead.
            finish(null);
        }
    }

    /**
     * The sources this request may pick from, in a fixed order: the system
     * picker and the built-in browser always, and the camera when a photo can
     * satisfy the page's accept. A source that cannot satisfy the accept is
     * never offered, so a document input offers no camera.
     */
    private static List<SourceRow> sourcesFor(WebAppUploadRequest request) {
        PlatformMimeTypes mimeTypes = new PlatformMimeTypes();
        List<SourceRow> sources = new ArrayList<>();
        sources.add(new SourceRow(SourceKind.ANDROID,
                R.drawable.ic_source_android, R.string.webapp_upload_source_android));
        sources.add(new SourceRow(SourceKind.NUSADESK,
                R.drawable.ic_source_nusadesk, R.string.webapp_upload_source_nusadesk));
        if (request.allowsStillImage(mimeTypes)) {
            sources.add(new SourceRow(SourceKind.CAMERA,
                    R.drawable.ic_source_camera, R.string.webapp_upload_source_camera));
        }
        return sources;
    }

    /**
     * Starts a camera capture, asking for the {@code CAMERA} grant first when
     * this app does not hold it.
     */
    private void startCapture(Activity activity) {
        if (!CameraCapture.hasCameraPermission(activity)) {
            if (pending != null) {
                pending.awaitingCapture = true;
            }
            try {
                activity.requestPermissions(
                        new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            } catch (RuntimeException refused) {
                if (pending != null) {
                    pending.awaitingCapture = false;
                }
                finish(null);
            }
            return;
        }
        launchCapture(activity);
    }

    /** Launches the camera with a fresh cache target for one photo. */
    private void launchCapture(Activity activity) {
        try {
            File target = CameraCapture.newTarget(activity);
            activity.startActivityForResult(
                    CameraCapture.captureIntent(CameraCaptureProvider.uriFor(activity, target)),
                    REQUEST_CAPTURE_PHOTO);
            if (pending != null) {
                pending.captureTarget = target;
            }
        } catch (RuntimeException noCamera) {
            // No camera app answered (ActivityNotFoundException), or the target
            // could not be prepared: the page gets "nothing chosen" rather than
            // an input that waits forever.
            finish(null);
        }
    }

    /**
     * Reads a camera answer. The file this app prepared is preferred because it
     * is provably readable by the renderer; a camera app that ignored
     * {@code EXTRA_OUTPUT} and returned its own URI is the fallback, and an
     * empty or missing artifact answers {@code null} rather than handing the
     * page a zero-byte file.
     */
    private Uri[] captureResult(int resultCode, Intent data) {
        Pending current = pending;
        if (resultCode != Activity.RESULT_OK) {
            return null;
        }
        File target = current == null ? null : current.captureTarget;
        if (target != null && target.length() > 0) {
            return new Uri[]{CameraCaptureProvider.uriFor(context, target)};
        }
        Uri returned = data == null ? null : data.getData();
        return returned == null ? null : new Uri[]{returned};
    }

    private void startSystemPicker(Activity activity, WebAppUploadRequest request) {
        try {
            Intent intent = WebAppUploadIntents.pickerIntent(request);
            activity.startActivityForResult(intent, REQUEST_PICK_UPLOAD);
            if (pending != null) {
                pending.systemPickerLaunched = true;
            }
        } catch (ActivityNotFoundException noPicker) {
            // No document picker answered the intent: the page gets "nothing
            // chosen" rather than an input that waits forever.
            finish(null);
        } catch (RuntimeException launchFailed) {
            // Same contract for a picker that refuses to start at all.
            finish(null);
        }
    }

    private void startLocalPicker(Activity activity, WebAppUploadRequest request) {
        LocalFilePickerDialog.Selection selection;
        switch (request.getMode()) {
            case OPEN_MULTIPLE:
                selection = LocalFilePickerDialog.Selection.MULTIPLE_FILES;
                break;
            case SAVE:
                selection = LocalFilePickerDialog.Selection.NEW_FILE;
                break;
            case OPEN:
            case OPEN_FOLDER:
            default:
                selection = LocalFilePickerDialog.Selection.SINGLE_FILE;
                break;
        }
        // The built-in picker hands back the paths the user chose; wrapped as
        // file:// URIs they are what the renderer reads and what PRoot binds.
        openDialog = LocalFilePickerDialog.show(activity, new File("/"), selection,
                request.getFilenameHint(), files -> {
                    List<Uri> uris = new ArrayList<>();
                    for (File file : files) {
                        uris.add(Uri.fromFile(file));
                    }
                    finish(uris.toArray(new Uri[0]));
                });
        if (openDialog != null) {
            // Cancel, Back, and a touch outside all dismiss the dialog without
            // calling its listener: the dismissal itself is the third cancel
            // path, and it is what keeps the page's input from waiting forever.
            // After a successful pick this is a no-op (finish already ran).
            openDialog.setOnDismissListener(dialog -> finish(null));
        }
    }

    private void finish(Uri[] uris) {
        Pending current = pending;
        pending = null;
        dismissOpenDialog();
        if (current != null) {
            current.callback.onReceiveValue(uris);
        }
    }

    /** Dismisses a dialog this chooser opened, if one is still on screen. */
    private void dismissOpenDialog() {
        AlertDialog dialog = openDialog;
        openDialog = null;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /**
     * Reads the picker's answer: the clip data a multi-selection arrives in,
     * else the single data URI, else {@code null} for a cancelled picker. A
     * clip that carries no usable URI falls back to the data URI rather than
     * answering "nothing".
     */
    static Uri[] parseResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return null;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return null;
        }
        return uris.toArray(new Uri[0]);
    }

    private static WebAppUploadRequest requestOf(WebChromeClient.FileChooserParams params) {
        return WebAppUploadRequest.create(
                modeOf(params.getMode()),
                permissionOf(params),
                params.getAcceptTypes(),
                params.isCaptureEnabled(),
                params.getFilenameHint());
    }

    private static WebAppUploadRequest.Mode modeOf(int platformMode) {
        if (platformMode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            return WebAppUploadRequest.Mode.OPEN_MULTIPLE;
        }
        if (platformMode == WebChromeClient.FileChooserParams.MODE_SAVE) {
            return WebAppUploadRequest.Mode.SAVE;
        }
        if (platformMode == WebChromeClient.FileChooserParams.MODE_OPEN_FOLDER) {
            return WebAppUploadRequest.Mode.OPEN_FOLDER;
        }
        return WebAppUploadRequest.Mode.OPEN;
    }

    /**
     * The permission mode is a platform API 37 addition, so it is only asked
     * for on that platform and above; everything older is read-only, which is
     * what its file chooser contract ever expressed.
     */
    private static WebAppUploadRequest.Permission permissionOf(
            WebChromeClient.FileChooserParams params) {
        if (Build.VERSION.SDK_INT < FILE_SYSTEM_ACCESS_API) {
            return WebAppUploadRequest.Permission.READ;
        }
        return params.getPermissionMode()
                == WebChromeClient.FileChooserParams.PERMISSION_MODE_READ_WRITE
                ? WebAppUploadRequest.Permission.READ_WRITE
                : WebAppUploadRequest.Permission.READ;
    }

    /** Resolves the Activity a dialog or picker needs, unwrapping wrappers. */
    private static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    /** The one callback a page is waiting on, and where it is waiting. */
    private static final class Pending {
        private final ValueCallback<Uri[]> callback;
        /** Set once the system picker is really in front of this request. */
        private boolean systemPickerLaunched;
        /** Set while the camera permission for a queued capture is being asked. */
        private boolean awaitingCapture;
        /** The cache file a launched capture writes into. */
        private File captureTarget;

        private Pending(ValueCallback<Uri[]> callback) {
            this.callback = callback;
        }
    }

    /** Which source a row starts. */
    private enum SourceKind { ANDROID, NUSADESK, CAMERA }

    /**
     * One row of the source chooser: a mark and its plain name. A scheme or a
     * path in the label is noise to the person picking a file (and the built-in
     * picker states the real path itself), so the row carries only the name.
     */
    private static final class SourceRow {
        private final SourceKind kind;
        private final int iconRes;
        private final int labelRes;

        private SourceRow(SourceKind kind, int iconRes, int labelRes) {
            this.kind = kind;
            this.iconRes = iconRes;
            this.labelRes = labelRes;
        }
    }

    /** Renders {@link SourceRow}s as icon + label rows. */
    private static final class SourceAdapter extends ArrayAdapter<SourceRow> {
        private SourceAdapter(Context context, List<SourceRow> rows) {
            super(context, 0, rows);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : LayoutInflater.from(getContext())
                    .inflate(R.layout.widget_upload_source_row, parent, false);
            SourceRow source = getItem(position);
            ImageView icon = row.findViewById(R.id.upload_source_icon);
            TextView label = row.findViewById(R.id.upload_source_label);
            icon.setImageResource(source.iconRes);
            label.setText(source.labelRes);
            return row;
        }
    }
}
