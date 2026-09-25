package gh.nusashell.nusadesk.domain.terminal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of every terminal tab plus the current selection
 * (ADR-0054).
 *
 * <p>This is the whole renderable state of the multi-tab terminal: views show
 * one surface per tab, highlight {@link #getSelectedTabId()}, and map each
 * tab's {@link TerminalSessionStatus} onto its banner/overlay. A snapshot is
 * republished on every state change — tab added, closed, selected, or a tab's
 * session moving — so consumers never diff; they just render the latest.</p>
 *
 * <p>An <em>empty</em> snapshot (no runtime session running) is a real state:
 * the terminal surface renders its "runtime not running" prompt from it.</p>
 */
public final class TerminalTabsSnapshot {

    private static final TerminalTabsSnapshot EMPTY =
            new TerminalTabsSnapshot(Collections.emptyList(), null);

    private final List<TerminalTabSnapshot> tabs;
    private final String selectedTabId;

    /**
     * @param tabs          tabs in insertion order; the initial shell tab is
     *                      first when the runtime has just started, but users
     *                      may close it and empty snapshots are valid
     * @param selectedTabId id of the visible tab, or {@code null} when there
     *                      are no tabs; must name an existing tab otherwise
     */
    public TerminalTabsSnapshot(List<TerminalTabSnapshot> tabs, String selectedTabId) {
        if (tabs == null) {
            throw new IllegalArgumentException("tabs must not be null");
        }
        List<TerminalTabSnapshot> copy =
                Collections.unmodifiableList(new ArrayList<>(tabs));
        if (selectedTabId != null) {
            boolean found = false;
            for (TerminalTabSnapshot tab : copy) {
                if (tab.getId().equals(selectedTabId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("selectedTabId must name an existing tab");
            }
        }
        this.tabs = copy;
        this.selectedTabId = selectedTabId;
    }

    /** Shared empty snapshot: the state while no runtime session is running. */
    public static TerminalTabsSnapshot empty() {
        return EMPTY;
    }

    /** All tabs in insertion order; unmodifiable, never null. */
    public List<TerminalTabSnapshot> getTabs() {
        return tabs;
    }

    /** Id of the selected (visible) tab, or {@code null} when empty. */
    public String getSelectedTabId() {
        return selectedTabId;
    }

    /** The selected tab, or {@code null} when the snapshot is empty. */
    public TerminalTabSnapshot selected() {
        return tab(selectedTabId);
    }

    /** The tab with the given id, or {@code null} when unknown. */
    public TerminalTabSnapshot tab(String id) {
        if (id == null) {
            return null;
        }
        for (TerminalTabSnapshot tab : tabs) {
            if (tab.getId().equals(id)) {
                return tab;
            }
        }
        return null;
    }

    public int tabCount() {
        return tabs.size();
    }

    public boolean isEmpty() {
        return tabs.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TerminalTabsSnapshot)) {
            return false;
        }
        TerminalTabsSnapshot that = (TerminalTabsSnapshot) o;
        return Objects.equals(tabs, that.tabs)
                && Objects.equals(selectedTabId, that.selectedTabId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tabs, selectedTabId);
    }
}
