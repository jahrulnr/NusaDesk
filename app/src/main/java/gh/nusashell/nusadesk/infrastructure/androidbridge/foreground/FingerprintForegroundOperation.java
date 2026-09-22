package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.app.Activity;
import android.app.Application;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@code fingerprint} foreground operation behind
 * {@code fingerprint.authenticate} ({@code termux-fingerprint}).
 *
 * <p>Params mirror the upstream script flags flattened to bridge fields:
 * {@code title} (default {@code "Authenticate"}), {@code description},
 * {@code subtitle}, {@code cancel} (the negative button text, default
 * {@code "Cancel"}), and {@code timeout_ms} (bounded 1 s-5 min, default
 * 60 s — the same idea as the upstream fixed sensor timeout, made a
 * parameter).</p>
 *
 * <p>The prompt is the platform {@link BiometricPrompt} (API 28+, inside
 * the translucent host activity); the app carries no androidx biometric
 * dependency. Results mirror the upstream JSON shape flattened to the
 * bridge contract: {@code auth_result} is a boolean (true only on
 * {@code onAuthenticationSucceeded}), {@code failed_attempts} counts the
 * rejected swipes, and {@code errors_json} is a pre-encoded array of
 * upstream-style {@code ERROR_*} names. A cancel/dismiss is a normal
 * result with {@code auth_result:false} and a cancellation code, never a
 * claimed success.</p>
 *
 * <p>Every terminal path settles the sink exactly once and releases the
 * prompt: an auth callback, the negative button, the {@code timeout_ms}
 * task, or the activity being destroyed — the latter already settles the
 * parked wait as {@code foreground-cancelled}, so the destroy hook only
 * cancels authentication and drops the pending timeout.</p>
 */
