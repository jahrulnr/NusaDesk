package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidPermissionChecker;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CapabilityPermission;

/**
 * The {@code dialog} foreground operation behind {@code dialog.show}
 * ({@code termux-dialog}).
 *
 * <p>Params mirror the upstream widget contract: {@code widget} (one of
 * {@code checkbox}, {@code confirm}, {@code counter}, {@code date},
 * {@code radio}, {@code sheet}, {@code speech}, {@code spinner},
 * {@code text} — the default — or {@code time}), {@code title},
 * {@code hint}, {@code values} (upstream comma list where {@code \,}
 * escapes a comma), and the {@code password}, {@code multiline},
 * {@code numeric}, and {@code date_format} modifiers.</p>
 *
 * <p>Results mirror the upstream JSON shape flattened to bridge fields:
 * {@code code} is {@code -1} for the positive button
 * ({@code DialogInterface.BUTTON_POSITIVE}), {@code -2} for cancel or
 * dismiss ({@code BUTTON_NEGATIVE}), and {@code 0} for the sheet and
 * speech widgets which set no button code upstream; {@code text} is always
 * present; {@code index} is the chosen list position when a single-pick
 * widget selected one; {@code values_json} is the checkbox selection as a
 * pre-encoded {@code [{"index","text"}]} array; and {@code error} carries
 * widget-level failures (the recognizer's {@code ERROR_*} name, an invalid
 * counter range, a bad date pattern) like the upstream error field. A
 * cancelled dialog is therefore a normal success {@code {code:-2}} so the
 * guest exit status stays 0.</p>
 *
 * <p>Every terminal path dismisses the dialog and releases the recognizer
 * (speech widget) exactly once: a button click, a sheet pick, a dismiss
 * event, the permission answer, or the activity being destroyed — the
 * latter already settles the parked wait as {@code foreground-cancelled},
 * so the destroy hook only frees the UI/mic.</p>
 */
