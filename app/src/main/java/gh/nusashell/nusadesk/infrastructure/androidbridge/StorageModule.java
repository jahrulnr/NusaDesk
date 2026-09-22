package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.app.DownloadManager;
import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import gh.nusashell.nusadesk.application.workspace.WorkspaceStore;
import gh.nusashell.nusadesk.domain.workspace.WorkspaceFolder;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.SafForegroundOperation;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.ShareForegroundOperation;
import gh.nusashell.nusadesk.infrastructure.workspace.SharedPreferencesWorkspaceStore;

/**
 * Storage capability domain: SAF tree/document access, the storage picker,
 * the share sheet, media scanning, wallpaper, and the system download
 * manager — thirteen bridge methods.
 *
 * <p>SAF mirrors the upstream {@code termux-saf-*} contract: a tree grant is
 * taken once through {@code ACTION_OPEN_DOCUMENT_TREE} and persisted
 * ({@code saf.manage}), then list/stat/create/read/write/remove run against
 * {@link DocumentsContract} under that grant. Entry objects keep the upstream
 * key set ({@code name}, {@code type}, {@code uri}, {@code last_modified},
 * {@code length} for non-directories) and are handed to the guest as
 * pre-encoded JSON strings, like the other modules' {@code *_json} fields.</p>
 *
 * <p>{@code saf.read} returns the document bytes base64-encoded in
 * {@code data}, bounded per call: {@link #SAF_READ_MAX_BYTES} is sized so the
 * encoded payload stays inside the guest's 16 KiB response budget and the
 * 64 KiB wire frame; {@code truncated} tells the script to keep paging with
 * {@code offset}. {@code saf.write} takes the opposite direction through the
 * staging rule (contract 8.1): the script stages the bytes in guest
 * {@code /tmp} and passes that guest path, resolved here with
 * {@link GuestFilePathResolver}.</p>
 *
 * <p>{@code storage.get} and {@code share.send} are interactive and run
 * through {@link CapabilityForegroundHost} — see
 * {@link SafForegroundOperation} and {@link ShareForegroundOperation}.
 * {@code media.scan} resolves guest paths like every file method, plus the
 * workspace bind (guest {@code ~/nusadesk} → the user-picked shared folder):
 * files inside the private rootfs cannot be indexed by the media provider,
 * and the callback-confirmed {@code scanned} count reports that honestly
 * instead of pretending.</p>
 *
 * <p>Every method validates params first ({@link CapabilityParams}, unknown
 * keys fail closed), never throws past {@link #handle}, and maps platform
 * failures to lowercase-kebab typed errors — {@code SecurityException} on a
 * document URI is {@code saf-permission-denied}, an unparseable URI is
 * {@code saf-invalid-uri}; platform exception messages are logged, never put
 * on the wire.</p>
 */
public final class StorageModule implements CapabilityModule {
    private static final String TAG = "StorageModule";

    private static final String METHOD_SAF_MANAGE = "saf.manage";
    private static final String METHOD_SAF_TREES = "saf.trees";
    private static final String METHOD_SAF_LIST = "saf.list";
    private static final String METHOD_SAF_STAT = "saf.stat";
    private static final String METHOD_SAF_CREATE = "saf.create";
    private static final String METHOD_SAF_READ = "saf.read";
    private static final String METHOD_SAF_WRITE = "saf.write";
    private static final String METHOD_SAF_REMOVE = "saf.remove";
    private static final String METHOD_STORAGE_GET = "storage.get";
    private static final String METHOD_SHARE_SEND = "share.send";
    private static final String METHOD_MEDIA_SCAN = "media.scan";
    private static final String METHOD_WALLPAPER_SET = "wallpaper.set";
    private static final String METHOD_DOWNLOAD_REQUEST = "download.request";

    /** Bounded wait for a parked picker/chooser operation, per the contract. */
    private static final long FOREGROUND_TIMEOUT_MILLIS = 120_000L;

    private static final int URI_MAX_CHARS = 2048;
    private static final int NAME_MAX_CHARS = 512;
    private static final int MIME_MAX_CHARS = 128;
    private static final int PATH_MAX_CHARS = 4096;
    private static final int TEXT_MAX_CHARS = 8192;
    private static final int TITLE_MAX_CHARS = 512;
    private static final int DOWNLOAD_NAME_MAX_CHARS = 255;

