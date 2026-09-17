package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable bounded result of an SMS inbox read, kept Android-free for
 * testing.
 *
 * <p>Only the minimal fields of the contract cross this boundary: sender
 * address, timestamp, read state, and a snippet capped at
 * {@link MessagingReadPolicy#MAX_SNIPPET_CHARS} single-line characters. The
 * full message body, person, subject, service center, status, error code,
 * thread id, and creator are deliberately omitted. Rows are capped at
 * {@link MessagingReadPolicy#MAX_ROWS}; any capping sets {@link #isTruncated()}.
 * Permission, unavailable, and error states are explicit value objects; the
 * bridge encodes only a {@link MessagingReadState#READING} snapshot with
 * fields and reports every other state as a typed error. SMS send is a
 * side-effecting operation and is out of scope for this read slice.</p>
 */
public final class SmsSnapshot {
    private final MessagingReadState state;
    private final List<SmsEntry> entries;
    private final boolean truncated;

    private SmsSnapshot(MessagingReadState state, List<SmsEntry> entries, boolean truncated) {
        this.state = state;
        List<SmsEntry> bounded = new ArrayList<>();
        boolean capped = truncated;
        if (entries != null) {
            for (SmsEntry entry : entries) {
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

    /** A bounded SMS list; extra rows are dropped and reported via {@link #isTruncated()}. */
    public static SmsSnapshot reading(List<SmsEntry> entries, boolean truncated) {
        return new SmsSnapshot(MessagingReadState.READING, entries, truncated);
    }

    /** No read-sms grant and no recorded denial; the later consent flow may ask. */
    public static SmsSnapshot permissionRequired() {
        return new SmsSnapshot(MessagingReadState.PERMISSION_REQUIRED, null, false);
    }

    /** No read-sms grant and the user previously denied it. */
    public static SmsSnapshot permissionDenied() {
        return new SmsSnapshot(MessagingReadState.PERMISSION_DENIED, null, false);
    }

    /** The SMS provider is absent or returned no cursor. */
    public static SmsSnapshot unavailable() {
        return new SmsSnapshot(MessagingReadState.UNAVAILABLE, null, false);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static SmsSnapshot error() {
        return new SmsSnapshot(MessagingReadState.ERROR, null, false);
    }

    public MessagingReadState getState() {
        return state;
    }

    /** Bounded SMS entries; empty unless {@link MessagingReadState#READING}. */
    public List<SmsEntry> getEntries() {
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
        for (SmsEntry entry : entries) {
            rows.add(entry.rowFields());
        }
        return MessagingReadPolicy.encodeRows(rows);
    }

    /** One bounded SMS inbox entry. */
    public static final class SmsEntry {
        private final String address;
        private final long timestampUtcMillis;
        private final boolean read;
        private final String snippet;

        /**
         * @param snippet the message preview; sanitized and capped at
         *                {@link MessagingReadPolicy#MAX_SNIPPET_CHARS}
         * @throws IllegalArgumentException when {@code address} is blank or
         *                                  {@code timestampUtcMillis} is not positive
         */
        public SmsEntry(String address, long timestampUtcMillis, boolean read, String snippet) {
            if (address == null || address.trim().isEmpty()) {
                throw new IllegalArgumentException("address must not be blank");
            }
            if (timestampUtcMillis <= 0L) {
                throw new IllegalArgumentException("timestampUtcMillis must be positive");
            }
            this.address = MessagingReadPolicy.truncate(
                    address.trim(), MessagingReadPolicy.MAX_NUMBER_CHARS);
            this.timestampUtcMillis = timestampUtcMillis;
            this.read = read;
            this.snippet = MessagingReadPolicy.sanitizeSnippet(snippet);
        }

        public String getAddress() {
            return address;
        }

        public long getTimestampUtcMillis() {
            return timestampUtcMillis;
        }

        public boolean isRead() {
            return read;
        }

        /** Single-line message preview; never the full body. */
        public String getSnippet() {
            return snippet;
        }

        /** Flat, bounded row fields. */
        public Map<String, Object> rowFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("address", address);
            fields.put("timestamp_utc_ms", timestampUtcMillis);
            fields.put("read", read);
            fields.put("snippet", snippet);
            return fields;
        }
    }
}
