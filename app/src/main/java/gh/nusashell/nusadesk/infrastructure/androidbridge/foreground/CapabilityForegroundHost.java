package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Settings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidCapabilityProtocol;

/**
 * Serializes and drives bridge operations that need a visible activity.
 *
 * <p>One instance is created per bridge; the single parked operation is a
 * static slot so exactly one interactive operation runs device-wide no
 * matter which module asked. {@link #execute} is called on a bridge
 * connection thread and blocks it for at most {@code timeoutMillis}: the
 * host parks the operation, launches {@link CapabilityForegroundActivity}
 * with {@link Intent#FLAG_ACTIVITY_NEW_TASK}, and waits for the activity to
 * deliver the operation's result through {@link #complete}.</p>
 *
 * <p>Starting an activity while the app has no foreground window is refused
 * by the platform on Android 10+ unless the app holds the overlay grant, so
 * the host checks that combination up front and answers
 * {@link #ERROR_FOREGROUND_REQUIRED} instead of silently hanging. A second
 * execute while one is parked answers {@link #ERROR_BUSY}; the latch
 * expiring answers {@link #ERROR_TIMEOUT}; the activity dying without a
 * result answers {@link #ERROR_CANCELLED}. Every path settles the parked
 * operation, so nothing can wedge the slot.</p>
 */
public final class CapabilityForegroundHost {
    /** A second operation arrived while one is parked. */
    public static final String ERROR_BUSY = "foreground-busy";
    /** The bounded wait expired before the activity produced a result. */
    public static final String ERROR_TIMEOUT = "foreground-timeout";
    /** The activity finished or died without producing a result. */
    public static final String ERROR_CANCELLED = "foreground-cancelled";
    /** The platform would refuse the activity start from the background. */
    public static final String ERROR_REQUIRED = "foreground-required";
    /** The activity start itself failed. */
    public static final String ERROR_UNAVAILABLE = "foreground-unavailable";

    /** Human hint carried after {@link #ERROR_FOREGROUND_REQUIRED}'s colon. */
    private static final String FOREGROUND_REQUIRED_HINT =
            "grant draw-over-apps via permission.request mode=settings "
                    + "permission=SYSTEM_ALERT_WINDOW";

    /** The one parked operation; null when no foreground work is running. */
    private static final AtomicReference<PendingOperation> PENDING =
            new AtomicReference<>();

    private final Context context;

