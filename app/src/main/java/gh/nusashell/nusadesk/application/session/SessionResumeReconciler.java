package gh.nusashell.nusadesk.application.session;

import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;

/**
 * Reconciles a persisted session snapshot after Activity recreation.
 *
 * <p>An honest recovery never shows a false running state. A persisted
 * {@code RUNNING} becomes {@code RECONNECTING} so the host re-verifies health
 * before exposing the WebView. Transient states that were interrupted by host
 * death become {@code FAILED} with a retryable reason; stable terminal states
 * are returned unchanged.</p>
 */
public final class SessionResumeReconciler {
    private SessionResumeReconciler() {
    }

    public static SessionSnapshot reconcile(SessionSnapshot snapshot, long now) {
        if (snapshot == null) {
            return null;
        }
        switch (snapshot.getState()) {
            case RUNNING:
                return new SessionSnapshot(
                        snapshot.getSessionId(),
                        snapshot.getAppId(),
                        snapshot.getAppVersion(),
                        SessionState.RECONNECTING,
                        snapshot.getEndpoint(),
                        snapshot.getStartedAtEpochMillis(),
                        now,
                        "resuming: re-verifying previous session",
                        snapshot.getReconnectAttempts() + 1);
            case STARTING:
            case RECONNECTING:
            case STOPPING:
                return new SessionSnapshot(
                        snapshot.getSessionId(),
                        snapshot.getAppId(),
                        snapshot.getAppVersion(),
                        SessionState.FAILED,
                        snapshot.getEndpoint(),
                        snapshot.getStartedAtEpochMillis(),
                        now,
                        "session interrupted by host recreation",
                        snapshot.getReconnectAttempts());
            default:
                return snapshot;
        }
    }
}
