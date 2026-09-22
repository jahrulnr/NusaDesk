package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidPermissionChecker;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CapabilityPermission;

/**
 * The {@code speech} foreground operation behind {@code speech.recognize}
 * ({@code termux-speech-to-text}).
 *
 * <p>{@link SpeechRecognizer} only listens while the app is visible, so the
 * module parks this operation in the foreground host and it runs inside the
 * translucent {@link CapabilityForegroundActivity} — headless like upstream
 * {@code SpeechToTextService}: no dialog is shown, the mic indicator is the
 * only UI. Params are {@code language} (BCP-47 tag, default {@code en-US}),
 * {@code prompt} (optional {@code EXTRA_PROMPT}), and {@code max_results}
 * (1–10, default 10).</p>
 *
 * <p>Success fields are {@code text} (the best final hypothesis, empty when
 * the recognizer heard nothing — upstream prints nothing and exits 0 for
 * {@code NO_MATCH}/{@code SPEECH_TIMEOUT}) and {@code matches_json}, a
 * pre-encoded array of every final alternative. Partial hypotheses cannot
 * be streamed over the one-response bridge contract, so they are not
 * requested and never reported.</p>
 *
 * <p>Every terminal path destroys the recognizer exactly once and reports
 * through the sink: a runtime callback, a typed recognizer error, or the
 * activity being destroyed underneath (host timeout, task removal), which
 * the activity-settled {@code foreground-cancelled} already covers — the
 * lifecycle hook only releases the mic.</p>
 */
public final class SpeechForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name {@code speech.recognize} runs. */
    public static final String KIND = "speech";

    private static final String TAG = "SpeechForegroundOperation";
    private static final int LANGUAGE_MAX_CHARS = 64;
    private static final int PROMPT_MAX_CHARS = 256;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        String language = stringParam(params.get("language"), "en-US");
        String prompt = stringParam(params.get("prompt"), null);
        long maxResults = longParam(params.get("max_results"), 10);
        if (language.isEmpty() || language.length() > LANGUAGE_MAX_CHARS
                || (prompt != null && prompt.length() > PROMPT_MAX_CHARS)
                || maxResults < 1 || maxResults > 10) {
            sink.error("invalid-argument");
            return;
        }
        CapabilityPermission state =
                new AndroidPermissionChecker(activity).check(Manifest.permission.RECORD_AUDIO);
        if (state != CapabilityPermission.GRANTED) {
            sink.error("speech-permission-"
                    + (state == CapabilityPermission.DENIED ? "denied" : "required"));
            return;
        }
        final SpeechRecognizer recognizer;
        try {
            if (!recognitionAvailable(activity)) {
                sink.error("speech-unavailable");
                return;
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        } catch (RuntimeException e) {
            Log.w(TAG, "recognizer creation failed", e);
            sink.error("speech-unavailable");
            return;
        }
        if (recognizer == null) {
            sink.error("speech-unavailable");
            return;
        }

        Session session = new Session(activity, recognizer);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results == null
                        ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("text", matches == null || matches.isEmpty() ? "" : matches.get(0));
                fields.put("matches_json", matchesJson(matches));
                session.destroy();
                sink.success(fields);
            }

            @Override
            public void onError(int error) {
                session.destroy();
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        // Heard nothing usable: upstream prints nothing and
                        // exits 0, so report an honest empty result.
                        Map<String, Object> fields = new LinkedHashMap<>();
                        fields.put("text", "");
                        fields.put("matches_json", "[]");
                        sink.success(fields);
                        return;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        sink.error("speech-permission-denied");
                        return;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        sink.error("speech-busy");
                        return;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        sink.error("speech-unavailable:network error");
                        return;
                    case SpeechRecognizer.ERROR_AUDIO:
                        sink.error("speech-unavailable:audio error");
                        return;
                    case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                    case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                        sink.error("speech-unavailable:language unsupported");
                        return;
                    default:
                        sink.error("speech-unavailable");
                }
            }

            @Override
            public void onReadyForSpeech(Bundle bundle) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // Partials cannot reach the guest over the one-response
                // contract; they are intentionally dropped, not reported.
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, (int) maxResults);
        if (prompt != null && !prompt.isEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        }
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "startListening failed", e);
            session.destroy();
            sink.error("speech-unavailable");
        }
    }

    /**
     * Per-run recognizer handle: destroys the recognizer once, and unhooks
     * itself when the host activity dies (host timeout or task removal) so
     * the mic is never held open for a bridge call that already reported.
     */
    private static final class Session implements Application.ActivityLifecycleCallbacks {
        private final CapabilityForegroundActivity activity;
        private final SpeechRecognizer recognizer;
        private boolean destroyed;

        Session(CapabilityForegroundActivity activity, SpeechRecognizer recognizer) {
            this.activity = activity;
            this.recognizer = recognizer;
            activity.getApplication().registerActivityLifecycleCallbacks(this);
        }

        synchronized void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // The recognizer service is already gone.
            }
        }

        @Override
        public void onActivityDestroyed(Activity other) {
            if (other == activity) {
                destroy();
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

    /** Pre-encoded JSON array of the final alternatives, bounded upstream. */
    private static String matchesJson(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(jsonString(matches.get(i)));
        }
        return json.append(']').toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
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
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    /**
     * Whether a speech recognizer exists on this device. The platform
     * {@link SpeechRecognizer#isRecognitionAvailable} entry point is API 31+;
     * below it the upstream intent-resolution check is the only source.
     */
    private static boolean recognitionAvailable(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SpeechRecognizer.isRecognitionAvailable(activity);
            }
            PackageManager pm = activity.getPackageManager();
            return pm != null && !pm.queryIntentActivities(
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String stringParam(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static long longParam(Object value, long fallback) {
        return value instanceof Long ? (Long) value : fallback;
    }
}
