package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.SpeechForegroundOperation;

/**
 * Speech surface of the capability bridge (wave 2, W2b).
 *
 * <p>{@code tts.engines} (no params) lists the installed TTS engines as
 * {@code engines_json}, a pre-encoded {@code [{"name","label","default"}]}
 * array mirroring upstream {@code termux-tts-engines}. {@code tts.speak}
 * speaks inline {@code text} (bounded by the param-string contract) or a
 * guest-staged {@code text_path} file inside the rootfs; it accepts
 * {@code engine}, {@code language}/{@code region}/{@code variant},
 * {@code pitch} and {@code rate} (decimal strings, 0.1–3.0, default 1.0 —
 * the wire carries no floating-point type), and {@code stream}
 * ({@code music}, {@code notification}, {@code ring}, {@code alarm},
 * {@code system}, {@code call}). Like upstream it queues one utterance per
 * non-empty input line and waits for every utterance to settle, so the
 * guest exit code reflects a real completion; the wait is bounded and a
 * late engine answer is {@code tts-timeout}.</p>
 *
 * <p>{@code speech.recognize} needs a visible app, so it runs the
 * {@link SpeechForegroundOperation} through the foreground host after the
 * RECORD_AUDIO check. It accepts {@code language} (BCP-47 tag, default
 * {@code en-US}), {@code prompt} (shown by recognizers that honor
 * {@code EXTRA_PROMPT}), and {@code max_results} (1–10, default 10), and
 * returns {@code text} (the best final hypothesis) plus
 * {@code matches_json} (every final alternative). Partial results cannot
 * be streamed over the one-response bridge contract, so none are
 * reported.</p>
 *
 * <p>Nothing here throws into the handler: schema violations are
 * {@link CapabilityParams.Invalid} ({@code invalid-argument}), engine or
 * recognizer absence is {@code tts-unavailable}/{@code speech-unavailable},
 * and platform exception messages never reach the guest.</p>
 */
public final class SpeechModule implements CapabilityModule {
    public static final String METHOD_TTS_ENGINES = "tts.engines";
    public static final String METHOD_TTS_SPEAK = "tts.speak";
    public static final String METHOD_SPEECH_RECOGNIZE = "speech.recognize";

    private static final String TAG = "SpeechModule";

    /** Bounded wait for a foreground speech-recognition round. */
    private static final long FOREGROUND_TIMEOUT_MILLIS = 120_000L;
    /** Bounded wait for the TTS engine connection, like upstream. */
    private static final long TTS_INIT_TIMEOUT_MILLIS = 10_000L;
    /** Bounded wait for every queued utterance to settle. */
    private static final long TTS_UTTERANCE_TIMEOUT_MILLIS = 300_000L;

    private static final int TEXT_MAX_CHARS = 8192;
    private static final int STAGED_TEXT_MAX_CHARS = 32_768;
    private static final long STAGED_FILE_MAX_BYTES = 256L * 1024L;
    private static final int ENGINE_MAX_CHARS = 128;
    private static final int LOCALE_PART_MAX_CHARS = 35;
    private static final int FLOAT_MAX_CHARS = 16;
    private static final int STREAM_MAX_CHARS = 16;
    private static final int LANGUAGE_MAX_CHARS = 64;
    private static final int PROMPT_MAX_CHARS = 256;
    private static final float MIN_VOICE_FACTOR = 0.1f;
    private static final float MAX_VOICE_FACTOR = 3.0f;

    private static final Map<String, Integer> STREAMS = streams();

    private final Context context;
    private final AndroidPermissionChecker permissionChecker;
    private final CapabilityForegroundHost foregroundHost;
    private final GuestFilePathResolver pathResolver;
    private final Handler mainHandler;

