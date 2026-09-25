package gh.nusashell.nusadesk.infrastructure.service;

import android.os.Handler;
import android.os.Looper;

import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process publish/subscribe channel carrying {@link TerminalTabsSnapshot}
 * from the host-owned terminal tab set to presentation views and the service's
 * notification.
 *
 * <p>Mirrors {@link RuntimeStatusBus}: the bus retains the most recent
 * snapshot so a view created after a transition (rotation, recreation)
 * immediately receives the last known tab set. A freshly restarted process has
 * no retained snapshot, which is honest — the runtime reconcile then drives
 * the terminal tabs controller, which publishes here. Callbacks are always
 * delivered on the main thread; no secret material passes through.</p>
 */
public final class TerminalTabsBus {

    private static final TerminalTabsBus INSTANCE = new TerminalTabsBus();

    public static TerminalTabsBus getInstance() {
        return INSTANCE;
    }

    /** Listener for terminal tabs snapshot deliveries. Called on the main thread. */
    public interface Listener {
        void onTerminalTabs(TerminalTabsSnapshot snapshot);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile TerminalTabsSnapshot lastSnapshot = TerminalTabsSnapshot.empty();

    private TerminalTabsBus() {
    }

    /**
     * Register a listener and immediately deliver the last known snapshot.
     * Safe to call from any thread.
     */
    public void register(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.addIfAbsent(listener);
        TerminalTabsSnapshot current = lastSnapshot;
        mainHandler.post(() -> {
            if (listeners.contains(listener)) {
                listener.onTerminalTabs(current);
            }
        });
    }

    /** Remove a previously registered listener. */
    public void unregister(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Publish a new snapshot. Retains it for late subscribers and delivers it
     * to every registered listener on the main thread. Safe to call from any
     * thread (the controller receives bridge callbacks on bridge threads).
     */
    public void publish(TerminalTabsSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        lastSnapshot = snapshot;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onTerminalTabs(snapshot);
            }
        });
    }

    /** @return the most recently published snapshot (never null). */
    public TerminalTabsSnapshot current() {
        return lastSnapshot;
    }
}
