package gh.nusashell.nusadesk.application.terminal;

import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;

/**
 * The presentation-facing boundary of the host-owned terminal tab set
 * (ADR-0054, building on ADR-0033).
 *
 * <p>The tabs themselves live in the host service; a surface only reads the
 * current {@link TerminalTabsSnapshot} (also retained and republished by the
 * terminal tabs bus), switches or closes tabs, asks for new ones, and drives
 * input/output through the per-tab {@link TerminalSessionPort} returned by
 * {@link #tab}. It never opens or closes an SSH connection itself.</p>
 *
 * <p>Tabs belong to the current runtime session and any tab may be closed.
 * When all tabs are closed the snapshot is empty; the runtime itself remains
 * running and the surface can open a fresh tab.</p>
 */
public interface TerminalTabsPort {

    /** Current tab set and selection. */
    TerminalTabsSnapshot snapshot();

    /**
     * The session port driving one tab's input, geometry, reconnect, and
     * output listener, or {@code null} when the id is unknown.
     */
    TerminalSessionPort tab(String tabId);

    /** Make a tab the visible one. A no-op when the id is unknown. */
    void select(String tabId);

    /**
     * Open an additional shell tab ("New terminal") and select it.
     *
     * @return the new tab's snapshot
     * @throws TerminalTabException {@link TerminalTabException.Reason#TAB_LIMIT}
     *                              at the cap, or
     *                              {@link TerminalTabException.Reason#NOT_RUNNING}
     *                              when no runtime session is running
     */
    TerminalTabSnapshot openShell() throws TerminalTabException;

    /**
     * Open a tab running a terminal-command app's command, or select the app's
     * existing tab when it is still live.
     *
     * <p>{@code command} is validated as a {@code TerminalCommand} before any
     * tab work happens. A live tab for the same {@code commandAppId} (session
     * state not {@code DROPPED}/{@code FAILED}) is selected and returned
     * instead of opening a second tab for that app; a dead tab for the same
     * app is closed first so it cannot consume a slot.</p>
     *
     * @return the selected command tab's snapshot
     * @throws TerminalTabException {@link TerminalTabException.Reason#INVALID_COMMAND},
     *                              {@link TerminalTabException.Reason#NOT_RUNNING},
     *                              or {@link TerminalTabException.Reason#TAB_LIMIT}
     */
    TerminalTabSnapshot openOrSelectCommand(String commandAppId, String command)
            throws TerminalTabException;

    /**
     * Close a tab and its session. A no-op for an unknown id. Closing the
     * selected tab selects the previous tab in insertion order; closing the
     * last tab leaves an empty snapshot while the runtime keeps running.
     */
    void close(String tabId);

    /** Whether the tab cap is reached, so surfaces can hide "New terminal". */
    boolean isFull();
}
