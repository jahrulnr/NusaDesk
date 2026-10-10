package gh.nusashell.nusadesk.domain.session;

/**
 * Deterministic policy that turns guest liveness-probe results into session
 * state changes (ADR-0062).
 *
 * <p>The host canary asks the guest to run one short command on a fixed
 * cadence. A single missed probe proves nothing — a busy guest or a transient
 * SSH hiccup can cost one — so the session only degrades after
 * {@link #FAILURE_THRESHOLD} consecutive misses, and it revives on the first
 * success. Holding the counting and the thresholds here keeps the state
 * machine itself free of probe arithmetic: the caller owns cadence and the
 * probe mechanism, this class owns "when does a miss become a state".</p>
 *
 * <p>The tracker is deliberately reset only by construction: a new runtime
 * session starts with a fresh instance, so a wedge-and-restart can never
 * inherit a stale failure count. Not thread-safe; the owning controller is
 * driven on the main thread.</p>
 */
public final class SessionLivenessPolicy {

    /**
     * Consecutive probe misses before a running session is declared
     * {@link SessionState#UNRESPONSIVE} — about 45 s at the 15 s canary
     * cadence, so a momentary stall never reaches the user.
     */
    public static final int FAILURE_THRESHOLD = 3;

    private int consecutiveFailures;

    /**
     * Record one probe outcome against the current session state.
     *
     * <p>Only {@code RUNNING} and {@code UNRESPONSIVE} consume a result: a
     * probe that lands while the session is elsewhere — a late in-flight
     * result arriving during {@code STOPPING}, say — is stale evidence from
     * a different state and must neither transition nor count toward the
     * threshold, or a returning healthy session would degrade on borrowed
     * misses.</p>
     *
     * @param current the session state the probe ran against
     * @param alive   {@code true} when the guest completed the probe command
     *                inside its bound
     * @return the state the session must move to, or {@code null} when the
     *         result changes nothing the user can see
     */
    public SessionState record(SessionState current, boolean alive) {
        if (current != SessionState.RUNNING && current != SessionState.UNRESPONSIVE) {
            return null;
        }
        consecutiveFailures = alive ? 0 : consecutiveFailures + 1;
        if (current == SessionState.RUNNING && consecutiveFailures >= FAILURE_THRESHOLD) {
            return SessionState.UNRESPONSIVE;
        }
        if (current == SessionState.UNRESPONSIVE && alive) {
            return SessionState.RUNNING;
        }
        return null;
    }

    /** Consecutive misses recorded so far; diagnostic, e.g. for logs. */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
}