    /**
     * Raw bytes one {@code saf.read} call may return. The guest decodes a
     * base64 field inside a response line capped at 16 KiB, so 8 KiB of
     * binary (~10.7 KiB encoded) is the transport-safe per-call bound; the
     * script pages through {@code offset} until {@code truncated} clears.
     */
    private static final int SAF_READ_MAX_BYTES = 8 * 1024;

    /** Bound on a pre-encoded SAF entry array, inside the guest response cap. */
    private static final int ENTRIES_JSON_MAX_CHARS = 12 * 1024;
    private static final int LIST_MAX_ENTRIES = 512;

    private static final int SCAN_MAX_PATHS = 64;
    private static final int SCAN_WAIT_MILLIS = 10_000;

    private static final long SHARE_MAX_BYTES = 64L * 1024 * 1024;
    private static final long WALLPAPER_MAX_BYTES = 32L * 1024 * 1024;
    private static final int WALLPAPER_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int WALLPAPER_READ_TIMEOUT_MILLIS = 30_000;
    private static final int WALLPAPER_MAX_DIMENSION = 4096;

    private static final String DIR_MIME = "vnd.android.document/directory";
    private static final String DEFAULT_CREATE_MIME = "application/octet-stream";

    private final Context context;
    private final ContentResolver resolver;
    private final GuestFilePathResolver paths;
    private final CapabilityForegroundHost foregroundHost;
    private final AndroidPermissionChecker checker;
    private final WorkspaceStore workspaceStore;

