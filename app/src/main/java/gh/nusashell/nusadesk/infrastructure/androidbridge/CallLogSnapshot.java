package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable bounded result of a call-log read, kept Android-free for testing.
 *
 * <p>Only the minimal fields of the contract cross this boundary: number,
 * the provider's cached display name, call type, timestamp, and duration.
 * Geocoded location, phone account, subscription, presentation, and every
 * provider id are deliberately omitted. Rows are capped at
 * {@link MessagingReadPolicy#MAX_ROWS}; any capping sets {@link #isTruncated()}.
 * Permission, unavailable, and error states are explicit value objects; the
 * bridge encodes only a {@link MessagingReadState#READING} snapshot with
 * fields and reports every other state as a typed error.</p>
 */
public final class CallLogSnapshot {
    /** The bounded set of call types the contract exposes. */
    public enum CallType {
        INCOMING,
        OUTGOING,
        MISSED,
        REJECTED,
        BLOCKED,
        VOICEMAIL,
        ANSWERED_EXTERNALLY,
        UNKNOWN
    }

    private final MessagingReadState state;
    private final List<CallLogEntry> entries;
    private final boolean truncated;

    private CallLogSnapshot(MessagingReadState state, List<CallLogEntry> entries,
                            boolean truncated) {
        this.state = state;
        List<CallLogEntry> bounded = new ArrayList<>();
        boolean capped = truncated;
        if (entries != null) {
            for (CallLogEntry entry : entries) {
                if (bounded.size() >= MessagingReadPolicy.MAX_ROWS) {
                    capped = true;
                    break;
                }
                bounded.add(entry);
            }
        }
        this.entries = Collections.unmodifiableList(bounded);
        this.truncated = capped;
    }

    /** A bounded call-log list; extra rows are dropped and reported via {@link #isTruncated()}. */
    public static CallLogSnapshot reading(List<CallLogEntry> entries, boolean truncated) {
        return new CallLogSnapshot(MessagingReadState.READING, entries, truncated);
    }

    /** No read-call-log grant and no recorded denial; the later consent flow may ask. */
    public static CallLogSnapshot permissionRequired() {
        return new CallLogSnapshot(MessagingReadState.PERMISSION_REQUIRED, null, false);
    }

    /** No read-call-log grant and the user previously denied it. */
    public static CallLogSnapshot permissionDenied() {
        return new CallLogSnapshot(MessagingReadState.PERMISSION_DENIED, null, false);
    }

    /** The call-log provider is absent or returned no cursor. */
    public static CallLogSnapshot unavailable() {
        return new CallLogSnapshot(MessagingReadState.UNAVAILABLE, null, false);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static CallLogSnapshot error() {
        return new CallLogSnapshot(MessagingReadState.ERROR, null, false);
    }

    public MessagingReadState getState() {
        return state;
    }

    /** Bounded call-log entries; empty unless {@link MessagingReadState#READING}. */
    public List<CallLogEntry> getEntries() {
        return entries;
    }

    /** True when rows were dropped to enforce the bounds. */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Flat fields for the RPC response envelope. Only a reading has a field
     * representation; other states are reported as typed errors by the
     * request handler and must not look like real data here.
     */
    public Map<String, Object> responseFields() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("response fields are defined only for a reading");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("available", true);
        fields.put("count", (long) entries.size());
        fields.put("truncated", truncated);
        return fields;
    }

    /**
     * The bounded row array for the RPC payload. Rows that do not fit the
     * frame budget are dropped deterministically and reported through
     * {@link MessagingReadPolicy.EncodedRows#isTruncated()}.
     */
    public MessagingReadPolicy.EncodedRows encodeRows() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("rows are defined only for a reading");
        }
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (CallLogEntry entry : entries) {
            rows.add(entry.rowFields());
        }
        return MessagingReadPolicy.encodeRows(rows);
    }

    /** One bounded call-log entry. */
    public static final class CallLogEntry {
        private final String number;
        private final String cachedName;
        private final CallType type;
        private final long timestampUtcMillis;
        private final long durationSeconds;

        /**
         * @param cachedName the provider's cached contact name, or {@code null}
         *                   when the call log carries none
         * @throws IllegalArgumentException when {@code number} is blank,
         *                                  {@code timestampUtcMillis} is not
         *                                  positive, or {@code durationSeconds}
         *                                  is negative
         */
        public CallLogEntry(String number, String cachedName, CallType type,
                            long timestampUtcMillis, long durationSeconds) {
            if (number == null || number.trim().isEmpty()) {
                throw new IllegalArgumentException("number must not be blank");
            }
            if (type == null) {
                throw new IllegalArgumentException("type must not be null");
            }
            if (timestampUtcMillis <= 0L) {
                throw new IllegalArgumentException("timestampUtcMillis must be positive");
            }
            if (durationSeconds < 0L) {
                throw new IllegalArgumentException("durationSeconds must not be negative");
            }
            this.number = MessagingReadPolicy.truncate(
                    number.trim(), MessagingReadPolicy.MAX_NUMBER_CHARS);
            this.cachedName = cachedName == null ? null : MessagingReadPolicy.truncate(
                    cachedName.trim(), MessagingReadPolicy.MAX_NAME_CHARS);
            this.type = type;
            this.timestampUtcMillis = timestampUtcMillis;
            this.durationSeconds = durationSeconds;
        }

        public String getNumber() {
            return number;
        }

        /** {@code null} when the call log carried no cached name. */
        public String getCachedName() {
            return cachedName;
        }

        public CallType getType() {
            return type;
        }

        public long getTimestampUtcMillis() {
            return timestampUtcMillis;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }

        /** Flat, bounded row fields; the cached name is omitted when absent. */
        public Map<String, Object> rowFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("number", number);
            if (cachedName != null && !cachedName.isEmpty()) {
                fields.put("name", cachedName);
            }
            fields.put("type", type.name().toLowerCase(Locale.ROOT));
            fields.put("timestamp_utc_ms", timestampUtcMillis);
            fields.put("duration_seconds", durationSeconds);
            return fields;
        }
    }
}
