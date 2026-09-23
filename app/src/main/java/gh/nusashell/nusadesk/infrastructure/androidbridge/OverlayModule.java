package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guest-driven overlay surface of the capability bridge (wave 3): one small,
 * non-interactive {@link TextView} the guest can pin over other apps through
 * the {@code SYSTEM_ALERT_WINDOW} special-access grant. There is no upstream
 * Termux:API counterpart; the {@code overlay.*} surface is NusaDesk-native.
 * Exactly one overlay exists at a time; the module owns it and
 * {@link #close()} always detaches it.
 *
 * <p>{@code overlay.show} (params {@code text} required &le;256 chars,
 * {@code x} optional int -5000..5000 default 24, {@code y} optional int
 * -5000..5000 default 120, {@code size} optional 10..72 sp default 16,
 * {@code color} optional {@code #RRGGBB}/{@code #AARRGGBB} literal &le;9
 * chars) attaches the view as {@code TYPE_APPLICATION_OVERLAY} with
 * {@code FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE|FLAG_LAYOUT_NO_LIMITS} — a
 * display-only plate that can sit partially off-screen and can never steal
 * focus or touches. Success fields are {@code shown=true}, {@code x},
 * {@code y}. A missing draw-over-apps grant answers
 * {@code overlay-permission-required:grant draw-over-apps (SYSTEM_ALERT_WINDOW)
 * in Settings (permission.request mode=settings)} (special access has no
 * denied state — {@link AndroidPermissionChecker} reads it through
 * {@link android.provider.Settings#canDrawOverlays}), a live overlay answers
 * {@code overlay-already-shown}, and a platform refusal or an expired
 * main-thread wait is {@code overlay-failed:<reason>}.</p>
 *
 * <p>{@code overlay.update} (same params, all optional, at least one
 * required) rewrites the live overlay in place and answers
 * {@code updated=true}; without a live overlay it answers
 * {@code overlay-not-shown}. {@code overlay.status} (no params) reports
 * {@code shown}, {@code text}, {@code x}, {@code y}, {@code size}, and
 * {@code color} (empty/zero values when nothing is shown).
 * {@code overlay.hide} (no params) is idempotent: {@code shown=false}
 * always, {@code was_shown} whether this call actually detached a view.</p>
 *
 * <p>Every view operation runs on the main looper: the bridge thread posts
 * the work and waits at most {@link #VIEW_TIMEOUT_MILLIS}, and a call that
 * already arrived on the main thread runs inline so it can never deadlock on
 * its own latch. {@code TYPE_APPLICATION_OVERLAY} exists since API 26 and
 * minSdk is 29, so the {@link Build.VERSION#SDK_INT} gate is defensive and
 * answers {@code overlay-unsupported:requires Android 8} if the floor ever
 * drops.</p>
 */
public final class OverlayModule implements CapabilityModule {
    private static final String TAG = "OverlayModule";

    private static final String METHOD_SHOW = "overlay.show";
    private static final String METHOD_UPDATE = "overlay.update";
    private static final String METHOD_STATUS = "overlay.status";
    private static final String METHOD_HIDE = "overlay.hide";

    private static final int TEXT_MAX_CHARS = 256;
    private static final int COLOR_MAX_CHARS = 9;
    private static final int COORD_MIN = -5000;
    private static final int COORD_MAX = 5000;
    private static final int DEFAULT_X = 24;
    private static final int DEFAULT_Y = 120;
    private static final int SIZE_MIN = 10;
    private static final int SIZE_MAX = 72;
    private static final int DEFAULT_SIZE = 16;

    /** Semi-transparent black plate behind the text. */
    private static final int BACKGROUND_COLOR = 0xB3000000;
    private static final int DEFAULT_TEXT_COLOR = Color.WHITE;
    private static final int PADDING_DP = 12;
    private static final int OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    /** Bounded wait for a view operation parked on the main looper. */
    private static final long VIEW_TIMEOUT_MILLIS = 5_000L;

    /** Immutable overlay content and placement; replaced atomically per update. */
    static final class OverlaySpec {
        final String text;
        final int x;
        final int y;
        final int size;
        /** Raw {@code #RRGGBB}/{@code #AARRGGBB} literal, {@code ""} for the default. */
        final String color;

        OverlaySpec(String text, int x, int y, int size, String color) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.size = size;
            this.color = color;
        }
    }

    /**
     * The platform calls this module owns, seamed for tests: view creation,
     * {@link WindowManager#addView}, {@link WindowManager#updateViewLayout},
     * and {@link WindowManager#removeViewImmediate}. Every method runs on the
     * main thread; a {@link RuntimeException} is a platform refusal and maps
     * to {@code overlay-failed}.
     */
    interface OverlayBackend {
        /** Create, style, and attach the overlay view; returns it as the handle. */
        View add(OverlaySpec spec);
        /** Re-apply the spec to the attached view and update its layout. */
        void update(View view, OverlaySpec spec);
        /** Detach the view. */
        void remove(View view);
    }

    /** A main-thread wait that expired before the posted work ran. */
    private static final class MainThreadTimeout extends RuntimeException {
    }

    /** Work executed on the main looper via {@link #onMain}. */
    private interface MainJob<T> {
        T run();
    }

    private final AndroidPermissionChecker permissions;
    private final OverlayBackend backend;
    private final Handler mainHandler;
    private final Object stateGuard = new Object();
    private View overlayView;
    private OverlaySpec spec;
    private volatile boolean closed;

    public OverlayModule(Context context) {
        this(context, platformBackend(context));
    }

    /**
     * Package-private seam constructor: the backend is injectable so the
     * method contract is testable without a real {@link WindowManager}.
     */
    OverlayModule(Context context, OverlayBackend backend) {
        if (context == null || backend == null) {
            throw new IllegalArgumentException("context and backend must not be null");
        }
        this.permissions = new AndroidPermissionChecker(context.getApplicationContext());
        this.backend = backend;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private static OverlayBackend platformBackend(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new PlatformBackend(context.getApplicationContext());
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_SHOW, METHOD_UPDATE, METHOD_STATUS, METHOD_HIDE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_SHOW, METHOD_UPDATE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_SHOW:
                return show(request);
            case METHOD_UPDATE:
                return update(request);
            case METHOD_STATUS:
                return status(request);
            case METHOD_HIDE:
                return hide(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    public void close() {
        closed = true;
        View view;
        synchronized (stateGuard) {
            view = overlayView;
            overlayView = null;
            spec = null;
        }
        if (view != null) {
            try {
                onMain(() -> {
                    backend.remove(view);
                    return null;
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "overlay release failed", e);
            }
        }
    }

    /**
     * {@code overlay.show}. Params validate first, then the closed and API
     * gates, then the special-access grant (read through
     * {@link android.provider.Settings#canDrawOverlays}), then the
     * single-overlay state. The view add itself runs on the main thread.
     */
    @SuppressLint("ObsoleteSdkInt") // Defensive per contract: the API-26 floor is already enforced by minSdk 29.
    private AndroidCapabilityProtocol.Response show(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text", "x", "y", "size", "color"));
        OverlaySpec spec = new OverlaySpec(
                params.requireString("text", TEXT_MAX_CHARS),
                (int) params.optionalLong("x", COORD_MIN, COORD_MAX, DEFAULT_X),
                (int) params.optionalLong("y", COORD_MIN, COORD_MAX, DEFAULT_Y),
                (int) params.optionalLong("size", SIZE_MIN, SIZE_MAX, DEFAULT_SIZE),
                colorParam(params, ""));
        if (closed) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "overlay-failed:module closed");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "overlay-unsupported:requires Android 8");
        }
        if (permissions.check(Manifest.permission.SYSTEM_ALERT_WINDOW)
                != CapabilityPermission.GRANTED) {
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "overlay-permission-required:grant draw-over-apps (SYSTEM_ALERT_WINDOW)"
                            + " in Settings (permission.request mode=settings)");
        }
        synchronized (stateGuard) {
            if (overlayView != null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-already-shown");
            }
            View view;
            try {
                view = onMain(() -> backend.add(spec));
            } catch (MainThreadTimeout e) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-failed:main thread timed out");
            } catch (RuntimeException e) {
                Log.w(TAG, "overlay.show rejected", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-failed:window add rejected");
            }
            if (view == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-failed:window add rejected");
            }
            overlayView = view;
            this.spec = spec;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("shown", true);
        fields.put("x", (long) spec.x);
        fields.put("y", (long) spec.y);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code overlay.update}. The merged spec is applied to the live view on
     * the main thread; the stored spec is replaced only after the platform
     * accepts it, so a rejected update leaves the reported state truthful.
     */
    private AndroidCapabilityProtocol.Response update(AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text", "x", "y", "size", "color"));
        if (!params.has("text") && !params.has("x") && !params.has("y")
                && !params.has("size") && !params.has("color")) {
            throw new CapabilityParams.Invalid(
                    "missing parameter: one of text, x, y, size, color");
        }
        synchronized (stateGuard) {
            if (overlayView == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-not-shown");
            }
            OverlaySpec merged = new OverlaySpec(
                    params.optionalString("text", TEXT_MAX_CHARS, spec.text),
                    (int) params.optionalLong("x", COORD_MIN, COORD_MAX, spec.x),
                    (int) params.optionalLong("y", COORD_MIN, COORD_MAX, spec.y),
                    (int) params.optionalLong("size", SIZE_MIN, SIZE_MAX, spec.size),
                    colorParam(params, spec.color));
            try {
                onMain(() -> {
                    backend.update(overlayView, merged);
                    return null;
                });
            } catch (MainThreadTimeout e) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-failed:main thread timed out");
            } catch (RuntimeException e) {
                Log.w(TAG, "overlay.update rejected", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "overlay-failed:update rejected");
            }
            spec = merged;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("updated", true);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** {@code overlay.status} — a pure state read; it never touches the view. */
    private AndroidCapabilityProtocol.Response status(AndroidCapabilityProtocol.Request request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        synchronized (stateGuard) {
            boolean shown = overlayView != null;
            fields.put("shown", shown);
            fields.put("text", shown ? spec.text : "");
            fields.put("x", (long) (shown ? spec.x : 0));
            fields.put("y", (long) (shown ? spec.y : 0));
            fields.put("size", (long) (shown ? spec.size : 0));
            fields.put("color", shown ? spec.color : "");
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code overlay.hide} — idempotent. State clears before the detach so a
     * platform refusal (the view already being gone) can never leave the
     * module believing a phantom overlay is up.
     */
    private AndroidCapabilityProtocol.Response hide(AndroidCapabilityProtocol.Request request) {
        boolean wasShown;
        synchronized (stateGuard) {
            wasShown = overlayView != null;
            if (wasShown) {
                View view = overlayView;
                overlayView = null;
                spec = null;
                try {
                    onMain(() -> {
                        backend.remove(view);
                        return null;
                    });
                } catch (RuntimeException e) {
                    Log.w(TAG, "overlay.hide detach failed", e);
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("shown", false);
        fields.put("was_shown", wasShown);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * The {@code color} param: an optional {@code #RRGGBB}/{@code #AARRGGBB}
     * literal (9 chars bound), validated through {@link Color#parseColor} so
     * a malformed value is {@code invalid-argument}, never a mid-apply crash.
     */
    private static String colorParam(CapabilityParams params, String fallback) {
        String color = params.optionalString("color", COLOR_MAX_CHARS, fallback);
        if (color.isEmpty()) {
            return color;
        }
        if (color.charAt(0) != '#') {
            throw new CapabilityParams.Invalid("parameter is not a hex color: color");
        }
        try {
            Color.parseColor(color);
        } catch (IllegalArgumentException e) {
            throw new CapabilityParams.Invalid("parameter is not a hex color: color");
        }
        return color;
    }

    /**
     * Run {@code job} on the main thread and block the calling bridge thread
     * for at most {@link #VIEW_TIMEOUT_MILLIS}. Work already on the main
     * thread runs inline so it can never deadlock on its own latch; a
     * platform refusal is rethrown to the caller.
     */
    private <T> T onMain(MainJob<T> job) {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            return job.run();
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Object> failure = new AtomicReference<>();
        if (!mainHandler.post(() -> {
            try {
                value.set(job.run());
            } catch (RuntimeException | Error e) {
                failure.set(e);
            }
            latch.countDown();
        })) {
            throw new MainThreadTimeout();
        }
        try {
            if (!latch.await(VIEW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new MainThreadTimeout();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MainThreadTimeout();
        }
        Object error = failure.get();
        if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
        return value.get();
    }

    /**
     * The real surface: a padded {@link TextView} on a semi-transparent plate
     * attached as {@code TYPE_APPLICATION_OVERLAY} with
     * {@code FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE|FLAG_LAYOUT_NO_LIMITS},
     * top/start-gravity positioned by the spec's raw pixel offsets.
     */
    private static final class PlatformBackend implements OverlayBackend {
        private final Context context;
        private final WindowManager windows;

        PlatformBackend(Context context) {
            this.context = context;
            this.windows = context.getSystemService(WindowManager.class);
        }

        @Override
        public View add(OverlaySpec spec) {
            if (windows == null) {
                throw new IllegalStateException("window service unavailable");
            }
            TextView view = new TextView(context);
            apply(view, spec);
            windows.addView(view, layoutParams(spec));
            return view;
        }

        @Override
        public void update(View view, OverlaySpec spec) {
            apply((TextView) view, spec);
            windows.updateViewLayout(view, layoutParams(spec));
        }

        @Override
        public void remove(View view) {
            windows.removeViewImmediate(view);
        }

        private void apply(TextView view, OverlaySpec spec) {
            view.setText(spec.text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.size);
            view.setTextColor(spec.color.isEmpty()
                    ? DEFAULT_TEXT_COLOR : Color.parseColor(spec.color));
            view.setBackgroundColor(BACKGROUND_COLOR);
            int padding = Math.round(PADDING_DP
                    * context.getResources().getDisplayMetrics().density);
            view.setPadding(padding, padding, padding, padding);
        }

        private static WindowManager.LayoutParams layoutParams(OverlaySpec spec) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    OVERLAY_FLAGS, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = spec.x;
            params.y = spec.y;
            params.setTitle("NusaDesk overlay");
            return params;
        }
    }
}
