package gh.nusashell.nusadesk.infrastructure.sshserver;

/**
 * Bounded health check confirming the SSH bridge server is actually accepting
 * connections after bind.
 *
 * <p>Readiness means the advertised health check succeeds, not merely that
 * {@code start()} returned (AGENTS.md). The bridge calls this after the MINA
 * server binds the loopback port and publishes a readiness frame only when it
 * returns {@code true}. The default {@link JdkTcpHealthProbe} opens a TCP
 * connection to the bound loopback port; tests can substitute a fake.</p>
 */
@FunctionalInterface
public interface SshBridgeHealthProbe {

    /**
     * @param host          loopback host the server bound
     * @param port          concrete port the server bound
     * @param timeoutMillis maximum time to wait for a connection
     * @return {@code true} if the server is accepting connections on the endpoint
     */
    boolean isHealthy(String host, int port, long timeoutMillis);
}
