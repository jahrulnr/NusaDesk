package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * Immutable, non-secret configuration for one outbound SSH shell session.
 *
 * <p>Carries only connection coordinates and timeouts. Secrets (passwords and
 * private keys) never live here: they are sourced on demand from a
 * {@link SshCredentialProvider} so that sensitive buffers can be zeroed
 * immediately after use. The {@code credentialId} is a non-secret handle the
 * bridge resolves through the credential vault adapter.</p>
 *
 * <p>Validation is enforced at construction so the bridge cannot be asked to
 * dial an invalid endpoint. The host is an opaque string (DNS name or literal
 * address) because this type is the generic client primitive, not a product
 * surface: the app has exactly one SSH endpoint — the in-app guest Linux on the
 * fixed loopback port — and production code must build its configuration
 * through {@link LocalSshSessionFactory}, which accepts neither a host nor a
 * port (ADR-0013). This constructor remains for internal and test use.</p>
 */
public final class SshSessionConfig {
    /** Default PTY type requested for the shell channel. */
    public static final String DEFAULT_TERMINAL_TYPE = "xterm-256color";

    private final String host;
    private final int port;
    private final String username;
    private final String credentialId;
    private final int connectTimeoutMillis;
    private final int authTimeoutMillis;
    private final int channelOpenTimeoutMillis;
    private final int keepAliveIntervalSeconds;
    private final String terminalType;
    private final int initialCols;
    private final int initialRows;

    public SshSessionConfig(
            String host,
            int port,
            String username,
            String credentialId,
            int connectTimeoutMillis,
            int authTimeoutMillis,
            int channelOpenTimeoutMillis,
            int keepAliveIntervalSeconds,
            String terminalType,
            int initialCols,
            int initialRows) {
        this.host = requireNonBlank(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.username = requireNonBlank(username, "username");
        this.credentialId = requireNonBlank(credentialId, "credentialId");
        if (connectTimeoutMillis < 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must not be negative");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        if (authTimeoutMillis < 0) {
            throw new IllegalArgumentException("authTimeoutMillis must not be negative");
        }
        this.authTimeoutMillis = authTimeoutMillis;
        if (channelOpenTimeoutMillis < 0) {
            throw new IllegalArgumentException("channelOpenTimeoutMillis must not be negative");
        }
        this.channelOpenTimeoutMillis = channelOpenTimeoutMillis;
        if (keepAliveIntervalSeconds < 0) {
            throw new IllegalArgumentException("keepAliveIntervalSeconds must not be negative");
        }
        this.keepAliveIntervalSeconds = keepAliveIntervalSeconds;
        this.terminalType = requireNonBlank(terminalType, "terminalType");
        if (initialCols < 1 || initialCols > 1024) {
            throw new IllegalArgumentException("initialCols must be between 1 and 1024");
        }
        this.initialCols = initialCols;
        if (initialRows < 1 || initialRows > 1024) {
            throw new IllegalArgumentException("initialRows must be between 1 and 1024");
        }
        this.initialRows = initialRows;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getAuthTimeoutMillis() {
        return authTimeoutMillis;
    }

    public int getChannelOpenTimeoutMillis() {
        return channelOpenTimeoutMillis;
    }

    public int getKeepAliveIntervalSeconds() {
        return keepAliveIntervalSeconds;
    }

    public String getTerminalType() {
        return terminalType;
    }

    public int getInitialCols() {
        return initialCols;
    }

    public int getInitialRows() {
        return initialRows;
    }

    /** Host identity used for host-key trust scoping (host:port). */
    public String hostKeyScope() {
        return host + ":" + port;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