public final class DialogForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name {@code dialog.show} runs. */
    public static final String KIND = "dialog";

    private static final String TAG = "DialogForegroundOperation";
    private static final int REQUEST_CODE_AUDIO = 0x4E51;
    private static final int WIDGET_MAX_CHARS = 16;
    private static final int TITLE_MAX_CHARS = 512;
    private static final int HINT_MAX_CHARS = 8192;
    private static final int VALUES_MAX_CHARS = 8192;
    private static final int DATE_FORMAT_MAX_CHARS = 64;
    private static final int MAX_VALUES = 64;
    private static final int COUNTER_DEFAULT_MIN = 0;
    private static final int COUNTER_DEFAULT_MAX = 100;

    private static final Set<String> WIDGETS = Set.of("checkbox", "confirm",
            "counter", "date", "radio", "sheet", "speech", "spinner", "text",
            "time");

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
        if (needsValues(options.widget) && options.values.isEmpty()) {
            sink.error("invalid-argument");
            return;
        }
        if ("date".equals(options.widget) && !options.dateFormat.isEmpty()) {
            try {
                new SimpleDateFormat(options.dateFormat, Locale.US);
            } catch (RuntimeException e) {
                Map<String, Object> fields = cancelledFields();
                fields.put("error", "invalid date format");
                sink.success(fields);
                return;
            }
        }
        Session session = new Session(activity);
        switch (options.widget) {
            case "confirm":
                showConfirm(activity, options, session, sink);
                return;
            case "checkbox":
                showCheckbox(activity, options, session, sink);
                return;
            case "counter":
                showCounter(activity, options, session, sink);
                return;
            case "date":
                showDate(activity, options, session, sink);
                return;
            case "radio":
                showRadio(activity, options, session, sink);
                return;
            case "sheet":
                showSheet(activity, options, session, sink);
                return;
            case "speech":
                showSpeech(activity, options, session, sink);
                return;
            case "spinner":
                showSpinner(activity, options, session, sink);
                return;
            case "time":
                showTime(activity, options, session, sink);
                return;
            default:
                showText(activity, options, session, sink);
        }
    }

    private static boolean needsValues(String widget) {
        return "checkbox".equals(widget) || "radio".equals(widget)
                || "sheet".equals(widget) || "spinner".equals(widget);
    }

    // ------------------------------------------------------------------
    // Widget builders — every one settles the sink exactly once, whether
    // through a button, a pick, a dismiss, or the destroy cleanup.
    // ------------------------------------------------------------------

    /** Text entry: hint, password, multiline, and numeric input types. */
    private static void showText(CapabilityForegroundActivity activity, Options options,
                                 Session session, ResultSink sink) {
        EditText input = new EditText(activity);
        if (!options.hint.isEmpty()) {
            input.setHint(options.hint);
        }
        int flags = InputType.TYPE_CLASS_TEXT;
        if (options.password) {
            flags = options.numeric
                    ? flags | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    : flags | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        }
        if (options.multiline) {
            flags |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            input.setLines(4);
        }
        if (options.numeric) {
            flags &= ~InputType.TYPE_CLASS_TEXT;
            flags |= InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        input.setInputType(flags);
        showButtonDialog(activity, options, session, wrap(activity, input),
                "OK", "Cancel", sink,
                () -> positive(sink, session, input.getText().toString()));
    }

    /** Yes/No confirmation: positive {@code yes}, negative {@code no}. */
    private static void showConfirm(CapabilityForegroundActivity activity,
                                    Options options, Session session, ResultSink sink) {
        TextView message = new TextView(activity);
        message.setText(options.hint.isEmpty() ? "Confirm" : options.hint);
        message.setTextSize(20);
        showButtonDialog(activity, options, session, wrap(activity, message),
                "Yes", "No", sink,
                () -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("code", (long) DialogInterface.BUTTON_POSITIVE);
                    fields.put("text", "yes");
                    sink.success(fields);
                    session.cleanup();
                },
                () -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("code", (long) DialogInterface.BUTTON_NEGATIVE);
                    fields.put("text", "no");
                    sink.success(fields);
                    session.cleanup();
                });
    }

    /** Multi-pick checkboxes over the values list. */
    private static void showCheckbox(CapabilityForegroundActivity activity,
                                     Options options, Session session, ResultSink sink) {
        LinearLayout layout = verticalList(activity);
        List<CheckBox> boxes = new ArrayList<>();
        for (int i = 0; i < options.values.size(); i++) {
            CheckBox box = new CheckBox(activity);
            box.setText(options.values.get(i));
            box.setTextSize(18);
            box.setPadding(16, 16, 16, 16);
            layout.addView(box);
            boxes.add(box);
        }
        showButtonDialog(activity, options, session, wrap(activity, layout),
                "OK", "Cancel", sink,
                () -> {
                    StringBuilder text = new StringBuilder("[");
                    StringBuilder json = new StringBuilder("[");
                    boolean first = true;
                    for (int i = 0; i < boxes.size(); i++) {
                        if (!boxes.get(i).isChecked()) {
                            continue;
                        }
                        if (!first) {
                            text.append(", ");
                            json.append(',');
                        }
                        first = false;
                        text.append(options.values.get(i));
                        json.append("{\"index\":").append(i)
                                .append(",\"text\":")
                                .append(jsonString(options.values.get(i)))
                                .append('}');
                    }
                    text.append(']');
                    json.append(']');
                    Map<String, Object> fields = positiveFields(text.toString());
                    fields.put("values_json", json.toString());
                    sink.success(fields);
                    session.cleanup();
                });
    }

    /** +/- counter bounded by the upstream default range. */
    private static void showCounter(CapabilityForegroundActivity activity,
                                    Options options, Session session, ResultSink sink) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(activity);
        label.setTextSize(20);
        Button decrement = new Button(activity);
        decrement.setText("-");
        Button increment = new Button(activity);
        increment.setText("+");
        int[] counter = {(COUNTER_DEFAULT_MAX - COUNTER_DEFAULT_MIN) / 2};
        label.setText(String.valueOf(counter[0]));
        decrement.setOnClickListener(v -> {
            if (counter[0] - 1 >= COUNTER_DEFAULT_MIN) {
                label.setText(String.valueOf(--counter[0]));
            }
        });
        increment.setOnClickListener(v -> {
            if (counter[0] + 1 <= COUNTER_DEFAULT_MAX) {
                label.setText(String.valueOf(++counter[0]));
            }
        });
        row.addView(decrement);
        row.addView(label);
        row.addView(increment);
        showButtonDialog(activity, options, session, wrap(activity, row),
                "OK", "Cancel", sink,
                () -> positive(sink, session, label.getText().toString()));
    }

    /** Date picker; {@code date_format} or the upstream Date.toString text. */
    private static void showDate(CapabilityForegroundActivity activity, Options options,
                                 Session session, ResultSink sink) {
        DatePicker picker = new DatePicker(activity);
        showButtonDialog(activity, options, session, wrap(activity, picker),
                "OK", "Cancel", sink,
                () -> {
                    Calendar calendar = Calendar.getInstance();
                    calendar.set(picker.getYear(), picker.getMonth(),
                            picker.getDayOfMonth(), 0, 0, 0);
                    String text;
                    if (options.dateFormat.isEmpty()) {
                        text = calendar.getTime().toString();
                    } else {
                        SimpleDateFormat format =
                                new SimpleDateFormat(options.dateFormat, Locale.US);
                        format.setTimeZone(calendar.getTimeZone());
                        text = format.format(calendar.getTime());
                    }
                    positive(sink, session, text);
                });
    }

    /** Single-pick radio buttons; nothing checked answers empty text. */
    private static void showRadio(CapabilityForegroundActivity activity, Options options,
                                  Session session, ResultSink sink) {
        RadioGroup group = new RadioGroup(activity);
        group.setPadding(16, 16, 16, 16);
        List<RadioButton> buttons = new ArrayList<>();
        for (int i = 0; i < options.values.size(); i++) {
            RadioButton button = new RadioButton(activity);
            button.setText(options.values.get(i));
            button.setId(i);
            button.setTextSize(18);
            button.setPadding(16, 16, 16, 16);
            group.addView(button);
            buttons.add(button);
        }
        showButtonDialog(activity, options, session, wrap(activity, group),
                "OK", "Cancel", sink,
                () -> {
                    int index = group.indexOfChild(
                            group.findViewById(group.getCheckedRadioButtonId()));
                    Map<String, Object> fields = positiveFields(
                            index >= 0 ? options.values.get(index) : "");
                    if (index >= 0) {
                        fields.put("index", (long) index);
                    }
                    sink.success(fields);
                    session.cleanup();
                });
    }

    /**
     * Single-pick list standing in for the upstream material bottom sheet —
     * the app carries no material dependency, and the upstream result shape
     * (pick {@code code:0} + {@code text} + {@code index}, dismiss
     * {@code code:-2}) is identical.
     */
    private static void showSheet(CapabilityForegroundActivity activity, Options options,
                                  Session session, ResultSink sink) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(options.title)
                .setItems(options.values.toArray(new String[0]),
                        (dialog, which) -> {
                            Map<String, Object> fields = new LinkedHashMap<>();
                            fields.put("code", 0L);
                            fields.put("text", options.values.get(which));
                            fields.put("index", (long) which);
                            sink.success(fields);
                            session.cleanup();
                        });
        showDialog(session, builder.create(), sink);
    }

    /** Dropdown spinner; always has a selection. */
    private static void showSpinner(CapabilityForegroundActivity activity,
                                    Options options, Session session, ResultSink sink) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, options.values);
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        showButtonDialog(activity, options, session, wrap(activity, spinner),
                "OK", "Cancel", sink,
                () -> {
                    int index = spinner.getSelectedItemPosition();
                    Map<String, Object> fields = positiveFields(
                            index >= 0 ? options.values.get(index) : "");
                    if (index >= 0) {
                        fields.put("index", (long) index);
                    }
                    sink.success(fields);
                    session.cleanup();
                });
    }

    /**
     * Microphone input: like upstream this requests RECORD_AUDIO from the
     * dialog itself; a denial or a missing recognizer answers the upstream
     * cancelled shape, and recognizer errors answer {@code code:0} with the
     * upstream {@code ERROR_*} name in {@code error}.
     */
    private static void showSpeech(CapabilityForegroundActivity activity,
                                   Options options, Session session, ResultSink sink) {
        AndroidPermissionChecker checker = new AndroidPermissionChecker(activity);
        Runnable start = () -> startSpeechListening(activity, options, session, sink);
        if (checker.check(Manifest.permission.RECORD_AUDIO)
                == CapabilityPermission.GRANTED) {
            start.run();
            return;
        }
        activity.setPermissionResultHandler((requestCode, names, results) -> {
            if (requestCode != REQUEST_CODE_AUDIO) {
                return;
            }
            boolean granted = results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                start.run();
            } else {
                // Upstream finishes the activity on denial, which posts the
                // cancelled result shape.
                sink.success(cancelledFields());
                session.cleanup();
            }
        });
        try {
            activity.requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_AUDIO);
        } catch (RuntimeException e) {
            Log.w(TAG, "permission request failed", e);
            sink.success(cancelledFields());
            session.cleanup();
        }
    }

    private static void startSpeechListening(CapabilityForegroundActivity activity,
                                             Options options, Session session,
                                             ResultSink sink) {
        if (session.isCleaned()) {
            // The activity died while the permission prompt was open; the
            // parked wait already settled, so do not start a recognizer.
            return;
        }
        final SpeechRecognizer recognizer;
        try {
            if (!recognitionAvailable(activity)) {
                sink.success(cancelledFields());
                session.cleanup();
                return;
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        } catch (RuntimeException e) {
            Log.w(TAG, "recognizer creation failed", e);
            sink.success(cancelledFields());
            session.cleanup();
            return;
        }
        if (recognizer == null) {
            sink.success(cancelledFields());
            session.cleanup();
            return;
        }
        session.recognizer = recognizer;
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results == null
                        ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("code", 0L);
                fields.put("text", matches == null || matches.isEmpty() ? "" : matches.get(0));
                sink.success(fields);
                session.cleanup();
            }

            @Override
            public void onError(int error) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("code", 0L);
                fields.put("text", "");
                fields.put("error", speechErrorName(error));
                sink.success(fields);
                session.cleanup();
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
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
        TextView message = new TextView(activity);
        message.setText(options.hint.isEmpty() ? "Listening for speech..." : options.hint);
        message.setTextSize(20);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(options.title)
                .setView(wrap(activity, message))
                .setNegativeButton("Cancel", (d, which) -> {
                    sink.success(cancelledFields());
                    session.cleanup();
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        showDialog(session, dialog, sink);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "startListening failed", e);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("code", 0L);
            fields.put("text", "");
            fields.put("error", "ERROR_CLIENT");
            sink.success(fields);
            session.cleanup();
        }
    }

    /** Time picker answering {@code HH:mm} like upstream. */
    private static void showTime(CapabilityForegroundActivity activity, Options options,
                                 Session session, ResultSink sink) {
        TimePicker picker = new TimePicker(activity);
        showButtonDialog(activity, options, session, wrap(activity, picker),
                "OK", "Cancel", sink,
                () -> positive(sink, session, String.format(Locale.getDefault(),
                        "%02d:%02d", picker.getHour(), picker.getMinute())));
    }

    // ------------------------------------------------------------------
    // Shared dialog plumbing
    // ------------------------------------------------------------------

    private interface Positive {
        void onPositive();
    }

    /**
     * Show a titled two-button dialog. The positive button settles through
     * {@code onPositive}; the negative button and every other dismissal
     * (back, outside touch, destroy cleanup) answer the upstream cancelled
     * shape — with the sink's once-only guarantee absorbing the dismiss
     * that follows a positive click.
     */
    private static void showButtonDialog(CapabilityForegroundActivity activity,
                                         Options options, Session session, View view,
                                         String positiveText, String negativeText,
                                         ResultSink sink, Positive onPositive) {
        showButtonDialog(activity, options, session, view, positiveText,
                negativeText, sink, onPositive, null);
    }

    private static void showButtonDialog(CapabilityForegroundActivity activity,
                                         Options options, Session session, View view,
                                         String positiveText, String negativeText,
                                         ResultSink sink, Positive onPositive,
                                         Runnable onNegative) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(options.title)
                .setView(view)
                .setPositiveButton(positiveText, (d, which) -> onPositive.onPositive())
                .setNegativeButton(negativeText, (d, which) -> {
                    if (onNegative != null) {
                        onNegative.run();
                    } else {
                        sink.success(cancelledFields());
                        session.cleanup();
                    }
                })
                .create();
        showDialog(session, dialog, sink);
    }

    private static void showDialog(Session session, Dialog dialog, ResultSink sink) {
        session.dialog = dialog;
        dialog.setOnDismissListener(d -> {
            session.cleanup();
            sink.success(cancelledFields());
        });
        try {
            dialog.show();
        } catch (RuntimeException e) {
            Log.w(TAG, "dialog show failed", e);
            sink.error("dialog-unavailable");
            session.cleanup();
        }
    }

    /** Wrap the widget view in the upstream scrollable margin frame. */
    private static View wrap(CapabilityForegroundActivity activity, View view) {
        FrameLayout layout = new FrameLayout(activity);
        int margin = (int) (24 * activity.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(margin, margin, margin, margin);
        view.setLayoutParams(params);
        layout.addView(view);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(layout);
        return scroll;
    }

    private static LinearLayout verticalList(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static void positive(ResultSink sink, Session session, String text) {
        Map<String, Object> fields = positiveFields(text);
        sink.success(fields);
        session.cleanup();
    }

    private static Map<String, Object> positiveFields(String text) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", (long) DialogInterface.BUTTON_POSITIVE);
        fields.put("text", text == null ? "" : text);
        return fields;
    }

    private static Map<String, Object> cancelledFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", (long) DialogInterface.BUTTON_NEGATIVE);
        fields.put("text", "");
        return fields;
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

    /** The upstream error names for the speech widget's {@code error} field. */
    private static String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK:
                return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "ERROR_SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_CLIENT:
                return "ERROR_CLIENT";
            default:
                return "ERROR_UNKNOWN";
        }
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

    /**
     * Per-run UI state: the shown dialog and (speech widget) the recognizer.
     * {@link #cleanup} is idempotent and also runs when the host activity is
     * destroyed, so no widget can outlive the parked bridge request.
     */
    private static final class Session implements Application.ActivityLifecycleCallbacks {
        private final CapabilityForegroundActivity activity;
        private Dialog dialog;
        private SpeechRecognizer recognizer;
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
            SpeechRecognizer speech = recognizer;
            recognizer = null;
            if (speech != null) {
                try {
                    speech.destroy();
                } catch (RuntimeException ignored) {
                    // The recognizer service is already gone.
                }
            }
            Dialog shown = dialog;
            dialog = null;
            if (shown != null) {
                try {
                    shown.dismiss();
                } catch (RuntimeException ignored) {
                    // The window is already gone with the activity.
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

    /** The validated widget options for one run. */
    private static final class Options {
        String widget;
        String title;
        String hint;
        List<String> values;
        boolean password;
        boolean multiline;
        boolean numeric;
        String dateFormat;

        static Options parse(Map<String, Object> params) {
            Options options = new Options();
            options.widget = stringParam(params.get("widget"), "text");
            options.title = stringParam(params.get("title"), "");
            options.hint = stringParam(params.get("hint"), "");
            options.values = parseValues(stringParam(params.get("values"), ""));
            options.password = booleanParam(params.get("password"), false);
            options.multiline = booleanParam(params.get("multiline"), false);
            options.numeric = booleanParam(params.get("numeric"), false);
            options.dateFormat = stringParam(params.get("date_format"), "");
            if (!WIDGETS.contains(options.widget)
                    || options.widget.length() > WIDGET_MAX_CHARS
                    || options.title.length() > TITLE_MAX_CHARS
                    || options.hint.length() > HINT_MAX_CHARS
                    || options.dateFormat.length() > DATE_FORMAT_MAX_CHARS
                    || options.values == null) {
                return null;
            }
            return options;
        }

        /**
         * Upstream value-list parsing: split on commas not preceded by a
         * backslash, trim each entry, then unescape {@code \,} to {@code ,}.
         */
        private static List<String> parseValues(String raw) {
            List<String> values = new ArrayList<>();
            if (raw.isEmpty()) {
                return values;
            }
            if (raw.length() > VALUES_MAX_CHARS) {
                return null;
            }
            for (String part : raw.split("(?<!\\\\),")) {
                values.add(part.trim().replace("\\,", ","));
                if (values.size() > MAX_VALUES) {
                    return null;
                }
            }
            return values;
        }
    }

    private static String stringParam(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    private static boolean booleanParam(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
