package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidPermissionChecker;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CapabilityPermission;

/**
 * The {@code call} foreground operation behind {@code phone.call}.
 *
 * <p>Placing a call is {@code startActivity(ACTION_CALL, tel:<number>)},
 * which Android only honours from a foreground state — a background
 * {@code context.startActivity} is silently dropped on API 29+. The module
 * therefore parks this operation with {@link CapabilityForegroundHost} and
 * the visible {@link CapabilityForegroundActivity} performs the actual
 * launch; when the app cannot come to foreground the guest gets the host's
 * typed {@code foreground-required}, never a silent no-op.</p>
 *
 * <p>{@code number} is re-validated here defensively (the module already
 * bounded it), {@code CALL_PHONE} is re-checked at launch time, and the
 * upstream {@code tel:} URI rule applies: {@code #} is escaped to {@code %23}
 * so it survives URI parsing. A device without a dialer answers
 * {@code call-unavailable}; a mid-flight permission revocation answers
 * {@code call-permission-denied}.</p>
 */
public final class CallForegroundOperation implements ForegroundOperation {

    /** Catalog kind and the operation name {@code phone.call} runs. */
    public static final String KIND = "call";

    private static final int MAX_NUMBER_CHARS = 64;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        Object raw = params.get("number");
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()
                || ((String) raw).length() > MAX_NUMBER_CHARS) {
            sink.error("invalid-argument");
            return;
        }
        String number = ((String) raw).trim();
        AndroidPermissionChecker checker = new AndroidPermissionChecker(activity);
        CapabilityPermission permission = checker.check(
                android.Manifest.permission.CALL_PHONE);
        if (permission != CapabilityPermission.GRANTED) {
            sink.error(permission == CapabilityPermission.DENIED
                    ? "call-permission-denied" : "call-permission-required");
            return;
        }
        // Upstream Termux rule: '#' must be percent-escaped inside tel: URIs.
        Uri uri = Uri.parse("tel:" + number.replace("#", "%23"));
        Intent intent = new Intent(Intent.ACTION_CALL, uri);
        try {
            activity.startActivity(intent);
        } catch (SecurityException e) {
            sink.error("call-permission-denied");
            return;
        } catch (ActivityNotFoundException e) {
            sink.error("call-unavailable");
            return;
        } catch (RuntimeException e) {
            sink.error("call-unavailable");
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("called", Boolean.TRUE);
        fields.put("number", number);
        sink.success(fields);
    }
}
