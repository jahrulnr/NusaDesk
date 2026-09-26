package gh.nusashell.nusadesk.presentation.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabKind;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The isolation contract of ADR-0054: the built-in terminal owns the shell
 * tabs, a launcher terminal-command app owns exactly its own tab, and neither
 * surface can render or reach the other's tab.
 */
public class TerminalSurfaceScopeTest {

    private static TerminalTabSnapshot shellTab(String id, int ordinal) {
        return new TerminalTabSnapshot(
                id, TerminalTabKind.SHELL, null, null, ordinal, true, running());
    }

    private static TerminalTabSnapshot commandTab(String id, String appId, int ordinal) {
        return new TerminalTabSnapshot(
                id, TerminalTabKind.COMMAND, appId, "top", ordinal, true, running());
    }

    private static TerminalSessionStatus running() {
        return new TerminalSessionStatus(TerminalSessionState.RUNNING, null);
    }

    @Test
    public void shellScopeOwnsShellTabsOnly() {
        TerminalTabSnapshot shell = shellTab("t1", 1);
        TerminalTabSnapshot command = commandTab("t2", "top", 2);
        TerminalSurfaceScope scope = TerminalSurfaceScope.shellTabs();

        assertTrue(scope.includes(shell));
        assertFalse(scope.includes(command));
        assertFalse(scope.isCommandAppScope());
        assertEquals(
                List.of(shell),
                scope.inScope(new TerminalTabsSnapshot(
                        Arrays.asList(shell, command), "t2")));
    }

    @Test
    public void commandScopeOwnsExactlyThatAppsTab() {
        TerminalTabSnapshot mine = commandTab("t2", "top", 2);
        TerminalTabSnapshot other = commandTab("t3", "devin", 3);
        TerminalSurfaceScope scope = TerminalSurfaceScope.commandApp("top");

        assertTrue(scope.isCommandAppScope());
        assertTrue(scope.includes(mine));
        assertFalse(scope.includes(other));
        assertFalse(scope.includes(shellTab("t1", 1)));
        assertEquals(
                List.of(mine),
                scope.inScope(new TerminalTabsSnapshot(Arrays.asList(mine, other), "t3")));
    }

    @Test
    public void visiblePrefersTheSelectionWhileItIsInScope() {
        TerminalTabSnapshot shell1 = shellTab("t1", 1);
        TerminalTabSnapshot shell2 = shellTab("t2", 2);
        TerminalTabsSnapshot snapshot =
                new TerminalTabsSnapshot(Arrays.asList(shell1, shell2), "t1");

        assertEquals(shell1, TerminalSurfaceScope.shellTabs().visible(snapshot));
    }

    @Test
    public void visibleFallsBackToTheMostRecentInScopeTab() {
        TerminalTabSnapshot shell1 = shellTab("t1", 1);
        TerminalTabSnapshot shell2 = shellTab("t2", 2);
        TerminalTabSnapshot command = commandTab("t3", "top", 3);
        TerminalTabsSnapshot snapshot =
                new TerminalTabsSnapshot(Arrays.asList(shell1, shell2, command), "t3");

        // The selection belongs to the command app; the terminal keeps showing
        // its own most recent tab, and the app surface shows its tab.
        assertEquals(shell2, TerminalSurfaceScope.shellTabs().visible(snapshot));
        assertEquals(command, TerminalSurfaceScope.commandApp("top").visible(snapshot));
    }

    @Test
    public void visibleIsNullWithoutInScopeTabs() {
        TerminalTabSnapshot command = commandTab("t3", "top", 3);

        assertNull(TerminalSurfaceScope.shellTabs().visible(
                new TerminalTabsSnapshot(List.of(command), "t3")));
        assertNull(TerminalSurfaceScope.commandApp("devin").visible(
                new TerminalTabsSnapshot(List.of(command), "t3")));
        assertNull(TerminalSurfaceScope.shellTabs().visible(null));
        assertTrue(TerminalSurfaceScope.shellTabs().inScope(null).isEmpty());
    }

    @Test
    public void commandScopeRejectsABlankAppId() {
        try {
            TerminalSurfaceScope.commandApp(null);
            fail("null must be rejected");
        } catch (IllegalArgumentException expected) {
            // the only contract this test asserts
        }
        try {
            TerminalSurfaceScope.commandApp("   ");
            fail("blank must be rejected");
        } catch (IllegalArgumentException expected) {
            // the only contract this test asserts
        }
    }
}
