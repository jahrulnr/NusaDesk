package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;
import gh.nusashell.nusadesk.application.terminal.TerminalTabException;
import gh.nusashell.nusadesk.domain.network.RuntimePort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabKind;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionConfig;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransport;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransportFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link TerminalTabsController} with fake per-tab transports so the
 * multi-tab contract (ADR-0054) — root shell tab, monotonic ids, the cap, the
 * per-app live-tab rule, selection on close, and the runtime lifecycle of
 * ADR-0033 — is exercised without a real SSH client.
 */
public class TerminalTabsControllerTest {

    private static final String APP_ID = "ubuntu-base-arm64";
    private static final String VERSION = "0.1.0";
    private static final String SESSION = "sess-1";

    private List<FakeTransport> created;
    private List<TerminalTabsSnapshot> published;
    private TerminalTabsController controller;

    @Before
    public void setUp() {
        created = new ArrayList<>();
        published = new ArrayList<>();
        TerminalTransportFactory factory = () -> {
            FakeTransport transport = new FakeTransport();
            created.add(transport);
            return transport;
        };
        // A direct executor keeps every transition synchronous, matching the
        // production main-thread executor's serialization guarantees.
        controller = new TerminalTabsController(factory, published::add, Runnable::run);
    }

    @Test
    public void emptyUntilARuntimeSessionRuns() {
        assertTrue(controller.snapshot().isEmpty());
        assertNull(controller.snapshot().getSelectedTabId());
        assertNull(controller.snapshot().selected());
        assertFalse(controller.isFull());
    }

    @Test
    public void runtimeRunningOpensThePermanentShellTab() {
        controller.onRuntimeStatus(running(SESSION));

        TerminalTabsSnapshot snapshot = last();
        assertEquals(1, snapshot.tabCount());
        TerminalTabSnapshot shell = snapshot.tab(TerminalTabsController.SHELL_TAB_ID);
        assertEquals(TerminalTabKind.SHELL, shell.getKind());
        assertEquals(1, shell.getDisplayOrdinal());
        assertTrue(shell.isClosable());
        assertNull(shell.getCommandAppId());
        assertNull(shell.getCommand());
        assertEquals(TerminalSessionState.CONNECTING, shell.getStatus().getState());
        assertEquals(TerminalTabsController.SHELL_TAB_ID, snapshot.getSelectedTabId());
        assertEquals(1, created.size());
        assertNull("the shell tab stays a shell channel", created.get(0).command);
    }

    @Test
    public void sameSessionRepublishLeavesTabsAlone() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();

        controller.onRuntimeStatus(running(SESSION));

