package gh.nusashell.nusadesk.application.session;

import gh.nusashell.nusadesk.domain.session.ReadinessFrame;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;

/**
 * Application boundary for the serialised runtime session lifecycle.
 *
 * <p>Implementations own the guest process handshake and persist state through
 * {@link SessionStateStore} so the session survives Activity recreation. The
 * contract is serialised per session: start, reconnect, cancel, stop, and
 * resume are not concurrent. Listeners receive honest snapshots only; a
 * {@code RUNNING} snapshot is never emitted without an accepted readiness
 * frame.</p>
 */
public interface SessionLifecycleUseCase {

    /** Begin a session for the given app identity. */
    void start(String appId, String appVersion, String sessionId, Listener listener);

    /** Reconnect after the guest endpoint changed or a transient drop. */
    void reconnect(Listener listener);

    /** Cancel an in-flight start or running session. */
    void cancel(Listener listener);

    /** Stop a running session gracefully. */
    void stop(Listener listener);

    /** Reconcile persisted state after Activity recreation and resume if possible. */
    void resume(Listener listener);

    /** Accept a readiness frame emitted by the guest, updating the session endpoint. */
    void onReadinessFrame(ReadinessFrame frame, Listener listener);

    interface Listener {
        void onSnapshot(SessionSnapshot snapshot);
    }
}
