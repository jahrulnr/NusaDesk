package gh.nusashell.nusadesk.infrastructure.integration;

import org.junit.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM tests for {@link SshBannerProbe} against real loopback sockets:
 * only a genuine {@code SSH-} protocol banner counts as readiness.
 */
public class SshBannerProbeTest {

    private final SshBannerProbe probe = new SshBannerProbe();

    @Test
    public void healthyWhenPeerEmitsSshBanner() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            serveOnce(server, "SSH-2.0-OpenSSH_9.6\r\n");
            assertTrue(probe.isHealthy("127.0.0.1", server.getLocalPort(), 3_000L));
        }
    }

    @Test
    public void unhealthyWhenPeerSpeaksNonSsh() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            // The probe retries until the deadline, so keep serving garbage.
            Thread t = serveForever(server, "HTTP/1.1 200 OK\r\n");
            assertFalse(probe.isHealthy("127.0.0.1", server.getLocalPort(), 700L));
            t.join(2_000L);
        }
    }

    @Test
    public void unhealthyWhenNothingListens() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = s.getLocalPort();
        }
        assertFalse(probe.isHealthy("127.0.0.1", port, 500L));
    }

    @Test
    public void rejectsInvalidArguments() {
        assertFalse(probe.isHealthy(null, 22, 500L));
        assertFalse(probe.isHealthy("", 22, 500L));
        assertFalse(probe.isHealthy("127.0.0.1", 0, 500L));
        assertFalse(probe.isHealthy("127.0.0.1", 70000, 500L));
    }

    private static void serveOnce(ServerSocket server, String banner) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept();
                 OutputStream out = s.getOutputStream()) {
                out.write(banner.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static Thread serveForever(ServerSocket server, String banner) {
        Thread t = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    try (Socket s = server.accept();
                         OutputStream out = s.getOutputStream()) {
                        out.write(banner.getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
