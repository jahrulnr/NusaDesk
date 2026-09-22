package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code share} foreground operation behind {@code share.send}
 * ({@code termux-share}).
 *
 * <p>Params mirror the upstream script: {@code action} ({@code edit},
 * {@code send}, or {@code view} — default {@code view}), {@code mime}
 * (resolved by the caller: explicit content type or an extension guess),
 * {@code title} (mapped to {@link Intent#EXTRA_SUBJECT}), and
 * {@code default_receiver} (skip the chooser like upstream {@code -d}).
 * Exactly one of {@code text} (shared as {@link Intent#EXTRA_TEXT}) or
 * {@code host_path} (a file the module resolved inside the guest rootfs) is
 * present.</p>
 *
 * <p>Sharing a file needs a {@code content://} URI another app may read, and
 * the manifest declares no file-serving provider, so the file is staged into
 * the public {@code Downloads} collection through {@link MediaStore} — no
 * permission is needed there on API 29+ — and the resulting media URI is what
 * the chooser hands out with a read grant. The staged copy stays in
 * {@code Downloads}: it is the user's own file and removing it could break a
 * share target that reads lazily. The staged row is deleted again when the
 * copy itself fails.</p>
 *
 * <p>The chooser is fire-and-forget like upstream: a launched sheet answers
 * {@code shared=true}; the platform refusing the start (or a direct
 * {@code -d} intent with no receiver) is {@code share-unavailable}.</p>
 */
public final class ShareForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name {@code share.send} runs. */
    public static final String KIND = "share";

    private static final String TAG = "ShareForegroundOperation";
    private static final int COPY_BUFFER_BYTES = 8 * 1024;
    private static final int TEXT_MAX_CHARS = 8192;
    private static final int NAME_MAX_CHARS = 255;
    private static final int MIME_MAX_CHARS = 128;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        String action = stringParam(params.get("action"), "view");
        boolean defaultReceiver = booleanParam(params.get("default_receiver"), false);
        String title = stringParam(params.get("title"), "");
        String text = stringParam(params.get("text"), null);
        String hostPath = stringParam(params.get("host_path"), null);
        String name = stringParam(params.get("name"), "");
        String mime = stringParam(params.get("mime"), "");

        if (!"edit".equals(action) && !"send".equals(action) && !"view".equals(action)
                || name.length() > NAME_MAX_CHARS || mime.length() > MIME_MAX_CHARS
                || (text != null && text.length() > TEXT_MAX_CHARS)
                || (text != null) == (hostPath != null)) {
            sink.error("invalid-argument");
            return;
        }

        if (hostPath == null) {
            launch(activity, sink, action, defaultReceiver, title, mime, text, null);
            return;
        }
        Path source = Paths.get(hostPath);
        new Thread(() -> {
            Uri staged;
            try {
                staged = stageInDownloads(activity, source, name, mime);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "share staging failed", e);
                sink.error("share-unavailable");
                return;
            }
            if (staged == null) {
                sink.error("share-unavailable");
                return;
            }
            try {
                activity.runOnUiThread(() -> launch(
                        activity, sink, action, defaultReceiver, title, mime, null, staged));
            } catch (RuntimeException e) {
                sink.error("share-unavailable");
            }
        }, "share-stage").start();
    }

    /**
     * Copy {@code source} into the public {@code Downloads} collection and
     * return its media URI, or null when the provider refuses the insert.
     */
    private static Uri stageInDownloads(CapabilityForegroundActivity activity,
                                        Path source, String name, String mime)
            throws IOException {
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri staged = null;
        try {
            staged = resolver.insert(collection, values);
            if (staged == null) {
                return null;
            }
            try (InputStream in = new FileInputStream(source.toFile());
                    OutputStream out = resolver.openOutputStream(staged, "wt")) {
                if (out == null) {
                    throw new IOException("provider refused the staged download");
                }
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(staged, done, null, null);
            return staged;
        } catch (IOException | RuntimeException e) {
            if (staged != null) {
                try {
                    resolver.delete(staged, null, null);
                } catch (RuntimeException ignored) {
                    // Best effort: an orphaned pending row expires on its own.
                }
            }
            throw e;
        }
    }

    /** Build the share intent and launch the chooser (or the direct target). */
    private static void launch(CapabilityForegroundActivity activity, ResultSink sink,
                               String action, boolean defaultReceiver, String title,
                               String mime, String text, Uri stream) {
        String intentAction;
        switch (action) {
            case "edit":
                intentAction = Intent.ACTION_EDIT;
                break;
            case "send":
                intentAction = Intent.ACTION_SEND;
                break;
            default:
                intentAction = Intent.ACTION_VIEW;
                break;
        }
        String effectiveMime = mime.isEmpty()
                ? (stream != null ? "application/octet-stream" : "text/plain")
                : mime;
        Intent intent = new Intent(intentAction);
        if (!title.isEmpty()) {
            intent.putExtra(Intent.EXTRA_SUBJECT, title);
        }
        if (stream != null) {
            if (Intent.ACTION_SEND.equals(intentAction)) {
                intent.putExtra(Intent.EXTRA_STREAM, stream);
                intent.setType(effectiveMime);
            } else {
                intent.setDataAndType(stream, effectiveMime);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.putExtra(Intent.EXTRA_TEXT, text);
            intent.setType(effectiveMime);
        }
        Intent launch = defaultReceiver
                ? intent
                : Intent.createChooser(intent, "Share");
        if (stream != null) {
            // The chooser is its own intent: the platform hands the chosen
            // target the inner intent's grant, but the chooser intent must
            // carry the read flag as well or a receiver can end up with a
            // content:// URI it cannot open. Device-observed on the S10e
            // (2026-09-22): Google's viewer answered "cannot load object" for
            // a shared MediaStore URI until the chooser carried the grant.
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        try {
            activity.startActivity(launch);
        } catch (RuntimeException e) {
            Log.w(TAG, "share launch refused", e);
            sink.error("share-unavailable");
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("shared", true);
        if (stream != null) {
            fields.put("uri", stream.toString());
        }
        sink.success(fields);
    }

    private static String stringParam(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static boolean booleanParam(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
