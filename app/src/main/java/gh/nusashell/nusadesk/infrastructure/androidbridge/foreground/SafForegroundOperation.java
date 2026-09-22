package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code saf} foreground operation behind {@code saf.manage} and
 * {@code storage.get}.
 *
 * <p>Param {@code op} selects the picker: {@code open-tree} launches
 * {@link Intent#ACTION_OPEN_DOCUMENT_TREE} and persists the returned grant
 * with {@code takePersistableUriPermission} so the later {@code saf.*}
 * document methods can use it. A cancelled picker reports
 * {@code uri=""} — the empty string upstream {@code termux-saf-managedir}
 * prints, not an error.</p>
 *
 * <p>{@code open-file} launches {@link Intent#ACTION_OPEN_DOCUMENT}
 * ({@code CATEGORY_OPENABLE}, {@code *}/*) for {@code storage.get}. The
 * picked document is copied to {@code host_path} — the host path the calling
 * module already resolved from the guest staging path — on a worker thread
 * while the activity is still alive, because a transient URI grant dies with
 * the delivering activity. No persistable grant is taken: the grant is only
 * needed for this one copy, and persisting it would leak the picked document
 * into {@code saf.trees} (which upstream lists as managed trees only).</p>
 *
 * <p>A cancelled file picker reports {@code picked=false}; success reports
 * {@code picked=true} plus {@code uri}, {@code name}, {@code mime}, and the
 * copied {@code bytes}.</p>
 */
public final class SafForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name {@code saf.manage}/{@code storage.get} run. */
    public static final String KIND = "saf";

    private static final String TAG = "SafForegroundOperation";
    private static final int REQUEST_TREE = 0x5A10;
    private static final int REQUEST_FILE = 0x5A11;
    private static final int COPY_BUFFER_BYTES = 8 * 1024;
    private static final int HOST_PATH_MAX_CHARS = 8192;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        String op = stringParam(params.get("op"), "");
        if ("open-tree".equals(op)) {
            openTree(activity, sink);
        } else if ("open-file".equals(op)) {
            openFile(activity, params, sink);
        } else {
            sink.error("invalid-argument");
        }
    }

    /**
     * Ask for a whole document tree and persist read+write on the grant.
     * Providers that only offer a read grant are retried read-only rather
     * than failing the pick outright.
     */
    @SuppressWarnings("deprecation")
    private void openTree(CapabilityForegroundActivity activity, ResultSink sink) {
        activity.setActivityResultHandler((requestCode, resultCode, data) -> {
            if (requestCode != REQUEST_TREE) {
                return;
            }
            Uri tree = data == null ? null : data.getData();
            if (resultCode != Activity.RESULT_OK || tree == null) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("uri", "");
                sink.success(fields);
                return;
            }
            try {
                activity.getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException readWriteRefused) {
                try {
                    activity.getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (RuntimeException readRefused) {
                    Log.w(TAG, "tree grant persist failed", readRefused);
                    sink.error("saf-unavailable");
                    return;
                }
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("uri", tree.toString());
            sink.success(fields);
        });
        try {
            activity.startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_TREE);
        } catch (RuntimeException e) {
            sink.error("saf-unavailable");
        }
    }

    /**
     * Ask for one openable document and copy its content to the staging file
     * at {@code host_path}. The copy runs off the main thread but before the
     * activity finishes, so the picker's transient read grant is still valid.
     */
    @SuppressWarnings("deprecation")
    private void openFile(CapabilityForegroundActivity activity,
                          Map<String, Object> params, ResultSink sink) {
        String hostPath = stringParam(params.get("host_path"), "");
        if (hostPath.isEmpty() || hostPath.length() > HOST_PATH_MAX_CHARS) {
            sink.error("invalid-argument");
            return;
        }
        activity.setActivityResultHandler((requestCode, resultCode, data) -> {
            if (requestCode != REQUEST_FILE) {
                return;
            }
            Uri document = data == null ? null : data.getData();
            if (resultCode != Activity.RESULT_OK || document == null) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("picked", false);
                sink.success(fields);
                return;
            }
            new Thread(() -> copyDocument(activity, document, hostPath, sink),
                    "saf-storage-get").start();
        });
        try {
            activity.startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"),
                    REQUEST_FILE);
        } catch (RuntimeException e) {
            sink.error("storage-unavailable");
        }
    }

    /** Copy the picked document into {@code hostPath}; reports through {@code sink}. */
    private void copyDocument(CapabilityForegroundActivity activity, Uri document,
                              String hostPath, ResultSink sink) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("picked", true);
        fields.put("uri", document.toString());
        try {
            String[] metadata = documentMetadata(activity, document);
            if (metadata[0] != null) {
                fields.put("name", metadata[0]);
            }
            if (metadata[1] != null) {
                fields.put("mime", metadata[1]);
            }
            Path target = Paths.get(hostPath);
            long bytes;
            try (InputStream in = activity.getContentResolver().openInputStream(document);
                    OutputStream out = Files.newOutputStream(target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                if (in == null) {
                    sink.error("storage-unavailable");
                    return;
                }
                bytes = copy(in, out);
            }
            fields.put("bytes", bytes);
            sink.success(fields);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "storage.get copy failed", e);
            sink.error("storage-unavailable");
        }
    }

    /** The picked document's display name and mime type, or nulls when absent. */
    private static String[] documentMetadata(CapabilityForegroundActivity activity,
                                             Uri document) {
        String[] result = new String[2];
        try (Cursor cursor = activity.getContentResolver().query(document,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                result[0] = cursor.getString(0);
            }
        } catch (RuntimeException e) {
            // A provider without the column still yields a valid copy.
        }
        try {
            result[1] = activity.getContentResolver().getType(document);
        } catch (RuntimeException e) {
            // As above.
        }
        return result;
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static String stringParam(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }
}