    public CapabilityForegroundHost(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    /**
     * Run the catalog operation {@code kind} inside a fresh
     * {@link CapabilityForegroundActivity} and block the calling bridge
     * thread for at most {@code timeoutMillis}. The returned response carries
     * a blank request id; the calling module re-wraps it with the real one.
     */
    public AndroidCapabilityProtocol.Response execute(String kind, Map<String, Object> params,
                                                      long timeoutMillis) {
        ForegroundOperation operation = ForegroundOperationCatalog.operations().get(kind);
        if (operation == null) {
            return AndroidCapabilityProtocol.Response.error("", "foreground-unknown");
        }
        PendingOperation pending = new PendingOperation(kind, operation,
                params == null ? Map.of() : params);
        if (!PENDING.compareAndSet(null, pending)) {
            return AndroidCapabilityProtocol.Response.error("", ERROR_BUSY);
        }
        try {
            if (backgroundStartLikelyRefused()) {
                pending.finish(null, ERROR_REQUIRED + ":" + FOREGROUND_REQUIRED_HINT);
            } else {
                try {
                    context.startActivity(
                            new Intent(context, CapabilityForegroundActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    pending.finish(null, ERROR_UNAVAILABLE);
                }
            }
            boolean finished;
            try {
                finished = pending.await(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            if (!finished) {
                pending.finish(null, ERROR_TIMEOUT);
                finishActivityQuietly(pending);
            }
        } finally {
            PENDING.compareAndSet(pending, null);
        }
        String error = pending.error();
        if (error != null) {
            return AndroidCapabilityProtocol.Response.error("", error);
        }
        return AndroidCapabilityProtocol.Response.success("", pending.fields());
    }

    /**
     * Release a parked operation when the bridge closes, so its awaiting
     * bridge thread unwinds as {@link #ERROR_CANCELLED} instead of blocking
     * for the full timeout.
     */
    public void close() {
        PendingOperation pending = PENDING.get();
        if (pending != null) {
            pending.finish(null, ERROR_CANCELLED);
            finishActivityQuietly(pending);
        }
    }

    /**
     * Deliver an operation result. The activity calls this with the kind it
     * ran; a kind that does not match the parked operation (or no parked
     * operation at all, after a timeout) is dropped rather than corrupting a
     * newer wait.
     */
    public static void complete(String kind, Map<String, Object> fields, String typedError) {
        PendingOperation pending = PENDING.get();
        if (pending != null && pending.kind().equals(kind)) {
            pending.finish(fields, typedError);
        }
    }

    /**
     * Deliver a result to one specific parked operation. The activity uses
     * this identity-bound form internally so a stale instance can never
     * settle a different operation that happens to share the kind.
     */
    static void complete(PendingOperation pending, Map<String, Object> fields,
                         String typedError) {
        if (pending != null) {
            pending.finish(fields, typedError);
        }
    }

    /** The currently parked operation, or null; read by the foreground activity. */
    static PendingOperation pendingOperation() {
        return PENDING.get();
    }

    /**
     * Whether the platform is expected to refuse the activity start: no
     * overlay grant and no foreground window. Refusing early keeps a blocked
     * start from looking like a 120 s hang.
     */
    private boolean backgroundStartLikelyRefused() {
        try {
            if (Settings.canDrawOverlays(context)) {
                return false;
            }
        } catch (RuntimeException e) {
            return true;
        }
        return !appInForeground();
    }

    private boolean appInForeground() {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            List<ActivityManager.RunningAppProcessInfo> processes =
                    manager.getRunningAppProcesses();
            if (processes == null) {
                return false;
            }
            int pid = Process.myPid();
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == pid) {
                    return process.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void finishActivityQuietly(PendingOperation pending) {
        CapabilityForegroundActivity activity = pending.activity();
        if (activity == null) {
            return;
        }
        try {
            activity.runOnUiThread(activity::finish);
        } catch (RuntimeException e) {
            // Best effort only; the activity finishes itself on delivery or
            // dies with its task.
        }
    }

    /**
     * The one parked foreground operation. The waiting bridge thread owns
     * the slot; the foreground activity claims it, attaches itself, runs the
     * operation, and settles the result exactly once via {@link #finish}.
     */
    static final class PendingOperation {
        private final String kind;
        private final ForegroundOperation operation;
        private final Map<String, Object> params;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile Map<String, Object> resultFields;
        private volatile String resultError;
        private volatile CapabilityForegroundActivity activity;

        PendingOperation(String kind, ForegroundOperation operation,
                         Map<String, Object> params) {
            this.kind = kind;
            this.operation = operation;
            this.params = params;
        }

        String kind() {
            return kind;
        }

        ForegroundOperation operation() {
            return operation;
        }

        Map<String, Object> params() {
            return params;
        }

        /** True the first time an activity claims this operation. */
        boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        boolean isDone() {
            return done.get();
        }

        void attachActivity(CapabilityForegroundActivity activity) {
            this.activity = activity;
        }

        CapabilityForegroundActivity activity() {
            return activity;
        }

        boolean await(long timeoutMillis) throws InterruptedException {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        /** Settle the result once; later calls are ignored. */
        void finish(Map<String, Object> fields, String typedError) {
            if (done.compareAndSet(false, true)) {
                resultFields = fields == null ? Map.of() : fields;
                resultError = typedError;
                latch.countDown();
            }
        }

        Map<String, Object> fields() {
            return resultFields;
        }

        String error() {
            return resultError;
        }
    }
}
