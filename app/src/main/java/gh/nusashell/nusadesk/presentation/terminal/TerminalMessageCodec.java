package gh.nusashell.nusadesk.presentation.terminal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes and decodes the fixed terminal bridge protocol to compact JSON strings
 * sent over a {@code WebMessagePort}, with size and type validation at the boundary.
 *
 * <p>The page side decodes loosely (it is trusted, packaged content); the native
 * side decodes strictly here. Every message received from the page is validated:
 * the raw string must not exceed {@link #MAX_MESSAGE_BYTES}, must be a well-formed
 * object for this protocol, must carry a known {@code t} of the correct shape, and
 * must have correctly typed fields. Invalid messages are rejected by returning
 * {@code null} rather than throwing — the caller drops them silently. No message
 * content is logged.</p>
 *
 * <p>The wire form is intentionally minimal and hand-rolled (no {@code org.json},
 * which is unavailable in Android-free unit tests). It is not a general JSON parser:
 * it understands only the flat objects this protocol emits.</p>
 *
 * <h2>Wire shapes</h2>
 * <pre>
 *   {"t":"ready"}
 *   {"t":"input","d":"..."}
 *   {"t":"resize","c":80,"r":24}
 *   {"t":"write","d":"..."}
 *   {"t":"writeStderr","d":"..."}
 *   {"t":"setSize","c":80,"r":24}
 *   {"t":"fit"}
 *   {"t":"focus"}
 * </pre>
 *
 * <p>The {@code t} tag on the wire is a short lowercase name, not the enum name, to
 * keep the page shim small and the payload compact.</p>
 */
public final class TerminalMessageCodec {

    /** Hard cap on a single decoded message string. Larger frames are rejected. */
    public static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private TerminalMessageCodec() {
    }

    /** Encode a host -> page command to a JSON string for {@code postMessage}. */
    public static String encode(TerminalMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        switch (message.getType()) {
            case WRITE:
                return "{\"t\":\"write\",\"d\":" + quote(message.getData()) + "}";
            case WRITE_STDERR:
                return "{\"t\":\"writeStderr\",\"d\":" + quote(message.getData()) + "}";
            case SET_SIZE:
                return "{\"t\":\"setSize\",\"c\":" + message.getCols()
                        + ",\"r\":" + message.getRows() + "}";
            case FIT:
                return "{\"t\":\"fit\"}";
            case FOCUS:
                return "{\"t\":\"focus\"}";
            // Page-originated types are not encoded by the host; reject to catch misuse.
            case READY:
            case INPUT:
            case RESIZE:
            default:
                throw new IllegalArgumentException(
                        "encode is for host->page commands; not " + message.getType());
        }
    }

    /**
     * Decode a page -> host event. Returns {@code null} if the input is null, too
     * large, malformed, or does not match a known event shape.
     */
    public static TerminalMessage decode(String raw) {
        if (raw == null) {
            return null;
        }
        // UTF-8 byte length is the meaningful cap; char count is a cheap upper bound
        // check before any parsing. A char is 1-2 bytes in UTF-8 for BMP text, so this
        // never under-reports; for the rare astral-char-only payload it over-reports
        // and rejects early, which is safe.
        if (raw.length() > MAX_MESSAGE_BYTES) {
            return null;
        }
        Map<String, Object> fields;
        try {
            fields = parseObject(raw);
        } catch (ParseException e) {
            return null;
        }
        if (fields == null) {
            return null;
        }
        Object tag = fields.get("t");
        if (!(tag instanceof String)) {
            return null;
        }
        switch ((String) tag) {
            case "ready":
                return TerminalMessage.signal(TerminalMessage.Type.READY);
            case "input":
                return textOr(fields, TerminalMessage.Type.INPUT);
            case "resize":
                return sizeOr(fields, TerminalMessage.Type.RESIZE);
            // Host-originated tags are never accepted from the page.
            default:
                return null;
        }
    }

    private static TerminalMessage textOr(Map<String, Object> fields, TerminalMessage.Type type) {
        Object d = fields.get("d");
        if (!(d instanceof String)) {
            return null;
        }
        return TerminalMessage.text(type, (String) d);
    }

    private static TerminalMessage sizeOr(Map<String, Object> fields, TerminalMessage.Type type) {
        Object c = fields.get("c");
        Object r = fields.get("r");
        if (!(c instanceof Long) || !(r instanceof Long)) {
            return null;
        }
        int cols = ((Long) c).intValue();
        int rows = ((Long) r).intValue();
        if (cols < 1 || cols > 1024 || rows < 1 || rows > 1024) {
            return null;
        }
        return TerminalMessage.size(type, cols, rows);
    }

    // ---- minimal JSON for this protocol only ----

    private static final class ParseException extends Exception {
        ParseException(String msg) {
            super(msg);
        }
    }

    /**
     * Parse a flat JSON object whose values are strings or integers into a map.
     * Returns null for an empty/non-object input. Throws ParseException on
     * structural errors so the caller can map them to "reject".
     */
    private static Map<String, Object> parseObject(String json) throws ParseException {
        int n = json.length();
        int i = skipSpace(json, 0);
        if (i >= n || json.charAt(i) != '{') {
            return null;
        }
        i++;
        Map<String, Object> out = new LinkedHashMap<>();
        i = skipSpace(json, i);
        if (i < n && json.charAt(i) == '}') {
            return out;
        }
        while (i < n) {
            i = skipSpace(json, i);
            if (i >= n || json.charAt(i) != '"') {
                throw new ParseException("expected key string");
            }
            int[] keyEnd = new int[1];
            String key = parseString(json, i, keyEnd);
            i = keyEnd[0];
            i = skipSpace(json, i);
            if (i >= n || json.charAt(i) != ':') {
                throw new ParseException("expected ':'");
            }
            i++;
            i = skipSpace(json, i);
            if (i >= n) {
                throw new ParseException("unexpected end");
            }
            char c = json.charAt(i);
            if (c == '"') {
                int[] valEnd = new int[1];
                String value = parseString(json, i, valEnd);
                i = valEnd[0];
                out.put(key, value);
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int[] numEnd = new int[1];
                long value = parseNumber(json, i, numEnd);
                i = numEnd[0];
                out.put(key, value);
            } else {
                throw new ParseException("unsupported value type");
            }
            i = skipSpace(json, i);
            if (i < n && json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < n && json.charAt(i) == '}') {
                return out;
            }
            throw new ParseException("expected ',' or '}'");
        }
        throw new ParseException("unterminated object");
    }

    private static int skipSpace(String s, int i) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static String parseString(String s, int start, int[] endOut) throws ParseException {
        int n = s.length();
        int i = start + 1; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (i < n) {
            char c = s.charAt(i++);
            if (c == '"') {
                endOut[0] = i;
                return sb.toString();
            }
            if (c == '\\') {
                if (i >= n) {
                    throw new ParseException("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > n) {
                            throw new ParseException("bad \\u");
                        }
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            cp = (cp << 4) | hex(s.charAt(i++));
                        }
                        sb.append((char) cp);
                        break;
                    default:
                        throw new ParseException("bad escape char");
                }
            } else if (c < 0x20) {
                throw new ParseException("unescaped control char");
            } else {
                sb.append(c);
            }
        }
        throw new ParseException("unterminated string");
    }

    private static long parseNumber(String s, int start, int[] endOut) throws ParseException {
        int n = s.length();
        int i = start;
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        long value = 0;
        boolean any = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                any = true;
                i++;
            } else {
                break;
            }
        }
        if (!any) {
            throw new ParseException("bad number");
        }
        endOut[0] = i;
        return value;
    }

    private static int hex(char c) throws ParseException {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new ParseException("bad hex digit");
    }

    /** JSON-escape and quote a string per RFC 8259. */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
