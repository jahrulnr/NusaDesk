package gh.nusashell.nusadesk.domain.session;

/**
 * Deterministic policy that advances a {@link SessionSnapshot} through the
 * {@link SessionState} machine.
 *
 * <p>Keeps validation in the domain so the application layer never builds an
 * invalid snapshot. Transitions do not change the endpoint; the infrastructure
 * updates it separately after accepting a readiness frame.</p>
 */
public final class SessionTransitionPolicy {
    private SessionTransitionPolicy() {
    }

    /**
     * Apply a lifecycle transition, returning the next immutable snapshot.
     *
     * @param current the snapshot to advance; must not be null
     * @param next    the target state; must be reachable from {@code current}
     * @param now     the update timestamp; must not precede the snapshot start
     * @param reason  failure reason, used only when transitioning to {@link SessionState#FAILED}
     * @return a new snapshot reflecting the transition
     * @throws IllegalStateException if the transition is not allowed by the state machine
     */
    public static SessionSnapshot attempt(
            SessionSnapshot current, SessionState next, long now, String reason) {
        if (current == null) {
            throw new IllegalArgumentException("current snapshot must not be null");
        }
        if (!current.getState().canTransitionTo(next)) {
            throw new IllegalStateException(
                    "invalid session transition: " + current.getState() + " -> " + next);
        }
        int reconnects = next == SessionState.RECONNECTING
                ? current.getReconnectAttempts() + 1
                : (next == SessionState.RUNNING ? 0 : current.getReconnectAttempts());
        String failureReason = next == SessionState.FAILED
                ? (reason == null ? "" : reason)
                : "";
        return new SessionSnapshot(
                current.getSessionId(),
                current.getAppId(),
                current.getAppVersion(),
                next,
                current.getEndpoint(),
                current.getStartedAtEpochMillis(),
                now,
                failureReason,
                reconnects);
    }

    /** User-initiated cancellation from any active state. */
    public static SessionSnapshot cancel(SessionSnapshot current, long now, String reason) {
        return attempt(current, SessionState.CANCELLED, now, reason);
    }
}
