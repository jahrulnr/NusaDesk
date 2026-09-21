package gh.nusashell.nusadesk.presentation.webapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory tab stack for one web app surface.
 *
 * <p>A registered web app's own page is the permanent root tab; pages it opens
 * through a user-gesture new-window request ({@code target="_blank"},
 * {@code window.open()}) become child tabs so they cannot replace the root
 * page. The stack is the single authority for which tab the surface renders:
 * the shell adds, selects, and closes tabs here and mirrors the result into
 * WebViews, instead of each WebView guessing what comes next.</p>
 *
 * <p>Child ids are minted from a monotonic in-memory counter, so a closed tab's
 * id is never reused and a stale reference cannot silently resurrect the wrong
 * WebView. The stack is deliberately pure: no Android imports, no persistence,
 * no WebView handles, so its limits and selection policy are asserted on the
 * JVM. WebView/renderer ownership lives in the view layer, keyed by these
 * immutable tab ids.</p>
 */
public final class WebAppTabStack {

    /** Stable id of the permanent root tab that hosts the app's own page. */
    public static final String ROOT_TAB_ID = "root";

    /**
     * Maximum number of live child tabs. One unbounded {@code window.open()}
     * loop must not be able to grow renderer count without limit.
     */
    public static final int MAX_CHILD_TABS = 4;

    /** Immutable snapshot of one tab, keyed by its stable id. */
    public static final class Tab {
        private final String id;
        private final boolean root;

        private Tab(String id, boolean root) {
            this.id = id;
            this.root = root;
        }

        public String getId() {
            return id;
        }

        /** True for the permanent root tab, which can never be closed. */
        public boolean isRoot() {
            return root;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Tab)) {
                return false;
            }
            Tab that = (Tab) other;
            return id.equals(that.id) && root == that.root;
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + (root ? 1 : 0);
        }

        @Override
        public String toString() {
            return "Tab{id=" + id + ", root=" + root + "}";
        }
    }

    /** Insertion-ordered so tab order is stable and deterministic: root first. */
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private String selectedId = ROOT_TAB_ID;
    private int nextChildOrdinal = 1;

    public WebAppTabStack() {
        tabs.put(ROOT_TAB_ID, new Tab(ROOT_TAB_ID, true));
    }

    /** The id of the tab the surface currently renders. Never null. */
    public String selectedTabId() {
        return selectedId;
    }

    /** Immutable snapshot of every live tab, root first, in insertion order. */
    public List<Tab> tabs() {
        return Collections.unmodifiableList(new ArrayList<>(tabs.values()));
    }

    /** The snapshot for one tab id, or null when no such tab is live. */
    public Tab tab(String id) {
        return id == null ? null : tabs.get(id);
    }

    public boolean contains(String id) {
        return id != null && tabs.containsKey(id);
    }

    /** Number of live child tabs; the root tab is not counted. */
    public int childCount() {
        return tabs.size() - 1;
    }

    /** True when another {@link #addTab()} call would be refused. */
    public boolean isFull() {
        return childCount() >= MAX_CHILD_TABS;
    }

    /**
     * Opens a child tab and selects it, so a user-gesture new-window request
     * surfaces immediately without touching the root page.
     *
     * @return the new tab's snapshot, or null when {@link #isFull()}
     */
    public Tab addTab() {
        if (isFull()) {
            return null;
        }
        String id;
        do {
            id = "tab-" + nextChildOrdinal++;
        } while (tabs.containsKey(id));
        Tab tab = new Tab(id, false);
        tabs.put(id, tab);
        selectedId = id;
        return tab;
    }

    /**
     * Selects a live tab.
     *
     * @return true when the selection changed; false for null or unknown ids,
     *         which leave the current selection untouched
     */
    public boolean select(String id) {
        if (!contains(id)) {
            return false;
        }
        selectedId = id;
        return true;
    }

    /**
     * Closes a child tab. The root tab is permanent and close on it — like on
     * null or unknown ids — is refused with false.
     *
     * <p>When the closed tab was selected, the previous tab in stack order
     * becomes selected: the earlier sibling, or the root tab when the first
     * child closes. The rule is deterministic so Back and {@code
     * window.close()} always land on the same tab.</p>
     *
     * @return true when a child tab was removed
     */
    public boolean close(String id) {
        if (id == null || ROOT_TAB_ID.equals(id) || !tabs.containsKey(id)) {
            return false;
        }
        if (id.equals(selectedId)) {
            List<String> order = new ArrayList<>(tabs.keySet());
            selectedId = order.get(order.indexOf(id) - 1);
        }
        tabs.remove(id);
        return true;
    }
}
