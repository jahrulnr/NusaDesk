package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.util.Log;

import java.util.List;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.FingerprintForegroundOperation;

/**
 * Fingerprint surface of the capability bridge (wave 2, last Termux:API
 * parity command).
 *
 * <p>{@code fingerprint.authenticate} shows the platform
 * {@link android.hardware.biometrics.BiometricPrompt} inside the
 * foreground host's translucent activity and reports one authentication
 * round. Params mirror the upstream {@code termux-fingerprint} flags:
 * {@code title}, {@code description}, {@code subtitle}, {@code cancel}
 * (the negative button text), plus {@code timeout_ms} — a bounded
 * 1 s-5 min version of the upstream fixed sensor timeout, default
 * 60 s.</p>
 *
 * <p>Result fields mirror the upstream JSON shape, flattened to the
 * bridge contract: {@code auth_result} (boolean; upstream's
 * {@code AUTH_RESULT_*} string collapses to true only on success),
 * {@code failed_attempts} (rejected swipes before the terminal answer),
 * and {@code errors_json} (a pre-encoded array of upstream-style
 * {@code ERROR_*} names). A cancel or dismiss is a normal result with
 * {@code auth_result:false} and a cancellation code — never a claimed
 * success.</p>
 *
 * <p>Hardware/enrollment is validated before the foreground launch, like
 * upstream: no fingerprint sensor (or a busy/unavailable service)
 * answers typed {@code fingerprint-unavailable}, while a sensor with no
 * enrolled fingerprints answers the result body with
 * {@code ERROR_NO_ENROLLED_FINGERPRINTS}, matching upstream's errors
 * array. {@code USE_BIOMETRIC} is a normal manifest permission, so no
 * runtime grant check applies. No platform exception message ever
 * reaches the guest.</p>
 */
public final class FingerprintModule implements CapabilityModule {
    public static final String METHOD_AUTHENTICATE = "fingerprint.authenticate";

    private static final String TAG = "FingerprintModule";

    /**
     * Extra room past the prompt's own {@code timeout_ms} so the
     * operation's ERROR_TIMEOUT result lands before the host's outer
     * bound fires.
     */
    private static final long HOST_WAIT_MARGIN_MILLIS = 30_000L;

    private static final int TITLE_MAX_CHARS = 512;
    private static final int DESCRIPTION_MAX_CHARS = 1024;
    private static final int SUBTITLE_MAX_CHARS = 512;
    private static final int CANCEL_MAX_CHARS = 128;

    private final Context context;
    private final CapabilityForegroundHost foregroundHost;

    public FingerprintModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.foregroundHost = new CapabilityForegroundHost(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_AUTHENTICATE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_AUTHENTICATE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (!METHOD_AUTHENTICATE.equals(request.getMethod())) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "unsupported-method");
        }
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("title", "description", "subtitle", "cancel",
                "timeout_ms"));
        params.optionalString("title", TITLE_MAX_CHARS, "");
        params.optionalString("description", DESCRIPTION_MAX_CHARS, "");
        params.optionalString("subtitle", SUBTITLE_MAX_CHARS, "");
        params.optionalString("cancel", CANCEL_MAX_CHARS, "");
        long timeoutMs = params.optionalLong("timeout_ms",
                FingerprintForegroundOperation.MIN_TIMEOUT_MILLIS,
                FingerprintForegroundOperation.MAX_TIMEOUT_MILLIS,
                FingerprintForegroundOperation.DEFAULT_TIMEOUT_MILLIS);

        int availability = availability();
        if (availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            // Upstream reports this in the result body, not as a refusal.
            return AndroidCapabilityProtocol.Response.success(request.getId(),
                    FingerprintForegroundOperation.resultFields(false, 0,
                            List.of(FingerprintForegroundOperation.ERROR_NO_ENROLLED)));
        }
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "fingerprint-unavailable");
        }
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                FingerprintForegroundOperation.KIND, request.getParams(),
                timeoutMs + HOST_WAIT_MARGIN_MILLIS);
        // The host answers with a blank request id; attach the real one.
        return result.isOk()
                ? AndroidCapabilityProtocol.Response.success(request.getId(), result.getFields())
                : AndroidCapabilityProtocol.Response.error(request.getId(), result.getError());
    }

    @Override
    public void close() {
        foregroundHost.close();
    }

    /**
     * Hardware and enrollment state for the prompt. The fingerprint
     * feature gate keeps strong-face-only devices honest — upstream only
     * ever asked about fingerprints — and the manager answer then
     * separates "nothing enrolled" from a missing or busy sensor.
     */
    @SuppressWarnings("deprecation") // no-arg canAuthenticate is the API 29 form
    private int availability() {
        try {
            PackageManager packages = context.getPackageManager();
            if (packages == null
                    || !packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
            }
            BiometricManager manager = context.getSystemService(BiometricManager.class);
            if (manager == null) {
                return BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return manager.canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG);
            }
            return manager.canAuthenticate();
        } catch (RuntimeException e) {
            Log.w(TAG, "biometric availability check failed", e);
            return BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
        }
    }
}