    public SpeechModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        this.context = application;
        this.permissionChecker = new AndroidPermissionChecker(application);
        this.foregroundHost = new CapabilityForegroundHost(application);
        this.pathResolver = new GuestFilePathResolver(application);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_TTS_ENGINES, METHOD_TTS_SPEAK, METHOD_SPEECH_RECOGNIZE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_TTS_SPEAK, METHOD_SPEECH_RECOGNIZE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (METHOD_TTS_ENGINES.equals(request.getMethod())) {
            return ttsEngines(request);
        }
        if (METHOD_TTS_SPEAK.equals(request.getMethod())) {
            return ttsSpeak(request);
        }
        if (METHOD_SPEECH_RECOGNIZE.equals(request.getMethod())) {
            return speechRecognize(request);
        }
        return AndroidCapabilityProtocol.Response.error(request.getId(), "unsupported-method");
    }

    @Override
    public void close() {
        foregroundHost.close();
    }

    // ------------------------------------------------------------------
    // tts.engines
    // ------------------------------------------------------------------

    private AndroidCapabilityProtocol.Response ttsEngines(
            AndroidCapabilityProtocol.Request request) {
        TtsSession session = initTts(null);
        if (session.error != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), session.error);
        }
        try {
            String defaultEngine = session.tts.getDefaultEngine();
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (EngineInfo info : session.tts.getEngines()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"name\":").append(jsonString(info.name))
                        .append(",\"label\":")
                        .append(jsonString(info.label == null ? "" : info.label))
                        .append(",\"default\":")
                        .append(info.name.equals(defaultEngine))
                        .append('}');
            }
            json.append(']');
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("engines_json", json.toString());
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        } catch (RuntimeException e) {
            Log.w(TAG, "engine listing failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "tts-unavailable");
        } finally {
            session.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // tts.speak
    // ------------------------------------------------------------------

    private AndroidCapabilityProtocol.Response ttsSpeak(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text", "text_path", "engine", "language",
                "region", "variant", "pitch", "rate", "stream"));
        String text = params.optionalString("text", TEXT_MAX_CHARS, null);
        String textPath = params.optionalString("text_path", 4096, null);
        String engine = params.optionalString("engine", ENGINE_MAX_CHARS, "");
        String language = params.optionalString("language", LOCALE_PART_MAX_CHARS, "");
        String region = params.optionalString("region", LOCALE_PART_MAX_CHARS, "");
        String variant = params.optionalString("variant", LOCALE_PART_MAX_CHARS, "");
        float pitch = voiceFactor(params.optionalString("pitch", FLOAT_MAX_CHARS, ""));
        float rate = voiceFactor(params.optionalString("rate", FLOAT_MAX_CHARS, ""));
        String streamName = params.optionalString("stream", STREAM_MAX_CHARS, "music");
        Integer stream = STREAMS.get(streamName);
        if (stream == null || (text != null && textPath != null)) {
            throw new CapabilityParams.Invalid("unsupported parameter value");
        }
        if (text == null) {
            text = textPath == null ? "" : stagedText(textPath);
            if (text == null) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "invalid-argument");
            }
        }

        List<String> utterances = utterances(text);
        TtsSession session = initTts(engine.isEmpty() ? null : engine);
        if (session.error != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), session.error);
        }
        try {
            Boolean languageSupported = null;
            if (!language.isEmpty()) {
                int result = session.tts.setLanguage(locale(language, region, variant));
                languageSupported = result >= TextToSpeech.LANG_AVAILABLE;
            }
            session.tts.setPitch(pitch);
            session.tts.setSpeechRate(rate);

            // Install the completion listener before queueing so no engine
            // callback can race ahead of the counter.
            session.installListener(utterances.size());
            for (int i = 0; i < utterances.size(); i++) {
                String id = "nusadesk-tts-" + i;
                Bundle speakParams = new Bundle();
                speakParams.putInt(Engine.KEY_PARAM_STREAM, stream);
                speakParams.putString(Engine.KEY_PARAM_UTTERANCE_ID, id);
                if (session.tts.speak(utterances.get(i), TextToSpeech.QUEUE_ADD,
                        speakParams, id) == TextToSpeech.ERROR) {
                    // speak() refused the utterance: no callback will arrive,
                    // so settle the slot here instead of waiting it out.
                    session.utteranceFailed();
                    session.utteranceDone();
                }
            }
            if (!await(session.doneLatch, TTS_UTTERANCE_TIMEOUT_MILLIS)) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "tts-timeout");
            }
            if (session.failed.get()) {
                if (Boolean.FALSE.equals(languageSupported)) {
                    // The engine does not carry the requested language, so the
                    // utterance cannot succeed. Name the language instead of
                    // reporting a generic failure (device-observed on the S10e
                    // with Samsung TTS and the device locale id-ID).
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "tts-language-unsupported:" + language);
                }
                // No explicit language was requested: the engine speaks its own
                // default, and a failure there is usually the same unsupported
                // language condition. Ask the engine which language it is on
                // and name it when it is not available.
                java.util.Locale current = session.tts.getLanguage();
                if (current != null && !current.toString().isEmpty()
                        && session.tts.isLanguageAvailable(current) < TextToSpeech.LANG_AVAILABLE) {
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "tts-language-unsupported:" + current);
                }
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "tts-unavailable:utterance failed");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("spoken", true);
            String usedEngine = session.tts.getDefaultEngine();
            fields.put("engine", usedEngine == null ? "" : usedEngine);
            fields.put("utterances", utterances.size());
            if (languageSupported != null) {
                fields.put("language_supported", languageSupported);
            }
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        } catch (RuntimeException e) {
            Log.w(TAG, "speak failed", e);
            return AndroidCapabilityProtocol.Response.error(request.getId(), "tts-unavailable");
        } finally {
            session.shutdown();
        }
    }

    /**
     * Split the input into utterances like upstream: one per non-empty line,
     * hard-split at the platform's per-utterance limit so a long line cannot
     * be refused by the engine.
     */
    private static List<String> utterances(String text) {
        int max = TextToSpeech.getMaxSpeechInputLength();
        List<String> utterances = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            for (int start = 0; start < line.length(); start += max) {
                utterances.add(line.substring(start, Math.min(start + max, line.length())));
            }
        }
        return utterances;
    }

    private static Locale locale(String language, String region, String variant) {
        if (!region.isEmpty()) {
            return variant.isEmpty()
                    ? new Locale(language, region)
                    : new Locale(language, region, variant);
        }
        return new Locale(language);
    }

    /**
     * Parse the decimal-string voice factor ({@code pitch}, {@code rate}):
     * the wire type is a bounded string, so a malformed or out-of-range
     * value is {@code invalid-argument} rather than a guessed default.
     */
    private static float voiceFactor(String value) {
        if (value.isEmpty()) {
            return 1.0f;
        }
        final float parsed;
        try {
            parsed = Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new CapabilityParams.Invalid("parameter is not a decimal");
        }
        if (Float.isNaN(parsed) || Float.isInfinite(parsed)
                || parsed < MIN_VOICE_FACTOR || parsed > MAX_VOICE_FACTOR) {
            throw new CapabilityParams.Invalid("parameter out of range");
        }
        return parsed;
    }

    /** Read a guest-staged text file; null when the path is unusable. */
    private String stagedText(String guestPath) {
        final Path host;
        try {
            host = pathResolver.resolveForRead(guestPath);
        } catch (GuestFilePathResolver.Invalid e) {
            return null;
        }
        try {
            long size = Files.size(host);
            if (size > STAGED_FILE_MAX_BYTES) {
                return null;
            }
            String text = new String(Files.readAllBytes(host), StandardCharsets.UTF_8);
            return text.length() <= STAGED_TEXT_MAX_CHARS ? text : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // speech.recognize
    // ------------------------------------------------------------------

    private AndroidCapabilityProtocol.Response speechRecognize(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("language", "prompt", "max_results"));
        params.optionalString("language", LANGUAGE_MAX_CHARS, "");
        params.optionalString("prompt", PROMPT_MAX_CHARS, "");
        params.optionalLong("max_results", 1, 10, 10);

        CapabilityPermission state = permissionChecker.check(
                Manifest.permission.RECORD_AUDIO);
        if (state != CapabilityPermission.GRANTED) {
            return AndroidCapabilityProtocol.Response.error(request.getId(),
                    "speech-permission-"
                            + (state == CapabilityPermission.DENIED ? "denied" : "required"));
        }
        if (!recognitionAvailable(context)) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "speech-unavailable");
        }
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                SpeechForegroundOperation.KIND, request.getParams(),
                FOREGROUND_TIMEOUT_MILLIS);
        return rewrap(request.getId(), result);
    }

    /**
     * Whether a speech recognizer exists on this device. The platform
     * {@link SpeechRecognizer#isRecognitionAvailable} entry point is API 31+;
     * below it the upstream intent-resolution check is the only source.
     */
    static boolean recognitionAvailable(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SpeechRecognizer.isRecognitionAvailable(context);
            }
            PackageManager pm = context.getPackageManager();
            return pm != null && !pm.queryIntentActivities(
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // TTS session plumbing
    // ------------------------------------------------------------------

    /**
     * Create and initialize a {@link TextToSpeech} on the main thread —
     * the engine connection and its callbacks need a looper — and wait for
     * the bounded init. The returned session either holds a ready engine or
     * carries the typed error the caller reports.
     */
    private TtsSession initTts(String engine) {
        TtsSession session = new TtsSession();
        try {
            mainHandler.post(() -> {
                try {
                    session.tts = new TextToSpeech(context, status -> {
                        session.initOk.set(status == TextToSpeech.SUCCESS);
                        session.initLatch.countDown();
                    }, engine);
                } catch (RuntimeException e) {
                    Log.w(TAG, "TTS construction failed", e);
                    session.initLatch.countDown();
                }
            });
        } catch (RuntimeException e) {
            session.error = "tts-unavailable";
            return session;
        }
        if (!await(session.initLatch, TTS_INIT_TIMEOUT_MILLIS)) {
            session.error = "tts-timeout";
            session.shutdown();
            return session;
        }
        if (!session.initOk.get() || session.tts == null) {
            session.error = "tts-unavailable";
            session.shutdown();
        }
        return session;
    }

    /** Per-call TTS state: never shared between bridge requests. */
    private final class TtsSession {
        volatile TextToSpeech tts;
        volatile String error;
        final AtomicBoolean initOk = new AtomicBoolean();
        final AtomicBoolean failed = new AtomicBoolean();
        final CountDownLatch initLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(1);
        private final AtomicInteger remaining = new AtomicInteger();

        void utteranceFailed() {
            failed.set(true);
        }

        void utteranceDone() {
            if (remaining.decrementAndGet() <= 0) {
                doneLatch.countDown();
            }
        }

        void installListener(int count) {
            remaining.set(count);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    // Nothing to report; only completion matters.
                }

                @Override
                public void onError(String utteranceId) {
                    utteranceFailed();
                    utteranceDone();
                }

                @Override
                public void onDone(String utteranceId) {
                    utteranceDone();
                }
            });
            if (count == 0) {
                doneLatch.countDown();
            }
        }

        /** Stop and release the engine on the main thread; bounded. */
        void shutdown() {
            TextToSpeech engine = tts;
            if (engine == null) {
                return;
            }
            CountDownLatch released = new CountDownLatch(1);
            try {
                mainHandler.post(() -> {
                    try {
                        engine.stop();
                    } catch (RuntimeException ignored) {
                        // Already disconnected.
                    }
                    try {
                        engine.shutdown();
                    } catch (RuntimeException ignored) {
                        // Already disconnected.
                    }
                    released.countDown();
                });
                released.await(2, TimeUnit.SECONDS);
            } catch (RuntimeException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean await(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Map<String, Integer> streams() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("alarm", AudioManager.STREAM_ALARM);
        map.put("call", AudioManager.STREAM_VOICE_CALL);
        map.put("music", AudioManager.STREAM_MUSIC);
        map.put("notification", AudioManager.STREAM_NOTIFICATION);
        map.put("ring", AudioManager.STREAM_RING);
        map.put("system", AudioManager.STREAM_SYSTEM);
        return map;
    }

    private static String jsonString(String value) {
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

    /** Re-wrap a foreground host response (blank id) with the request's id. */
    private static AndroidCapabilityProtocol.Response rewrap(
            String requestId, AndroidCapabilityProtocol.Response result) {
        return result.isOk()
                ? AndroidCapabilityProtocol.Response.success(requestId, result.getFields())
                : AndroidCapabilityProtocol.Response.error(requestId, result.getError());
    }
}
