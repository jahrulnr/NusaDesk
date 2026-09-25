package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * Production {@link TerminalTransport} backed by {@link SshClientBridge}.
 *
 * <p>This is the only place the terminal session owner touches a concrete SSH
 * client. The bridge itself is constructed with the fixed local endpoint
 * configuration from {@link LocalSshSessionFactory} — no host, port, or
 * credential parameter is accepted (ADR-0013).</p>
 */
public final class SshClientBridgeTransport implements TerminalTransport {

    private final SshClientBridge bridge;

    public SshClientBridgeTransport(SshClientBridge bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        this.bridge = bridge;
    }

    @Override
    public void start(SshSessionConfig config, String command, SshSessionListener listener) {
        bridge.start(config, command, listener);
    }

    @Override
    public void write(byte[] data) {
        bridge.write(data);
    }

    @Override
    public void resize(int cols, int rows) {
        bridge.resize(cols, rows);
    }

    @Override
    public void close() {
        bridge.close();
    }
}
