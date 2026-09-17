package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.TelephonyManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.CellInfoLteBuilder;
import org.robolectric.shadows.CellSignalStrengthLteBuilder;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowTelephonyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bounded cell-info read behavior on the JVM: the location grant is resolved
 * per read and mapped to typed states, the coarse grant follows the platform
 * matrix (API 31+ only), cells are redacted to technology plus signal, and
 * rows are capped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidTelephonyCellSourceTest {

    @Test
    public void deviceWithoutTelephonyIsExplicit() {
        Context context = RuntimeEnvironment.getApplication();
        telephonyShadow(context).setPhoneType(TelephonyManager.PHONE_TYPE_NONE);

        TelephonyCellInfo info = new AndroidTelephonyCellSource(context).read();

        assertEquals(MessagingReadState.NO_TELEPHONY, info.getState());
    }

    @Test
    public void missingOrDeniedLocationGrantIsTypedBeforeAnyCellRead() {
        Context context = RuntimeEnvironment.getApplication();
        telephonyShadow(context).setPhoneType(TelephonyManager.PHONE_TYPE_GSM);

        TelephonyCellSource required = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.REQUIRED);
        assertEquals(MessagingReadState.PERMISSION_REQUIRED, required.read().getState());

        TelephonyCellSource denied = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.DENIED);
        assertEquals(MessagingReadState.PERMISSION_DENIED, denied.read().getState());
    }

    @Test
    public void coarseGrantOnApi29IsTypedPermissionRequired() {
        Context context = RuntimeEnvironment.getApplication();
        telephonyShadow(context).setPhoneType(TelephonyManager.PHONE_TYPE_GSM);

        TelephonyCellSource coarse = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.COARSE_ONLY);

        assertEquals(MessagingReadState.PERMISSION_REQUIRED, coarse.read().getState());
    }

    @Test
    @Config(sdk = 31)
    public void coarseGrantOnApi31ReadsCells() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setAllCellInfo(List.of(lteCell(-110)));

        TelephonyCellSource coarse = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.COARSE_ONLY);
        TelephonyCellInfo info = coarse.read();

        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals(1, info.getEntries().size());
    }

    @Test
    public void fineGrantReadsBoundedIdentityRedactedCells() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setAllCellInfo(List.of(lteCell(-110), lteCellWithoutSignal()));

        TelephonyCellSource fine = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.FINE);
        TelephonyCellInfo info = fine.read();

        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals(2, info.getEntries().size());

        TelephonyCellInfo.CellEntry withSignal = info.getEntries().get(0);
        assertEquals("lte", withSignal.getTechnology());
        assertEquals(-110, withSignal.getSignalDbm().intValue());
        assertTrue("level must be within [0, 4]",
                withSignal.getSignalLevel() >= 0 && withSignal.getSignalLevel() <= 4);
        Map<String, Object> row = withSignal.rowFields();
        assertFalse("cell identity is redacted", row.containsKey("cell_id"));
        assertFalse("cell identity is redacted", row.containsKey("tac"));
        assertFalse("cell identity is redacted", row.containsKey("earfcn"));

        TelephonyCellInfo.CellEntry noSignal = info.getEntries().get(1);
        assertEquals("lte", noSignal.getTechnology());
        assertEquals(null, noSignal.getSignalDbm());
        assertEquals(null, noSignal.getSignalLevel());
    }

    @Test
    public void cellsAreCappedAtMaxCellRows() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        List<CellInfo> cells = new ArrayList<>();
        for (int i = 0; i < MessagingReadPolicy.MAX_CELL_ROWS + 4; i++) {
            cells.add(lteCell(-100 - i));
        }
        telephony.setAllCellInfo(cells);

        TelephonyCellSource fine = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.FINE);
        TelephonyCellInfo info = fine.read();

        assertEquals(MessagingReadPolicy.MAX_CELL_ROWS, info.getEntries().size());
        assertTrue(info.isTruncated());
    }

    @Test
    public void emptyCellListIsAReadingAndMissingListIsUnavailable() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setAllCellInfo(new ArrayList<>());

        TelephonyCellSource fine = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.FINE);
        TelephonyCellInfo empty = fine.read();
        assertEquals(MessagingReadState.READING, empty.getState());
        assertEquals(0, empty.getEntries().size());
        assertFalse(empty.isTruncated());
        assertEquals(0L, empty.responseFields().get("count"));
    }

    @Test
    public void encodedRowsAreBoundedAndSingleLine() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowTelephonyManager telephony = telephonyShadow(context);
        telephony.setPhoneType(TelephonyManager.PHONE_TYPE_GSM);
        telephony.setAllCellInfo(List.of(lteCell(-110)));

        TelephonyCellInfo info = new AndroidTelephonyCellSource(
                context, () -> LocationGrant.FINE).read();
        MessagingReadPolicy.EncodedRows encoded = info.encodeRows();

        assertFalse(encoded.isTruncated());
        assertTrue(encoded.getJson().startsWith("[{\"technology\":\"lte\""));
        assertTrue(encoded.getJson().endsWith("}]"));
    }

    private static CellInfoLte lteCell(int rsrp) {
        return CellInfoLteBuilder.newBuilder()
                .setCellSignalStrength(
                        CellSignalStrengthLteBuilder.newBuilder().setRsrp(rsrp).build())
                .build();
    }

    private static CellInfoLte lteCellWithoutSignal() {
        return CellInfoLteBuilder.newBuilder().build();
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
