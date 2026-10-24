package gh.nusashell.nusadesk.infrastructure.webapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Process-lifetime restart work survives Activity recreation; no View or Activity is retained. */
public final class WebAppRestartSessions {
    private static WebAppRestartCoordinator instance;
    private WebAppRestartSessions() { }
    public static synchronized WebAppRestartCoordinator get(Context context) {
        if (instance == null) {
            Handler main = new Handler(Looper.getMainLooper());
            WebAppRegistry registrations = new WebAppRegistry(new SharedPreferencesWebAppStore(context.getApplicationContext()));
            instance = new WebAppRestartCoordinator(Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "web-server-restart"); thread.setDaemon(true); return thread;
            }), main::post, WebAppRestartSessions::awaitPortRelease,
                    () -> TerminalTabsRegistry.getInstance().port(), registrations::list);
        }
        return instance;
    }
    static void awaitPortRelease(int port, BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            } catch (ConnectException refused) {
                return;
            } catch (SocketTimeoutException stillBound) {
                // A listener that never completes the connect still owns the port.
            }
            if (System.nanoTime() >= deadline) throw new IOException("The previous server still owns port " + port + ". Stop it in its terminal before retrying.");
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for port " + port + " to be released");
            }
        }
        throw new IOException("Server restart cancelled");
    }
}
