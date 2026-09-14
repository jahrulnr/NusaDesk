package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.session.SessionState;

/**
 * Presentation vocabulary for the one Linux session the Android host owns.
 *
 * <p>This is a pure mapping from the domain {@link SessionState} to a label,
 * badge, and detail sentence. It is not a second state machine: it never invents
 * a state the domain has not published.</p>
 *
 * <p>It deliberately carries <em>no</em> action. Linux starts from an Activity
 * foreground event and is stopped from the platform's own foreground-service
 * notification, so no app surface offers a session control (ADR-0013); the
 * launcher states readiness instead, and surfaces read {@link #isHealthy()} only
 * to decide whether they need to say anything at all.</p>
 */
public final class SessionUiState {

    /** Coarse session grouping used for copy and colour. */
    public enum Kind { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    private final Kind kind;
    private final int labelRes;
    private final int detailRes;
    private final String failureReason;

    private SessionUiState(Kind kind, int labelRes, int detailRes, String failureReason) {
        this.kind = kind;
        this.labelRes = labelRes;
        this.detailRes = detailRes;
        this.failureReason = failureReason;
    }

    /**
     * Maps a published session state to UI vocabulary. A {@code null} state is
     * treated as "no session yet", which is the honest stopped state.
     */
    public static SessionUiState from(SessionState state, String failureReason) {
        switch (state == null ? SessionState.NOT_STARTED : state) {
            case STARTING:
                return new SessionUiState(Kind.STARTING,
                        R.string.session_starting, R.string.session_starting_detail, null);
            case RECOVERING:
                return new SessionUiState(Kind.STARTING,
                        R.string.session_recovering, R.string.session_recovering_detail, null);
            case RUNNING:
                return new SessionUiState(Kind.RUNNING,
                        R.string.session_running, R.string.session_running_detail, null);
            case RECONNECTING:
                return new SessionUiState(Kind.RUNNING,
                        R.string.session_reconnecting, R.string.session_reconnecting_detail, null);
            case STOPPING:
                return new SessionUiState(Kind.STOPPING,
                        R.string.session_stopping, R.string.session_stopping_detail, null);
            case FAILED:
                return new SessionUiState(Kind.FAILED,
                        R.string.session_failed, R.string.session_failed_detail,
                        normalizeReason(failureReason));
            default:
                return new SessionUiState(Kind.STOPPED,
                        R.string.session_stopped, R.string.session_stopped_detail, null);
        }
    }

    /** The state a fresh process with no published status reports. */
    public static SessionUiState unknown() {
        return from(SessionState.NOT_STARTED, null);
    }

    /** A blank host reason is treated as absent so the UI never prints nothing. */
    private static String normalizeReason(String reason) {
        return reason == null || reason.trim().isEmpty() ? null : reason;
    }

    public Kind getKind() {
        return kind;
    }

    /** Full sentence label for a status row. */
    public int getLabelRes() {
        return labelRes;
    }

    /** Short label for a compact status pill. */
    public int getBadgeRes() {
        switch (kind) {
            case RUNNING:
                return R.string.session_badge_running;
            case STARTING:
                return R.string.session_badge_starting;
            case STOPPING:
                return R.string.session_badge_stopping;
            case FAILED:
                return R.string.session_badge_failed;
            default:
                return R.string.session_badge_stopped;
        }
    }

    public int getDetailRes() {
        return detailRes;
    }

    /** Non-secret failure reason, or {@code null} when the session did not fail. */
    public String getFailureReason() {
        return failureReason;
    }

    /** True only for a session the host has published as running. */
    public boolean isHealthy() {
        return kind == Kind.RUNNING;
    }
}
