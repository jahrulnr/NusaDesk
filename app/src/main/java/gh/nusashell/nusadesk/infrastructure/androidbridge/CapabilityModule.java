package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.List;
import java.util.Set;

/**
 * One capability domain served over the bridge.
 *
 * <p>The request handler dispatches a built-in method first, then the first
 * module that declares the method. A module therefore owns a disjoint method
 * set: it declares the methods it answers ({@link #methods()}), the subset
 * that may carry a bounded {@code params} object
 * ({@link #parameterMethods()}), and returns one protocol response per call.
 * The framework enforces the parameter declaration before a module is reached,
 * so a method that does not declare parameters never sees a non-empty
 * {@code params} map.</p>
 *
 * <p>A module must not throw: it returns a typed error response instead. The
 * framework still guards the call, mapping {@link CapabilityParams.Invalid} to
 * {@code invalid-argument} and any other runtime failure to
 * {@code capability-unavailable}, so one broken capability can never take the
 * bridge down.</p>
 */
public interface CapabilityModule {
    /** Method names this module answers, in capability-list order. */
    List<String> methods();

    /** Subset of {@link #methods()} that may carry a {@code params} object. */
    Set<String> parameterMethods();

    /** Handle one request whose {@link AndroidCapabilityProtocol.Request#getMethod()} this module declares. */
    AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request);

    /** Release per-module resources when the bridge closes. */
    default void close() {
    }
}
