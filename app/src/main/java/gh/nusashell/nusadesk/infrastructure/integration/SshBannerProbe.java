package gh.nusashell.nusadesk.infrastructure.integration;

import gh.nusashell.nusadesk.infrastructure.sshserver.SshBridgeHealthProbe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Readiness probe for the guest-native SSH daemon: connects to the loopback
 * endpoint and requires the first server line to be a real SSH protocol banner
 * ({@code "SSH-"}). This is stronger than a bare TCP accept check — a port
 * captured by an unrelated loopback service between selection and the guest
 * bind cannot produce the banner, so a wedged or hijacked port fails honestly
 * instead of publishing a wrong endpoint.
 *
 * <p>Polls until the deadline because the freshly spawned daemon needs a short
 * moment to bind and start listening.</p>
 */
public final class SshBannerProbe implements SshBridgeHealthProbe {

    private static final int BANNER_LIMIT = 256;
    private static final long RETRY_DELAY_MILLIS = 100L;
    private static final int CONNECT_TIMEOUT_MILLIS = 500;

    @Override
    public boolean isHealthy(String host, int port, long timeoutMillis) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            return false;
        }
        long deadline = System.nanoTime()
                + Math.max(timeoutMillis, CONNECT_TIMEOUT_MILLIS) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (readBanner(host, port)) {
                return true;
            }
            try {
                Thread.sleep(RETRY_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean readBanner(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while (line.size() < BANNER_LIMIT && (b = in.read()) != -1) {
                if (b == '\n') {
                    break;
                }
                line.write(b);
            }
            String banner = line.toString(StandardCharsets.US_ASCII.name()).trim();
            return banner.startsWith("SSH-");
        } catch (Exception e) {
            return false;
        }
    }
}
