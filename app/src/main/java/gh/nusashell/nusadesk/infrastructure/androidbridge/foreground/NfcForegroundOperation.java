package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NFC read/write operation for the {@code nfc.read} / {@code nfc.write}
 * bridge methods ({@code termux-nfc}), kind {@code nfc}.
 *
 * <p>{@link NfcAdapter#enableReaderMode} requires a resumed activity, so the
 * operation arms reader mode from a main-thread runnable posted during
 * {@link #run} — retrying until the activity is resumed, bounded — then waits
 * for the tag callback. A discovered tag is handled on the reader callback
 * thread: {@code read} connects and encodes the NDEF payload into the
 * upstream JSON shape ({@code short}: {@code {"Record":{"Payload":..}}};
 * {@code full}: id/typeTag/maxSize/techList plus the {@code record} set);
 * {@code write} connects and writes one UTF-8 {@code RTD_TEXT} record
 * ("en"), like upstream's {@code createTextRecord}. Every path reports once
 * through the sink and disables reader mode before the activity finishes;
 * the host's bounded wait covers "no tag ever arrives".</p>
 */
public final class NfcForegroundOperation implements ForegroundOperation {

    /** Catalog kind the module parks operations under. */
    public static final String KIND = "nfc";

    private static final String TAG_LOG = "NfcForegroundOperation";

    /** Reader-mode tech set: every tag family that can carry NDEF. */
    private static final int READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A
            | NfcAdapter.FLAG_READER_NFC_B
            | NfcAdapter.FLAG_READER_NFC_F
            | NfcAdapter.FLAG_READER_NFC_V
            | NfcAdapter.FLAG_READER_NFC_BARCODE;

    /** Bound on the write payload, matching the module's param bound. */
    private static final int TEXT_MAX_CHARS = 4096;
    /** Bound on a rendered payload string inside the JSON field. */
    private static final int PAYLOAD_MAX_CHARS = 4096;
    /** Bound on records rendered into one tag_json value. */
    private static final int MAX_RECORDS = 16;
    /** Bound on the whole tag_json field, inside the wire budget. */
    private static final int TAG_JSON_MAX_CHARS = 12 * 1024;

    private static final long RESUME_RETRY_MILLIS = 50L;
    private static final int RESUME_RETRY_ATTEMPTS = 40;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        String op = stringParam(params, "op", "");
        if (!"read".equals(op) && !"write".equals(op)) {
            sink.error("invalid-argument");
            return;
        }
        String mode = stringParam(params, "mode", "short");
        if (!"short".equals(mode) && !"full".equals(mode)) {
            sink.error("invalid-argument");
            return;
        }
        String text = stringParam(params, "text", "");
        if ("write".equals(op) && text.length() > TEXT_MAX_CHARS) {
            sink.error("invalid-argument");
            return;
        }
        NfcAdapter adapter;
        try {
            adapter = NfcAdapter.getDefaultAdapter(activity);
        } catch (RuntimeException e) {
            Log.w(TAG_LOG, "nfc adapter lookup failed", e);
            sink.error("nfc-unavailable");
            return;
        }
        if (adapter == null) {
            sink.error("nfc-unavailable");
            return;
        }
        boolean enabled;
        try {
            enabled = adapter.isEnabled();
        } catch (RuntimeException e) {
            sink.error("nfc-unavailable");
            return;
        }
        if (!enabled) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("nfc_present", true);
            fields.put("nfc_enabled", false);
            sink.success(fields);
            return;
        }
        armReaderMode(activity, adapter, op, mode, text, sink,
                RESUME_RETRY_ATTEMPTS);
    }

    /**
     * Enable reader mode once the activity is resumed. {@code run} executes
     * inside {@code onCreate} — before {@code onResume} — and the platform
     * rejects reader mode on a non-resumed activity, so this retries on the
     * main looper for a bounded window; the host's operation timeout is the
     * outer bound anyway.
     */
    private static void armReaderMode(CapabilityForegroundActivity activity,
                                      NfcAdapter adapter, String op, String mode,
                                      String text, ResultSink sink, int attemptsLeft) {
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return; // the host already settled this operation
            }
            try {
                adapter.enableReaderMode(activity,
                        tag -> handleTag(activity, adapter, op, mode, text, tag, sink),
                        READER_FLAGS, null);
            } catch (RuntimeException e) {
                if (attemptsLeft > 1) {
                    armReaderMode(activity, adapter, op, mode, text, sink,
                            attemptsLeft - 1);
                } else {
                    Log.w(TAG_LOG, "nfc reader mode refused", e);
                    sink.error("nfc-unavailable");
                }
            }
        }, RESUME_RETRY_MILLIS);
    }

    /**
     * Handle one discovered tag on the reader callback thread, then settle
     * the operation exactly once. Reader mode is always disabled again — the
     * platform also releases it with the finishing activity, so both orders
     * stay safe.
     */
    private static void handleTag(CapabilityForegroundActivity activity,
                                  NfcAdapter adapter, String op, String mode,
                                  String text, Tag tag, ResultSink sink) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nfc_present", true);
        fields.put("nfc_enabled", true);
        String error = null;
        try {
            Ndef ndef = Ndef.get(tag);
            if (ndef == null) {
                error = "nfc-tag-not-ndef";
            } else {
                ndef.connect();
                try {
                    if ("write".equals(op)) {
                        error = writeTag(ndef, text);
                        if (error == null) {
                            fields.put("written", true);
                        }
                    } else {
                        String tagJson = tagJson(tag, ndef, "full".equals(mode));
                        if (tagJson == null) {
                            error = "nfc-read-failed";
                        } else {
                            fields.put("tag_json", tagJson);
                        }
                    }
                } finally {
                    try {
                        ndef.close();
                    } catch (IOException ignored) {
                        // Closing a lost tag is a no-op.
                    }
                }
            }
        } catch (IOException | android.nfc.FormatException | RuntimeException e) {
            Log.w(TAG_LOG, "nfc " + op + " failed", e);
            error = "write".equals(op) ? "nfc-write-failed" : "nfc-read-failed";
        }
        try {
            adapter.disableReaderMode(activity);
        } catch (RuntimeException ignored) {
            // The finishing activity releases reader mode anyway.
        }
        if (error == null) {
            sink.success(fields);
        } else {
            sink.error(error);
        }
    }

    /**
     * Write one UTF-8 {@code RTD_TEXT} record ("en") — the same message
     * upstream's {@code createTextRecord} builds. Returns {@code null} on
     * success or the typed error.
     */
    private static String writeTag(Ndef ndef, String text) throws IOException {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] language = "en".getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[1 + language.length + textBytes.length];
        payload[0] = (byte) language.length; // UTF-8 status byte + lang length
        System.arraycopy(language, 0, payload, 1, language.length);
        System.arraycopy(textBytes, 0, payload, 1 + language.length,
                textBytes.length);
        NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_TEXT, new byte[0], payload);
        NdefMessage message = new NdefMessage(new NdefRecord[]{record});
        try {
            if (!ndef.isWritable()) {
                return "nfc-tag-readonly";
            }
            int maxSize = ndef.getMaxSize();
            if (maxSize > 0 && message.toByteArray().length > maxSize) {
                return "nfc-write-too-large";
            }
            ndef.writeNdefMessage(message);
        } catch (android.nfc.FormatException e) {
            Log.w(TAG_LOG, "nfc write format refused", e);
            return "nfc-write-failed";
        }
        return null;
    }

    /**
     * Encode the tag's NDEF content in the upstream JSON shape. {@code short}
     * renders {@code {"Record":{"Payload":..}}} (an array for more than one
     * record); {@code full} renders {@code id}, {@code typeTag},
     * {@code maxSize}, {@code techList}, and {@code record} objects carrying
     * {@code type}/{@code tnf}/{@code URI?}/{@code mime}/{@code payload}.
     * Returns {@code null} when the rendered object would exceed the field
     * bound.
     */
    private static String tagJson(Tag tag, Ndef ndef, boolean full)
            throws IOException, android.nfc.FormatException {
        NdefMessage message = ndef.getNdefMessage();
        NdefRecord[] records = message == null
                ? new NdefRecord[0] : message.getRecords();
        StringBuilder out = new StringBuilder(512);
        out.append('{');
        if (full) {
            out.append("\"id\":").append(jsonString(hexId(tag)));
            out.append(",\"typeTag\":").append(jsonString(ndefType(ndef)));
            out.append(",\"maxSize\":").append(ndefMaxSize(ndef));
            out.append(",\"techList\":[");
            String[] techs = tag.getTechList();
            if (techs != null) {
                for (int i = 0; i < techs.length; i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(jsonString(techs[i]));
                }
            }
            out.append(']');
            out.append(",\"record\":");
            appendRecords(out, records, true);
        } else if (records.length > 0) {
            out.append("\"Record\":");
            appendRecords(out, records, false);
        }
        out.append('}');
        return out.length() <= TAG_JSON_MAX_CHARS ? out.toString() : null;
    }

    /** Render the record set: one object, or an array for multiple. */
    private static void appendRecords(StringBuilder out, NdefRecord[] records,
                                      boolean full) {
        int count = Math.min(records.length, MAX_RECORDS);
        if (count == 0) {
            out.append("[]");
            return;
        }
        boolean array = count > 1;
        if (array) {
            out.append('[');
        }
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(',');
            }
            appendRecord(out, records[i], full);
        }
        if (array) {
            out.append(']');
        }
    }

    private static void appendRecord(StringBuilder out, NdefRecord record,
                                     boolean full) {
        out.append('{');
        if (full) {
            out.append("\"type\":")
                    .append(jsonString(new String(record.getType(),
                            StandardCharsets.UTF_8)));
            out.append(",\"tnf\":").append(record.getTnf());
            android.net.Uri uri = record.toUri();
            if (uri != null) {
                out.append(",\"URI\":").append(jsonString(uri.toString()));
            }
            out.append(",\"mime\":").append(jsonString(record.toMimeType()));
            out.append(",\"payload\":").append(jsonString(payloadText(record)));
        } else {
            out.append("\"Payload\":").append(jsonString(payloadText(record)));
        }
        out.append('}');
    }

    /**
     * Decode a record payload to text like upstream: a TNF_WELL_KNOWN record
     * skips the status byte and language code ({@code payload[0]+1}); the
     * offset is masked and clamped so a malformed status byte can never walk
     * out of the array.
     */
    private static String payloadText(NdefRecord record) {
        byte[] payload = record.getPayload();
        if (payload == null || payload.length == 0) {
            return "";
        }
        int pos = record.getTnf() == NdefRecord.TNF_WELL_KNOWN
                ? (payload[0] & 0xFF) + 1 : 0;
        if (pos > payload.length) {
            pos = payload.length;
        }
        String text = new String(payload, pos, payload.length - pos,
                StandardCharsets.UTF_8);
        return text.length() > PAYLOAD_MAX_CHARS
                ? text.substring(0, PAYLOAD_MAX_CHARS) : text;
    }

    private static String hexId(Tag tag) {
        byte[] id = tag.getId();
        if (id == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(id.length * 2);
        for (byte part : id) {
            out.append(String.format("%02x", part & 0xFF));
        }
        return out.toString();
    }

    private static String ndefType(Ndef ndef) {
        try {
            return ndef.getType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int ndefMaxSize(Ndef ndef) {
        try {
            return ndef.getMaxSize();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String stringParam(Map<String, Object> params, String key,
                                      String fallback) {
        Object value = params == null ? null : params.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /** Minimal JSON string escaping for the pre-encoded tag field. */
    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }
}
