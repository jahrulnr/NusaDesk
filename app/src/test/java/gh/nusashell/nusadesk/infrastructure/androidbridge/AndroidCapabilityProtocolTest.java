package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AndroidCapabilityProtocolTest {

    @Test
    public void decodesExactAuthenticatedRequestShape() {
        AndroidCapabilityProtocol.Request request =
                AndroidCapabilityProtocol.decodeRequest(
                        "{\"v\":1,\"id\":\"7\",\"token\":\"secret\","
                                + "\"method\":\"battery.status\"}");

        assertNotNull(request);
        assertEquals(1L, request.getVersion());
        assertEquals("7", request.getId());
        assertEquals("secret", request.getToken());
        assertEquals("battery.status", request.getMethod());
    }

    @Test
    public void rejectsUnknownVersionMissingFieldsDuplicatesAndTrailingData() {
        assertNull(AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":2,\"id\":\"7\",\"token\":\"x\",\"method\":\"battery.status\"}"));
        assertNull(AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\"}"));
        assertNull(AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"7\",\"id\":\"8\",\"token\":\"x\","
                        + "\"method\":\"battery.status\"}"));
        assertNull(AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"battery.status\"}x"));
        assertNull(AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"battery.status\","
                        + "\"extra\":true}"));
    }

    @Test
    public void rejectsOversizedAndUnboundedText() {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 257; i++) {
            token.append('x');
        }
        assertNull(AndroidCapabilityProtocol.decodeRequest(
                "{\"v\":1,\"id\":\"7\",\"token\":\"" + token
                        + "\",\"method\":\"battery.status\"}"));
        StringBuilder frame = new StringBuilder();
        for (int i = 0; i <= AndroidCapabilityProtocol.MAX_FRAME_BYTES; i++) {
            frame.append(' ');
        }
        assertNull(AndroidCapabilityProtocol.decodeRequest(frame.toString()));
    }

    @Test
    public void encodesSuccessfulFlatResponseAndEscapesStrings() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", "Charging\nnow");
        fields.put("capacity", 81L);
        fields.put("present", true);

        String json = AndroidCapabilityProtocol.encodeResponse(
                AndroidCapabilityProtocol.Response.success("7", fields));

        assertEquals("{\"v\":1,\"id\":\"7\",\"ok\":true,"
                + "\"status\":\"Charging\\nnow\",\"capacity\":81,\"present\":true}", json);
    }

    @Test
    public void encodesErrorWithoutLeakingPlatformDetails() {
        String json = AndroidCapabilityProtocol.encodeResponse(
                AndroidCapabilityProtocol.Response.error("7", "unauthorized"));
        assertEquals("{\"v\":1,\"id\":\"7\",\"ok\":false,\"error\":\"unauthorized\"}", json);
    }

    @Test
    public void encodesFiniteDoubleValues() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sensor", "accelerometer");
        fields.put("x", 0.5);
        fields.put("y", -9.81);
        fields.put("z", 0.0);

        String json = AndroidCapabilityProtocol.encodeResponse(
                AndroidCapabilityProtocol.Response.success("7", fields));

        assertEquals("{\"v\":1,\"id\":\"7\",\"ok\":true,"
                + "\"sensor\":\"accelerometer\",\"x\":0.5,\"y\":-9.81,\"z\":0.0}", json);
    }

    @Test
    public void rejectsNonFiniteDoubleValues() {
        for (double invalid : new double[]{
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("x", invalid);
            try {
                AndroidCapabilityProtocol.encodeResponse(
                        AndroidCapabilityProtocol.Response.success("1", fields));
                assertTrue("expected non-finite rejection for " + invalid, false);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    public void decodesBoundedParamsForADeclaringMethod() {
        AndroidCapabilityProtocol.Request request =
                AndroidCapabilityProtocol.decodeRequest(
                        "{\"v\":1,\"id\":\"7\",\"token\":\"secret\","
                                + "\"method\":\"calendar.insert\",\"params\":{"
                                + "\"title\":\"Rapat\",\"begin_ms\":1760003600000,"
                                + "\"all_day\":false,\"calendar_id\":3}}");

        assertNotNull(request);
        Map<String, Object> params = request.getParams();
        assertEquals("Rapat", params.get("title"));
        assertEquals(1760003600000L, params.get("begin_ms"));
        assertEquals(Boolean.FALSE, params.get("all_day"));
        assertEquals(3L, params.get("calendar_id"));
    }

    @Test
    public void aRequestWithoutParamsCarriesAnEmptyMap() {
        AndroidCapabilityProtocol.Request request =
                AndroidCapabilityProtocol.decodeRequest(
                        "{\"v\":1,\"id\":\"7\",\"token\":\"secret\","
                                + "\"method\":\"media.status\"}");

        assertNotNull(request);
        assertTrue(request.getParams().isEmpty());
    }

    @Test
    public void rejectsUnboundedOrMalformedParams() {
        String[] rejected = {
                // not an object
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.delete\","
                        + "\"params\":\"event_id=7\"}",
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.delete\","
                        + "\"params\":[7]}",
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.delete\","
                        + "\"params\":null}",
                // nested object and array values
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.delete\","
                        + "\"params\":{\"event_id\":{\"nested\":1}}}",
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.delete\","
                        + "\"params\":{\"event_id\":[1]}}",
                // unsupported scalar type
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.delete\","
                        + "\"params\":{\"event_id\":1.5}}",
                // too many keys
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.insert\","
                        + "\"params\":{\"a\":1,\"b\":1,\"c\":1,\"d\":1,\"e\":1,\"f\":1,"
                        + "\"g\":1,\"h\":1,\"i\":1}}",
                // oversized key and oversized string value
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.insert\","
                        + "\"params\":{\"" + "k".repeat(33) + "\":\"v\"}}",
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.insert\","
                        + "\"params\":{\"title\":\"" + "t".repeat(257) + "\"}}",
                // control character in a string value
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.insert\","
                        + "\"params\":{\"title\":\"two\\nlines\"}}",
                // unsafe key shape
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.insert\","
                        + "\"params\":{\"title[$ne]\":\"v\"}}",
                // an unknown top-level field alongside params
                "{\"v\":1,\"id\":\"7\",\"token\":\"x\",\"method\":\"calendar.insert\","
                        + "\"params\":{\"title\":\"v\"},\"extra\":true}"
        };
        for (String raw : rejected) {
            assertNull("must reject " + raw,
                    AndroidCapabilityProtocol.decodeRequest(raw));
        }
    }

    @Test
    public void rejectsResponseEnvelopeCollisionsAndUnsupportedValues() {
        Map<String, Object> collision = new LinkedHashMap<>();
        collision.put("ok", 1L);
        try {
            AndroidCapabilityProtocol.encodeResponse(
                    AndroidCapabilityProtocol.Response.success("1", collision));
            assertTrue("expected collision rejection", false);
        } catch (IllegalArgumentException expected) {
            // expected
        }

        Map<String, Object> unsupported = new LinkedHashMap<>();
        unsupported.put("nested", new Object());
        try {
            AndroidCapabilityProtocol.encodeResponse(
                    AndroidCapabilityProtocol.Response.success("1", unsupported));
            assertTrue("expected unsupported-value rejection", false);
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertFalse(AndroidCapabilityProtocol.encodeResponse(
                AndroidCapabilityProtocol.Response.error("1", "x")).isEmpty());
    }
}
