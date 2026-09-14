package gh.nusashell.nusadesk.infrastructure.sshserver;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Default {@link SshBridgeHealthProbe} using a JDK {@link Socket} connect.
 *
 * <p>Confirms the bound loopback port is actually accepting TCP connections,
 * not merely that {@code SshServer.start()} returned. The connect is bounded
 * by the supplied timeout so a hung accept path cannot stall readiness. This
 * is a reachability check; authentication and authorization are enforced
 * separately by the password authenticator.</p>
 */
public final class JdkTcpHealthProbe implements SshBridgeHealthProbe {

    @Override
    public boolean isHealthy(String host, int port, long timeoutMillis) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (port <= 0 || port > 65535) {
            return false;
        }
        int timeout = timeoutMillis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeoutMillis;
        if (timeout < 0) {
            timeout = 0;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return socket.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
