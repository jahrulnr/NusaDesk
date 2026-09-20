package gh.nusashell.nusadesk.infrastructure.backup;

import gh.nusashell.nusadesk.domain.backup.BackupManifest;
import gh.nusashell.nusadesk.domain.backup.BackupMode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code org.json} codec for the {@code manifest.json} entry that leads every
 * backup archive. The manifest is a flat object; every required field must be
 * present with the right type or the decode is {@link Malformed}.
 */
public final class BackupManifestCodec {

    /** Tar entry name of the manifest, always the first entry in an archive. */
    public static final String ENTRY_NAME = "manifest.json";

    /** The manifest existed but could not be understood as format v1. */
    public static final class Malformed extends Exception {
        Malformed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private BackupManifestCodec() {
    }

    public static byte[] encode(BackupManifest manifest) {
        try {
            JSONArray roots = new JSONArray();
            for (String root : manifest.getRoots()) {
                roots.put(root);
            }
            JSONObject json = new JSONObject()
                    .put("formatVersion", manifest.getFormatVersion())
                    .put("mode", manifest.getMode().getWireValue())
                    .put("runtimeAppId", manifest.getRuntimeAppId())
                    .put("runtimeVersion", manifest.getRuntimeVersion())
                    .put("appVersion", manifest.getAppVersion())
                    .put("createdAtEpochMs", manifest.getCreatedAtEpochMs())
                    .put("roots", roots)
                    .put("entries", manifest.getEntries())
                    .put("totalBytes", manifest.getTotalBytes());
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            // Value objects validate their invariants, so encode cannot fail
            // on a well-formed manifest; a JSONException here is a platform bug.
            throw new IllegalStateException("could not encode backup manifest", exception);
        }
    }

    public static BackupManifest decode(byte[] jsonBytes) throws Malformed {
        try {
            JSONObject json = new JSONObject(new String(jsonBytes, StandardCharsets.UTF_8));
            int formatVersion = json.getInt("formatVersion");
            if (formatVersion != BackupManifest.FORMAT_VERSION) {
                throw new Malformed("unsupported backup format version " + formatVersion, null);
            }
            BackupMode mode = BackupMode.fromWireValue(json.getString("mode"));
            if (mode == null) {
                throw new Malformed("unknown backup mode", null);
            }
            List<String> roots = new ArrayList<>();
            JSONArray rootsJson = json.optJSONArray("roots");
            if (rootsJson != null) {
                for (int i = 0; i < rootsJson.length(); i++) {
                    roots.add(rootsJson.getString(i));
                }
            }
            return new BackupManifest(
                    formatVersion,
                    mode,
                    json.getString("runtimeAppId"),
                    json.getString("runtimeVersion"),
                    json.getString("appVersion"),
                    json.getLong("createdAtEpochMs"),
                    roots,
                    json.getLong("entries"),
                    json.getLong("totalBytes"));
        } catch (JSONException | IllegalArgumentException exception) {
            throw new Malformed("backup manifest is not readable", exception);
        }
    }
}
