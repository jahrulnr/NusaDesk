package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable bounded snapshot of visible cell towers, kept Android-free for
 * testing.
 *
 * <p>Only the minimal fields of the contract cross this boundary: radio
 * technology and, when the platform carried a valid signal, the signal in
 * dBm and its 0-4 level. Cell identity (CID, LAC/TAC, PCI/PSC, ARFCN/EARFCN,
 * timing advance) is precise location data and is deliberately redacted, as
 * is the registered/camped state. Rows are capped at
 * {@link MessagingReadPolicy#MAX_CELL_ROWS}; any capping sets
 * {@link #isTruncated()}. Reading cell info needs a location grant, so
 * permission, unavailable, no-telephony, and error states are explicit value
 * objects; the bridge encodes only a {@link MessagingReadState#READING}
 * snapshot with fields and reports every other state as a typed error.</p>
 */
public final class TelephonyCellInfo {
    private final MessagingReadState state;
    private final List<CellEntry> entries;
    private final boolean truncated;

    private TelephonyCellInfo(MessagingReadState state, List<CellEntry> entries,
                              boolean truncated) {
        this.state = state;
        List<CellEntry> bounded = new ArrayList<>();
        boolean capped = truncated;
        if (entries != null) {
            for (CellEntry entry : entries) {
                if (bounded.size() >= MessagingReadPolicy.MAX_CELL_ROWS) {
                    capped = true;
                    break;
                }
                bounded.add(entry);
            }
        }
        this.entries = Collections.unmodifiableList(bounded);
        this.truncated = capped;
    }

    /** A bounded cell list; extra rows are dropped and reported via {@link #isTruncated()}. */
    public static TelephonyCellInfo reading(List<CellEntry> entries, boolean truncated) {
        return new TelephonyCellInfo(MessagingReadState.READING, entries, truncated);
    }

    /** No location grant and no recorded denial; the later consent flow may ask. */
    public static TelephonyCellInfo permissionRequired() {
        return new TelephonyCellInfo(MessagingReadState.PERMISSION_REQUIRED, null, false);
    }

    /** No location grant and the user previously denied it. */
    public static TelephonyCellInfo permissionDenied() {
        return new TelephonyCellInfo(MessagingReadState.PERMISSION_DENIED, null, false);
    }

    /** No telephony radio on this device. */
    public static TelephonyCellInfo noTelephony() {
        return new TelephonyCellInfo(MessagingReadState.NO_TELEPHONY, null, false);
    }

    /** The telephony service is absent or returned no cell list. */
    public static TelephonyCellInfo unavailable() {
        return new TelephonyCellInfo(MessagingReadState.UNAVAILABLE, null, false);
    }

    /** The platform rejected or corrupted the read; never fabricate values. */
    public static TelephonyCellInfo error() {
        return new TelephonyCellInfo(MessagingReadState.ERROR, null, false);
    }

    public MessagingReadState getState() {
        return state;
    }

    /** Bounded cell entries; empty unless {@link MessagingReadState#READING}. */
    public List<CellEntry> getEntries() {
        return entries;
    }

    /** True when rows were dropped to enforce the bounds. */
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
     * The bounded row array for the RPC payload. Rows that do not fit the
     * frame budget are dropped deterministically and reported through
     * {@link MessagingReadPolicy.EncodedRows#isTruncated()}.
     */
    public MessagingReadPolicy.EncodedRows encodeRows() {
        if (state != MessagingReadState.READING) {
            throw new IllegalStateException("rows are defined only for a reading");
        }
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (CellEntry entry : entries) {
            rows.add(entry.rowFields());
        }
        return MessagingReadPolicy.encodeRows(rows);
    }

    /** One bounded, identity-redacted cell entry. */
    public static final class CellEntry {
        private final String technology;
        private final Integer signalDbm;
        private final Integer signalLevel;

        /**
         * @param technology lower-case radio technology name
         * @param signalDbm  signal strength in dBm, or {@code null} when the
         *                   platform did not carry a valid value
         * @param signalLevel 0-4 signal level, or {@code null} when the
         *                    signal strength is absent; both are carried
         *                    together or omitted together
         * @throws IllegalArgumentException on blank technology, an invalid
         *                                  signal value, or a level without a
         *                                  signal
         */
        public CellEntry(String technology, Integer signalDbm, Integer signalLevel) {
            if (technology == null || technology.trim().isEmpty()) {
                throw new IllegalArgumentException("technology must not be blank");
            }
            if (signalDbm != null && (signalDbm >= 0 || signalDbm <= -150)) {
                throw new IllegalArgumentException("signalDbm must be within (-150, 0)");
            }
            if (signalLevel != null && (signalLevel < 0 || signalLevel > 4)) {
                throw new IllegalArgumentException("signalLevel must be within [0, 4]");
            }
            if ((signalDbm == null) != (signalLevel == null)) {
                throw new IllegalArgumentException("signal and level must be carried together");
            }
            this.technology = MessagingReadPolicy.truncate(technology.trim(), 16);
            this.signalDbm = signalDbm;
            this.signalLevel = signalLevel;
        }

        public String getTechnology() {
            return technology;
        }

        /** {@code null} when the platform did not carry a valid signal. */
        public Integer getSignalDbm() {
            return signalDbm;
        }

        /** {@code null} when the platform did not carry a valid signal. */
        public Integer getSignalLevel() {
            return signalLevel;
        }

        /** Flat, bounded row fields; signal fields are omitted when absent. */
        public Map<String, Object> rowFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("technology", technology);
            if (signalDbm != null) {
                fields.put("signal_dbm", (long) signalDbm);
                fields.put("signal_level", (long) signalLevel);
            }
            return fields;
        }
    }
}
