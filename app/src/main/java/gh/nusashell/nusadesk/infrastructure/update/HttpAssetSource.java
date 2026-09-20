package gh.nusashell.nusadesk.infrastructure.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Android-facing {@link ApkDownloader.UpdateSource}: one HTTPS release
 * asset (github.com {@code .../releases/download/...}), opened once and
 * reused for both the expected-length probe and the stream.
 *
 * <p>The asset path is the release channel's own download endpoint, not the
 * JSON API: it does not draw on the unauthenticated 60/hour API budget that
 * shared-egress tunnels (Cloudflare WARP was observed on the test device)
 routinely exhaust. Redirects are followed to the CDN object.</p>
 */
public final class HttpAssetSource implements ApkDownloader.UpdateSource {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private final String url;
    private HttpURLConnection connection;

    public HttpAssetSource(String url) {
        this.url = url;
    }

    @Override
    public long expectedLength() throws IOException {
        ensureConnected();
        long length = connection.getContentLengthLong();
        return length >= 0 ? length : -1L;
    }

    @Override
    public InputStream open() throws IOException {
        ensureConnected();
        return connection.getInputStream();
    }

    private void ensureConnected() throws IOException {
        if (connection != null) {
            return;
        }
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "NusaDesk");
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
    }
}
