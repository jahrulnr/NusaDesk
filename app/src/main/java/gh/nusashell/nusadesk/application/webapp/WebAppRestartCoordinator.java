package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.application.terminal.TerminalTabsPort;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** App-scoped restart: stop the old command, await port release, then start one replacement. */
public final class WebAppRestartCoordinator {
    public interface PortRelease {
        void await(int port, BooleanSupplier cancelled) throws Exception;
    }
    private final Executor worker, main;
    private final PortRelease release;
    private final Supplier<TerminalTabsPort> tabs;
    private final Supplier<List<WebAppDefinition>> registrations;
    // Mutated on the main executor, but a rejected task is failed from the worker.
    private final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    public WebAppRestartCoordinator(Executor worker, Executor main, PortRelease release,
            Supplier<TerminalTabsPort> tabs, Supplier<List<WebAppDefinition>> registrations) {
        this.worker = worker; this.main = main; this.release = release;
        this.tabs = tabs; this.registrations = registrations;
    }
    public CompletableFuture<Void> pending(String id) { return pending.get(id); }
    public void cancel(String id) {
        CompletableFuture<Void> old = pending.remove(id);
        if (old != null) old.cancel(false);
    }
    /** Called on the main executor. Repeated requests share the same in-flight restart. */
    public CompletableFuture<Void> restart(WebAppDefinition app) {
        String id = app.getId().value();
        if (pending.containsKey(id)) return pending.get(id);
        CompletableFuture<Void> result = new CompletableFuture<>();
        pending.put(id, result);
        CompletableFuture<Void> stopped;
        try {
            stopped = WebAppCommandSession.stopAndAwait(app.getId(), tabs.get(), worker);
        } catch (RuntimeException failure) {
            fail(id, result, failure);
            return result;
        }
        stopped.whenComplete((unused, stopError) -> {
            try {
                worker.execute(() -> {
                    Throwable error = stopError;
                    try { if (error == null) release.await(app.getGuestPort(), result::isCancelled); }
                    catch (Exception failure) { error = failure; }
                    Throwable failure = error;
                    try {
                        main.execute(() -> {
                            if (pending.get(id) != result) return;
                            pending.remove(id);
                            boolean registered;
                            // WebAppDefinition.equals covers every stored field, so an
                            // app edited since this restart began no longer matches.
                            try { registered = registrations.get().contains(app); }
                            catch (RuntimeException lookup) { result.completeExceptionally(lookup); return; }
                            if (!registered) { result.cancel(false); return; }
                            if (failure != null) { result.completeExceptionally(failure); return; }
                            try { WebAppCommandSession.start(app, tabs.get()); result.complete(null); }
                            catch (Exception startFailure) { result.completeExceptionally(startFailure); }
                        });
                    } catch (RuntimeException rejected) { fail(id, result, rejected); }
                });
            } catch (RuntimeException rejected) { fail(id, result, rejected); }
        });
        return result;
    }

    /** Completes {@code result} exceptionally and drops the pending entry only when it is still this restart's. */
    private void fail(String id, CompletableFuture<Void> result, Throwable failure) {
        pending.remove(id, result);
        result.completeExceptionally(failure);
    }
}
