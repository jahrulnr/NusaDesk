package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;

/**
 * Optional screen contract for views that render the live runtime session
 * status published by {@code RuntimeStatusBus}.
 *
 * <p>The bus retains the last status, so a screen implementing this receives
 * the current session state immediately on registration and on every later
 * transition — enough to survive Activity recreation without faking a running
 * session.</p>
 */
public interface SessionStatusAware {

    /**
     * Render the latest host runtime status. Always invoked on the main thread.
     * The status carries no secrets.
     */
    void renderSessionStatus(HostRuntimeStatus status);
}
