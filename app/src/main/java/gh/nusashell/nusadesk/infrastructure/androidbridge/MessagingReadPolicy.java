package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.List;
import java.util.Map;

/**
 * Bounded-read policy for the messaging/telephony/contacts surface, kept
 * Android-free for testing.
 *
 * <p>Every guest-visible value and row count is capped here so a platform
 * provider can never push an unbounded payload over the bridge:
 * {@link #MAX_ROWS} caps result rows, {@link #MAX_QUERY_CHARS} caps the
 * optional sanitized search query, per-field caps bound names, numbers, and
 * message snippets, and {@link #encodeRows(List)} serializes row maps into a
 * single-line JSON array that is guaranteed to leave room for the protocol
 * envelope. Rows that do not fit the byte budget are dropped from the end
 * deterministically and reported through {@link EncodedRows#isTruncated()},
 * never silently.</p>
 *
 * <p>The side-effecting operations ({@code sms.send}, {@code phone.call}) are
 * not part of this read policy at all: they live in the comms capability
 * module, which owns their runtime grants and dispatch. This class only ever
 * bounds reads.</p>
 */
public final class MessagingReadPolicy {
    /** Hard cap on result rows for every list read. */
    public static final int MAX_ROWS = 50;
    /** Default row limit when the guest does not send one. */
    public static final int DEFAULT_LIMIT = 20;
    /** Max length of the optional sanitized search query. */
    public static final int MAX_QUERY_CHARS = 64;
    /** Max length of one contact display name. */
    public static final int MAX_NAME_CHARS = 128;
    /** Max length of one phone number or SMS address. */
    public static final int MAX_NUMBER_CHARS = 32;
    /** Max phone numbers carried per contact entry. */
    public static final int MAX_NUMBERS_PER_CONTACT = 5;
    /** Max length of one SMS inbox snippet. */
    public static final int MAX_SNIPPET_CHARS = 160;
    /** Max cell entries carried by one telephony cell read. */
    public static final int MAX_CELL_ROWS = 10;
    /**
     * Envelope margin left for the RPC response fields (version, id, ok,
     * available, count, truncated) plus the frame terminator, so an encoded
     * row array can never push the final frame over the protocol limit.
     */
    public static final int ENVELOPE_MARGIN_CHARS = 512;
    /** Row-array byte budget: the protocol frame limit minus the envelope margin. */
    public static final int ROWS_BUDGET_CHARS =
            AndroidCapabilityProtocol.MAX_FRAME_BYTES - ENVELOPE_MARGIN_CHARS;

    private MessagingReadPolicy() {
    }

    /**
     * Sanitize an optional guest query: control characters are dropped, the
     * result is trimmed, and the length is capped. A {@code null} or blank
     * input becomes the empty string, which means "no filter".
     */
    public static String sanitizeQuery(String query) {
        if (query == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(query.length());
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        return truncate(cleaned.toString().trim(), MAX_QUERY_CHARS);
    }

    /**
     * Escape a value so it matches literally inside a SQL {@code LIKE}
     * pattern. The caller must pass {@code ESCAPE '\'} with the pattern.
     */
    public static String escapeLikeLiteral(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * Truncate to at most {@code maxChars} characters without splitting a
     * surrogate pair; {@code null} becomes the empty string.
     */
    public static String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Reduce an SMS body to a single-line snippet: control characters and
     * runs of whitespace collapse into single spaces, and the result is
     * trimmed and capped at {@link #MAX_SNIPPET_CHARS}.
     */
    public static String sanitizeSnippet(String body) {
        if (body == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(body.length());
        boolean pendingSpace = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ' ' || Character.isISOControl(c)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && cleaned.length() > 0) {
                    cleaned.append(' ');
                }
                pendingSpace = false;
                cleaned.append(c);
            }
        }
        return truncate(cleaned.toString(), MAX_SNIPPET_CHARS);
    }

    /**
     * Encode bounded row maps as one newline-free JSON array within
     * {@link #ROWS_BUDGET_CHARS}. Rows are added in order until the budget or
     * {@link #MAX_ROWS} is reached; remaining rows are dropped and reported
     * through {@link EncodedRows#isTruncated()}, so the encoded array always
     * fits the protocol frame. A row that cannot be encoded (invalid key,
     * unsupported value type, or non-finite double) fails closed.
     */
    public static EncodedRows encodeRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        StringBuilder json = new StringBuilder(256);
        json.append('[');
        int count = 0;
        boolean truncated = false;
        for (Map<String, Object> row : rows) {
            if (count >= MAX_ROWS) {
                truncated = true;
                break;
            }
            String encodedRow = encodeRow(row);
            int comma = json.length() > 1 ? 1 : 0;
            if (json.length() + comma + encodedRow.length() > ROWS_BUDGET_CHARS) {
                truncated = true;
                break;
            }
            if (comma == 1) {
                json.append(',');
            }
            json.append(encodedRow);
            count++;
        }
        json.append(']');
        return new EncodedRows(json.toString(), truncated);
    }

    /** One bounded JSON row array plus whether rows were dropped to fit the budget. */
    public static final class EncodedRows {
        private final String json;
        private final boolean truncated;

        private EncodedRows(String json, boolean truncated) {
            this.json = json;
            this.truncated = truncated;
        }

        public String getJson() {
            return json;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    private static String encodeRow(Map<String, Object> row) {
        if (row == null) {
            throw new IllegalArgumentException("row must not be null");
        }
        StringBuilder out = new StringBuilder(64);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (!safeFieldName(entry.getKey())) {
                throw new IllegalArgumentException("invalid row field: " + entry.getKey());
            }
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
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("non-finite row value");
            }
            return String.valueOf(number);
        }
        if (value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("unsupported row value type: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static boolean safeFieldName(String value) {
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

    private static String quote(String value) {
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
