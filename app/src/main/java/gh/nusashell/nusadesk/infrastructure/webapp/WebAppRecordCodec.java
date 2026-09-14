package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android-free encoder/decoder for one persisted web-app record.
 *
 * <p>A record is a flat {@code <id>.<field>} string map, the shape an app-private
 * {@code SharedPreferences} file stores. The id is the key prefix rather than a
 * field, which is safe only because {@link WebAppId} rejects the separator
 * character — no two ids can produce overlapping prefixes.</p>
 *
 * <p>Reading is the corruption boundary. A record is rebuilt through the
 * {@link WebAppDefinition} constructor, so every stored value is re-validated on
 * the way in: a tampered name, port, icon token, timestamp, or order is dropped
 * instead of becoming a launcher entry. Kept separate from the
 * {@code SharedPreferences} adapter so all of that logic is unit-testable on the
 * JVM without an Android runtime.</p>
 */
final class WebAppRecordCodec {
    static final String FIELD_NAME = "name";
    static final String FIELD_ICON_URI = "iconUri";
    static final String FIELD_GUEST_PORT = "guestPort";
    static final String FIELD_CREATED_AT = "createdAt";
    static final String FIELD_UPDATED_AT = "updatedAt";
    static final String FIELD_SORT_ORDER = "sortOrder";
    static final String SEPARATOR = ".";

    /** Storage key prefix owned by one web app. */
    static String keyPrefix(WebAppId id) {
        return id.value() + SEPARATOR;
    }

    /** The field map persisted for one definition. A null icon means "no icon". */
    Map<String, String> toFields(WebAppDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_NAME, definition.getDisplayName());
        fields.put(FIELD_ICON_URI, definition.getIconUri());
        fields.put(FIELD_GUEST_PORT, Integer.toString(definition.getGuestPort()));
        fields.put(FIELD_CREATED_AT, Long.toString(definition.getCreatedAtEpochMillis()));
        fields.put(FIELD_UPDATED_AT, Long.toString(definition.getUpdatedAtEpochMillis()));
        fields.put(FIELD_SORT_ORDER, Integer.toString(definition.getSortOrder()));
        return fields;
    }

    /**
     * Rebuilds one record from its field map.
     *
     * @param rawId  the storage key prefix, which is the app id
     * @param fields the stored fields; {@code null} when the record is unreadable
     * @return the definition, or {@code null} when the record is missing a
     *         required field, holds a malformed value, or violates a domain rule
     */
    WebAppDefinition fromFields(String rawId, Map<String, String> fields) {
        if (!WebAppId.isValid(rawId) || fields == null) {
            return null;
        }
        String name = fields.get(FIELD_NAME);
        String guestPort = fields.get(FIELD_GUEST_PORT);
        String createdAt = fields.get(FIELD_CREATED_AT);
        String updatedAt = fields.get(FIELD_UPDATED_AT);
        String sortOrder = fields.get(FIELD_SORT_ORDER);
        if (name == null || guestPort == null || createdAt == null
                || updatedAt == null || sortOrder == null) {
            return null;
        }
        try {
            return new WebAppDefinition(
                    WebAppId.of(rawId),
                    name,
                    fields.get(FIELD_ICON_URI),
                    Integer.parseInt(guestPort.trim()),
                    Long.parseLong(createdAt.trim()),
                    Long.parseLong(updatedAt.trim()),
                    Integer.parseInt(sortOrder.trim()));
        } catch (IllegalArgumentException corrupt) {
            return null;
        }
    }

    /**
     * Reads a whole preferences map.
     *
     * <p>Keys that are not web-app records are ignored so this codec never
     * claims storage it does not own. A record whose id prefix is malformed, that
     * is missing a required field, that holds a non-string value, or that fails
     * validation is reported through {@link ParseResult#getCorruptIds()} so the
     * adapter can prune it instead of leaving it to be re-read forever.</p>
     */
    ParseResult parseAll(Map<String, ?> all) {
        List<WebAppDefinition> definitions = new ArrayList<>();
        Set<String> corruptIds = new LinkedHashSet<>();
        if (all == null) {
            return new ParseResult(definitions, corruptIds);
        }

        Map<String, Map<String, String>> records = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            int separator = key == null ? -1 : key.indexOf(SEPARATOR);
            if (separator <= 0 || separator == key.length() - 1) {
                // Not a web-app record, or a stray key with no field name. Skipped
                // rather than reported corrupt: an empty field must not invalidate
                // a sibling key that holds a real field.
                continue;
            }
            String rawId = key.substring(0, separator);
            Map<String, String> fields = records.get(rawId);
            if (fields == null) {
                fields = new HashMap<>();
                records.put(rawId, fields);
            }
            Object value = entry.getValue();
            if (value instanceof String) {
                fields.put(key.substring(separator + 1), (String) value);
            } else {
                // A stored value of another type cannot be read back as a field.
                corruptIds.add(rawId);
            }
        }

        for (Map.Entry<String, Map<String, String>> record : records.entrySet()) {
            String rawId = record.getKey();
            if (corruptIds.contains(rawId)) {
                continue;
            }
            WebAppDefinition definition = fromFields(rawId, record.getValue());
            if (definition == null) {
                corruptIds.add(rawId);
            } else {
                definitions.add(definition);
            }
        }
        return new ParseResult(definitions, corruptIds);
    }

    /** What one read of the persisted map produced. */
    static final class ParseResult {
        private final List<WebAppDefinition> definitions;
        private final Set<String> corruptIds;

        private ParseResult(List<WebAppDefinition> definitions, Set<String> corruptIds) {
            this.definitions = Collections.unmodifiableList(new ArrayList<>(definitions));
            this.corruptIds = Collections.unmodifiableSet(new LinkedHashSet<>(corruptIds));
        }

        /** Every record that could be read back as a complete, valid definition. */
        List<WebAppDefinition> getDefinitions() {
            return definitions;
        }

        /** Raw key prefixes that must be pruned because they cannot be read back. */
        Set<String> getCorruptIds() {
            return corruptIds;
        }
    }
}
