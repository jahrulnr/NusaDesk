package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small, strict, line-delimited JSON contract for the host capability bridge.
 *
 * <p>This is deliberately not a general JSON implementation. Requests are
 * flat objects with string, integer, and boolean values; responses may also
 * carry finite double values (sensor axes). That keeps the guest contract
 * dependency-free (busybox, Python, or a small shell client can use it),
 * bounds the parser, and makes unsupported shapes fail closed.</p>
 */
public final class AndroidCapabilityProtocol {
    public static final long VERSION = 1L;
    public static final int MAX_FRAME_BYTES = 16 * 1024;

    private AndroidCapabilityProtocol() {
    }

    /** One authenticated guest request. */
    public static final class Request {
        /** Max keys in one {@code params} object. */
        public static final int MAX_PARAM_KEYS = 8;
        /** Max length of one {@code params} key. */
        public static final int MAX_PARAM_KEY_CHARS = 32;
        /** Max length of one string {@code params} value. */
        public static final int MAX_PARAM_STRING_CHARS = 256;

        private final long version;
        private final String id;
        private final String token;
        private final String method;
        private final Map<String, Object> params;

        private Request(long version, String id, String token, String method,
                        Map<String, Object> params) {
            this.version = version;
            this.id = id;
            this.token = token;
            this.method = method;
            this.params = params == null ? new LinkedHashMap<>() : params;
        }

        public long getVersion() {
            return version;
        }

        public String getId() {
            return id;
        }

        public String getToken() {
            return token;
        }

        public String getMethod() {
            return method;
        }

        /**
         * Bounded, flat per-method parameters; empty when the frame carried
         * none. Only methods that declare parameters may accept a non-empty
         * map — the handler rejects the rest with a typed error.
         */
        public Map<String, Object> getParams() {
            return new LinkedHashMap<>(params);
        }
    }

    /** One response; fields are flat primitive values by contract. */
    public static final class Response {
        private final String id;
        private final boolean ok;
        private final String error;
        private final Map<String, Object> fields;

        private Response(String id, boolean ok, String error, Map<String, Object> fields) {
            this.id = id == null ? "" : id;
            this.ok = ok;
            this.error = error;
            this.fields = new LinkedHashMap<>();
            if (fields != null) {
                this.fields.putAll(fields);
            }
        }

        public static Response success(String id, Map<String, Object> fields) {
            return new Response(id, true, null, fields);
        }

        public static Response error(String id, String error) {
            if (error == null || error.trim().isEmpty()) {
                throw new IllegalArgumentException("error must not be blank");
            }
            return new Response(id, false, error, null);
        }

        public String getId() {
            return id;
        }

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> getFields() {
            return new LinkedHashMap<>(fields);
        }
    }

