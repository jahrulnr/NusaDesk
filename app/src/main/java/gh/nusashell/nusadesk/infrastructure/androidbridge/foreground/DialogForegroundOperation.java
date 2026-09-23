package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

import gh.nusashell.nusadesk.R;
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
        Shell shell = new Shell(activity, options.title);
        EditText input = (EditText) shell.inflate(R.layout.nusadesk_dialog_text);
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
            input.setGravity(Gravity.TOP | Gravity.START);
        }
        if (options.numeric) {
            flags &= ~InputType.TYPE_CLASS_TEXT;
            flags |= InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        input.setInputType(flags);
        // Float the window above the IME and open the keyboard with the field
        // already focused, so the input is never hidden behind it.
        Window window = shell.dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        shell.dialog.setOnShowListener(d -> input.requestFocus());
        showButtonDialog(shell, session, "OK", "Cancel", sink,
                () -> positive(sink, session, input.getText().toString()), null);
    }

    /** Yes/No confirmation: positive {@code yes}, negative {@code no}. */
    private static void showConfirm(CapabilityForegroundActivity activity,
                                    Options options, Session session, ResultSink sink) {
        Shell shell = new Shell(activity, options.title);
        TextView message = (TextView) shell.inflate(R.layout.nusadesk_dialog_message);
        message.setText(options.hint.isEmpty() ? "Confirm" : options.hint);
        showButtonDialog(shell, session, "Yes", "No", sink,
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
        Shell shell = new Shell(activity, options.title);
        LinearLayout layout = verticalList(shell.themeContext);
        LayoutInflater inflater = LayoutInflater.from(shell.themeContext);
        List<CheckBox> boxes = new ArrayList<>();
        for (int i = 0; i < options.values.size(); i++) {
            CheckBox box = (CheckBox) inflater.inflate(
                    R.layout.nusadesk_dialog_checkbox_item, layout, false);
            box.setText(options.values.get(i));
            layout.addView(box);
            boxes.add(box);
        }
        shell.content.addView(layout);
        showButtonDialog(shell, session, "OK", "Cancel", sink,
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
        Shell shell = new Shell(activity, options.title);
        View row = shell.inflate(R.layout.nusadesk_dialog_counter);
        Button decrement = row.findViewById(R.id.nusadesk_dialog_counter_minus);
        TextView label = row.findViewById(R.id.nusadesk_dialog_counter_value);
        Button increment = row.findViewById(R.id.nusadesk_dialog_counter_plus);
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
        showButtonDialog(shell, session, "OK", "Cancel", sink,
                () -> positive(sink, session, label.getText().toString()), null);
    }

    /** Date picker; {@code date_format} or the upstream Date.toString text. */
    private static void showDate(CapabilityForegroundActivity activity, Options options,
                                 Session session, ResultSink sink) {
        Shell shell = new Shell(activity, options.title);
        DatePicker picker = (DatePicker) shell.inflate(R.layout.nusadesk_dialog_date);
        showButtonDialog(shell, session, "OK", "Cancel", sink,
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
        Shell shell = new Shell(activity, options.title);
        RadioGroup group = new RadioGroup(shell.themeContext);
        LayoutInflater inflater = LayoutInflater.from(shell.themeContext);
        for (int i = 0; i < options.values.size(); i++) {
            RadioButton button = (RadioButton) inflater.inflate(
                    R.layout.nusadesk_dialog_radio_item, group, false);
            button.setText(options.values.get(i));
            button.setId(i);
            group.addView(button);
        }
        shell.content.addView(group);
        showButtonDialog(shell, session, "OK", "Cancel", sink,
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
        Shell shell = new Shell(activity, options.title);
        LinearLayout list = verticalList(shell.themeContext);
        LayoutInflater inflater = LayoutInflater.from(shell.themeContext);
        for (int i = 0; i < options.values.size(); i++) {
            final int index = i;
            TextView item = (TextView) inflater.inflate(
                    R.layout.nusadesk_dialog_sheet_item, list, false);
            item.setText(options.values.get(i));
            item.setOnClickListener(v -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("code", 0L);
                fields.put("text", options.values.get(index));
                fields.put("index", (long) index);
                sink.success(fields);
                session.cleanup();
            });
            list.addView(item);
        }
        shell.content.addView(list);
        shell.hideButtons();
        present(session, shell, sink);
    }

    /** Dropdown spinner; always has a selection. */
    private static void showSpinner(CapabilityForegroundActivity activity,
                                    Options options, Session session, ResultSink sink) {
        Shell shell = new Shell(activity, options.title);
        Spinner spinner = (Spinner) shell.inflate(R.layout.nusadesk_dialog_spinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(shell.themeContext,
                R.layout.nusadesk_dialog_spinner_item, options.values);
        adapter.setDropDownViewResource(
                R.layout.nusadesk_dialog_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        showButtonDialog(shell, session, "OK", "Cancel", sink,
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
        Shell shell = new Shell(activity, options.title);
        TextView message = (TextView) shell.inflate(R.layout.nusadesk_dialog_message);
        message.setText(options.hint.isEmpty() ? "Listening for speech..." : options.hint);
        shell.positive.setVisibility(View.GONE);
        shell.negative.setText(R.string.nusadesk_dialog_cancel);
        shell.negative.setOnClickListener(v -> cancel(sink, session));
        shell.dialog.setCanceledOnTouchOutside(false);
        present(session, shell, sink);
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
        Shell shell = new Shell(activity, options.title);
        TimePicker picker = (TimePicker) shell.inflate(R.layout.nusadesk_dialog_time);
        showButtonDialog(shell, session, "OK", "Cancel", sink,
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
     * Wire a two-button shell and show it. The positive button settles
     * through {@code onPositive}; the negative button and every other
     * dismissal (back, outside touch, destroy cleanup) answer the upstream
     * cancelled shape — with the sink's once-only guarantee absorbing the
     * dismiss that follows a positive click.
     */
    private static void showButtonDialog(Shell shell, Session session,
                                         String positiveText, String negativeText,
                                         ResultSink sink, Positive onPositive) {
        showButtonDialog(shell, session, positiveText, negativeText, sink,
                onPositive, null);
    }

    private static void showButtonDialog(Shell shell, Session session,
                                         String positiveText, String negativeText,
                                         ResultSink sink, Positive onPositive,
                                         Runnable onNegative) {
        shell.positive.setText(positiveText);
        shell.positive.setOnClickListener(v -> onPositive.onPositive());
        shell.negative.setText(negativeText);
        shell.negative.setOnClickListener(v -> {
            if (onNegative != null) {
                onNegative.run();
            } else {
                cancel(sink, session);
            }
        });
        present(session, shell, sink);
    }

    private static void present(Session session, Shell shell, ResultSink sink) {
        session.dialog = shell.dialog;
        shell.dialog.setOnDismissListener(d -> {
            session.cleanup();
            sink.success(cancelledFields());
        });
        try {
            sizeWindow(shell);
            shell.dialog.show();
        } catch (RuntimeException e) {
            Log.w(TAG, "dialog show failed", e);
            sink.error("dialog-unavailable");
            session.cleanup();
        }
    }

    /**
     * Give the floating window the app dialog's size: nearly the screen width
     * on a phone, capped on tablets. The height stays {@code WRAP_CONTENT} so
     * every measure pass recomputes it — a fixed height measured before
     * {@code show()} goes stale the moment the window is resized (e.g. the
     * IME under {@code adjustResize}), and the shorter window then squeezes
     * the action row. The shell's weighted content slot yields space instead,
     * so the buttons keep their touch-target height whenever the dialog is
     * shorter than its content.
     */
    private static void sizeWindow(Shell shell) {
        Window window = shell.dialog.getWindow();
        if (window == null) {
            return;
        }
        DisplayMetrics metrics = shell.themeContext.getResources().getDisplayMetrics();
        int margin = shell.themeContext.getResources()
                .getDimensionPixelSize(R.dimen.nusadesk_dialog_screen_margin);
        int maxWidth = shell.themeContext.getResources()
                .getDimensionPixelSize(R.dimen.nusadesk_dialog_max_width);
        int width = Math.min(metrics.widthPixels - 2 * margin, maxWidth);
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.CENTER);
    }

    private static void cancel(ResultSink sink, Session session) {
        sink.success(cancelledFields());
        session.cleanup();
    }

    private static LinearLayout verticalList(Context context) {
        LinearLayout layout = new LinearLayout(context);
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
     * The shared app-styled dialog frame: a plain {@link Dialog} themed by
     * {@code NusaDeskDialogTheme} carrying {@code nusadesk_dialog_shell} — a
     * rounded elevated surface, an optional title, a scrolling content slot,
     * a divider, and the action row. The themed context matters: the host
     * activity is {@code Theme.Translucent.NoTitleBar}, so widget bodies and
     * picker internals must inflate from {@link #themeContext}, never the
     * activity, or they pick up the platform theme's look.
     */
    private static final class Shell {
        final Dialog dialog;
        final Context themeContext;
        final FrameLayout content;
        final View divider;
        final LinearLayout buttons;
        final Button positive;
        final Button negative;

        Shell(Activity activity, String title) {
            dialog = new Dialog(activity, R.style.NusaDeskDialogTheme);
            themeContext = dialog.getContext();
            dialog.setContentView(R.layout.nusadesk_dialog_shell);
            TextView titleView = dialog.findViewById(R.id.nusadesk_dialog_title);
            if (title == null || title.isEmpty()) {
                titleView.setVisibility(View.GONE);
            } else {
                titleView.setText(title);
            }
            content = dialog.findViewById(R.id.nusadesk_dialog_content);
            divider = dialog.findViewById(R.id.nusadesk_dialog_divider);
            buttons = dialog.findViewById(R.id.nusadesk_dialog_buttons);
            positive = dialog.findViewById(R.id.nusadesk_dialog_positive);
            negative = dialog.findViewById(R.id.nusadesk_dialog_negative);
            dialog.setCanceledOnTouchOutside(true);
        }

        /** Inflate a widget body into the content slot; returns it for binding. */
        View inflate(int layoutRes) {
            View view = LayoutInflater.from(themeContext)
                    .inflate(layoutRes, content, false);
            content.addView(view);
            return view;
        }

        /** Sheet-style: no action row — the option rows settle the dialog. */
        void hideButtons() {
            divider.setVisibility(View.GONE);
            buttons.setVisibility(View.GONE);
        }
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
