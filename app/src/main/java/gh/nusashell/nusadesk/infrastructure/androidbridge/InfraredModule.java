package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infrared capability domain behind two bridge methods, over the platform's
 * {@link ConsumerIrManager}.
 *
 * <p>{@code infrared.frequencies} (no params) reports the emitter's carrier
 * ranges as one pre-encoded {@code ranges_json} array of
 * {@code {"min","max"}} objects. A device without an IR emitter (the S10e is
 * one) or a missing service answers the typed
 * {@code infrared-unavailable:this device has no IR emitter} — a deliberate,
 * documented absence rather than upstream's empty list, so the guest can
 * distinguish "no hardware" from "no ranges".</p>
 *
 * <p>{@code infrared.transmit} takes {@code frequency} (Hz, required) and
 * {@code pattern} (a comma-separated list of on/off microsecond periods,
 * required, bounded to {@link #MAX_PATTERN_PERIODS} entries and
 * {@link #MAX_PATTERN_TOTAL_US} total like upstream's two-second limit). On
 * success it reports {@code transmitted}, {@code frequency}, and
 * {@code periods}; a platform refusal is {@code infrared-transmit-failed} and
 * a missing emitter is the same typed {@code infrared-unavailable} as above.
 * No platform exception message crosses the wire.</p>
 */
public final class InfraredModule implements CapabilityModule {
    private static final String TAG = "InfraredModule";

    private static final String METHOD_FREQUENCIES = "infrared.frequencies";
    private static final String METHOD_TRANSMIT = "infrared.transmit";

    private static final String ERROR_NO_EMITTER =
            "infrared-unavailable:this device has no IR emitter";

    private static final long FREQUENCY_MIN_HZ = 1L;
    private static final long FREQUENCY_MAX_HZ = 10_000_000L;
    private static final int MAX_PATTERN_PERIODS = 256;
    private static final int MAX_PATTERN_ENTRY_CHARS = 12;
    private static final long PERIOD_MIN_US = 1L;
    private static final long PERIOD_MAX_US = 1_000_000L;
    /** Upstream's documented pattern ceiling: on/off periods under two seconds. */
    private static final long MAX_PATTERN_TOTAL_US = 2_000_000L;

    private final Context context;

    public InfraredModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_FREQUENCIES, METHOD_TRANSMIT);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_TRANSMIT);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_FREQUENCIES:
                return frequencies(request);
            case METHOD_TRANSMIT:
                return transmit(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /** {@code infrared.frequencies} — the emitter's carrier ranges, or the typed absence. */
    private AndroidCapabilityProtocol.Response frequencies(
            AndroidCapabilityProtocol.Request request) {
        ConsumerIrManager infrared = emitter();
        if (infrared == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), ERROR_NO_EMITTER);
        }
        ConsumerIrManager.CarrierFrequencyRange[] ranges;
        try {
            ranges = infrared.getCarrierFrequencies();
        } catch (RuntimeException e) {
            Log.w(TAG, "infrared.frequencies failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "infrared-unavailable");
        }
        if (ranges == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "infrared-unavailable");
        }
        StringBuilder json = new StringBuilder(ranges.length * 32);
        json.append('[');
        for (int i = 0; i < ranges.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"min\":").append(ranges[i].getMinFrequency())
                    .append(",\"max\":").append(ranges[i].getMaxFrequency())
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ranges_json", json.toString());
        fields.put("count", (long) ranges.length);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code infrared.transmit} — params {@code frequency} (Hz, required
     * 1..10000000) and {@code pattern} (required comma-separated microsecond
     * on/off periods). Params validate first (fail closed), the emitter check
     * is the typed absence, and a refused transmit is
     * {@code infrared-transmit-failed}.
     */
    private AndroidCapabilityProtocol.Response transmit(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("frequency", "pattern"));
        if (!params.has("frequency")) {
            throw new CapabilityParams.Invalid("missing integer parameter: frequency");
        }
        long frequency = params.optionalLong(
                "frequency", FREQUENCY_MIN_HZ, FREQUENCY_MAX_HZ, 0L);
        List<String> patternText = params.optionalStringList(
                "pattern", MAX_PATTERN_PERIODS, MAX_PATTERN_ENTRY_CHARS);
        if (patternText.isEmpty()) {
            throw new CapabilityParams.Invalid("missing list parameter: pattern");
        }
        int[] pattern = new int[patternText.size()];
        long total = 0L;
        for (int i = 0; i < patternText.size(); i++) {
            long period;
            try {
                period = Long.parseLong(patternText.get(i));
            } catch (NumberFormatException e) {
                throw new CapabilityParams.Invalid(
                        "pattern entry is not an integer: " + patternText.get(i));
            }
            if (period < PERIOD_MIN_US || period > PERIOD_MAX_US) {
                throw new CapabilityParams.Invalid("pattern entry out of range: pattern");
            }
            pattern[i] = (int) period;
            total += period;
        }
        if (total > MAX_PATTERN_TOTAL_US) {
            throw new CapabilityParams.Invalid("pattern exceeds two seconds: pattern");
        }

        ConsumerIrManager infrared = emitter();
        if (infrared == null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), ERROR_NO_EMITTER);
        }
        try {
            infrared.transmit((int) frequency, pattern);
        } catch (SecurityException e) {
            Log.w(TAG, "infrared.transmit refused", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "infrared-permission-denied:TRANSMIT_IR");
        } catch (RuntimeException e) {
            Log.w(TAG, "infrared.transmit failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "infrared-transmit-failed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("transmitted", true);
        fields.put("frequency", frequency);
        fields.put("periods", (long) pattern.length);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** The IR manager when it exists and reports an emitter; {@code null} otherwise. */
    private ConsumerIrManager emitter() {
        ConsumerIrManager infrared;
        try {
            infrared = (ConsumerIrManager)
                    context.getSystemService(Context.CONSUMER_IR_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
        if (infrared == null) {
            return null;
        }
        try {
            return infrared.hasIrEmitter() ? infrared : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
