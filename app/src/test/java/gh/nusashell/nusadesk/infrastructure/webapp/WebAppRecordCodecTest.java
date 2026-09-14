package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

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
public class WebAppRecordCodecTest {
    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";

    private final WebAppRecordCodec codec = new WebAppRecordCodec();

    private static WebAppDefinition definition() {
        return new WebAppDefinition(
                WebAppId.of("app-1"), "Notes", ICON, 8080, 100L, 200L, 3);
    }

    private static Map<String, String> validFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put(WebAppRecordCodec.FIELD_NAME, "Notes");
        fields.put(WebAppRecordCodec.FIELD_ICON_URI, ICON);
        fields.put(WebAppRecordCodec.FIELD_GUEST_PORT, "8080");
        fields.put(WebAppRecordCodec.FIELD_CREATED_AT, "100");
        fields.put(WebAppRecordCodec.FIELD_UPDATED_AT, "200");
        fields.put(WebAppRecordCodec.FIELD_SORT_ORDER, "3");
        return fields;
    }

    private static Map<String, Object> stored(String rawId, Map<String, String> fields) {
        Map<String, Object> all = new HashMap<>();
        String prefix = rawId + WebAppRecordCodec.SEPARATOR;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            all.put(prefix + field.getKey(), field.getValue());
        }
        return all;
    }

    @Test
    public void roundTripsADefinitionThroughFields() {
        WebAppDefinition original = definition();
        assertEquals(original, codec.fromFields("app-1", codec.toFields(original)));
    }

    @Test
    public void roundTripsAnAppWithoutAnIcon() {
        WebAppDefinition original = new WebAppDefinition(
                WebAppId.of("app-1"), "Notes", null, 8080, 100L, 100L, 0);
        Map<String, String> fields = codec.toFields(original);
        assertNull(fields.get(WebAppRecordCodec.FIELD_ICON_URI));
        assertEquals(original, codec.fromFields("app-1", fields));
    }

    @Test
    public void trimsStoredStringFields() {
        Map<String, String> fields = validFields();
        fields.put(WebAppRecordCodec.FIELD_NAME, "  Notes  ");
        fields.put(WebAppRecordCodec.FIELD_GUEST_PORT, " 8080 ");
        fields.put(WebAppRecordCodec.FIELD_CREATED_AT, " 100 ");
        fields.put(WebAppRecordCodec.FIELD_UPDATED_AT, " 200 ");
        fields.put(WebAppRecordCodec.FIELD_SORT_ORDER, " 3 ");
        WebAppDefinition rebuilt = codec.fromFields("app-1", fields);
        assertEquals("Notes", rebuilt.getDisplayName());
        assertEquals(8080, rebuilt.getGuestPort());
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
                WebAppRecordCodec.FIELD_NAME,
                WebAppRecordCodec.FIELD_GUEST_PORT,
                WebAppRecordCodec.FIELD_CREATED_AT,
                WebAppRecordCodec.FIELD_UPDATED_AT,
                WebAppRecordCodec.FIELD_SORT_ORDER}) {
            Map<String, String> fields = validFields();
            fields.remove(required);
            assertNull("missing " + required + " must be treated as corrupt",
                    codec.fromFields("app-1", fields));
        }
    }

    @Test
    public void dropsRecordsWithMalformedNumbers() {
        Map<String, String> fields = validFields();
        fields.put(WebAppRecordCodec.FIELD_GUEST_PORT, "eighty-eighty");
        assertNull(codec.fromFields("app-1", fields));

        fields = validFields();
        fields.put(WebAppRecordCodec.FIELD_CREATED_AT, "not-a-time");
        assertNull(codec.fromFields("app-1", fields));

        fields = validFields();
        fields.put(WebAppRecordCodec.FIELD_SORT_ORDER, "1.5");
        assertNull(codec.fromFields("app-1", fields));
    }

    @Test
    public void dropsRecordsThatViolateDomainRules() {
        Map<String, String> outOfRange = validFields();
        outOfRange.put(WebAppRecordCodec.FIELD_GUEST_PORT, "70000");
        assertNull(codec.fromFields("app-1", outOfRange));

        Map<String, String> reserved = validFields();
        reserved.put(WebAppRecordCodec.FIELD_GUEST_PORT,
                Integer.toString(GuestPortPolicy.RESERVED_GUEST_SSH_PORT));
        assertNull(codec.fromFields("app-1", reserved));

        Map<String, String> blankName = validFields();
        blankName.put(WebAppRecordCodec.FIELD_NAME, "   ");
        assertNull(codec.fromFields("app-1", blankName));

        Map<String, String> tamperedIcon = validFields();
        tamperedIcon.put(WebAppRecordCodec.FIELD_ICON_URI, "file:///data/local/tmp/icon.png");
        assertNull(codec.fromFields("app-1", tamperedIcon));

        Map<String, String> invertedTimes = validFields();
        invertedTimes.put(WebAppRecordCodec.FIELD_UPDATED_AT, "50");
        assertNull(codec.fromFields("app-1", invertedTimes));

        Map<String, String> negativeOrder = validFields();
        negativeOrder.put(WebAppRecordCodec.FIELD_SORT_ORDER, "-1");
        assertNull(codec.fromFields("app-1", negativeOrder));
    }

    @Test
    public void keyPrefixIsTheIdPlusTheSeparator() {
        assertEquals("app-1.", WebAppRecordCodec.keyPrefix(WebAppId.of("app-1")));
        // The prefix scheme is only unambiguous while no field name repeats the
        // separator; otherwise two ids could overlap.
        for (String field : codec.toFields(definition()).keySet()) {
            assertFalse(field, field.contains(WebAppRecordCodec.SEPARATOR));
        }
    }

    @Test
    public void theStoredKeyShapeReadsBackAsTheSameDefinition() {
        Map<String, Object> all = stored("app-1", codec.toFields(definition()));
        for (String key : all.keySet()) {
            assertTrue(key, key.startsWith(WebAppRecordCodec.keyPrefix(WebAppId.of("app-1"))));
        }
        assertEquals(definition(), codec.parseAll(all).getDefinitions().get(0));
    }

    @Test
    public void parseAllReadsEveryValidRecord() {
        Map<String, Object> all = new HashMap<>();
        all.putAll(stored("app-1", validFields()));
        all.putAll(stored("app-2", validFields()));

        WebAppRecordCodec.ParseResult result = codec.parseAll(all);
        assertEquals(2, result.getDefinitions().size());
        assertTrue(result.getCorruptIds().isEmpty());
    }

    @Test
    public void parseAllIsEmptyForEmptyOrNullStorage() {
        assertTrue(codec.parseAll(new HashMap<String, Object>()).getDefinitions().isEmpty());
        assertTrue(codec.parseAll(null).getDefinitions().isEmpty());
    }

    @Test
    public void parseAllIgnoresKeysThatAreNotWebAppRecords() {
        Map<String, Object> all = new HashMap<>();
        all.put("someOtherPreference", "value");
        all.put("schemaVersion", 2);
        all.put(".name", "orphan");
        all.putAll(stored("app-1", validFields()));

        WebAppRecordCodec.ParseResult result = codec.parseAll(all);
        assertEquals(1, result.getDefinitions().size());
        assertTrue("foreign keys must not be reported as corrupt records",
                result.getCorruptIds().isEmpty());
    }

    @Test
    public void parseAllReportsAndSkipsCorruptRecords() {
        Map<String, Object> all = new HashMap<>();
        all.putAll(stored("app-1", validFields()));

        Map<String, String> partial = validFields();
        partial.remove(WebAppRecordCodec.FIELD_GUEST_PORT);
        all.putAll(stored("app-2", partial));

        all.put("bad id.name", "Notes");
        all.put("app-3.name", 42);

        WebAppRecordCodec.ParseResult result = codec.parseAll(all);
        assertEquals(1, result.getDefinitions().size());
        assertEquals("app-1", result.getDefinitions().get(0).getId().value());
        assertEquals(3, result.getCorruptIds().size());
        assertTrue(result.getCorruptIds().contains("app-2"));
        assertTrue(result.getCorruptIds().contains("bad id"));
        assertTrue(result.getCorruptIds().contains("app-3"));
    }

    @Test
    public void parseAllResultsAreUnmodifiable() {
        WebAppRecordCodec.ParseResult result = codec.parseAll(stored("app-1", validFields()));
        List<WebAppDefinition> definitions = result.getDefinitions();
        try {
            definitions.clear();
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
    public void rejectsNullDefinitionOnEncode() {
        try {
            codec.toFields(null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void encodedFieldNamesAreStable() {
        Map<String, String> fields = codec.toFields(definition());
        assertTrue(fields.containsKey(WebAppRecordCodec.FIELD_NAME));
        assertTrue(fields.containsKey(WebAppRecordCodec.FIELD_ICON_URI));
        assertTrue(fields.containsKey(WebAppRecordCodec.FIELD_GUEST_PORT));
        assertTrue(fields.containsKey(WebAppRecordCodec.FIELD_CREATED_AT));
        assertTrue(fields.containsKey(WebAppRecordCodec.FIELD_UPDATED_AT));
        assertTrue(fields.containsKey(WebAppRecordCodec.FIELD_SORT_ORDER));
        assertFalse(fields.containsKey("id"));
    }
}
