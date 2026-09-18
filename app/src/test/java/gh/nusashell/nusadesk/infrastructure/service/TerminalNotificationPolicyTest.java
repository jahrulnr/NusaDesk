package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalNotificationPolicyTest {

    @Test
    public void notStartedHasNoLineAndNoAction() {
        TerminalSessionStatus status = status(TerminalSessionState.NOT_STARTED);
        assertEquals("", TerminalNotificationPolicy.line(status));
        assertFalse(TerminalNotificationPolicy.showsReconnectAction(status));
    }

    @Test
    public void connectingAndRunningAndReconnectingReportProgressWithoutAction() {
        assertEquals("Terminal: connecting…",
                TerminalNotificationPolicy.line(status(TerminalSessionState.CONNECTING)));
        assertEquals("Terminal: connected",
                TerminalNotificationPolicy.line(status(TerminalSessionState.RUNNING)));
        assertEquals("Terminal: reconnecting…",
                TerminalNotificationPolicy.line(status(TerminalSessionState.RECONNECTING)));

        // A live or in-flight shell needs no action: the notification must not
        // offer a second attach that could fight the one in progress.
        assertFalse(TerminalNotificationPolicy.showsReconnectAction(
                status(TerminalSessionState.CONNECTING)));
        assertFalse(TerminalNotificationPolicy.showsReconnectAction(
                status(TerminalSessionState.RUNNING)));
        assertFalse(TerminalNotificationPolicy.showsReconnectAction(
                status(TerminalSessionState.RECONNECTING)));
    }

    @Test
    public void droppedAndFailedOfferTheReconnectAction() {
        assertEquals("Terminal disconnected — tap Reconnect",
                TerminalNotificationPolicy.line(status(TerminalSessionState.DROPPED)));
        assertEquals("Terminal failed — tap Reconnect",
                TerminalNotificationPolicy.line(status(TerminalSessionState.FAILED)));

        // The runtime stayed up while its shell ended: this is exactly the
        // case the explicit Reconnect action exists for (ADR-0033).
        assertTrue(TerminalNotificationPolicy.showsReconnectAction(
                status(TerminalSessionState.DROPPED)));
        assertTrue(TerminalNotificationPolicy.showsReconnectAction(
                status(TerminalSessionState.FAILED)));
    }

    @Test
    public void nullStatusIsSafe() {
        assertEquals("", TerminalNotificationPolicy.line(null));
        assertFalse(TerminalNotificationPolicy.showsReconnectAction(null));
    }

    private static TerminalSessionStatus status(TerminalSessionState state) {
        return new TerminalSessionStatus(state, "");
    }
}
