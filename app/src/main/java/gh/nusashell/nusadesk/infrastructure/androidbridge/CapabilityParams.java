package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, typed reads of one request's {@code params} object.
 *
 * <p>Every module starts with {@link #rejectUnknown(Set)} so a misspelled or
 * unsupported argument fails closed instead of being silently ignored, then
 * reads its declared keys through the typed accessors. A violation throws
 * {@link Invalid}, which the framework maps to the typed
 * {@code invalid-argument} error; modules never need to validate shapes by
 * hand and never guess a default for a malformed value.</p>
 *
 * <p>The class is Android-free on purpose: it is the whole parameter contract
 * and stays unit-testable without a device.</p>
 */
final class CapabilityParams {
    /** Thrown when a params object violates the declared schema. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    private final Map<String, Object> params;

    private CapabilityParams(Map<String, Object> params) {
        this.params = params;
    }

    static CapabilityParams of(AndroidCapabilityProtocol.Request request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return new CapabilityParams(request.getParams());
    }

    static CapabilityParams of(Map<String, Object> params) {
        return new CapabilityParams(params == null ? Map.of() : params);
    }

    /** Fail closed on any key the method does not declare. */
    void rejectUnknown(Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw new Invalid("unsupported parameter: " + key);
            }
        }
    }

    boolean has(String key) {
        return params.containsKey(key);
    }

    String requireString(String key, int maxChars) {
        Object value = params.get(key);
        if (!(value instanceof String)) {
            throw new Invalid("missing string parameter: " + key);
        }
        return bounded((String) value, key, maxChars);
    }

    String optionalString(String key, int maxChars, String fallback) {
        if (!params.containsKey(key)) {
            return fallback;
        }
        Object value = params.get(key);
        if (!(value instanceof String)) {
            throw new Invalid("parameter is not a string: " + key);
        }
        return bounded((String) value, key, maxChars);
    }

    long optionalLong(String key, long min, long max, long fallback) {
        if (!params.containsKey(key)) {
            return fallback;
        }
        Object value = params.get(key);
        if (!(value instanceof Long)) {
            throw new Invalid("parameter is not an integer: " + key);
        }
        long number = (Long) value;
        if (number < min || number > max) {
            throw new Invalid("parameter out of range: " + key);
        }
        return number;
    }

    boolean optionalBoolean(String key, boolean fallback) {
        if (!params.containsKey(key)) {
            return fallback;
        }
        Object value = params.get(key);
        if (!(value instanceof Boolean)) {
            throw new Invalid("parameter is not a boolean: " + key);
        }
        return (Boolean) value;
    }

    /**
     * One comma-separated list value, trimmed, with empty entries dropped.
     * Each entry is bounded, and the list length is bounded so a request can
     * never fan out into an unbounded platform call.
     */
    List<String> optionalStringList(String key, int maxItems, int maxCharsEach) {
        String raw = optionalString(key, maxItems * (maxCharsEach + 1), "");
        List<String> values = new ArrayList<>();
        if (raw.trim().isEmpty()) {
            return values;
        }
        for (String part : raw.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.length() > maxCharsEach) {
                throw new Invalid("list entry too long: " + key);
            }
            values.add(entry);
            if (values.size() > maxItems) {
                throw new Invalid("too many list entries: " + key);
            }
        }
        return values;
    }

    /** The declared keys present in this params object, for diagnostics. */
    Set<String> keys() {
        return new LinkedHashSet<>(params.keySet());
    }

    private static String bounded(String value, String key, int maxChars) {
        if (value.length() > maxChars) {
            throw new Invalid("parameter too long: " + key);
        }
        return value;
    }
}