    /** Decode a single request frame; malformed or unsupported frames return {@code null}. */
    public static Request decodeRequest(String raw) {
        if (raw == null || raw.length() > MAX_FRAME_BYTES) {
            return null;
        }
        try {
            Map<String, Object> fields = parseObject(raw);
            if (fields == null || fields.size() < 4 || fields.size() > 5
                    || !fields.containsKey("v")
                    || !fields.containsKey("id")
                    || !fields.containsKey("token")
                    || !fields.containsKey("method")) {
                return null;
            }
            Object version = fields.get("v");
            Object id = fields.get("id");
            Object token = fields.get("token");
            Object method = fields.get("method");
            if (!(version instanceof Long)
                    || !(id instanceof String)
                    || !(token instanceof String)
                    || !(method instanceof String)
                    || ((Long) version) != VERSION) {
                return null;
            }
            String idText = (String) id;
            String tokenText = (String) token;
            String methodText = (String) method;
            if (!boundedText(idText, 64)
                    || !boundedText(tokenText, 256)
                    || !boundedText(methodText, 64)) {
                return null;
            }
            if (!fields.containsKey("params")) {
                if (fields.size() != 4) {
                    // An unknown top-level field fails closed instead of being
                    // ignored: only `params` may extend the envelope.
                    return null;
                }
                return new Request(VERSION, idText, tokenText, methodText, null);
            }
            Map<String, Object> params = boundedParams(fields.get("params"));
            if (params == null) {
                return null;
            }
            return new Request(VERSION, idText, tokenText, methodText, params);
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * Validate the optional {@code params} object: a flat, small map of string,
     * integer, or boolean values with safe short keys. Anything else (nested
     * objects or arrays, oversized keys or strings, control characters) is
     * rejected so a request can never smuggle an unbounded structure.
     */
    private static Map<String, Object> boundedParams(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> candidate = (Map<?, ?>) raw;
        if (candidate.size() > Request.MAX_PARAM_KEYS) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : candidate.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                return null;
            }
            String key = (String) entry.getKey();
            if (!isSafeFieldName(key) || key.length() > Request.MAX_PARAM_KEY_CHARS) {
                return null;
            }
            Object value = entry.getValue();
            if (value instanceof String) {
                String text = (String) value;
                if (text.length() > Request.MAX_PARAM_STRING_CHARS
                        || containsControlCharacter(text)) {
                    return null;
                }
            } else if (!(value instanceof Long) && !(value instanceof Boolean)) {
                return null;
            }
            params.put(key, value);
        }
        return params;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Encode a response as one newline-free JSON frame. */
    public static String encodeResponse(Response response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", VERSION);
        fields.put("id", response.getId());
        fields.put("ok", response.isOk());
        if (response.isOk()) {
            for (Map.Entry<String, Object> entry : response.fields.entrySet()) {
                if (!isSafeFieldName(entry.getKey())) {
                    throw new IllegalArgumentException("invalid response field: " + entry.getKey());
                }
                if (fields.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("response field collides with envelope: "
                            + entry.getKey());
                }
                fields.put(entry.getKey(), entry.getValue());
            }
        } else {
            fields.put("error", response.getError());
        }
        String encoded = encodeObject(fields);
        if (encoded.length() > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("response exceeds frame limit");
        }
        return encoded;
    }

    private static boolean boundedText(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max;
    }

    private static boolean isSafeFieldName(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == '_' || c == '-' || c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }

    private static String encodeObject(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(entry.getKey())).append(':').append(encodeValue(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String encodeValue(Object value) {
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Long || value instanceof Integer) {
            return String.valueOf(value);
        }
        if (value instanceof Double) {
            double number = (Double) value;
            // NaN/Infinity are not JSON tokens; reject them at the codec so a
            // platform quirk can never reach the guest as an invalid frame.
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("non-finite response value");
            }
            return String.valueOf(number);
        }
        if (value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("unsupported response value type");
    }

    private static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private static Map<String, Object> parseObject(String json) throws ParseException {
        int[] end = new int[1];
        Map<String, Object> result = parseObjectAt(json, 0, end);
        if (result == null) {
            return null;
        }
        return skipSpace(json, end[0]) == json.length() ? result : null;
    }

    /**
     * Parse one object beginning at {@code start}; {@code end[0]} receives the
     * index just past its closing brace. Nested objects are parsed so the
     * bounded {@code params} object is expressible, and the caller bounds their
     * shape strictly.
     */
    private static Map<String, Object> parseObjectAt(String json, int start, int[] end)
            throws ParseException {
        int length = json.length();
        int index = skipSpace(json, start);
        if (index >= length || json.charAt(index) != '{') {
            return null;
        }
        index++;
        Map<String, Object> result = new LinkedHashMap<>();
        index = skipSpace(json, index);
        if (index < length && json.charAt(index) == '}') {
            end[0] = index + 1;
            return result;
        }
        while (index < length) {
            index = skipSpace(json, index);
            if (index >= length || json.charAt(index) != '"') {
                throw new ParseException("expected key");
            }
            int[] valueEnd = new int[1];
            String key = parseString(json, index, valueEnd);
            index = skipSpace(json, valueEnd[0]);
            if (index >= length || json.charAt(index) != ':') {
                throw new ParseException("expected colon");
            }
            index = skipSpace(json, index + 1);
            if (index >= length) {
                throw new ParseException("missing value");
            }
            Object value;
            char first = json.charAt(index);
            if (first == '"') {
                value = parseString(json, index, valueEnd);
                index = valueEnd[0];
            } else if (first == '{') {
                value = parseObjectAt(json, index, valueEnd);
                if (value == null) {
                    throw new ParseException("bad nested object");
                }
                index = valueEnd[0];
            } else if (first == 't' || first == 'f') {
                boolean trueValue = json.startsWith("true", index);
                String literal = trueValue ? "true" : "false";
                if (!json.startsWith(literal, index)) {
                    throw new ParseException("bad boolean");
                }
                value = trueValue;
                index += literal.length();
            } else if (first == '-' || first >= '0' && first <= '9') {
                value = parseNumber(json, index, valueEnd);
                index = valueEnd[0];
            } else {
                throw new ParseException("unsupported value");
            }
            if (result.put(key, value) != null) {
                throw new ParseException("duplicate key");
            }
            index = skipSpace(json, index);
            if (index < length && json.charAt(index) == ',') {
                index++;
                continue;
            }
            if (index < length && json.charAt(index) == '}') {
                end[0] = index + 1;
                return result;
            }
            throw new ParseException("expected comma or end");
        }
        throw new ParseException("unterminated object");
    }

    private static int skipSpace(String value, int index) {
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                index++;
            } else {
                break;
            }
        }
        return index;
    }

    private static String parseString(String value, int start, int[] end) throws ParseException {
        int index = start + 1;
        StringBuilder result = new StringBuilder();
        while (index < value.length()) {
            char c = value.charAt(index++);
            if (c == '"') {
                end[0] = index;
                return result.toString();
            }
            if (c == '\\') {
                if (index >= value.length()) {
                    throw new ParseException("bad escape");
                }
                char escaped = value.charAt(index++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (index + 4 > value.length()) {
                            throw new ParseException("short unicode escape");
                        }
                        int codePoint = 0;
                        for (int i = 0; i < 4; i++) {
                            codePoint = (codePoint << 4) | hex(value.charAt(index++));
                        }
                        result.append((char) codePoint);
                        break;
                    default:
                        throw new ParseException("bad escape");
                }
            } else if (c < 0x20) {
                throw new ParseException("control character");
            } else {
                result.append(c);
            }
        }
        throw new ParseException("unterminated string");
    }

    private static Long parseNumber(String value, int start, int[] end) throws ParseException {
        int index = start;
        if (value.charAt(index) == '-') {
            index++;
        }
        int digits = index;
        while (index < value.length()) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') {
                break;
            }
            index++;
        }
        if (digits == index) {
            throw new ParseException("bad number");
        }
        try {
            Long parsed = Long.valueOf(value.substring(start, index));
            end[0] = index;
            return parsed;
        } catch (NumberFormatException e) {
            throw new ParseException("number overflow");
        }
    }

    private static int hex(char value) throws ParseException {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        throw new ParseException("bad hex");
    }

    private static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("cannot encode null string");
        }
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
            }
        }
        return result.append('"').toString();
    }
}
