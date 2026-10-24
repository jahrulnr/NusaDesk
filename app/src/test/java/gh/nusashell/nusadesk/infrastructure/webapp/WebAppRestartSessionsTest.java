package gh.nusashell.nusadesk.infrastructure.webapp;
import org.junit.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
public class WebAppRestartSessionsTest {
    @Test public void releaseFenceDoesNotCompleteWhileTheOldListenerOwnsThePort() throws Exception {
        try (ServerSocket old = new ServerSocket(0)) {
            CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
                try { WebAppRestartSessions.awaitPortRelease(old.getLocalPort(), () -> false); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            old.setSoTimeout(2000);
            try (Socket attempt = old.accept()) { assertFalse(waiter.isDone()); }
            old.close(); waiter.get(3, TimeUnit.SECONDS);
        }
    }
}