    public StorageModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        this.context = application;
        this.resolver = application.getContentResolver();
        this.paths = new GuestFilePathResolver(application);
        this.foregroundHost = new CapabilityForegroundHost(application);
        this.checker = new AndroidPermissionChecker(application);
        this.workspaceStore = new SharedPreferencesWorkspaceStore(application);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_SAF_MANAGE, METHOD_SAF_TREES, METHOD_SAF_LIST,
                METHOD_SAF_STAT, METHOD_SAF_CREATE, METHOD_SAF_READ,
                METHOD_SAF_WRITE, METHOD_SAF_REMOVE, METHOD_STORAGE_GET,
                METHOD_SHARE_SEND, METHOD_MEDIA_SCAN, METHOD_WALLPAPER_SET,
                METHOD_DOWNLOAD_REQUEST);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_SAF_LIST, METHOD_SAF_STAT, METHOD_SAF_CREATE,
                METHOD_SAF_READ, METHOD_SAF_WRITE, METHOD_SAF_REMOVE,
                METHOD_STORAGE_GET, METHOD_SHARE_SEND, METHOD_MEDIA_SCAN,
                METHOD_WALLPAPER_SET, METHOD_DOWNLOAD_REQUEST);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        try {
            switch (request.getMethod()) {
                case METHOD_SAF_MANAGE:
                    return safManage(request);
                case METHOD_SAF_TREES:
                    return safTrees(request);
                case METHOD_SAF_LIST:
                    return safList(request);
                case METHOD_SAF_STAT:
                    return safStat(request);
                case METHOD_SAF_CREATE:
                    return safCreate(request);
                case METHOD_SAF_READ:
                    return safRead(request);
                case METHOD_SAF_WRITE:
                    return safWrite(request);
                case METHOD_SAF_REMOVE:
                    return safRemove(request);
                case METHOD_STORAGE_GET:
                    return storageGet(request);
                case METHOD_SHARE_SEND:
                    return shareSend(request);
                case METHOD_MEDIA_SCAN:
                    return mediaScan(request);
                case METHOD_WALLPAPER_SET:
                    return wallpaperSet(request);
                case METHOD_DOWNLOAD_REQUEST:
                    return downloadRequest(request);
                default:
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "unsupported-method");
            }
        } catch (GuestFilePathResolver.Invalid invalid) {
            // A guest path outside the rootfs is a bad argument, not a
            // capability failure.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "invalid-argument");
        }
    }

    @Override
    public void close() {
        foregroundHost.close();
    }

    /**
     * {@code saf.manage} — no params. Opens the document-tree picker in the
     * foreground and persists the grant; the response carries the tree
     * {@code uri} (empty when the user dismissed the picker, like upstream's
     * empty line).
     */
    private AndroidCapabilityProtocol.Response safManage(
            AndroidCapabilityProtocol.Request request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("op", "open-tree");
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                SafForegroundOperation.KIND, params, FOREGROUND_TIMEOUT_MILLIS);
        return rewrap(request.getId(), result);
    }

    /**
     * {@code saf.trees} — no params. Stats every persisted URI permission
     * into the upstream entry shape, as one {@code entries_json} array.
     */
    private AndroidCapabilityProtocol.Response safTrees(
            AndroidCapabilityProtocol.Request request) {
        JsonListBuilder entries = new JsonListBuilder();
        List<UriPermission> permissions;
        try {
            permissions = resolver.getPersistedUriPermissions();
        } catch (RuntimeException e) {
            Log.w(TAG, "saf.trees failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "saf-unavailable");
        }
        try {
            for (UriPermission permission : permissions) {
                Uri document = toDocumentUri(permission.getUri());
                String entry = statEntryJson(document);
                if (entry != null && !entries.add(entry)) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "saf.trees stat failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "saf-unavailable");
        }
        return entries.respond(request.getId());
    }

    /**
     * {@code saf.list} — param {@code uri} (tree or document URI of a
     * folder). Answers {@code entries_json} of child documents, bounded.
     */
    private AndroidCapabilityProtocol.Response safList(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("uri"));
        Uri uri = parseSafUri(params.requireString("uri", URI_MAX_CHARS));
        if (uri == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        String documentId;
        Uri childrenUri;
        try {
            documentId = documentIdFor(uri);
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId);
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        JsonListBuilder entries = new JsonListBuilder();
        try (Cursor children = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null, null, null)) {
            if (children != null) {
                while (children.moveToNext() && entries.count() < LIST_MAX_ENTRIES) {
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(
                            uri, children.getString(0));
                    String entry = statEntryJson(child);
                    if (entry != null && !entries.add(entry)) {
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            return safPlatformError(request.getId(), e);
        }
        return entries.respond(request.getId());
    }

    /**
     * {@code saf.stat} — param {@code uri}. Answers {@code found} plus
     * {@code entry_json} in the upstream entry shape; a document the provider
     * no longer resolves is {@code found=false} with empty output, matching
     * upstream's empty stat.
     */
    private AndroidCapabilityProtocol.Response safStat(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("uri"));
        Uri uri = parseSafUri(params.requireString("uri", URI_MAX_CHARS));
        if (uri == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        String entry;
        try {
            entry = statEntryJson(toDocumentUri(uri));
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        } catch (RuntimeException e) {
            return safPlatformError(request.getId(), e);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("found", entry != null);
        if (entry != null) {
            fields.put("entry_json", entry);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code saf.create} — params {@code uri} (parent folder), {@code name},
     * optional {@code mime} (default {@code application/octet-stream};
     * {@code vnd.android.document/directory} for {@code termux-saf-mkdir}).
     * Answers {@code created} and the new document {@code uri}.
     */
    private AndroidCapabilityProtocol.Response safCreate(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("uri", "name", "mime"));
        Uri uri = parseSafUri(params.requireString("uri", URI_MAX_CHARS));
        if (uri == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        String name = params.requireString("name", NAME_MAX_CHARS);
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new CapabilityParams.Invalid("bad document name");
        }
        String mime = params.optionalString("mime", MIME_MAX_CHARS, "");
        if (mime.isEmpty()) {
            mime = DEFAULT_CREATE_MIME;
        }
        Uri created;
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    uri, documentIdFor(uri));
            created = DocumentsContract.createDocument(resolver, parent, mime, name);
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        } catch (java.io.FileNotFoundException e) {
            // createDocument throws a checked FileNotFoundException when the
            // parent no longer resolves; that is a typed not-found, not a crash.
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-not-found");
        } catch (RuntimeException e) {
            return safPlatformError(request.getId(), e);
        }
        if (created == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("created", true);
        fields.put("uri", created.toString());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code saf.read} — params {@code uri}, {@code offset} (default 0) and
     * {@code length} (1..{@link #SAF_READ_MAX_BYTES}, default max). Answers
     * {@code data} (base64), {@code bytes}, the echoed {@code offset}, and
     * {@code truncated} when more content remains.
     */
    private AndroidCapabilityProtocol.Response safRead(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("uri", "offset", "length"));
        Uri uri = parseSafUri(params.requireString("uri", URI_MAX_CHARS));
        if (uri == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        long offset = params.optionalLong("offset", 0L, Long.MAX_VALUE, 0L);
        int length = (int) params.optionalLong(
                "length", 1L, SAF_READ_MAX_BYTES, SAF_READ_MAX_BYTES);
        Uri document;
        try {
            document = toDocumentUri(uri);
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        byte[] buffer = new byte[length];
        int read = 0;
        boolean truncated;
        try (InputStream in = openDocumentStream(document)) {
            skipFully(in, offset);
            while (read < length) {
                int chunk = in.read(buffer, read, length - read);
                if (chunk < 0) {
                    break;
                }
                read += chunk;
            }
            truncated = in.read() != -1;
        } catch (FileNotFoundException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-not-found");
        } catch (RuntimeException e) {
            return safPlatformError(request.getId(), e);
        } catch (IOException e) {
            Log.w(TAG, "saf.read failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("data", Base64.getEncoder().encodeToString(
                read == buffer.length ? buffer
                        : java.util.Arrays.copyOf(buffer, read)));
        fields.put("bytes", (long) read);
        fields.put("offset", offset);
        fields.put("truncated", truncated);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code saf.write} — params {@code uri} and {@code path} (guest staging
     * file holding the bytes). Truncates the document like upstream's
     * {@code "rwt"} open mode, with a {@code "wt"} fallback for providers
     * that reject it. Answers {@code written} and {@code bytes}.
     */
    private AndroidCapabilityProtocol.Response safWrite(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("uri", "path"));
        Uri uri = parseSafUri(params.requireString("uri", URI_MAX_CHARS));
        if (uri == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        Path source = paths.resolveForRead(
                params.requireString("path", PATH_MAX_CHARS));
        Uri document;
        try {
            document = toDocumentUri(uri);
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        long bytes;
        try (InputStream in = Files.newInputStream(source)) {
            OutputStream out = openForTruncateWrite(document);
            try {
                if (out == null) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "saf-unavailable");
                }
                bytes = copy(in, out);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        Log.w(TAG, "saf.write close failed", e);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-not-found");
        } catch (RuntimeException e) {
            return safPlatformError(request.getId(), e);
        } catch (IOException e) {
            Log.w(TAG, "saf.write failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("written", true);
        fields.put("bytes", bytes);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code saf.remove} — param {@code uri}. Answers {@code deleted}; a
     * document that no longer exists is {@code saf-not-found}, matching the
     * upstream exit-code distinction between "couldn't delete" and an error.
     */
    private AndroidCapabilityProtocol.Response safRemove(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("uri"));
        Uri uri = parseSafUri(params.requireString("uri", URI_MAX_CHARS));
        if (uri == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        }
        boolean deleted;
        try {
            deleted = DocumentsContract.deleteDocument(resolver, uri);
        } catch (FileNotFoundException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-not-found");
        } catch (IllegalArgumentException e) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "saf-invalid-uri");
        } catch (RuntimeException e) {
            return safPlatformError(request.getId(), e);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deleted", deleted);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code storage.get} — param {@code path} (guest staging path the picked
     * document is copied to). Runs the {@code open-file} picker through the
     * foreground host and answers {@code copied} plus the picked
     * {@code uri}/{@code name}/{@code mime}/{@code bytes}. A dismissed picker
     * is {@code copied=false}, matching upstream's silent exit.
     */
    private AndroidCapabilityProtocol.Response storageGet(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("path"));
        String guestPath = params.requireString("path", PATH_MAX_CHARS);
        Path staging = paths.resolveForWrite(guestPath);
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", "open-file");
        opParams.put("host_path", staging.toString());
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                SafForegroundOperation.KIND, opParams, FOREGROUND_TIMEOUT_MILLIS);
        if (!result.isOk()) {
            return rewrap(request.getId(), result);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        boolean copied = Boolean.TRUE.equals(result.getFields().get("picked"));
        fields.put("copied", copied);
        fields.put("path", guestPath);
        if (copied) {
            copyField(result.getFields(), fields, "uri");
            copyField(result.getFields(), fields, "name");
            copyField(result.getFields(), fields, "mime");
            copyField(result.getFields(), fields, "bytes");
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code share.send} — params {@code action} (edit|send|view, default
     * view), {@code content_type}, {@code title}, {@code default_receiver},
     * and exactly one of {@code text} or {@code path} (guest staging file).
     * The chooser runs in the foreground; a staged file reports its
     * {@code uri} too so the staging into public Downloads is never hidden.
     */
    private AndroidCapabilityProtocol.Response shareSend(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("action", "content_type", "title",
                "default_receiver", "text", "path"));
        String action = params.optionalString("action", 16, "view");
        if (!"edit".equals(action) && !"send".equals(action) && !"view".equals(action)) {
            throw new CapabilityParams.Invalid("unsupported share action: " + action);
        }
        String contentType = params.optionalString("content_type", MIME_MAX_CHARS, "");
        String title = params.optionalString("title", TITLE_MAX_CHARS, "");
        boolean defaultReceiver = params.optionalBoolean("default_receiver", false);
        boolean hasText = params.has("text");
        boolean hasPath = params.has("path");
        if (hasText == hasPath) {
            throw new CapabilityParams.Invalid(
                    "exactly one of text or path is required");
        }

        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("action", action);
        if (!title.isEmpty()) {
            opParams.put("title", title);
        }
        if (defaultReceiver) {
            opParams.put("default_receiver", true);
        }
        if (hasText) {
            opParams.put("text", params.requireString("text", TEXT_MAX_CHARS));
            opParams.put("mime", contentType.isEmpty() ? "text/plain" : contentType);
        } else {
            String guestPath = params.requireString("path", PATH_MAX_CHARS);
            Path source = paths.resolveForRead(guestPath);
            try {
                if (Files.size(source) > SHARE_MAX_BYTES) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "share-too-large");
                }
            } catch (IOException e) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "share-unavailable");
            }
            String name = source.getFileName().toString();
            String mime = contentType.isEmpty() ? guessMime(name) : contentType;
            opParams.put("host_path", source.toString());
            opParams.put("name", name);
            opParams.put("mime", mime);
        }
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                ShareForegroundOperation.KIND, opParams, FOREGROUND_TIMEOUT_MILLIS);
        return rewrap(request.getId(), result);
    }

    /**
     * {@code media.scan} — params {@code paths} (newline-separated guest
     * paths, at most {@link #SCAN_MAX_PATHS}), {@code recursive},
     * {@code verbose} (accepted for upstream parity; the script expands
     * directories itself). Each path resolves inside the rootfs or — for
     * guest {@code ~/nusadesk/...} — to the bound workspace folder, then goes
     * to {@link MediaScannerConnection#scanFile}. Answers {@code requested},
     * the callback-confirmed {@code scanned} count, {@code failed}, and a
     * bounded {@code results_json} of per-path outcomes.
     */
    private AndroidCapabilityProtocol.Response mediaScan(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("paths", "recursive", "verbose"));
        String rawPaths = params.requireString("paths", TEXT_MAX_CHARS);
        List<String> guestPaths = new ArrayList<>();
        for (String entry : rawPaths.split("\n")) {
            if (entry.trim().isEmpty()) {
                continue;
            }
            if (entry.length() > PATH_MAX_CHARS) {
                throw new CapabilityParams.Invalid("path entry too long");
            }
            guestPaths.add(entry);
            if (guestPaths.size() > SCAN_MAX_PATHS) {
                throw new CapabilityParams.Invalid("too many scan paths");
            }
        }
        if (guestPaths.isEmpty()) {
            throw new CapabilityParams.Invalid("missing list parameter: paths");
        }

        List<String> hostPaths = new ArrayList<>();
        List<Integer> hostIndex = new ArrayList<>();
        for (int i = 0; i < guestPaths.size(); i++) {
            String hostPath = scanHostPath(guestPaths.get(i));
            if (hostPath != null) {
                hostPaths.add(hostPath);
                hostIndex.add(i);
            }
        }

        boolean[] confirmed = new boolean[guestPaths.size()];
        if (!hostPaths.isEmpty()) {
            CountDownLatch done = new CountDownLatch(hostPaths.size());
            Map<String, Integer> byHostPath = new LinkedHashMap<>();
            for (int i = 0; i < hostPaths.size(); i++) {
                byHostPath.put(hostPaths.get(i), hostIndex.get(i));
            }
            try {
                MediaScannerConnection.scanFile(context,
                        hostPaths.toArray(new String[0]), null,
                        (path, uri) -> {
                            Integer index = byHostPath.get(path);
                            if (index != null && uri != null) {
                                confirmed[index] = true;
                            }
                            done.countDown();
                        });
                try {
                    done.await(SCAN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "media.scan failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "media-unavailable");
            }
        }
        int scanned = 0;
        for (boolean ok : confirmed) {
            if (ok) {
                scanned++;
            }
        }
        int failed = guestPaths.size() - scanned;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requested", (long) guestPaths.size());
        fields.put("scanned", (long) scanned);
        fields.put("failed", (long) failed);
        fields.put("results_json", scanResultsJson(guestPaths, confirmed));
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code wallpaper.set} — params {@code path} (guest staging image) or
     * {@code url} (http/https image resource), plus {@code lockscreen}
     * (upstream {@code -l}: {@link WallpaperManager#FLAG_LOCK} only, else
     * {@code FLAG_SYSTEM}). Requires the install-time {@code SET_WALLPAPER}
     * grant. Answers {@code set} and {@code which} ({@code lock}|{@code system}).
     */
    private AndroidCapabilityProtocol.Response wallpaperSet(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("path", "url", "lockscreen"));
        boolean hasPath = params.has("path");
        boolean hasUrl = params.has("url");
        if (hasPath == hasUrl) {
            throw new CapabilityParams.Invalid(
                    "exactly one of path or url is required");
        }
        boolean lockscreen = params.optionalBoolean("lockscreen", false);
        if (checker.check(android.Manifest.permission.SET_WALLPAPER)
                != CapabilityPermission.GRANTED) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wallpaper-permission-required");
        }
        WallpaperManager manager;
        try {
            manager = WallpaperManager.getInstance(context);
        } catch (RuntimeException e) {
            manager = null;
        }
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wallpaper-unavailable");
        }

        Bitmap bitmap;
        if (hasPath) {
            Path source = paths.resolveForRead(
                    params.requireString("path", PATH_MAX_CHARS));
            try {
                if (Files.size(source) > WALLPAPER_MAX_BYTES) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "wallpaper-unavailable");
                }
            } catch (IOException e) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "wallpaper-unavailable");
            }
            bitmap = decodeBounded(source.toString(), null);
        } else {
            String url = params.requireString("url", URI_MAX_CHARS);
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw new CapabilityParams.Invalid("url must be http or https");
            }
            byte[] image = fetchBounded(url);
            if (image == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "wallpaper-unavailable");
            }
            bitmap = decodeBounded(null, image);
        }
        if (bitmap == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wallpaper-invalid");
        }
        int which = lockscreen ? WallpaperManager.FLAG_LOCK
                : WallpaperManager.FLAG_SYSTEM;
        try {
            manager.setBitmap(bitmap, null, true, which);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "wallpaper.set failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "wallpaper-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("set", true);
        fields.put("which", lockscreen ? "lock" : "system");
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code download.request} — params {@code url} (http/https, required),
     * {@code title}, {@code description}, and {@code name} (filename inside
     * the public {@code Download} directory; derived from the URL basename
     * when absent). Enqueues through {@link DownloadManager} with the public
     * destination per the domain contract and answers {@code enqueued} and
     * {@code download_id}.
     */
    @SuppressWarnings("deprecation")
    private AndroidCapabilityProtocol.Response downloadRequest(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("url", "title", "description", "name"));
        String url = params.requireString("url", URI_MAX_CHARS);
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new CapabilityParams.Invalid("url must be http or https");
        }
        String title = params.optionalString("title", TITLE_MAX_CHARS, "");
        String description = params.optionalString("description", TITLE_MAX_CHARS, "");
        String name = params.optionalString(
                "name", DOWNLOAD_NAME_MAX_CHARS, "");
        if (name.isEmpty()) {
            name = urlBasename(url);
        }
        if (name.isEmpty()) {
            name = "download";
        }
        name = name.replace("/", "_").replace("\\", "_");
        if ("..".equals(name) || ".".equals(name)) {
            name = "download";
        }

        DownloadManager manager;
        try {
            manager = (DownloadManager) context.getSystemService(
                    Context.DOWNLOAD_SERVICE);
        } catch (RuntimeException e) {
            manager = null;
        }
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "download-unavailable");
        }
        long id;
        try {
            DownloadManager.Request enqueue = new DownloadManager.Request(
                    Uri.parse(url));
            enqueue.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            enqueue.setVisibleInDownloadsUi(true);
            if (!title.isEmpty()) {
                enqueue.setTitle(title);
            }
            if (!description.isEmpty()) {
                enqueue.setDescription(description);
            }
            enqueue.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, name);
            id = manager.enqueue(enqueue);
        } catch (RuntimeException e) {
            Log.w(TAG, "download.request failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "download-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("enqueued", true);
        fields.put("download_id", id);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    // ------------------------------------------------------------------
    // SAF helpers
    // ------------------------------------------------------------------

    /** Parse a SAF URI argument; null when the string cannot be parsed. */
    private static Uri parseSafUri(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            return uri == null ? null : uri;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The document id of a SAF URI: {@code getDocumentId} when the URI is
     * already a document URI (a {@code saf-ls}/{@code saf-create} result),
     * else the tree document id — the same preference order upstream uses.
     */
    private static String documentIdFor(Uri uri) {
        try {
            return DocumentsContract.getDocumentId(uri);
        } catch (IllegalArgumentException e) {
            return DocumentsContract.getTreeDocumentId(uri);
        }
    }

    /**
     * Normalize a SAF argument to a document URI: unchanged when it already
     * is one, else rebuilt from the tree document id.
     */
    private static Uri toDocumentUri(Uri uri) {
        try {
            DocumentsContract.getDocumentId(uri);
            return uri;
        } catch (IllegalArgumentException e) {
            return DocumentsContract.buildDocumentUriUsingTree(
                    uri, DocumentsContract.getTreeDocumentId(uri));
        }
    }

    /**
     * One document entry in the upstream {@code termux-saf-*} shape
     * ({@code name}, {@code type}, {@code uri}, {@code last_modified}, and
     * {@code length} for non-directories), or null when the URI resolves to
     * no row.
     */
    private String statEntryJson(Uri document) {
        try (Cursor cursor = resolver.query(document, null, null, null, null)) {
            if (cursor == null || !cursor.moveToNext()) {
                return null;
            }
            StringBuilder entry = new StringBuilder(256);
            entry.append('{');
            boolean first = true;
            int index = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            String mime = null;
            if (index >= 0) {
                String name = cursor.getString(index);
                if (name != null) {
                    entry.append("\"name\":")
                            .append(AndroidCapabilityProtocol.encodeStringValue(name));
                    first = false;
                }
            }
            index = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (index >= 0) {
                mime = cursor.getString(index);
            }
            if (mime != null) {
                if (!first) {
                    entry.append(',');
                }
                entry.append("\"type\":")
                        .append(AndroidCapabilityProtocol.encodeStringValue(mime));
                first = false;
            }
            if (!first) {
                entry.append(',');
            }
            entry.append("\"uri\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(
                            document.toString()));
            index = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            if (index >= 0 && !cursor.isNull(index)) {
                entry.append(",\"last_modified\":").append(cursor.getLong(index));
            }
            if (mime != null && !DIR_MIME.equals(mime)) {
                index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    entry.append(",\"length\":").append(cursor.getLong(index));
                }
            }
            return entry.append('}').toString();
        }
    }

    /** A read stream for a document URI; FileNotFoundException maps up. */
    private InputStream openDocumentStream(Uri document)
            throws FileNotFoundException {
        InputStream in = resolver.openInputStream(document);
        if (in == null) {
            throw new FileNotFoundException("provider returned no stream");
        }
        return in;
    }

    /** Open a document for truncate-write: {@code "rwt"} first, {@code "wt"} fallback. */
    private OutputStream openForTruncateWrite(Uri document)
            throws FileNotFoundException {
        try {
            return resolver.openOutputStream(document, "rwt");
        } catch (IllegalArgumentException | FileNotFoundException e) {
            return resolver.openOutputStream(document, "wt");
        }
    }

    /** Map a SAF platform failure to the domain's typed error. */
    private static AndroidCapabilityProtocol.Response safPlatformError(
            String requestId, RuntimeException e) {
        Log.w(TAG, "saf platform failure", e);
        if (e instanceof SecurityException) {
            return AndroidCapabilityProtocol.Response.error(requestId,
                    "saf-permission-denied:grant the tree with termux-saf-managedir first");
        }
        return AndroidCapabilityProtocol.Response.error(requestId, "saf-unavailable");
    }

    /**
     * A bounded JSON-array builder for {@code entries_json}: appends stop
     * once {@link #ENTRIES_JSON_MAX_CHARS} would be exceeded, and the
     * response then carries {@code truncated=true}.
     */
    private static final class JsonListBuilder {
        private final StringBuilder json = new StringBuilder("[");
        private int count;
        private boolean truncated;

        /** False when the entry no longer fits inside the bound. */
        boolean add(String entry) {
            if (json.length() + entry.length() + 2 > ENTRIES_JSON_MAX_CHARS) {
                truncated = true;
                return false;
            }
            if (count > 0) {
                json.append(',');
            }
            json.append(entry);
            count++;
            return true;
        }

        int count() {
            return count;
        }

        AndroidCapabilityProtocol.Response respond(String requestId) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("entries_json", json.append(']').toString());
            if (truncated) {
                fields.put("truncated", true);
            }
            return AndroidCapabilityProtocol.Response.success(requestId, fields);
        }
    }

    // ------------------------------------------------------------------
    // media.scan helpers
    // ------------------------------------------------------------------

    /**
     * Resolve a guest path to a scannable host path: a file under the guest
     * workspace mount maps to the user-picked shared folder (the only guest
     * location the media provider can actually read); anything else goes
     * through the rootfs resolver. Null when the path cannot be resolved.
     */
    private String scanHostPath(String guestPath) {
        String mount = WorkspaceFolder.GUEST_MOUNT_PATH + "/";
        if (guestPath.startsWith(mount)) {
            WorkspaceFolder workspace;
            try {
                workspace = workspaceStore.load();
            } catch (RuntimeException e) {
                workspace = null;
            }
            if (workspace != null) {
                Path root = Paths.get(workspace.getHostPath());
                Path host = root.resolve(
                        guestPath.substring(mount.length())).normalize();
                if (host.startsWith(root) && Files.isRegularFile(host)) {
                    return host.toString();
                }
            }
            return null;
        }
        try {
            return paths.resolveForRead(guestPath).toString();
        } catch (GuestFilePathResolver.Invalid e) {
            return null;
        }
    }

    private static String scanResultsJson(List<String> guestPaths, boolean[] confirmed) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < guestPaths.size(); i++) {
            if (json.length() > ENTRIES_JSON_MAX_CHARS) {
                break;
            }
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"path\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(guestPaths.get(i)))
                    .append(",\"scanned\":").append(confirmed[i])
                    .append('}');
        }
        return json.append(']').toString();
    }

    // ------------------------------------------------------------------
    // wallpaper / misc helpers
    // ------------------------------------------------------------------

    /**
     * Download an http(s) resource bounded: connect/read timeouts, an
     * {@code image/*} content type, and at most {@link #WALLPAPER_MAX_BYTES}.
     * Null on any refusal — the caller maps it to {@code wallpaper-unavailable}
     * without leaking the platform message.
     */
    private static byte[] fetchBounded(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(WALLPAPER_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(WALLPAPER_READ_TIMEOUT_MILLIS);
            connection.connect();
            String contentType = connection.getHeaderField("Content-Type");
            if (contentType == null || !contentType.startsWith("image/")) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                    if (bytes.size() > WALLPAPER_MAX_BYTES) {
                        return null;
                    }
                }
                return bytes.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Decode an image (file path or downloaded bytes) with bounds-first
     * sampling so an oversized source cannot exhaust the heap; null when the
     * input is not a decodable image.
     */
    private static Bitmap decodeBounded(String file, byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (data != null) {
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            } else {
                BitmapFactory.decodeFile(file, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= WALLPAPER_MAX_DIMENSION
                    && bounds.outHeight / (sample * 2) >= WALLPAPER_MAX_DIMENSION) {
                sample *= 2;
            }
            decode.inSampleSize = sample;
            return data != null
                    ? BitmapFactory.decodeByteArray(data, 0, data.length, decode)
                    : BitmapFactory.decodeFile(file, decode);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The last path segment of an http(s) URL, or empty when none. */
    private static String urlBasename(String url) {
        try {
            String path = Uri.parse(url).getLastPathSegment();
            return path == null ? "" : path;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Mime guess from a file extension, else {@code application/octet-stream}. */
    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(
                            name.substring(dot + 1).toLowerCase(java.util.Locale.US));
            if (mime != null) {
                return mime;
            }
        }
        return DEFAULT_CREATE_MIME;
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            total += read;
        }
        out.flush();
        return total;
    }

    /** Skip {@code bytes} of a stream with a read-discard fallback. */
    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (in.read() < 0) {
                return;
            } else {
                remaining--;
            }
        }
    }

    private static void copyField(Map<String, Object> from, Map<String, Object> to,
                                  String key) {
        Object value = from.get(key);
        if (value != null) {
            to.put(key, value);
        }
    }

    /** Re-wrap a foreground host response (blank id) with the request's id. */
    private static AndroidCapabilityProtocol.Response rewrap(
            String requestId, AndroidCapabilityProtocol.Response result) {
        return result.isOk()
                ? AndroidCapabilityProtocol.Response.success(
                        requestId, result.getFields())
                : AndroidCapabilityProtocol.Response.error(requestId, result.getError());
    }
}
