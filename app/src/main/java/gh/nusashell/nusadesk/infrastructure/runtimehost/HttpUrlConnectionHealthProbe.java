package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * {@link HttpHealthProbe} backed by {@link HttpURLConnection}.
 *
 * <p>Performs a single GET with explicit connect/read timeouts, no redirects,
 * and {@code Connection: close}. No secrets are placed in the URL; the path is
 * provided by the caller and must be a secret-free health path. The connection
 * is always disconnected in the {@code finally} block to avoid leaking sockets.</p>
 */
public final class HttpUrlConnectionHealthProbe implements HttpHealthProbe {

    @Override
    public int probe(String scheme, String host, int port, String path,
                     int connectTimeoutMillis, int readTimeoutMillis) throws IOException {
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("scheme must be http or https");
        }
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/'");
        }
        if (connectTimeoutMillis < 0 || readTimeoutMillis < 0) {
            throw new IllegalArgumentException("timeouts must not be negative");
        }
        String hostPart = "::1".equals(host) ? "[" + host + "]" : host;
        URL url = new URL(scheme + "://" + hostPart + ":" + port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Connection", "close");
            connection.setUseCaches(false);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
