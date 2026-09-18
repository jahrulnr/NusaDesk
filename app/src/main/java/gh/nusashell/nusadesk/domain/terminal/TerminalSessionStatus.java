package gh.nusashell.nusadesk.domain.terminal;

import java.util.Objects;

/**
 * Immutable snapshot of the host-owned terminal SSH session.
 *
 * <p>Carries the {@link TerminalSessionState} plus a non-secret human-readable
 * detail (for example the failure reason). Views and the notification policy
 * render this; the terminal session bus retains the latest instance so a
 * surface created after a transition immediately receives the last known
 * state. Carries no Android types and no secret material.</p>
 */
public final class TerminalSessionStatus {

    private static final TerminalSessionStatus NOT_STARTED =
            new TerminalSessionStatus(TerminalSessionState.NOT_STARTED, "");

    private final TerminalSessionState state;
    private final String detail;

    public TerminalSessionStatus(TerminalSessionState state, String detail) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.state = state;
        this.detail = detail == null ? "" : detail;
    }

    /** Shared instance for the initial no-session state. */
    public static TerminalSessionStatus notStarted() {
        return NOT_STARTED;
    }

    public TerminalSessionState getState() {
        return state;
    }

    /** Non-secret detail; empty for normal transitions. */
    public String getDetail() {
        return detail;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TerminalSessionStatus)) {
            return false;
        }
        TerminalSessionStatus that = (TerminalSessionStatus) o;
        return state == that.state && Objects.equals(detail, that.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, detail);
    }
}
