package gh.nusashell.nusadesk.infrastructure.webapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves the one camera artifact a hosted page's file input is waiting for
 * (ADR-0059).
 *
 * <p>The device camera writes a photo or a video into the app cache through
 * this provider, because a camera app cannot write into another app's private
 * directory by itself and this app does not expose its storage to anyone. The
 * provider is therefore minimal by design:</p>
 *
 * <ul>
 *   <li>it is never exported; the camera app reaches one file at a time through
 *       the per-URI grant the launch intent carries
 *       ({@code FLAG_GRANT_WRITE_URI_PERMISSION}), and the WebView renderer
 *       reads the same URI back through this app;</li>
 *   <li>it serves only files directly inside {@link #directory(Context)}
 *       (the app cache's {@code camera} directory) and refuses anything else,
 *       including names that try to escape it;</li>
 *   <li>it is read/write for one file and offers only the two columns camera
 *       apps actually ask for ({@link OpenableColumns#DISPLAY_NAME} and
 *       {@link OpenableColumns#SIZE}); insert, update, and delete are not a
 *       contract this app has.</li>
 * </ul>
 */
public final class CameraCaptureProvider extends ContentProvider {

    /** Directory under the app cache that every capture lands in. */
    static final String DIRECTORY = "camera";

    /** The authority this provider answers on; derived so every build variant matches. */
    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".camera")
                .appendPath(DIRECTORY)
                .appendPath(file.getName())
                .build();
    }

    /** The directory every capture is written to; created on demand by the caller. */
    public static File directory(Context context) {
        return new File(context.getCacheDir(), DIRECTORY);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        File file = fileFor(uri);
        if (file == null) {
            return null;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String mimeType = dot < 0
                ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1));
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null) {
            throw new FileNotFoundException("not a capture file: " + uri);
        }
        boolean writable = mode != null && mode.indexOf('w') >= 0;
        int flags = writable
                ? ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        try {
            return ParcelFileDescriptor.open(file, flags);
        } catch (IOException | RuntimeException failed) {
            throw new FileNotFoundException("cannot open " + file.getName() + ": " + failed);
        }
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        if (file == null) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("the capture store is write-through-file only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("the capture store is write-through-file only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("the capture store is write-through-file only");
    }

    /**
     * Resolves a URI to a file inside the capture directory, or {@code null}
     * when it names anything else — a different directory, a name with a path
     * separator, or an entry that escapes the directory once resolved.
     */
    private File fileFor(Uri uri) {
        Context context = getContext();
        if (context == null || uri == null) {
            return null;
        }
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !DIRECTORY.equals(segments.get(0))) {
            return null;
        }
        String name = segments.get(1);
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return null;
        }
        File directory = directory(context);
        File candidate = new File(directory, name);
        try {
            String directoryPath = directory.getCanonicalPath();
            String candidatePath = candidate.getCanonicalPath();
            if (!candidatePath.startsWith(directoryPath + File.separator)) {
                return null;
            }
        } catch (IOException unresolved) {
            return null;
        }
        return candidate;
    }
}
