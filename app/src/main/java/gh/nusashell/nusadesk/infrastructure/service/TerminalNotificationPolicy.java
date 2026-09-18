package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;

/**
 * Deterministic, Android-free policy mapping a {@link TerminalSessionStatus}
 * to the terminal line of the host foreground notification and to the
 * visibility of its Reconnect action.
 *
 * <p>Kept pure so the wording and action visibility are locked by unit tests
 * independently of the Android notification framework. User-visible strings
 * stay in code for this slice, matching {@link RuntimeNotificationPolicy}.</p>
 */
public final class TerminalNotificationPolicy {

    private TerminalNotificationPolicy() {
    }

    /** Notification line for the terminal session, or empty when nothing to say. */
    public static String line(TerminalSessionStatus status) {
        if (status == null) {
            return "";
        }
        switch (status.getState()) {
            case NOT_STARTED:
                return "";
            case CONNECTING:
                return "Terminal: connecting…";
            case RUNNING:
                return "Terminal: connected";
            case RECONNECTING:
                return "Terminal: reconnecting…";
            case DROPPED:
                return "Terminal disconnected — tap Reconnect";
            case FAILED:
                return "Terminal failed — tap Reconnect";
            default:
                return "";
        }
    }

    /**
     * Whether the notification should offer the Reconnect action. Only when
     * the runtime session is still up but the shell dropped or failed — a
     * live shell needs no action and a stopped runtime has no shell to attach
     * to.
     */
    public static boolean showsReconnectAction(TerminalSessionStatus status) {
        if (status == null) {
            return false;
        }
        switch (status.getState()) {
            case DROPPED:
            case FAILED:
                return true;
            default:
                return false;
        }
    }
}
