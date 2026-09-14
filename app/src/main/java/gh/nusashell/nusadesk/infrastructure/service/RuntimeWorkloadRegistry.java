package gh.nusashell.nusadesk.infrastructure.service;

/**
 * Process-local registry for the single allowlisted runtime workload.
 *
 * <p>This is the handoff point between the future supervisor (which implements
 * and registers a {@link RuntimeWorkload}) and the host service (which
 * consumes it). It is deliberately not a generic service locator: it holds at
 * most one workload and the service refuses to start any runtime while it is
 * empty, so the host never starts arbitrary code.
 *
 * <p>A static instance is used because an Android {@code Service} is created by
 * the system from an {@code Intent} and cannot receive constructor arguments,
 * while the supervisor registers its workload out-of-band. The registry is the
 * smallest bridge that keeps the two decoupled without a binder.
 */
public final class RuntimeWorkloadRegistry {

    private static final RuntimeWorkloadRegistry INSTANCE = new RuntimeWorkloadRegistry();

    private RuntimeWorkload workload;

    private RuntimeWorkloadRegistry() {
    }

    public static RuntimeWorkloadRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register the single allowlisted workload, replacing any previous one.
     *
     * @throws IllegalArgumentException if {@code workload} is null
     */
    public synchronized void register(RuntimeWorkload workload) {
        if (workload == null) {
            throw new IllegalArgumentException("workload must not be null");
        }
        this.workload = workload;
    }

    /** Remove the registered workload so the host refuses further starts. */
    public synchronized void unregister() {
        this.workload = null;
    }

    /** Returns the registered workload, or {@code null} when none is registered. */
    public synchronized RuntimeWorkload get() {
        return workload;
    }

    public synchronized boolean isRegistered() {
        return workload != null;
    }
}
