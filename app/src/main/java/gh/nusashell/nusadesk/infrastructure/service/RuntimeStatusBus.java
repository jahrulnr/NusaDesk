package gh.nusashell.nusadesk.infrastructure.service;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process publish/subscribe channel carrying {@link HostRuntimeStatus} from
 * {@link RuntimeHostService} to presentation views.
 *
 * <p>The service and the Activity live in the same process, so no binder/AIDL
 * boundary is needed. The bus retains the most recent status so a view created
 * after a transition (rotation, recreation) immediately receives the last
 * known state instead of a blank {@code NOT_STARTED}; a freshly restarted
 * process has no retained status, which is itself honest — the persisted
 * snapshot is reconciled by the service and republished here.
 *
 * <p>Callbacks are always delivered on the main thread. No secret material
 * passes through the bus: {@link HostRuntimeStatus} carries a non-secret
 * detail only.
 */
public final class RuntimeStatusBus {

    /** Receives a new host status; invoked on the main thread. */
    public interface Listener {
        void onStatus(HostRuntimeStatus status);
    }

    private static final RuntimeStatusBus INSTANCE = new RuntimeStatusBus();

    public static RuntimeStatusBus getInstance() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile HostRuntimeStatus lastStatus;

    private RuntimeStatusBus() {
    }

    /**
     * Register a listener and immediately deliver the last known status if one
     * exists. Safe to call from any thread.
     */
    public void register(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.addIfAbsent(listener);
        HostRuntimeStatus current = lastStatus;
        if (current != null) {
            mainHandler.post(() -> {
                if (listeners.contains(listener)) {
                    listener.onStatus(current);
                }
            });
        }
    }

    /** Remove a previously registered listener. */
    public void unregister(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Publish a new status. Retains it for late subscribers and delivers it to
     * every registered listener on the main thread.
     */
    public void publish(HostRuntimeStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        lastStatus = status;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onStatus(status);
            }
        });
    }

    /** @return the most recently published status, or {@code null} if none. */
    public HostRuntimeStatus current() {
        return lastStatus;
    }
}
