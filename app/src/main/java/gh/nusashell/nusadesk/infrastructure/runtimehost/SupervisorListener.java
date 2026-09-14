package gh.nusashell.nusadesk.infrastructure.runtimehost;

import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Callback for supervisor state transitions.
 *
 * <p>The supervisor reports explicit {@link RuntimeState} transitions with a
 * short, secret-free detail string and the current published port (zero until
 * the guest announces a concrete endpoint). The listener is invoked under the
 * supervisor's lock; implementations must not perform long work or call back
 * into the supervisor synchronously.</p>
 */
public interface SupervisorListener {
    /**
     * @param state  the new runtime state
     * @param detail a short, non-sensitive description
     * @param port   the published loopback port, or {@code 0} when not running
     */
    void onState(RuntimeState state, String detail, int port);
}
