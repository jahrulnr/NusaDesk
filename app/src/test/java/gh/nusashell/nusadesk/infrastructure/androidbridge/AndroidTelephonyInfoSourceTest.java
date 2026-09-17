package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.telephony.TelephonyManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowTelephonyManager;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Device telephony snapshot behavior on the JVM: no telephony is an explicit
 * state, the base contract (phone type, SIM state) never needs a permission,
 * and network-type enrichment is carried only under the read-phone-state
 * grant and is otherwise omitted, never fabricated.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidTelephonyInfoSourceTest {

    @Test
    public void deviceWithoutTelephonyIsExplicit() {
        Context context = RuntimeEnvironment.getApplication();
        telephonyShadow(context).setPhoneType(TelephonyManager.PHONE_TYPE_NONE);

        TelephonyDeviceInfo info = new AndroidTelephonyInfoSource(context).read();

        assertEquals(MessagingReadState.NO_TELEPHONY, info.getState());
        assertEquals("none", info.getPhoneType());
    }

    @Test
    public void baseContractIsReadWithoutThePhoneStateGrant() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setSimState(TelephonyManager.SIM_STATE_READY);
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_PHONE_STATE);

        TelephonyDeviceInfo info = new AndroidTelephonyInfoSource(context).read();

        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals("gsm", info.getPhoneType());
        assertEquals("ready", info.getSimState());
        Map<String, Object> fields = info.responseFields();
        assertFalse("network type must not be fabricated without the grant",
                fields.containsKey("network_type"));
        assertFalse("data network type must not be fabricated without the grant",
                fields.containsKey("data_network_type"));
    }

    @Test
    public void networkFieldsAreCarriedUnderThePhoneStateGrant() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setSimState(TelephonyManager.SIM_STATE_READY);
        telephony.setNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        telephony.setDataNetworkType(TelephonyManager.NETWORK_TYPE_NR);
        Shadow.<ShadowContextWrapper>extract(context)
                .grantPermissions(Manifest.permission.READ_PHONE_STATE);

        TelephonyDeviceInfo info = new AndroidTelephonyInfoSource(context).read();

        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals("lte", info.getNetworkType());
        assertEquals("nr", info.getDataNetworkType());
        Map<String, Object> fields = info.responseFields();
        assertEquals("lte", fields.get("network_type"));
        assertEquals("nr", fields.get("data_network_type"));
        assertTrue(fields.containsKey("phone_type"));
        assertTrue(fields.containsKey("sim_state"));
    }

    @Test
    public void unknownSimAndNetworkValuesMapToUnknownNotCrash() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setSimState(TelephonyManager.SIM_STATE_UNKNOWN);
        telephony.setNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        Shadow.<ShadowContextWrapper>extract(context)
                .grantPermissions(Manifest.permission.READ_PHONE_STATE);

        TelephonyDeviceInfo info = new AndroidTelephonyInfoSource(context).read();

        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals("unknown", info.getSimState());
        assertEquals("unknown", info.getNetworkType());
    }

    private static ShadowTelephonyManager telephonyShadow(Context context) {
        TelephonyManager manager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        if (manager == null) {
            throw new AssertionError("Robolectric must provide a TelephonyManager");
        }
        return Shadow.extract(manager);
    }
}
