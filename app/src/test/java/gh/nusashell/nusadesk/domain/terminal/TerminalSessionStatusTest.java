package gh.nusashell.nusadesk.domain.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TerminalSessionStatusTest {

    @Test
    public void nullStateIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalSessionStatus(null, "x"));
    }

    @Test
    public void nullDetailBecomesEmpty() {
        TerminalSessionStatus status =
                new TerminalSessionStatus(TerminalSessionState.RUNNING, null);
        assertEquals("", status.getDetail());
    }

    @Test
    public void notStartedIsASharedEmptyDetailInstance() {
        TerminalSessionStatus status = TerminalSessionStatus.notStarted();
        assertEquals(TerminalSessionState.NOT_STARTED, status.getState());
        assertEquals("", status.getDetail());
        assertSame(status, TerminalSessionStatus.notStarted());
    }

    @Test
    public void equalityCoversStateAndDetail() {
        TerminalSessionStatus a =
                new TerminalSessionStatus(TerminalSessionState.FAILED, "boom");
        TerminalSessionStatus same =
                new TerminalSessionStatus(TerminalSessionState.FAILED, "boom");
        TerminalSessionStatus otherDetail =
                new TerminalSessionStatus(TerminalSessionState.FAILED, "other");
        TerminalSessionStatus otherState =
                new TerminalSessionStatus(TerminalSessionState.DROPPED, "boom");

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, otherDetail);
        assertNotEquals(a, otherState);
        assertNotEquals(a, null);
    }
}
