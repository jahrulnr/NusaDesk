package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import java.util.Map;

/**
 * One interactive operation that needs a visible activity to run.
 *
 * <p>Implementations are registered in {@link ForegroundOperationCatalog}
 * under a stable {@link #kind()} name and are executed by
 * {@link CapabilityForegroundHost}: the host parks the operation, launches
 * {@link CapabilityForegroundActivity}, and the activity calls
 * {@link #run} on the main thread. An operation may finish synchronously or
 * stay alive across an activity callback (a runtime-permission result, a
 * picker result); either way it reports exactly once through the supplied
 * {@link ResultSink} — never through the socket, and never by throwing past
 * the activity.</p>
 *
 * <p>Implementations are shared catalog instances, so per-request state
 * belongs in the {@code run} invocation (locals, closures, or the hooks the
 * activity exposes), not in fields.</p>
 */
public interface ForegroundOperation {
    /** Stable catalog key for this operation, lowercase-kebab. */
    String kind();

    /**
     * Run the operation inside the parked foreground activity. Bounded
     * request parameters arrive as the flat {@code params} map the guest
     * sent; the operation re-validates what it needs and reports through
     * {@code sink}.
     */
    void run(CapabilityForegroundActivity activity, Map<String, Object> params,
             ResultSink sink);

    /** One-shot result channel back to the parked bridge request. */
    interface ResultSink {
        /** Report success with flat response fields (string/number/boolean values). */
        void success(Map<String, Object> fields);

        /** Report a typed lowercase-kebab error, optionally with a {@code :hint}. */
        void error(String typedError);
    }
}
