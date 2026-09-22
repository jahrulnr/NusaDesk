package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.NfcForegroundOperation;

/**
 * NFC capability domain: {@code nfc.read} and {@code nfc.write} — the bridge
 * half of {@code termux-nfc}.
 *
 * <p>Reader mode needs a resumed activity, so both methods park a
 * {@link NfcForegroundOperation} through {@link CapabilityForegroundHost} and
 * block the connection thread for the guest-chosen wait
 * ({@code timeout_s}, bounded). A device without an adapter reports
 * {@code nfc-present=false} on the {@code probe} mode and the typed
 * {@code nfc-unavailable} on a real read/write; a present-but-disabled
 * adapter answers {@code nfc_enabled=false} like upstream's presence JSON.
 * Foreground-host failures are translated into the capability taxonomy
 * ({@code nfc-timeout} when the wait expired without a tag,
 * {@code nfc-busy} while another interactive operation holds the slot,
 * {@code nfc-cancelled} when the activity dies first).</p>
 */
public final class NfcModule implements CapabilityModule {
    private static final String TAG = "NfcModule";

    private static final String METHOD_NFC_READ = "nfc.read";
    private static final String METHOD_NFC_WRITE = "nfc.write";

    private static final int MODE_MAX_CHARS = 8;
    private static final int TEXT_MAX_CHARS = 4096;
    private static final long TIMEOUT_MIN_SECONDS = 5L;
    private static final long TIMEOUT_MAX_SECONDS = 300L;
    private static final long TIMEOUT_DEFAULT_SECONDS = 60L;

    private final Context context;
    private final CapabilityForegroundHost foregroundHost;

    public NfcModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.foregroundHost = new CapabilityForegroundHost(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_NFC_READ, METHOD_NFC_WRITE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_NFC_READ, METHOD_NFC_WRITE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_NFC_READ:
                return nfcRead(request);
            case METHOD_NFC_WRITE:
                return nfcWrite(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    public void close() {
        foregroundHost.close();
    }

    /**
     * {@code nfc.read} — params {@code mode} ({@code short}|{@code full},
     * or {@code probe} for the presence-only answer upstream gives a
     * mode-less invocation) and {@code timeout_s}. Success carries
     * {@code nfc_present}, {@code nfc_enabled}, and — on a real read —
     * {@code tag_json} in the upstream JSON shape.
     */
    private AndroidCapabilityProtocol.Response nfcRead(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("mode", "timeout_s"));
        String mode = params.optionalString("mode", MODE_MAX_CHARS, "short");
        if (!"short".equals(mode) && !"full".equals(mode)
                && !"probe".equals(mode)) {
            throw new CapabilityParams.Invalid("unsupported nfc mode: " + mode);
        }
        long timeoutSeconds = params.optionalLong("timeout_s",
                TIMEOUT_MIN_SECONDS, TIMEOUT_MAX_SECONDS, TIMEOUT_DEFAULT_SECONDS);
        NfcAdapter adapter = adapterOrNull();
        if (adapter == null) {
            if ("probe".equals(mode)) {
                return presence(request.getId(), false, null);
            }
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "nfc-unavailable");
        }
        if (!enabledOrFalse(adapter)) {
            return presence(request.getId(), true, Boolean.FALSE);
        }
        if ("probe".equals(mode)) {
            return presence(request.getId(), true, Boolean.TRUE);
        }
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", "read");
        opParams.put("mode", mode);
        return run(request, opParams, timeoutSeconds);
    }

    /**
     * {@code nfc.write} — params {@code text} (the NDEF text record body)
     * and {@code timeout_s}. Success carries {@code written=true} plus the
     * presence fields; an NDEF-incapable tag is {@code nfc-tag-not-ndef}.
     */
    private AndroidCapabilityProtocol.Response nfcWrite(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("text", "timeout_s"));
        String text = params.requireString("text", TEXT_MAX_CHARS);
        long timeoutSeconds = params.optionalLong("timeout_s",
                TIMEOUT_MIN_SECONDS, TIMEOUT_MAX_SECONDS, TIMEOUT_DEFAULT_SECONDS);
        NfcAdapter adapter = adapterOrNull();
        if (adapter == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "nfc-unavailable");
        }
        if (!enabledOrFalse(adapter)) {
            return presence(request.getId(), true, Boolean.FALSE);
        }
        Map<String, Object> opParams = new LinkedHashMap<>();
        opParams.put("op", "write");
        opParams.put("text", text);
        return run(request, opParams, timeoutSeconds);
    }

    /** Park the operation and translate host errors into nfc-* codes. */
    private AndroidCapabilityProtocol.Response run(
            AndroidCapabilityProtocol.Request request, Map<String, Object> opParams,
            long timeoutSeconds) {
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                NfcForegroundOperation.KIND, opParams, timeoutSeconds * 1000L);
        if (result.isOk()) {
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), result.getFields());
        }
        String error = result.getError();
        if (error == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "nfc-unavailable");
        }
        String code = error;
        String hint = "";
        int colon = error.indexOf(':');
        if (colon >= 0) {
            code = error.substring(0, colon);
            hint = error.substring(colon);
        }
        String mapped;
        switch (code) {
            case CapabilityForegroundHost.ERROR_TIMEOUT:
                mapped = "nfc-timeout:no tag presented before the wait expired";
                break;
            case CapabilityForegroundHost.ERROR_BUSY:
                mapped = "nfc-busy";
                break;
            case CapabilityForegroundHost.ERROR_CANCELLED:
                mapped = "nfc-cancelled";
                break;
            case CapabilityForegroundHost.ERROR_UNAVAILABLE:
            case "foreground-unknown":
                mapped = "nfc-unavailable";
                break;
            default:
                mapped = code + hint;
        }
        return AndroidCapabilityProtocol.Response.error(request.getId(), mapped);
    }

    /** Upstream's presence answer: {@code nfcPresent} always, {@code nfcActive} only when present. */
    private AndroidCapabilityProtocol.Response presence(String requestId,
            boolean present, Boolean enabled) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nfc_present", present);
        if (enabled != null) {
            fields.put("nfc_enabled", enabled.booleanValue());
        }
        return AndroidCapabilityProtocol.Response.success(requestId, fields);
    }

    private NfcAdapter adapterOrNull() {
        try {
            return NfcAdapter.getDefaultAdapter(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "nfc adapter lookup failed", e);
            return null;
        }
    }

    private static boolean enabledOrFalse(NfcAdapter adapter) {
        try {
            return adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
