package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;

import java.util.List;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.DialogForegroundOperation;

/**
 * Dialog surface of the capability bridge (wave 2, W2b).
 *
 * <p>{@code dialog.show} mirrors the upstream {@code termux-dialog}
 * widgets ({@code text} default, {@code confirm}, {@code checkbox},
 * {@code counter}, {@code date}, {@code radio}, {@code sheet},
 * {@code spinner}, {@code speech}, {@code time}) inside the foreground
 * host's translucent activity. Params: {@code widget} plus {@code title},
 * {@code hint}, {@code values} (upstream comma list, {@code \,} escapes a
 * comma), {@code password}, {@code multiline}, {@code numeric}, and
 * {@code date_format} (a {@link java.text.SimpleDateFormat} pattern).</p>
 *
 * <p>Result fields mirror the upstream JSON shape, flattened to the bridge
 * contract: {@code code} ({@code -1} = positive/OK, {@code -2} =
 * cancel/dismiss — {@code DialogInterface.BUTTON_POSITIVE} /
 * {@code BUTTON_NEGATIVE}; {@code 0} for the sheet and speech widgets
 * which set no button code upstream), {@code text} (always present, empty
 * on cancel), {@code index} (single-pick widgets, only when a value was
 * chosen), {@code values_json} (checkbox picks, a pre-encoded
 * {@code [{"index","text"}]} array), and {@code error} (widget-level
 * failures such as a speech error name). A cancelled dialog is a normal
 * success response with {@code code=-2}, so the guest exit status stays 0
 * like upstream.</p>
 *
 * <p>The module validates the widget name and parameter bounds and rejects
 * unknown keys; everything interactive happens in
 * {@link DialogForegroundOperation}. No platform exception message ever
 * reaches the guest.</p>
 */
public final class DialogModule implements CapabilityModule {
    public static final String METHOD_DIALOG_SHOW = "dialog.show";

    /** Bounded wait for a user answer; matches the other interactive ops. */
    private static final long FOREGROUND_TIMEOUT_MILLIS = 120_000L;

    static final Set<String> WIDGETS = Set.of("checkbox", "confirm", "counter",
            "date", "radio", "sheet", "speech", "spinner", "text", "time");

    private static final int TITLE_MAX_CHARS = 512;
    private static final int HINT_MAX_CHARS = 8192;
    private static final int VALUES_MAX_CHARS = 8192;
    private static final int DATE_FORMAT_MAX_CHARS = 64;
    private static final int WIDGET_MAX_CHARS = 16;

    private final CapabilityForegroundHost foregroundHost;

    public DialogModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.foregroundHost = new CapabilityForegroundHost(context.getApplicationContext());
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_DIALOG_SHOW);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_DIALOG_SHOW);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (!METHOD_DIALOG_SHOW.equals(request.getMethod())) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "unsupported-method");
        }
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("widget", "title", "hint", "values",
                "password", "multiline", "numeric", "date_format"));
        String widget = params.optionalString("widget", WIDGET_MAX_CHARS, "text");
        params.optionalString("title", TITLE_MAX_CHARS, "");
        params.optionalString("hint", HINT_MAX_CHARS, "");
        params.optionalString("values", VALUES_MAX_CHARS, "");
        params.optionalBoolean("password", false);
        params.optionalBoolean("multiline", false);
        params.optionalBoolean("numeric", false);
        params.optionalString("date_format", DATE_FORMAT_MAX_CHARS, "");
        if (!WIDGETS.contains(widget)) {
            throw new CapabilityParams.Invalid("unsupported parameter value: widget");
        }
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                DialogForegroundOperation.KIND, request.getParams(),
                FOREGROUND_TIMEOUT_MILLIS);
        // The host answers with a blank request id; attach the real one.
        return result.isOk()
                ? AndroidCapabilityProtocol.Response.success(request.getId(), result.getFields())
                : AndroidCapabilityProtocol.Response.error(request.getId(), result.getError());
    }

    @Override
    public void close() {
        foregroundHost.close();
    }
}
