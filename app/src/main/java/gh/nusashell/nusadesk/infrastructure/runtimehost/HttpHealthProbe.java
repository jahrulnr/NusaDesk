package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.io.IOException;

/**
 * Port that performs a single bounded HTTP GET against the guest runtime's
 * health endpoint and returns the HTTP status code.
 *
 * <p>Kept as a small interface local to the runtimehost package so the
 * {@link ReadinessHealthVerifier} logic can be unit-tested with a pure fake
 * instead of a real network stack or a fake Linux process. The concrete
 * {@link HttpUrlConnectionHealthProbe} uses {@link java.net.HttpURLConnection}.</p>
 *
 * <p>Implementations must not place secrets (tokens, credentials) in the URL or
 * query string. The health path is a fixed, secret-free path supplied by the
 * verifier; sensitive endpoints add an app token through a separate header
 * boundary, not the URL.</p>
 */
public interface HttpHealthProbe {
    /**
     * Issue a bounded GET to {@code <scheme>://<host>:<port><path>}.
     *
     * @param scheme                {@code http} or {@code https}
     * @param host                  loopback host
     * @param port                  concrete port published by the guest
     * @param path                  secret-free path beginning with {@code /}
     * @param connectTimeoutMillis  connect timeout in milliseconds; non-negative
     * @param readTimeoutMillis     read timeout in milliseconds; non-negative
     * @return the HTTP status code
     * @throws IOException if the endpoint is unreachable or the request times out
     */
    int probe(String scheme, String host, int port, String path,
              int connectTimeoutMillis, int readTimeoutMillis) throws IOException;
}
