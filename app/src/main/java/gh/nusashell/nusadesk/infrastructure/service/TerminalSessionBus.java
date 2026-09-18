package gh.nusashell.nusadesk.infrastructure.service;

import android.os.Handler;
import android.os.Looper;

import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process publish/subscribe channel carrying {@link TerminalSessionStatus}
 * from the host-owned terminal session to presentation views and the service's
 * notification.
 *
 * <p>Mirrors {@link RuntimeStatusBus}: the bus retains the most recent status
 * so a view created after a transition (rotation, recreation) immediately
 * receives the last known state. A freshly restarted process has no retained
 * status, which is honest — the runtime reconcile then drives the terminal
 * session controller, which publishes here. Callbacks are always delivered on
 * the main thread; no secret material passes through.</p>
 */
public final class TerminalSessionBus {

    private static final TerminalSessionBus INSTANCE = new TerminalSessionBus();

    public static TerminalSessionBus getInstance() {
        return INSTANCE;
    }

    /** Listener for terminal session status deliveries. Called on the main thread. */
    public interface Listener {
        void onTerminalStatus(TerminalSessionStatus status);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile TerminalSessionStatus lastStatus = TerminalSessionStatus.notStarted();

    private TerminalSessionBus() {
    }

    /**
     * Register a listener and immediately deliver the last known status.
     * Safe to call from any thread.
     */
    public void register(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.addIfAbsent(listener);
        TerminalSessionStatus current = lastStatus;
        mainHandler.post(() -> {
            if (listeners.contains(listener)) {
                listener.onTerminalStatus(current);
            }
        });
    }

    /** Remove a previously registered listener. */
    public void unregister(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Publish a new status. Retains it for late subscribers and delivers it to
     * every registered listener on the main thread. Safe to call from any
     * thread (the controller receives bridge callbacks on bridge threads).
     */
    public void publish(TerminalSessionStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        lastStatus = status;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onTerminalStatus(status);
            }
        });
    }

    /** @return the most recently published status (never null). */
    public TerminalSessionStatus current() {
        return lastStatus;
    }
}
