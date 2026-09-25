package gh.nusashell.nusadesk.infrastructure.terminal;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android-free encoder/decoder for one persisted terminal-command-app record.
 *
 * <p>A record is a flat {@code <id>.<field>} string map, the shape an app-private
 * {@code SharedPreferences} file stores. The id is the key prefix rather than a
 * field, which is safe only because {@link TerminalCommandAppId} rejects the
 * separator character — no two ids can produce overlapping prefixes.</p>
 *
 * <p>Reading is the corruption boundary. A record is rebuilt through the
 * {@link TerminalCommandApp} constructor, so every stored value is re-validated
 * on the way in: a tampered name, command, icon token, timestamp, or order is
 * dropped instead of becoming a launcher entry. The command is stored verbatim —
 * it may contain any printable characters — and is re-validated by the domain
 * constructor on read like every other field. Kept separate from the
 * {@code SharedPreferences} adapter so all of that logic is unit-testable on
 * the JVM without an Android runtime.</p>
 */
final class TerminalCommandRecordCodec {
    static final String FIELD_NAME = "name";
    static final String FIELD_ICON = "icon";
    static final String FIELD_COMMAND = "command";
    static final String FIELD_CREATED = "created";
    static final String FIELD_UPDATED = "updated";
    static final String FIELD_ORDER = "order";
    static final String SEPARATOR = ".";

    /** Storage key prefix owned by one terminal-command app. */
    static String keyPrefix(TerminalCommandAppId id) {
        return id.value() + SEPARATOR;
    }

    /** The field map persisted for one app. A null icon means "no icon". */
    Map<String, String> toFields(TerminalCommandApp app) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        Map<String, String> fields = new HashMap<>();
        fields.put(FIELD_NAME, app.getDisplayName());
        fields.put(FIELD_ICON, app.getIconUri());
        fields.put(FIELD_COMMAND, app.getCommand().value());
        fields.put(FIELD_CREATED, Long.toString(app.getCreatedAtEpochMillis()));
        fields.put(FIELD_UPDATED, Long.toString(app.getUpdatedAtEpochMillis()));
        fields.put(FIELD_ORDER, Integer.toString(app.getSortOrder()));
        return fields;
    }

    /**
     * Rebuilds one record from its field map.
     *
     * @param rawId  the storage key prefix, which is the app id
     * @param fields the stored fields; {@code null} when the record is unreadable
     * @return the app, or {@code null} when the record is missing a required
     *         field, holds a malformed value, or violates a domain rule
     */
    TerminalCommandApp fromFields(String rawId, Map<String, String> fields) {
        if (!TerminalCommandAppId.isValid(rawId) || fields == null) {
            return null;
        }
        String name = fields.get(FIELD_NAME);
        String command = fields.get(FIELD_COMMAND);
        String created = fields.get(FIELD_CREATED);
        String updated = fields.get(FIELD_UPDATED);
        String order = fields.get(FIELD_ORDER);
        if (name == null || command == null || created == null
                || updated == null || order == null) {
            return null;
        }
        try {
            return new TerminalCommandApp(
                    TerminalCommandAppId.of(rawId),
                    name,
                    fields.get(FIELD_ICON),
                    TerminalCommand.of(command),
                    Long.parseLong(created.trim()),
                    Long.parseLong(updated.trim()),
                    Integer.parseInt(order.trim()));
        } catch (IllegalArgumentException corrupt) {
            return null;
        }
    }

    /**
     * Reads a whole preferences map.
     *
     * <p>Keys that are not terminal-command-app records are ignored so this
     * codec never claims storage it does not own. A record whose id prefix is
     * malformed, that is missing a required field, that holds a non-string
     * value, or that fails validation is reported through
     * {@link ParseResult#getCorruptIds()} so the adapter can prune it instead of
     * leaving it to be re-read forever.</p>
     */
    ParseResult parseAll(Map<String, ?> all) {
        List<TerminalCommandApp> apps = new ArrayList<>();
        Set<String> corruptIds = new LinkedHashSet<>();
        if (all == null) {
            return new ParseResult(apps, corruptIds);
        }

        Map<String, Map<String, String>> records = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            int separator = key == null ? -1 : key.indexOf(SEPARATOR);
            if (separator <= 0 || separator == key.length() - 1) {
                // Not a terminal-command-app record, or a stray key with no
                // field name. Skipped rather than reported corrupt: an empty
                // field must not invalidate a sibling key that holds a real
                // field.
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
            TerminalCommandApp app = fromFields(rawId, record.getValue());
            if (app == null) {
                corruptIds.add(rawId);
            } else {
                apps.add(app);
            }
        }
        return new ParseResult(apps, corruptIds);
    }

    /** What one read of the persisted map produced. */
    static final class ParseResult {
        private final List<TerminalCommandApp> apps;
        private final Set<String> corruptIds;

        private ParseResult(List<TerminalCommandApp> apps, Set<String> corruptIds) {
            this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
            this.corruptIds = Collections.unmodifiableSet(new LinkedHashSet<>(corruptIds));
        }

        /** Every record that could be read back as a complete, valid app. */
        List<TerminalCommandApp> getApps() {
            return apps;
        }

        /** Raw key prefixes that must be pruned because they cannot be read back. */
        Set<String> getCorruptIds() {
            return corruptIds;
        }
    }
}
