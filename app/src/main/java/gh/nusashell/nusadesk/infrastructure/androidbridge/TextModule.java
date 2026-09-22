package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Text capability domain: clipboard read/write and toast display behind
 * three bridge methods.
 *
 * <p>{@code clipboard.get} reads the primary clip through
 * {@link ClipboardManager}. On Android 10+ only the foreground app (or the
 * default IME) may read the clipboard; a background read returns an empty
 * result that is indistinguishable from an empty clipboard unless
 * {@link ClipboardManager#hasPrimaryClip()} is consulted first — it is not
 * access-gated, so a clip that exists but cannot be read is reported
 * honestly as {@code clipboard-permission-required:bring the app to the
 * foreground} rather than as a fabricated empty string.</p>
 *
 * <p>{@code clipboard.set} writes the primary clip. {@code toast} shows a
 * {@link Toast} on the main thread: the bridge handles requests on a socket
 * thread, so the show is dispatched to the main looper and awaited with a
 * bound; a dispatch that never runs or a platform refusal answers
 * {@code toast-unavailable}.</p>
 *
 * <p>Text longer than the transport's inline bound, or text carrying
 * characters the framed params cannot represent (newlines and other control
 * characters), arrives as a staged file inside the guest rootfs
 * ({@code text_path}); the file is resolved through
 * {@link GuestFilePathResolver} and a bad path is {@code invalid-argument}.
 * Staged text is bounded and a cut is reported with {@code truncated}.</p>
 */
public final class TextModule implements CapabilityModule {
    private static final String TAG = "TextModule";

    private static final String METHOD_CLIPBOARD_GET = "clipboard.get";
    private static final String METHOD_CLIPBOARD_SET = "clipboard.set";
    private static final String METHOD_TOAST = "toast";

    private static final int TEXT_MAX_CHARS = 8192;
    private static final int CLIPBOARD_MAX_CHARS = 8192;
    private static final int STAGED_TEXT_MAX_CHARS = 32_768;
    private static final long STAGED_FILE_MAX_BYTES = 256 * 1024L;
    private static final long TOAST_DISPATCH_TIMEOUT_MS = 2_000L;

    private final Context context;
    private final GuestFilePathResolver resolver;

    public TextModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.resolver = new GuestFilePathResolver(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_CLIPBOARD_GET, METHOD_CLIPBOARD_SET, METHOD_TOAST);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_CLIPBOARD_SET, METHOD_TOAST);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_CLIPBOARD_GET:
                return clipboardGet(request);
            case METHOD_CLIPBOARD_SET:
                return clipboardSet(request);
            case METHOD_TOAST:
                return toast(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code clipboard.get} — no params. Reports {@code text} (bounded, with
     * {@code truncated} when the clip was cut to fit). A clip that exists
     * but the platform will not let a background app read is
     * {@code clipboard-permission-required}, never a fabricated empty
     * string; a missing clipboard service or a platform refusal is
     * {@code clipboard-unavailable}.
     */
    private AndroidCapabilityProtocol.Response clipboardGet(
            AndroidCapabilityProtocol.Request request) {
        ClipboardManager clipboard = clipboardManager();
        if (clipboard == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "clipboard-unavailable");
        }
        boolean hasClip;
        try {
            hasClip = clipboard.hasPrimaryClip();
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard.get: hasPrimaryClip failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "clipboard-unavailable");
        }
        if (!hasClip) {
            return clipboardText(request, "", false);
        }
        ClipData clip;
        try {
            clip = clipboard.getPrimaryClip();
        } catch (SecurityException e) {
            // A clip exists but this app is not the foreground reader.
            Log.w(TAG, "clipboard.get: read refused", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "clipboard-permission-required:bring the app to the foreground");
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard.get: read failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "clipboard-unavailable");
        }
        if (clip == null) {
            // Android 10+ answers null to a background read instead of
            // throwing; the clip exists, so the refusal is honest.
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "clipboard-permission-required:bring the app to the foreground");
        }
        String text;
        try {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < clip.getItemCount(); i++) {
                CharSequence item = clip.getItemAt(i).coerceToText(context);
                if (item != null) {
                    joined.append(item);
                }
            }
            text = joined.toString();
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard.get: coerce failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "clipboard-unavailable");
        }
        boolean truncated = text.length() > CLIPBOARD_MAX_CHARS;
        if (truncated) {
            text = text.substring(0, CLIPBOARD_MAX_CHARS);
        }
        return clipboardText(request, text, truncated);
    }

    /**
     * {@code clipboard.set} — params {@code text} (inline, <=8192 chars) or
     * {@code text_path} (staged guest file). Neither means an empty clip,
     * matching upstream's empty-stdin write; both is
     * {@code invalid-argument}. Reports {@code set}.
     */
    private AndroidCapabilityProtocol.Response clipboardSet(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text", "text_path"));
        boolean[] truncated = new boolean[1];
        String text = textParam(params, truncated);

        ClipboardManager clipboard = clipboardManager();
        if (clipboard == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "clipboard-unavailable");
        }
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("text", text));
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard.set failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "clipboard-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("set", true);
        if (truncated[0]) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code toast} — params {@code text} (or {@code text_path}) and
     * {@code short} (bool, default true). The show runs on the main looper
     * and is awaited with a bound: a refusal is {@code toast-unavailable}
     * and a main thread that never runs the dispatch is
     * {@code toast-unavailable} with a busy hint. Reports {@code shown}.
     */
    private AndroidCapabilityProtocol.Response toast(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text", "text_path", "short"));
        boolean[] truncated = new boolean[1];
        String text = textParam(params, truncated);
        boolean shortDuration = params.optionalBoolean("short", true);

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean shown = new AtomicBoolean();
        AtomicBoolean refused = new AtomicBoolean();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context, text,
                        shortDuration ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                shown.set(true);
            } catch (RuntimeException e) {
                Log.w(TAG, "toast refused", e);
                refused.set(true);
            } finally {
                done.countDown();
            }
        });
        boolean finished;
        try {
            finished = done.await(TOAST_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "toast-unavailable:the main thread did not run the toast");
        }
        if (refused.get() || !shown.get()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "toast-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("shown", true);
        if (truncated[0]) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * The {@code text}/{@code text_path} pair read as one value: inline when
     * present, staged guest file otherwise, empty when both are absent, and
     * {@link CapabilityParams.Invalid} when both are supplied.
     * {@code truncated[0]} is set when staged text was cut to the bound.
     */
    private String textParam(CapabilityParams params, boolean[] truncated) {
        if (params.has("text") && params.has("text_path")) {
            throw new CapabilityParams.Invalid(
                    "text and text_path are mutually exclusive");
        }
        if (!params.has("text_path")) {
            return params.optionalString("text", TEXT_MAX_CHARS, "");
        }
        String guestPath = params.optionalString("text_path",
                STAGED_TEXT_MAX_CHARS, "");
        Path file;
        try {
            file = resolver.resolveForRead(guestPath);
        } catch (GuestFilePathResolver.Invalid e) {
            throw new CapabilityParams.Invalid("text_path is not readable");
        }
        try {
            if (Files.size(file) > STAGED_FILE_MAX_BYTES) {
                throw new CapabilityParams.Invalid("text_path file too large");
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.length() > STAGED_TEXT_MAX_CHARS) {
                truncated[0] = true;
                return text.substring(0, STAGED_TEXT_MAX_CHARS);
            }
            return text;
        } catch (IOException e) {
            throw new CapabilityParams.Invalid("text_path is not readable");
        }
    }

    private ClipboardManager clipboardManager() {
        try {
            return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AndroidCapabilityProtocol.Response clipboardText(
            AndroidCapabilityProtocol.Request request, String text, boolean truncated) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("text", text);
        fields.put("truncated", truncated);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }
}
