package gh.nusashell.nusadesk.infrastructure.webapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;

/**
 * The device-camera capture half of a web-app upload (ADR-0059): the intent a
 * camera app answers, where its artifact lands, and whether this app may ask
 * for the camera at all.
 *
 * <p>The capture writes into a cache file this app owns — a camera app cannot
 * write into another app's private directory, so the file is handed over as a
 * URI from {@link CameraCaptureProvider}.</p>
 *
 * <p>{@code CAMERA} is required, not optional: this app's manifest already
 * declares the permission for the guest capability bridge, and the platform
 * refuses {@code ACTION_IMAGE_CAPTURE} to an app that declares it without
 * holding it. The caller therefore asks for the grant on first use.</p>
 *
 * <p>Recording is deliberately not offered. Android splits capture into a
 * separate {@code ACTION_VIDEO_CAPTURE}, and the device-verified Samsung
 * camcorder (S10e, Android 12) ignores {@code EXTRA_OUTPUT}: it wrote zero
 * bytes into an app-owned target, saved nothing itself, and returned no URI, so
 * a "Video" source would silently answer the page with nothing. A later slice
 * that wants it needs a {@code MediaStore} target (a recording that lands in
 * the user's gallery) or its own recorder; neither is this slice.</p>
 */
public final class CameraCapture {

    /** Prefix of a capture file's name, so a leftover is recognizable. */
    private static final String FILE_PREFIX = "photo";
    /** Extension of a capture file, and the type the provider reports for it. */
    private static final String FILE_EXTENSION = "jpg";

    /**
     * How long a capture may stay in the cache. The page reads its file during
     * the session that produced it; anything older is a leftover from a killed
     * app, and sweeping it keeps the store from growing without bound.
     */
    static final long MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;

    private CameraCapture() {
    }

    /**
     * Creates the cache file one photo will be written to, sweeping leftovers
     * from earlier sessions.
     *
     * @return the file to pass to {@link CameraCaptureProvider#uriFor(Context, File)}
     * @throws IllegalStateException when the capture directory cannot be created
     */
    public static File newTarget(Context context) {
        File directory = CameraCaptureProvider.directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create " + directory);
        }
        sweep(directory, System.currentTimeMillis() - MAX_AGE_MILLIS);
        return new File(directory,
                FILE_PREFIX + "-" + System.currentTimeMillis() + "." + FILE_EXTENSION);
    }

    /** The intent a camera app answers: it writes its photo into {@code target}. */
    public static Intent captureIntent(Uri target) {
        return new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, target)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    /** Whether the camera may be used without asking for the grant first. */
    public static boolean hasCameraPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Removes capture files older than {@code oldestKeptMillis}. */
    static void sweep(File directory, long oldestKeptMillis) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile() && file.lastModified() < oldestKeptMillis) {
                // A leftover the page can no longer be reading; a failure to
                // delete it just leaves it for the next sweep.
                file.delete();
            }
        }
    }
}
