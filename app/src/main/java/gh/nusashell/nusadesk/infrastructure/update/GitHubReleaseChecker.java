package gh.nusashell.nusadesk.infrastructure.update;

import gh.nusashell.nusadesk.domain.update.ApkDigest;
import gh.nusashell.nusadesk.domain.update.ReleaseVersion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One-shot check for a newer NusaDesk release on the signed GitHub release
 * channel (ADR-0038).
 *
 * <p>The endpoint is the fixed {@code /releases/latest} API, which already
 * returns only non-draft, non-prerelease releases. The fields read are the
 * release identity ({@code tag_name}, {@code html_url}) plus the first
 * asset's {@code browser_download_url}, {@code digest} (the
 * {@code sha256:<hex>} value GitHub computes on the uploaded file), and
 * {@code size} — the identity carries the version contract ({@code vX.Y.Z}
 * equals {@code versionName}), and the assisted download/install flow needs
 * the asset's URL, checksum, and size. There is no silent self-update.</p>
 *
 * <p>The check is synchronous and side-effect free: the coordinator owns the
 * executor it runs on and the {@link UpdateCheckPrefs} throttle. Every failure
 * — no network, any non-200 response (403 rate limit, 404 no releases, 429),
 * an oversized or malformed body, an unparsable tag — maps to
 * {@link UpdateCheckResult.Kind#UNAVAILABLE}. The method never throws, so a
 * foreground caller can fire-and-forget without an error surface.</p>
 */
public final class GitHubReleaseChecker {

    /** GitHub API endpoint for the newest public release of this app. */
    public static final String RELEASES_API_URL =
            "https://api.github.com/repos/jahrulnr/NusaDesk/releases/latest";

    private static final int TIMEOUT_MILLIS = 10_000;
    private static final int MAX_BODY_BYTES = 64 * 1024;

    /**
     * Performs one check against {@link #RELEASES_API_URL}.
     *
     * <p>Blocking call with 10 s connect/read timeouts — must not run on the
     * main thread.</p>
     *
     * @param currentVersionName the installed app's {@code versionName}; an
     *                           unparsable value can never report an update
     * @return the typed outcome; never {@code null}, never throws
     */
    public UpdateCheckResult check(String currentVersionName) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "NusaDesk");
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return UpdateCheckResult.unavailable();
            }
            String body = readBounded(connection.getInputStream());
            return body == null
                    ? UpdateCheckResult.unavailable()
                    : parseRelease(currentVersionName, body);
        } catch (Exception e) {
            return UpdateCheckResult.unavailable();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Compares a {@code /releases/latest} body against the installed version.
     *
     * <p>Package-visible so the unit test can feed a realistic fixture without
     * network. A missing or unparsable {@code tag_name}, a missing or
     * non-HTTPS {@code html_url}, and malformed JSON are all
     * {@code UNAVAILABLE} rather than parse errors.</p>
     */
    static UpdateCheckResult parseRelease(String currentVersionName, String jsonBody) {
        try {
            JSONObject json = new JSONObject(jsonBody);
            ReleaseVersion latest = ReleaseVersion.parseOrNull(
                    json.optString("tag_name", ""));
            String releaseUrl = json.optString("html_url", "");
            if (latest == null || !releaseUrl.startsWith("https://")) {
                return UpdateCheckResult.unavailable();
            }
            String downloadUrl = null;
            String digest = null;
            long sizeBytes = -1L;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                JSONObject asset = assets.getJSONObject(0);
                // The asset is usable only as a complete triple: an HTTPS url
                // AND a well-formed channel digest. A missing or malformed
                // piece drops the whole asset to the browser hand-off.
                String url = asset.optString("browser_download_url", "");
                String hex = ApkDigest.normalize(asset.optString("digest", ""));
                if (url.startsWith("https://") && hex != null) {
                    downloadUrl = url;
                    digest = "sha256:" + hex;
                    sizeBytes = asset.optLong("size", -1L);
                }
            }
            return latest.isNewerThan(ReleaseVersion.parseOrNull(currentVersionName))
                    ? UpdateCheckResult.updateAvailable(json.getString("tag_name"), releaseUrl,
                            downloadUrl, digest, sizeBytes)
                    : UpdateCheckResult.upToDate();
        } catch (Exception e) {
            return UpdateCheckResult.unavailable();
        }
    }

    /** Reads at most {@link #MAX_BODY_BYTES}; returns {@code null} when exceeded. */
    private static String readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * Typed, immutable outcome of one update check.
     *
     * <p>{@link Kind#UNAVAILABLE} deliberately covers every failure mode: the
     * feature is silent, so the UI never has to render an update error and a
     * caller only needs to special-case {@link Kind#UPDATE_AVAILABLE}.</p>
     */
    public static final class UpdateCheckResult {

        /** What the check concluded. */
        public enum Kind {
            /** The latest release is not newer than the installed version. */
            UP_TO_DATE,
            /** A newer release exists; {@link #getTag()} and {@link #getReleaseUrl()} describe it. */
            UPDATE_AVAILABLE,
            /** The check could not complete or its payload was unusable. */
            UNAVAILABLE
        }

        private final Kind kind;
        private final String tag;
        private final String releaseUrl;
        private final String downloadUrl;
        private final String digest;
        private final long sizeBytes;

        private UpdateCheckResult(Kind kind, String tag, String releaseUrl,
                String downloadUrl, String digest, long sizeBytes) {
            this.kind = kind;
            this.tag = tag;
            this.releaseUrl = releaseUrl;
            this.downloadUrl = downloadUrl;
            this.digest = digest;
            this.sizeBytes = sizeBytes;
        }

        /** The installed version is current. */
        public static UpdateCheckResult upToDate() {
            return new UpdateCheckResult(Kind.UP_TO_DATE, null, null, null, null, -1L);
        }

        /**
         * A newer release exists; {@code downloadUrl}/{@code digest}/
         * {@code sizeBytes} may be null when the channel did not report a
         * usable first asset, in which case only the browser hand-off applies.
         */
        public static UpdateCheckResult updateAvailable(String tag, String releaseUrl,
                String downloadUrl, String digest, long sizeBytes) {
            return new UpdateCheckResult(Kind.UPDATE_AVAILABLE, tag, releaseUrl,
                    downloadUrl, digest, sizeBytes);
        }

        /** The check failed silently. */
        public static UpdateCheckResult unavailable() {
            return new UpdateCheckResult(Kind.UNAVAILABLE, null, null, null, null, -1L);
        }

        /** The outcome kind. */
        public Kind getKind() {
            return kind;
        }

        /** The release tag (e.g. {@code "v0.4.0"}); set only for {@link Kind#UPDATE_AVAILABLE}. */
        public String getTag() {
            return tag;
        }

        /** HTTPS release page to open in a browser; set only for {@link Kind#UPDATE_AVAILABLE}. */
        public String getReleaseUrl() {
            return releaseUrl;
        }

        /**
         * HTTPS URL of the first release asset's APK, or {@code null} when the
         * channel did not report one over HTTPS — in which case the assisted
         * install must refuse and only the browser hand-off remains.
         */
        public String getDownloadUrl() {
            return downloadUrl;
        }

        /**
         * The channel-computed {@code sha256:<hex>} digest of the asset, or
         * {@code null} when it was not reported; the assisted install never
         * stages without it.
         */
        public String getDigest() {
            return digest;
        }

        /** The asset size in bytes, or {@code -1L} when unknown. */
        public long getSizeBytes() {
            return sizeBytes;
        }
    }
}
