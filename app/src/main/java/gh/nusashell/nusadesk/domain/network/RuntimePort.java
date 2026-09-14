package gh.nusashell.nusadesk.domain.network;

/**
 * A validated loopback endpoint owned by the Android runtime host.
 *
 * <p>Port zero is deliberately not accepted here: a concrete endpoint is
 * published only after the child process has bound and passed readiness.</p>
 */
public final class RuntimePort {
    private final String host;
    private final int port;

    public RuntimePort(String host, int port) {
        if (!isLoopbackHost(host)) {
            throw new IllegalArgumentException("runtime endpoint must be loopback");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String toUrl(String scheme) {
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException("scheme must be http or https");
        }
        String hostPart = "::1".equals(host) ? "[" + host + "]" : host;
        return scheme + "://" + hostPart + ":" + port;
    }

    private static boolean isLoopbackHost(String value) {
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
    }
}