public final class FingerprintForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name {@code fingerprint.authenticate} runs. */
    public static final String KIND = "fingerprint";

    /** Upstream error name for a sensor with no enrolled fingerprints. */
    public static final String ERROR_NO_ENROLLED = "ERROR_NO_ENROLLED_FINGERPRINTS";

    /** Bounds on {@code timeout_ms}; the module validates against the same values. */
    public static final long MIN_TIMEOUT_MILLIS = 1_000L;
    public static final long MAX_TIMEOUT_MILLIS = 300_000L;
    public static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    private static final String TAG = "FingerprintForegroundOp";
    private static final String DEFAULT_TITLE = "Authenticate";
    private static final String DEFAULT_CANCEL = "Cancel";
    private static final int TITLE_MAX_CHARS = 512;
    private static final int DESCRIPTION_MAX_CHARS = 1024;
    private static final int SUBTITLE_MAX_CHARS = 512;
    private static final int CANCEL_MAX_CHARS = 128;
    /** Upstream lockout escalation threshold on the failed-swipe count. */
    private static final int MAX_ATTEMPTS = 5;
    /**
     * Platform error delivered when the negative button dismisses the
     * prompt; hidden from the SDK stub jar, so the value is pinned here.
     */
    private static final int BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        Options options = Options.parse(params);
        if (options == null) {
            sink.error("invalid-argument");
            return;
        }
        Executor executor = activity.getMainExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        Session session = new Session(activity);
        CancellationSignal signal = new CancellationSignal();
        List<String> errors = new ArrayList<>();
        AtomicInteger failedAttempts = new AtomicInteger();
        AtomicBoolean settled = new AtomicBoolean();

        Runnable timeoutTask = () -> {
            errors.add("ERROR_TIMEOUT");
            settle(settled, session, sink, false, failedAttempts.get(), errors);
        };
        session.handler = handler;
        session.timeoutTask = timeoutTask;
        session.signal = signal;

        final BiometricPrompt prompt;
        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle(options.title.isEmpty() ? DEFAULT_TITLE : options.title)
                    .setNegativeButton(
                            options.cancel.isEmpty() ? DEFAULT_CANCEL : options.cancel,
                            executor,
                            (dialog, which) -> {
                                errors.add("ERROR_CANCELED");
                                settle(settled, session, sink, false,
                                        failedAttempts.get(), errors);
                            });
            if (!options.subtitle.isEmpty()) {
                builder.setSubtitle(options.subtitle);
            }
            if (!options.description.isEmpty()) {
                builder.setDescription(options.description);
            }
            prompt = builder.build();
        } catch (RuntimeException e) {
            Log.w(TAG, "prompt build failed", e);
            session.cleanup();
            sink.error("fingerprint-unavailable");
            return;
        }

        BiometricPrompt.AuthenticationCallback callback =
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                errors.add(errorName(errorCode));
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                        && failedAttempts.get() >= MAX_ATTEMPTS) {
                    errors.add("ERROR_TOO_MANY_FAILED_ATTEMPTS");
                }
                settle(settled, session, sink, false, failedAttempts.get(), errors);
            }

            @Override
            public void onAuthenticationSucceeded(
                    BiometricPrompt.AuthenticationResult result) {
                settle(settled, session, sink, true, failedAttempts.get(), errors);
            }

            @Override
            public void onAuthenticationFailed() {
                // One unrecognized swipe; like upstream the prompt keeps
                // listening and only the counter moves.
                failedAttempts.incrementAndGet();
            }
        };

        // Post the authenticate call so it runs after onCreate has returned —
        // the platform dialog shows once the activity is visible, and a
        // destroyed activity (cleaned session) never starts the prompt.
        handler.post(() -> {
            if (session.isCleaned()) {
                return;
            }
            try {
                prompt.authenticate(signal, executor, callback);
                handler.postDelayed(timeoutTask, options.timeoutMs);
            } catch (RuntimeException e) {
                Log.w(TAG, "authenticate refused", e);
                session.cleanup();
                sink.error("fingerprint-unavailable");
            }
        });
    }

    /**
     * The flat response fields for one authentication round, shared with
     * {@code FingerprintModule} so the no-enrollment answer — which never
     * reaches the foreground — reports the same shape.
     */
    public static Map<String, Object> resultFields(boolean authenticated,
                                                   long failedAttempts,
                                                   List<String> errors) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("auth_result", authenticated);
        fields.put("errors_json", errorsJson(errors));
        fields.put("failed_attempts", failedAttempts);
        return fields;
    }

    /**
     * Settle the operation exactly once. The fields are serialized before
     * cleanup because cancelling the signal can synchronously deliver one
     * last error callback that must not join the reported list.
     */
    private static void settle(AtomicBoolean settled, Session session, ResultSink sink,
                               boolean authenticated, int failedAttempts,
                               List<String> errors) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> fields = resultFields(authenticated, failedAttempts, errors);
        session.cleanup();
        sink.success(fields);
    }

    /** The upstream-style error names for the {@code errors} field. */
    private static String errorName(int errorCode) {
        switch (errorCode) {
            case BiometricPrompt.BIOMETRIC_ERROR_CANCELED:
            case BIOMETRIC_ERROR_NEGATIVE_BUTTON:
                return "ERROR_CANCELED";
            case BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED:
                return "ERROR_USER_CANCELED";
            case BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT:
                return "ERROR_LOCKOUT";
            case BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
                return "ERROR_LOCKOUT_PERMANENT";
            case BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT:
                return "ERROR_TIMEOUT";
            case BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS:
                return ERROR_NO_ENROLLED;
            case BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                return "ERROR_NO_HARDWARE";
            case BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                return "ERROR_HW_UNAVAILABLE";
            case BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL:
                return "ERROR_NO_DEVICE_CREDENTIAL";
            case BiometricPrompt.BIOMETRIC_ERROR_NO_SPACE:
                return "ERROR_NO_SPACE";
            case BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS:
                return "ERROR_UNABLE_TO_PROCESS";
            default:
                return "ERROR_UNKNOWN";
        }
    }

    private static String errorsJson(List<String> errors) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(jsonString(errors.get(i)));
        }
        return out.append(']').toString();
    }

    /** Minimal JSON string escaping for the pre-encoded errors field. */
    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * Per-run prompt state: the cancellation signal plus the pending timeout
     * task. {@link #cleanup} is idempotent and also runs when the host
     * activity is destroyed, so a prompt or timeout never outlives the
     * parked bridge request.
     */
    private static final class Session implements Application.ActivityLifecycleCallbacks {
        private final CapabilityForegroundActivity activity;
        private Handler handler;
        private Runnable timeoutTask;
        private CancellationSignal signal;
        private boolean cleaned;

        Session(CapabilityForegroundActivity activity) {
            this.activity = activity;
            activity.getApplication().registerActivityLifecycleCallbacks(this);
        }

        synchronized boolean isCleaned() {
            return cleaned;
        }

        synchronized void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            if (handler != null && timeoutTask != null) {
                handler.removeCallbacks(timeoutTask);
            }
            CancellationSignal pending = signal;
            signal = null;
            if (pending != null) {
                try {
                    pending.cancel();
                } catch (RuntimeException ignored) {
                    // The prompt is already gone with the activity.
                }
            }
        }

        @Override
        public void onActivityDestroyed(Activity other) {
            if (other == activity) {
                cleanup();
            }
        }

        @Override
        public void onActivityCreated(Activity other, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity other) {
        }

        @Override
        public void onActivityResumed(Activity other) {
        }

        @Override
        public void onActivityPaused(Activity other) {
        }

        @Override
        public void onActivityStopped(Activity other) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity other, Bundle outState) {
        }
    }

    /** The validated prompt options for one run. */
    private static final class Options {
        String title;
        String description;
        String subtitle;
        String cancel;
        long timeoutMs;

        static Options parse(Map<String, Object> params) {
            Options options = new Options();
            options.title = stringParam(params, "title", "");
            options.description = stringParam(params, "description", "");
            options.subtitle = stringParam(params, "subtitle", "");
            options.cancel = stringParam(params, "cancel", "");
            options.timeoutMs = longParam(params, "timeout_ms", DEFAULT_TIMEOUT_MILLIS);
            if (options.title.length() > TITLE_MAX_CHARS
                    || options.description.length() > DESCRIPTION_MAX_CHARS
                    || options.subtitle.length() > SUBTITLE_MAX_CHARS
                    || options.cancel.length() > CANCEL_MAX_CHARS
                    || options.timeoutMs < MIN_TIMEOUT_MILLIS
                    || options.timeoutMs > MAX_TIMEOUT_MILLIS) {
                return null;
            }
            return options;
        }
    }

    private static String stringParam(Map<String, Object> params, String key,
                                      String fallback) {
        Object value = params == null ? null : params.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static long longParam(Map<String, Object> params, String key,
                                  long fallback) {
        Object value = params == null ? null : params.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }
}
