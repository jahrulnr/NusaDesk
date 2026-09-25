package gh.nusashell.nusadesk.infrastructure.terminal;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for the persisted record shape. No Android runtime is used: the
 * codec is the whole corruption boundary, so a record that cannot be read back
 * exactly is proven to be dropped here rather than in the adapter.
 */
public class TerminalCommandRecordCodecTest {
    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";
    private static final String COMMAND = "docker exec -it codex bash";

    private final TerminalCommandRecordCodec codec = new TerminalCommandRecordCodec();

    private static TerminalCommandApp app() {
        return new TerminalCommandApp(
                TerminalCommandAppId.of("app-1"), "Codex", ICON,
                TerminalCommand.of(COMMAND), 100L, 200L, 3);
    }

    private static Map<String, String> validFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put(TerminalCommandRecordCodec.FIELD_NAME, "Codex");
        fields.put(TerminalCommandRecordCodec.FIELD_ICON, ICON);
        fields.put(TerminalCommandRecordCodec.FIELD_COMMAND, COMMAND);
        fields.put(TerminalCommandRecordCodec.FIELD_CREATED, "100");
        fields.put(TerminalCommandRecordCodec.FIELD_UPDATED, "200");
        fields.put(TerminalCommandRecordCodec.FIELD_ORDER, "3");
        return fields;
    }

    private static Map<String, Object> stored(String rawId, Map<String, String> fields) {
        Map<String, Object> all = new HashMap<>();
        String prefix = rawId + TerminalCommandRecordCodec.SEPARATOR;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            all.put(prefix + field.getKey(), field.getValue());
        }
        return all;
    }

    @Test
    public void roundTripsAnAppThroughFields() {
        TerminalCommandApp original = app();
        assertEquals(original, codec.fromFields("app-1", codec.toFields(original)));
    }

    @Test
    public void roundTripsAnAppWithoutAnIcon() {
        TerminalCommandApp original = new TerminalCommandApp(
                TerminalCommandAppId.of("app-1"), "Codex", null,
                TerminalCommand.of(COMMAND), 100L, 100L, 0);
        Map<String, String> fields = codec.toFields(original);
        assertNull(fields.get(TerminalCommandRecordCodec.FIELD_ICON));
        assertEquals(original, codec.fromFields("app-1", fields));
    }

    @Test
    public void storesTheCommandVerbatim() {
        String complex = "sh -c 'echo \"a b\" && exit' | tee /tmp/x";
        TerminalCommandApp original = new TerminalCommandApp(
                TerminalCommandAppId.of("app-1"), "Codex", null,
                TerminalCommand.of(complex), 100L, 100L, 0);
        Map<String, String> fields = codec.toFields(original);
        assertEquals(complex, fields.get(TerminalCommandRecordCodec.FIELD_COMMAND));
        assertEquals(complex,
                codec.fromFields("app-1", fields).getCommand().value());
    }

    @Test
    public void trimsStoredStringFields() {
        Map<String, String> fields = validFields();
        fields.put(TerminalCommandRecordCodec.FIELD_NAME, "  Codex  ");
        fields.put(TerminalCommandRecordCodec.FIELD_COMMAND, "  " + COMMAND + "  ");
        fields.put(TerminalCommandRecordCodec.FIELD_CREATED, " 100 ");
        fields.put(TerminalCommandRecordCodec.FIELD_UPDATED, " 200 ");
        fields.put(TerminalCommandRecordCodec.FIELD_ORDER, " 3 ");
        TerminalCommandApp rebuilt = codec.fromFields("app-1", fields);
        assertEquals("Codex", rebuilt.getDisplayName());
        assertEquals(COMMAND, rebuilt.getCommand().value());
        assertEquals(3, rebuilt.getSortOrder());
    }

    @Test
    public void rejectsAMalformedId() {
        assertNull(codec.fromFields("not a valid id", validFields()));
        assertNull(codec.fromFields(null, validFields()));
        assertNull(codec.fromFields("a.b", validFields()));
    }

    @Test
    public void rejectsNullFields() {
        assertNull(codec.fromFields("app-1", null));
    }

    @Test
    public void dropsRecordsWithAMissingRequiredField() {
        for (String required : new String[] {
                TerminalCommandRecordCodec.FIELD_NAME,
                TerminalCommandRecordCodec.FIELD_COMMAND,
                TerminalCommandRecordCodec.FIELD_CREATED,
                TerminalCommandRecordCodec.FIELD_UPDATED,
                TerminalCommandRecordCodec.FIELD_ORDER}) {
            Map<String, String> fields = validFields();
            fields.remove(required);
            assertNull("missing " + required + " must be treated as corrupt",
                    codec.fromFields("app-1", fields));
        }
    }

    @Test
    public void dropsRecordsWithMalformedNumbers() {
        Map<String, String> fields = validFields();
        fields.put(TerminalCommandRecordCodec.FIELD_CREATED, "not-a-time");
        assertNull(codec.fromFields("app-1", fields));

        fields = validFields();
        fields.put(TerminalCommandRecordCodec.FIELD_UPDATED, "soon");
        assertNull(codec.fromFields("app-1", fields));

        fields = validFields();
        fields.put(TerminalCommandRecordCodec.FIELD_ORDER, "1.5");
        assertNull(codec.fromFields("app-1", fields));
    }

    @Test
    public void dropsRecordsThatViolateDomainRules() {
        Map<String, String> blankName = validFields();
        blankName.put(TerminalCommandRecordCodec.FIELD_NAME, "   ");
        assertNull(codec.fromFields("app-1", blankName));

        Map<String, String> tamperedIcon = validFields();
        tamperedIcon.put(TerminalCommandRecordCodec.FIELD_ICON,
                "file:///data/local/tmp/icon.png");
        assertNull(codec.fromFields("app-1", tamperedIcon));

        Map<String, String> blankCommand = validFields();
        blankCommand.put(TerminalCommandRecordCodec.FIELD_COMMAND, "   ");
        assertNull(codec.fromFields("app-1", blankCommand));

        Map<String, String> multilineCommand = validFields();
        multilineCommand.put(TerminalCommandRecordCodec.FIELD_COMMAND,
                "echo one\necho two");
        assertNull(codec.fromFields("app-1", multilineCommand));

        Map<String, String> invertedTimes = validFields();
        invertedTimes.put(TerminalCommandRecordCodec.FIELD_UPDATED, "50");
        assertNull(codec.fromFields("app-1", invertedTimes));

        Map<String, String> negativeOrder = validFields();
        negativeOrder.put(TerminalCommandRecordCodec.FIELD_ORDER, "-1");
        assertNull(codec.fromFields("app-1", negativeOrder));
    }

    @Test
    public void keyPrefixIsTheIdPlusTheSeparator() {
        assertEquals("app-1.",
                TerminalCommandRecordCodec.keyPrefix(TerminalCommandAppId.of("app-1")));
        // The prefix scheme is only unambiguous while no field name repeats the
        // separator; otherwise two ids could overlap.
        for (String field : codec.toFields(app()).keySet()) {
            assertFalse(field, field.contains(TerminalCommandRecordCodec.SEPARATOR));
        }
    }

    @Test
    public void theStoredKeyShapeReadsBackAsTheSameApp() {
        Map<String, Object> all = stored("app-1", codec.toFields(app()));
        for (String key : all.keySet()) {
            assertTrue(key, key.startsWith(
                    TerminalCommandRecordCodec.keyPrefix(TerminalCommandAppId.of("app-1"))));
        }
        assertEquals(app(), codec.parseAll(all).getApps().get(0));
    }

    @Test
    public void parseAllReadsEveryValidRecord() {
        Map<String, Object> all = new HashMap<>();
        all.putAll(stored("app-1", validFields()));
        all.putAll(stored("app-2", validFields()));

        TerminalCommandRecordCodec.ParseResult result = codec.parseAll(all);
        assertEquals(2, result.getApps().size());
        assertTrue(result.getCorruptIds().isEmpty());
    }

    @Test
    public void parseAllIsEmptyForEmptyOrNullStorage() {
        assertTrue(codec.parseAll(new HashMap<String, Object>()).getApps().isEmpty());
        assertTrue(codec.parseAll(null).getApps().isEmpty());
    }

    @Test
    public void parseAllIgnoresKeysThatAreNotAppRecords() {
        Map<String, Object> all = new HashMap<>();
        all.put("someOtherPreference", "value");
        all.put("schemaVersion", 2);
        all.put(".name", "orphan");
        all.putAll(stored("app-1", validFields()));

        TerminalCommandRecordCodec.ParseResult result = codec.parseAll(all);
        assertEquals(1, result.getApps().size());
        assertTrue("foreign keys must not be reported as corrupt records",
                result.getCorruptIds().isEmpty());
    }

    @Test
    public void parseAllReportsAndSkipsCorruptRecords() {
        Map<String, Object> all = new HashMap<>();
        all.putAll(stored("app-1", validFields()));

        Map<String, String> partial = validFields();
        partial.remove(TerminalCommandRecordCodec.FIELD_COMMAND);
        all.putAll(stored("app-2", partial));

        all.put("bad id.name", "Codex");
        all.put("app-3.name", 42);

        TerminalCommandRecordCodec.ParseResult result = codec.parseAll(all);
        assertEquals(1, result.getApps().size());
        assertEquals("app-1", result.getApps().get(0).getId().value());
        assertEquals(3, result.getCorruptIds().size());
        assertTrue(result.getCorruptIds().contains("app-2"));
        assertTrue(result.getCorruptIds().contains("bad id"));
        assertTrue(result.getCorruptIds().contains("app-3"));
    }

    @Test
    public void parseAllResultsAreUnmodifiable() {
        TerminalCommandRecordCodec.ParseResult result =
                codec.parseAll(stored("app-1", validFields()));
        List<TerminalCommandApp> apps = result.getApps();
        try {
            apps.clear();
            fail();
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            result.getCorruptIds().add("app-9");
            fail();
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullAppOnEncode() {
        try {
            codec.toFields(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void encodedFieldNamesAreStable() {
        Map<String, String> fields = codec.toFields(app());
        assertTrue(fields.containsKey(TerminalCommandRecordCodec.FIELD_NAME));
        assertTrue(fields.containsKey(TerminalCommandRecordCodec.FIELD_ICON));
        assertTrue(fields.containsKey(TerminalCommandRecordCodec.FIELD_COMMAND));
        assertTrue(fields.containsKey(TerminalCommandRecordCodec.FIELD_CREATED));
        assertTrue(fields.containsKey(TerminalCommandRecordCodec.FIELD_UPDATED));
        assertTrue(fields.containsKey(TerminalCommandRecordCodec.FIELD_ORDER));
        assertFalse(fields.containsKey("id"));
    }
}
