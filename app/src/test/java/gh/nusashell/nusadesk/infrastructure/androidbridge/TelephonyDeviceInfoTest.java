package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class TelephonyDeviceInfoTest {

    @Test
    public void readingMapsBaseAndPermissionGatedFields() {
        TelephonyDeviceInfo info = TelephonyDeviceInfo.reading(
                "gsm", "ready", "lte", "nr");

        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals("gsm", info.getPhoneType());
        assertEquals("ready", info.getSimState());
        assertEquals("lte", info.getNetworkType());
        assertEquals("nr", info.getDataNetworkType());

        Map<String, Object> fields = info.responseFields();
        assertEquals(true, fields.get("available"));
        assertEquals("gsm", fields.get("phone_type"));
        assertEquals("ready", fields.get("sim_state"));
        assertEquals("lte", fields.get("network_type"));
        assertEquals("nr", fields.get("data_network_type"));
    }

    @Test
    public void readingOmitsPermissionGatedFieldsWhenNotGranted() {
        TelephonyDeviceInfo info = TelephonyDeviceInfo.reading("gsm", "ready", null, null);
        assertNull(info.getNetworkType());
        assertNull(info.getDataNetworkType());

        Map<String, Object> fields = info.responseFields();
        assertFalse("permission-gated fields are never fabricated",
                fields.containsKey("network_type"));
        assertFalse("permission-gated fields are never fabricated",
                fields.containsKey("data_network_type"));
    }

    @Test
    public void noTelephonyIsExplicit() {
        TelephonyDeviceInfo info = TelephonyDeviceInfo.noTelephony();
        assertEquals(MessagingReadState.NO_TELEPHONY, info.getState());
        assertEquals("none", info.getPhoneType());
        assertThrows(IllegalStateException.class, info::responseFields);
    }

    @Test
    public void nonReadingStatesAreExplicit() {
        assertEquals(MessagingReadState.PERMISSION_REQUIRED,
                TelephonyDeviceInfo.permissionRequired().getState());
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                TelephonyDeviceInfo.permissionDenied().getState());
        assertEquals(MessagingReadState.UNAVAILABLE,
                TelephonyDeviceInfo.unavailable().getState());
        assertEquals(MessagingReadState.ERROR,
                TelephonyDeviceInfo.error().getState());
        assertThrows(IllegalStateException.class,
                () -> TelephonyDeviceInfo.unavailable().responseFields());
    }

    @Test
    public void rejectsBlankRequiredFieldsAndUnboundedText() {
        assertThrows(IllegalArgumentException.class,
                () -> TelephonyDeviceInfo.reading("  ", "ready", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> TelephonyDeviceInfo.reading("gsm", null, null, null));
        StringBuilder longType = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longType.append('n');
        }
        TelephonyDeviceInfo capped = TelephonyDeviceInfo.reading(
                longType.toString(), longType.toString(), longType.toString(), null);
        assertEquals(16, capped.getPhoneType().length());
        assertEquals(32, capped.getSimState().length());
        assertEquals(32, capped.getNetworkType().length());
    }
}
