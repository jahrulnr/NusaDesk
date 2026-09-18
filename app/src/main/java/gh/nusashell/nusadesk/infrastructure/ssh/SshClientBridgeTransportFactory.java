package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;

import java.util.function.LongSupplier;

/**
 * Production {@link TerminalTransportFactory}: wraps a fresh
 * {@link SshClientBridge} configured for the fixed local endpoint.
 *
 * <p>The pinned-host-key-only trust policy comes from
 * {@link LocalSshSessionFactory#pinnedHostKeyOnly()}, so a foreign listener
 * holding the fixed port is refused rather than trusted on first contact
 * (ADR-0013).</p>
 */
public final class SshClientBridgeTransportFactory implements TerminalTransportFactory {

    private final SshCredentialProvider credentials;
    private final HostKeyTrustStore trustStore;
    private final SshReconnectPolicy reconnectPolicy;
    private final LongSupplier clock;

    public SshClientBridgeTransportFactory(
            SshCredentialProvider credentials,
            HostKeyTrustStore trustStore,
            SshReconnectPolicy reconnectPolicy,
            LongSupplier clock) {
        if (credentials == null) {
            throw new IllegalArgumentException("credentials must not be null");
        }
        if (trustStore == null) {
            throw new IllegalArgumentException("trustStore must not be null");
        }
        if (reconnectPolicy == null) {
            throw new IllegalArgumentException("reconnectPolicy must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.credentials = credentials;
        this.trustStore = trustStore;
        this.reconnectPolicy = reconnectPolicy;
        this.clock = clock;
    }

    @Override
    public TerminalTransport create() {
        return new SshClientBridgeTransport(new SshClientBridge(
                credentials, trustStore, LocalSshSessionFactory.pinnedHostKeyOnly(),
                reconnectPolicy, clock));
    }
}
