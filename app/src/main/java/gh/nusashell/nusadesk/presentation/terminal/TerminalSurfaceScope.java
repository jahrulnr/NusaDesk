package gh.nusashell.nusadesk.presentation.terminal;

import java.util.ArrayList;
import java.util.List;

import gh.nusashell.nusadesk.domain.terminal.TerminalTabKind;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;

/**
 * Which terminal tabs one surface owns (ADR-0054, isolation amendment).
 *
 * <p>The desktop shell isolates apps, and the terminal is one of them: the
 * built-in Terminal surface owns the shell tabs, and a launcher
 * terminal-command app gets a surface of its own that owns exactly that app's
 * tab. A tab that belongs to another surface is invisible and unreachable
 * there — never rendered, never listed in that surface's options menu, never
 * given a WebView — so opening one app cannot present itself as a way to
 * "open another app" from inside the terminal.</p>
 *
 * <p>Pure: it only reads snapshots. The surface uses it to decide which
 * bridges to keep and which tab is the one on screen; the host uses it to
 * build each surface's options menu.</p>
 */
public final class TerminalSurfaceScope {

    /** Owning terminal-command app id, or {@code null} for the shell scope. */
    private final String commandAppId;

    private TerminalSurfaceScope(String commandAppId) {
        this.commandAppId = commandAppId;
    }

    /** The built-in terminal's scope: the shell tabs, and only those. */
    public static TerminalSurfaceScope shellTabs() {
        return new TerminalSurfaceScope(null);
    }

    /** One launcher terminal-command app's scope: exactly that app's tab. */
    public static TerminalSurfaceScope commandApp(String commandAppId) {
        if (commandAppId == null || commandAppId.trim().isEmpty()) {
            throw new IllegalArgumentException("commandAppId must not be blank");
        }
        return new TerminalSurfaceScope(commandAppId);
    }

    /** Whether this scope belongs to a terminal-command app's surface. */
    public boolean isCommandAppScope() {
        return commandAppId != null;
    }

    /** Whether {@code tab} belongs to the surface this scope describes. */
    public boolean includes(TerminalTabSnapshot tab) {
        if (tab == null) {
            return false;
        }
        if (commandAppId == null) {
            return tab.getKind() == TerminalTabKind.SHELL;
        }
        return tab.getKind() == TerminalTabKind.COMMAND
                && commandAppId.equals(tab.getCommandAppId());
    }

    /** The in-scope tabs, in the snapshot's own order. Never null. */
    public List<TerminalTabSnapshot> inScope(TerminalTabsSnapshot snapshot) {
        List<TerminalTabSnapshot> result = new ArrayList<>();
        if (snapshot == null) {
            return result;
        }
        for (TerminalTabSnapshot tab : snapshot.getTabs()) {
            if (includes(tab)) {
                result.add(tab);
            }
        }
        return result;
    }

    /**
     * The in-scope tab this surface treats as the one on screen: the
     * selection while it belongs to the scope, otherwise the most recently
     * created in-scope tab — a hidden surface keeps showing its own tab while
     * the user is elsewhere, and re-selecting happens when the surface is
     * shown again. {@code null} when no in-scope tab exists.
     */
    public TerminalTabSnapshot visible(TerminalTabsSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        TerminalTabSnapshot selected = snapshot.selected();
        if (includes(selected)) {
            return selected;
        }
        List<TerminalTabSnapshot> tabs = inScope(snapshot);
        return tabs.isEmpty() ? null : tabs.get(tabs.size() - 1);
    }
}
