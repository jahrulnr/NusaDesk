package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

/**
 * Application boundary for selecting an already-bound runtime endpoint.
 *
 * <p>The first implementation must not reserve a port and release it before
 * the child process binds. The child should own the bind and report the
 * concrete endpoint through a readiness protocol.</p>
 */
public interface RuntimePortProvider {
    RuntimePort awaitReadyEndpoint(String appId) throws RuntimePortException;
}
