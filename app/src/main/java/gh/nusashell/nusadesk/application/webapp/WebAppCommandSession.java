package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.application.terminal.TerminalTabsPort;
import gh.nusashell.nusadesk.application.terminal.TerminalTabException;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

/** Routes a combined app to one service-owned command tab, independent of its web surface. */
public final class WebAppCommandSession {
    private WebAppCommandSession() { }

    public static boolean isOwner(String id) { return id != null && id.startsWith("web:"); }

    public static String ownerId(WebAppId id) { return "web:" + id.value(); }

    public static void start(WebAppDefinition app, TerminalTabsPort tabs) throws TerminalTabException {
        if (!app.hasStartupCommand()) return;
        if (tabs == null) throw new TerminalTabException(TerminalTabException.Reason.NOT_RUNNING, "Linux is not running");
        invalidateChangedCommand(app, tabs);
        tabs.openOrSelectCommand(ownerId(app.getId()), app.getStartupCommand().value());
    }

    /** Metadata edits keep the server alive; changing/removing its command closes only that session. */
    public static void invalidateChangedCommand(WebAppDefinition app, TerminalTabsPort tabs) {
        if (tabs == null) return;
        for (TerminalTabSnapshot tab : tabs.snapshot().getTabs()) {
            if (ownerId(app.getId()).equals(tab.getCommandAppId())
                    && (!app.hasStartupCommand() || !app.getStartupCommand().value().equals(tab.getCommand()))) {
                tabs.close(tab.getId());
            }
        }
    }

    public static java.util.concurrent.CompletableFuture<Void> stopAndAwait(WebAppId id, TerminalTabsPort tabs, java.util.concurrent.Executor worker) {
        java.util.List<java.util.concurrent.CompletableFuture<Void>> stopped = new java.util.ArrayList<>();
        if (tabs != null) for (TerminalTabSnapshot tab : tabs.snapshot().getTabs()) {
            if (ownerId(id).equals(tab.getCommandAppId())) stopped.add(tabs.closeAndAwait(tab.getId(), worker));
        }
        return java.util.concurrent.CompletableFuture.allOf(stopped.toArray(new java.util.concurrent.CompletableFuture<?>[0]));
    }

    public static void stop(WebAppId id, TerminalTabsPort tabs) {
        if (tabs == null) return;
        for (TerminalTabSnapshot tab : tabs.snapshot().getTabs()) {
            if (ownerId(id).equals(tab.getCommandAppId())) tabs.close(tab.getId());
        }
    }
}