        assertEquals("republishing RUNNING must not re-attach any tab", 2, created.size());
        assertEquals(2, last().tabCount());
    }

    @Test
    public void newRuntimeSessionReplacesEveryTab() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();
        FakeTransport shellTransport = created.get(0);
        FakeTransport extraTransport = created.get(1);

        controller.onRuntimeStatus(running("sess-2"));

        assertEquals(1, shellTransport.closeCount);
        assertEquals(1, extraTransport.closeCount);
        TerminalTabsSnapshot snapshot = last();
        assertEquals(1, snapshot.tabCount());
        assertEquals(TerminalTabsController.SHELL_TAB_ID, snapshot.getSelectedTabId());
        assertEquals(3, created.size());
        assertNull(created.get(2).command);
    }

    @Test
    public void runtimeNotRunningClosesEveryTabAndPublishesEmpty() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();

        controller.onRuntimeStatus(notRunning(SessionState.STOPPING));

        assertTrue(last().isEmpty());
        assertNull(last().getSelectedTabId());
        assertEquals(1, created.get(0).closeCount);
        assertEquals(1, created.get(1).closeCount);
        assertTrue(controller.snapshot().isEmpty());
    }

    @Test
    public void openShellAddsAClosableSelectedTab() throws Exception {
        controller.onRuntimeStatus(running(SESSION));

        TerminalTabSnapshot tab = controller.openShell();

        assertEquals("tab-1", tab.getId());
        assertEquals(TerminalTabKind.SHELL, tab.getKind());
        assertEquals(2, tab.getDisplayOrdinal());
        assertTrue(tab.isClosable());
        assertEquals("tab-1", last().getSelectedTabId());
        assertEquals(2, created.size());
        assertNull(created.get(1).command);
    }

    @Test
    public void openShellWithoutRuntimeThrowsNotRunning() {
        try {
            controller.openShell();
            fail("expected NOT_RUNNING");
        } catch (TerminalTabException e) {
            assertEquals(TerminalTabException.Reason.NOT_RUNNING, e.getReason());
        }
        assertTrue(created.isEmpty());
    }

    @Test
    public void tabLimitIsEnforced() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        for (int i = 0; i < TerminalTabsController.MAX_TABS - 1; i++) {
            controller.openShell();
        }

        assertEquals(TerminalTabsController.MAX_TABS, last().tabCount());
        assertTrue(controller.isFull());
        try {
            controller.openShell();
            fail("expected TAB_LIMIT");
        } catch (TerminalTabException e) {
            assertEquals(TerminalTabException.Reason.TAB_LIMIT, e.getReason());
        }
        assertEquals(TerminalTabsController.MAX_TABS, created.size());
    }

    @Test
    public void closingTheInitialShellIsAllowed() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        TerminalTabSnapshot second = controller.openShell();

        controller.close(TerminalTabsController.SHELL_TAB_ID);

        assertEquals(1, last().tabCount());
        assertEquals(second.getId(), last().getSelectedTabId());
        assertEquals(1, created.get(0).closeCount);
    }

    @Test
    public void cleanCommandExitClosesOnlyThatTabAndSelectsItsPreviousTab() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        TerminalTabSnapshot command = controller.openOrSelectCommand("app-1", "top");

        created.get(1).listener.onState(SshSessionState.CLOSED, "remote closed the session");

        assertNull(last().tab(command.getId()));
        assertEquals(1, last().tabCount());
        assertEquals(TerminalTabsController.SHELL_TAB_ID, last().getSelectedTabId());
        assertEquals(1, created.get(1).closeCount);
        assertEquals(0, created.get(0).closeCount);
    }

    @Test
    public void cleanExitOfTheLastTabLeavesAnEmptyTabSet() {
        controller.onRuntimeStatus(running(SESSION));

        created.get(0).listener.onState(SshSessionState.CLOSED, "remote closed the session");

        assertTrue(last().isEmpty());
        assertNull(last().getSelectedTabId());
        assertEquals(1, created.get(0).closeCount);
    }

    @Test
    public void closingAnUnknownTabIsANoOp() {
        controller.onRuntimeStatus(running(SESSION));

        controller.close("tab-99");

        assertEquals(1, last().tabCount());
    }

    @Test
    public void closingTheSelectedTabSelectsThePreviousInInsertionOrder() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();
        controller.openShell();
        assertEquals("tab-2", last().getSelectedTabId());

        controller.close("tab-2");
        assertEquals("tab-1", last().getSelectedTabId());
        assertEquals(2, last().tabCount());
        assertEquals(1, created.get(2).closeCount);

        controller.close("tab-1");
        assertEquals(TerminalTabsController.SHELL_TAB_ID, last().getSelectedTabId());
        assertEquals(1, last().tabCount());
    }

    @Test
    public void closingAnUnselectedTabKeepsTheSelection() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();
        controller.openShell();
        controller.close("tab-1");

        assertEquals("tab-2", last().getSelectedTabId());
        assertNull(last().tab("tab-1"));
    }

    @Test
    public void tabIdsAreMonotonicAndNeverReused() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        TerminalTabSnapshot first = controller.openShell();
        controller.close(first.getId());

        TerminalTabSnapshot second = controller.openShell();

        assertEquals("tab-2", second.getId());
    }

    @Test
    public void displayOrdinalsAreMonotonicWithinTheirOwnKind() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        TerminalTabSnapshot second = controller.openShell();
        controller.close(second.getId());

        TerminalTabSnapshot third = controller.openShell();
        TerminalTabSnapshot command = controller.openOrSelectCommand("app-1", "uptime");

        // Shell ordinals are the terminal's own numbering: a command app's tab
        // lives in its own surface and must not leave a gap in it (ADR-0054,
        // isolation amendment). Command tabs count on their own sequence.
        assertEquals(3, third.getDisplayOrdinal());
        assertEquals("command tabs count separately, from one", 1,
                command.getDisplayOrdinal());
        assertEquals(3, last().tab("tab-2").getDisplayOrdinal());
        assertEquals(1, last().tab(command.getId()).getDisplayOrdinal());
    }

    @Test
    public void selectSwitchesTheVisibleTab() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();
        controller.select(TerminalTabsController.SHELL_TAB_ID);

        assertEquals(TerminalTabsController.SHELL_TAB_ID, last().getSelectedTabId());

        controller.select("unknown");
        assertEquals(TerminalTabsController.SHELL_TAB_ID, last().getSelectedTabId());
    }

    @Test
    public void commandTabStoresTheAppAndValidatedCommand() throws Exception {
        controller.onRuntimeStatus(running(SESSION));

        TerminalTabSnapshot tab =
                controller.openOrSelectCommand("app-1", "  docker exec -it codex bash  ");

        assertEquals(TerminalTabKind.COMMAND, tab.getKind());
        assertEquals("app-1", tab.getCommandAppId());
        assertEquals("docker exec -it codex bash", tab.getCommand());
        assertTrue(tab.isClosable());
        assertEquals(tab.getId(), last().getSelectedTabId());
        // The exec transport runs the validated (trimmed) command.
        assertEquals("docker exec -it codex bash", created.get(1).command);
    }

    @Test
    public void openOrSelectCommandRejectsAnInvalidCommand() {
        controller.onRuntimeStatus(running(SESSION));

        try {
            controller.openOrSelectCommand("app-1", "ls\nrm -rf /");
            fail("expected INVALID_COMMAND");
        } catch (TerminalTabException e) {
            assertEquals(TerminalTabException.Reason.INVALID_COMMAND, e.getReason());
        }
        assertEquals(1, last().tabCount());
        assertEquals(1, created.size());
    }

    @Test
    public void openOrSelectCommandWithoutRuntimeThrowsNotRunning() {
        try {
            controller.openOrSelectCommand("app-1", "uptime");
            fail("expected NOT_RUNNING");
        } catch (TerminalTabException e) {
            assertEquals(TerminalTabException.Reason.NOT_RUNNING, e.getReason());
        }
        assertTrue(created.isEmpty());
    }

    @Test
    public void reopeningALiveCommandTabSelectsItInsteadOfOpening() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        TerminalTabSnapshot first = controller.openOrSelectCommand("app-1", "uptime");
        controller.select(TerminalTabsController.SHELL_TAB_ID);

        TerminalTabSnapshot again = controller.openOrSelectCommand("app-1", "uptime");

        assertEquals("the live command tab is reused", first.getId(), again.getId());
        assertEquals(first.getId(), last().getSelectedTabId());
        assertEquals("no second session is opened", 2, created.size());
    }

    @Test
    public void aDeadCommandTabIsClosedSoItCannotBlockTheCap() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openOrSelectCommand("app-1", "uptime");
        controller.openShell();
        controller.openShell();
        controller.openShell();
        assertEquals(TerminalTabsController.MAX_TABS, last().tabCount());

        // The command exits: its tab drops and no longer serves the app.
        created.get(1).listener.onClosed("command exited");
        assertEquals(TerminalSessionState.DROPPED,
                last().tab("tab-1").getStatus().getState());
        assertTrue(controller.isFull());

        TerminalTabSnapshot reopened = controller.openOrSelectCommand("app-1", "uptime");

        assertEquals(TerminalTabKind.COMMAND, reopened.getKind());
        assertEquals("app-1", reopened.getCommandAppId());
        assertNull("the dead tab was closed, not selected", last().tab("tab-1"));
        assertEquals(TerminalTabsController.MAX_TABS, last().tabCount());
        assertEquals(reopened.getId(), last().getSelectedTabId());
    }

    @Test
    public void commandTabAtTheCapWithNoDeadTabThrowsTabLimit() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openOrSelectCommand("app-1", "uptime");
        controller.openShell();
        controller.openShell();
        controller.openShell();
        assertTrue(controller.isFull());

        try {
            controller.openOrSelectCommand("app-2", "top");
            fail("expected TAB_LIMIT");
        } catch (TerminalTabException e) {
            assertEquals(TerminalTabException.Reason.TAB_LIMIT, e.getReason());
        }
    }

    @Test
    public void tabPortDrivesOnlyItsOwnSession() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();

        TerminalSessionPort port = controller.tab("tab-1");
        byte[] frame = "ls\n".getBytes(StandardCharsets.UTF_8);
        port.write(frame);
        port.resize(120, 40);

        assertEquals(1, created.get(1).writes.size());
        assertTrue(created.get(0).writes.isEmpty());
        assertNull(controller.tab("unknown"));
    }

    @Test
    public void aTabsSessionStateFlowsIntoTheSnapshot() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openOrSelectCommand("app-1", "uptime");

        created.get(1).listener.onState(SshSessionState.RUNNING, "command channel open");

        TerminalTabsSnapshot snapshot = last();
        assertEquals(TerminalSessionState.RUNNING,
                snapshot.tab("tab-1").getStatus().getState());
        assertEquals(TerminalSessionState.CONNECTING,
                snapshot.tab(TerminalTabsController.SHELL_TAB_ID).getStatus().getState());
    }

    @Test
    public void reconnectOnADroppedCommandTabRerunsTheCommand() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openOrSelectCommand("app-1", "uptime");
        created.get(1).listener.onClosed("command exited");
        assertEquals(TerminalSessionState.DROPPED,
                last().tab("tab-1").getStatus().getState());

        controller.tab("tab-1").reconnect();

        assertEquals(3, created.size());
        assertEquals("uptime", created.get(2).command);
        assertEquals(TerminalSessionState.CONNECTING,
                last().tab("tab-1").getStatus().getState());
    }

    @Test
    public void staleTransportCallbacksChangeNothing() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openOrSelectCommand("app-1", "uptime");
        FakeTransport stale = created.get(1);
        controller.tab("tab-1").reconnect();
        assertEquals(3, created.size());

        stale.listener.onState(SshSessionState.RUNNING, "stale shell");
        stale.listener.onClosed("stale close");

        assertEquals(TerminalSessionState.CONNECTING,
                last().tab("tab-1").getStatus().getState());
    }

    @Test
    public void closeTearsDownEveryTab() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        controller.openShell();

        controller.close();

        assertTrue(controller.snapshot().isEmpty());
        assertTrue(last().isEmpty());
        assertEquals(1, created.get(0).closeCount);
        assertEquals(1, created.get(1).closeCount);
    }

    private TerminalTabsSnapshot last() {
        return published.get(published.size() - 1);
    }

    private static HostRuntimeStatus running(String sessionId) {
        SessionSnapshot snapshot = new SessionSnapshot(
                sessionId, APP_ID, VERSION, SessionState.RUNNING,
                new RuntimePort("127.0.0.1", 22022), 10L, 10L, "", 0);
        return new HostRuntimeStatus(snapshot, true, "ubuntu-base-arm64/ssh");
    }

    private static HostRuntimeStatus notRunning(SessionState state) {
        SessionSnapshot snapshot = new SessionSnapshot(
                SESSION, APP_ID, VERSION, state, null, 10L, 10L, "", 0);
        return new HostRuntimeStatus(snapshot, true, "ubuntu-base-arm64/ssh");
    }

    private static final class FakeTransport implements TerminalTransport {
        SshSessionConfig config;
        String command;
        SshSessionListener listener;
        int startCount;
        int closeCount;
        boolean cleanupComplete = true;
        final List<byte[]> writes = new ArrayList<>();
        int[] lastResize;

        @Override
        public void start(SshSessionConfig sessionConfig, String command,
                SshSessionListener sessionListener) {
            config = sessionConfig;
            this.command = command;
            listener = sessionListener;
            startCount++;
        }

        @Override
        public void write(byte[] data) {
            writes.add(data);
        }

        @Override
        public void resize(int cols, int rows) {
            lastResize = new int[]{cols, rows};
        }

        @Override
        public void close() {
            closeCount++;
        }
        @Override public boolean awaitClosed(long timeoutMillis) { return cleanupComplete; }
    }

    @Test public void combinedWebCommandReusesItsServiceSessionAndIsolatesEditsAndStop() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        gh.nusashell.nusadesk.domain.webapp.WebAppDefinition app = new gh.nusashell.nusadesk.domain.webapp.WebAppDefinition(
                gh.nusashell.nusadesk.domain.webapp.WebAppId.of("web-app"), "Server", null, 4050, 1, 1, 0, "someprogram serve");
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.start(app, controller);
        String first = controller.snapshot().selected().getId();
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.start(app, controller);
        assertEquals(first, controller.snapshot().selected().getId()); assertEquals(2, created.size());
        gh.nusashell.nusadesk.domain.webapp.WebAppDefinition renamed = app.withDetails("Renamed", null, 4050, 2);
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.invalidateChangedCommand(renamed, controller);
        assertEquals(2, controller.snapshot().tabCount());
        gh.nusashell.nusadesk.domain.webapp.WebAppDefinition changed = app.withDetails("Server", null, 4050, 2, "otherprogram serve");
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.start(changed, controller);
        assertFalse(first.equals(controller.snapshot().selected().getId()));
        assertEquals(1, created.get(1).closeCount); assertEquals(2, controller.snapshot().tabCount());
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.stop(app.getId(), controller);
        assertEquals(1, controller.snapshot().tabCount());
        assertEquals(TerminalTabKind.SHELL, controller.snapshot().selected().getKind());
    }
    @Test public void exitedWebServerTerminalRemainsInspectableAndRetryReplacesOnlyItsDeadTab() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        TerminalTabSnapshot server = controller.openOrSelectCommand("web:app-1", "bad-server-command");
        created.get(1).listener.onState(SshSessionState.CLOSED, "command exited");
        assertEquals(TerminalSessionState.EXITED, controller.snapshot().tab(server.getId()).getStatus().getState());
        TerminalTabSnapshot retry = controller.openOrSelectCommand("web:app-1", "bad-server-command");
        assertNull(controller.snapshot().tab(server.getId()));
        assertEquals(TerminalSessionState.CONNECTING, retry.getStatus().getState());
        assertEquals(2, controller.snapshot().tabCount());
    }
    @Test public void deadCombinedTerminalsDoNotConsumeLiveCapacity() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        for (int n = 0; n < TerminalTabsController.MAX_TABS + 1; n++) {
            controller.openOrSelectCommand("web:failed-" + n, "false");
            created.get(created.size() - 1).listener.onState(SshSessionState.CLOSED, "exited");
        }
        assertFalse(controller.isFull());
        controller.openShell();
        assertTrue(controller.snapshot().tabCount() > TerminalTabsController.MAX_TABS);
    }
    @Test public void restartWaitsForOldPortReleaseAndDeduplicatesAcrossObservers() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        gh.nusashell.nusadesk.domain.webapp.WebAppDefinition app = new gh.nusashell.nusadesk.domain.webapp.WebAppDefinition(
                gh.nusashell.nusadesk.domain.webapp.WebAppId.of("restart"), "Server", null, 4050, 1, 1, 0, "server");
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.start(app, controller);
        List<Runnable> worker = new ArrayList<>();
        gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator restarts = new gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator(
                worker::add, Runnable::run, (port, cancelled) -> {
                    assertEquals(4050, port); assertEquals(1, created.get(1).closeCount);
                    assertEquals("replacement must not start before the release fence", 2, created.size());
                }, () -> controller, () -> java.util.Collections.singletonList(app));
        java.util.concurrent.CompletableFuture<Void> first = restarts.restart(app);
        assertEquals(first, restarts.restart(app)); assertFalse(first.isDone());
        assertEquals(1, worker.size()); worker.remove(0).run();
        assertFalse("port probing waits for channel cleanup", first.isDone());
        assertEquals(2, created.size());
        worker.remove(0).run();
        assertTrue(first.isDone()); assertFalse(first.isCompletedExceptionally());
        assertEquals(3, created.size()); assertNull(restarts.pending(app.getId().value()));
    }

    @Test public void editingOrDeletingDuringRestartCancelsTheReplacement() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        gh.nusashell.nusadesk.domain.webapp.WebAppDefinition app = new gh.nusashell.nusadesk.domain.webapp.WebAppDefinition(
                gh.nusashell.nusadesk.domain.webapp.WebAppId.of("deleted"), "Server", null, 4050, 1, 1, 0, "server");
        List<Runnable> worker = new ArrayList<>();
        gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator restarts = new gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator(
                worker::add, Runnable::run, (port, cancelled) -> { assertTrue(cancelled.getAsBoolean()); },
                () -> controller, () -> java.util.Collections.singletonList(app));
        java.util.concurrent.CompletableFuture<Void> future = restarts.restart(app);
        restarts.cancel(app.getId().value()); worker.remove(0).run();
        assertTrue(future.isCancelled()); assertEquals(1, created.size());
    }
    @Test public void failedChannelCleanupPreventsReplacementEvenWhenPortIsNotBound() throws Exception {
        controller.onRuntimeStatus(running(SESSION));
        gh.nusashell.nusadesk.domain.webapp.WebAppDefinition app = new gh.nusashell.nusadesk.domain.webapp.WebAppDefinition(
                gh.nusashell.nusadesk.domain.webapp.WebAppId.of("cold"), "Server", null, 4050, 1, 1, 0, "sleep 3; server");
        gh.nusashell.nusadesk.application.webapp.WebAppCommandSession.start(app, controller);
        created.get(1).cleanupComplete = false;
        List<Runnable> worker = new ArrayList<>();
        gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator restarts = new gh.nusashell.nusadesk.application.webapp.WebAppRestartCoordinator(
                worker::add, Runnable::run, (port, cancelled) -> { throw new AssertionError("port probe cannot bypass failed channel cleanup"); },
                () -> controller, () -> java.util.Collections.singletonList(app));
        java.util.concurrent.CompletableFuture<Void> future = restarts.restart(app);
        worker.remove(0).run(); worker.remove(0).run();
        assertTrue(future.isCompletedExceptionally()); assertEquals(2, created.size());
    }
}
