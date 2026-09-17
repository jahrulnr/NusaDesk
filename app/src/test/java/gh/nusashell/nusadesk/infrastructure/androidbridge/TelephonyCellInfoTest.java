package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TelephonyCellInfoTest {

    private static TelephonyCellInfo.CellEntry cell(String technology) {
        return new TelephonyCellInfo.CellEntry(technology, -110, 3);
    }

    @Test
    public void readingMapsBoundedEnvelopeFields() {
        TelephonyCellInfo info = TelephonyCellInfo.reading(
                List.of(cell("lte")), false);
        assertEquals(MessagingReadState.READING, info.getState());
        assertEquals(1, info.getEntries().size());

        Map<String, Object> fields = info.responseFields();
        assertEquals(true, fields.get("available"));
        assertEquals(1L, fields.get("count"));
        assertEquals(false, fields.get("truncated"));
    }

    @Test
    public void readingCapsRowsAtMaxCellRowsAndReportsTruncation() {
        List<TelephonyCellInfo.CellEntry> entries = new ArrayList<>();
        for (int i = 0; i < MessagingReadPolicy.MAX_CELL_ROWS + 5; i++) {
            entries.add(cell("gsm"));
        }
        TelephonyCellInfo info = TelephonyCellInfo.reading(entries, false);
        assertEquals(MessagingReadPolicy.MAX_CELL_ROWS, info.getEntries().size());
        assertTrue(info.isTruncated());
    }

    @Test
    public void nonReadingStatesAreExplicit() {
        assertEquals(MessagingReadState.PERMISSION_REQUIRED,
                TelephonyCellInfo.permissionRequired().getState());
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                TelephonyCellInfo.permissionDenied().getState());
        assertEquals(MessagingReadState.NO_TELEPHONY,
                TelephonyCellInfo.noTelephony().getState());
        assertEquals(MessagingReadState.UNAVAILABLE,
                TelephonyCellInfo.unavailable().getState());
        assertEquals(MessagingReadState.ERROR,
                TelephonyCellInfo.error().getState());
        assertThrows(IllegalStateException.class,
                () -> TelephonyCellInfo.noTelephony().responseFields());
        assertThrows(IllegalStateException.class,
                () -> TelephonyCellInfo.noTelephony().encodeRows());
    }

    @Test
    public void cellEntryRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new TelephonyCellInfo.CellEntry("  ", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TelephonyCellInfo.CellEntry("lte", 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TelephonyCellInfo.CellEntry("lte", -150, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TelephonyCellInfo.CellEntry("lte", -110, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new TelephonyCellInfo.CellEntry("lte", -110, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TelephonyCellInfo.CellEntry("lte", null, 3));
    }

    @Test
    public void rowFieldsRedactIdentityAndCarrySignalTogether() {
        Map<String, Object> row = cell("lte").rowFields();
        assertEquals("lte", row.get("technology"));
        assertEquals(-110L, row.get("signal_dbm"));
        assertEquals(3L, row.get("signal_level"));
        assertFalse("cell identity is precise location data and must be redacted",
                row.containsKey("cell_id"));
        assertFalse("cell identity is precise location data and must be redacted",
                row.containsKey("lac"));
        assertFalse("cell identity is precise location data and must be redacted",
                row.containsKey("tac"));

        Map<String, Object> noSignal = new TelephonyCellInfo.CellEntry(
                "gsm", null, null).rowFields();
        assertEquals("gsm", noSignal.get("technology"));
        assertFalse(noSignal.containsKey("signal_dbm"));
        assertFalse(noSignal.containsKey("signal_level"));
    }

    @Test
    public void encodeRowsIsBoundedAndSingleLine() {
        TelephonyCellInfo info = TelephonyCellInfo.reading(List.of(cell("lte")), false);
        MessagingReadPolicy.EncodedRows encoded = info.encodeRows();
        assertFalse(encoded.isTruncated());
        assertEquals("[{\"technology\":\"lte\",\"signal_dbm\":-110,\"signal_level\":3}]",
                encoded.getJson());
    }
}
