package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable bounded result of a contacts read, kept Android-free for testing.
 *
 * <p>Only the minimal fields of the contract cross this boundary: a display
 * name and up to {@link MessagingReadPolicy#MAX_NUMBERS_PER_CONTACT} phone
 * numbers per contact. Email addresses, postal addresses, photos, notes,
 * organization, and every provider id are deliberately omitted. Rows are
 * capped at {@link MessagingReadPolicy#MAX_ROWS}; any capping (rows, or
 * numbers within a contact) sets {@link #isTruncated()} so the guest can
 * never mistake a bounded result for the full address book. Permission,
 * unavailable, and error states are explicit value objects; the bridge
 * encodes only a {@link MessagingReadState#READING} snapshot with fields and
 * reports every other state as a typed error.</p>
 */
public final class ContactsSnapshot {
    private final MessagingReadState state;
    private final List<ContactEntry> entries;
    private final boolean truncated;

    private ContactsSnapshot(MessagingReadState state, List<ContactEntry> entries,
                             boolean truncated) {
        this.state = state;
        List<ContactEntry> bounded = new ArrayList<>();
        boolean capped = truncated;
        if (entries != null) {
            for (ContactEntry entry : entries) {
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

    /** A bounded contact list; extra rows are dropped and reported via {@link #isTruncated()}. */
    public static ContactsSnapshot reading(List<ContactEntry> entries, boolean truncated) {
        return new ContactsSnapshot(MessagingReadState.READING, entries, truncated);
    }

    /** No read-contacts grant and no recorded denial; the later consent flow may ask. */
    public static ContactsSnapshot permissionRequired() {
        return new ContactsSnapshot(MessagingReadState.PERMISSION_REQUIRED, null, false);
    }

    /** No read-contacts grant and the user previously denied it. */
    public static ContactsSnapshot permissionDenied() {
        return new ContactsSnapshot(MessagingReadState.PERMISSION_DENIED, null, false);
    }

    /** The contacts provider is absent or returned no cursor. */
    public static ContactsSnapshot unavailable() {
        return new ContactsSnapshot(MessagingReadState.UNAVAILABLE, null, false);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static ContactsSnapshot error() {
        return new ContactsSnapshot(MessagingReadState.ERROR, null, false);
    }

    public MessagingReadState getState() {
        return state;
    }

    /** Bounded contact entries; empty unless {@link MessagingReadState#READING}. */
    public List<ContactEntry> getEntries() {
        return entries;
    }

    /** True when rows or per-contact numbers were dropped to enforce the bounds. */
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
     * The bounded row array for the RPC payload: name plus one
     * {@code number_1}..{@code number_N} field per carried number. Rows that
     * do not fit the frame budget are dropped deterministically and reported
     * through {@link MessagingReadPolicy.EncodedRows#isTruncated()}.
     */
    public MessagingReadPolicy.EncodedRows encodeRows() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("rows are defined only for a reading");
        }
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (ContactEntry entry : entries) {
            rows.add(entry.rowFields());
        }
        return MessagingReadPolicy.encodeRows(rows);
    }

    /** One bounded contact entry: a display name and up to five numbers. */
    public static final class ContactEntry {
        private final String name;
        private final List<String> numbers;

        /**
         * @param name    display name; {@code null} or blank becomes the empty
         *                string (number-only contacts are valid)
         * @param numbers phone numbers; each must be non-blank and is trimmed
         *                and capped at {@link MessagingReadPolicy#MAX_NUMBER_CHARS}
         * @throws IllegalArgumentException when more than
         *                                  {@link MessagingReadPolicy#MAX_NUMBERS_PER_CONTACT}
         *                                  numbers are supplied or a number is blank
         */
        public ContactEntry(String name, List<String> numbers) {
            this.name = MessagingReadPolicy.truncate(
                    name == null ? "" : name.trim(), MessagingReadPolicy.MAX_NAME_CHARS);
            List<String> bounded = new ArrayList<>();
            if (numbers != null) {
                for (String number : numbers) {
                    if (number == null || number.trim().isEmpty()) {
                        throw new IllegalArgumentException("phone numbers must not be blank");
                    }
                    if (bounded.size() >= MessagingReadPolicy.MAX_NUMBERS_PER_CONTACT) {
                        throw new IllegalArgumentException("too many phone numbers per contact");
                    }
                    bounded.add(MessagingReadPolicy.truncate(
                            number.trim(), MessagingReadPolicy.MAX_NUMBER_CHARS));
                }
            }
            this.numbers = Collections.unmodifiableList(bounded);
        }

        public String getName() {
            return name;
        }

        public List<String> getNumbers() {
            return numbers;
        }

        /** Flat, bounded row fields: {@code name} plus {@code number_1}..{@code number_N}. */
        public Map<String, Object> rowFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("name", name);
            for (int i = 0; i < numbers.size(); i++) {
                fields.put("number_" + (i + 1), numbers.get(i));
            }
            return fields;
        }
    }
}
