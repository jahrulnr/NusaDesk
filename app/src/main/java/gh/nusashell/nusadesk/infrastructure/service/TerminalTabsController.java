package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;
import gh.nusashell.nusadesk.application.terminal.TerminalTabException;
import gh.nusashell.nusadesk.application.terminal.TerminalTabsPort;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabKind;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransportFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Host-owned set of terminal tabs for the running runtime session
 * (ADR-0054, extending ADR-0033).
 *
 * <p>One controller owns every terminal session the user can see: the initial
 * shell tab (id {@value #SHELL_TAB_ID}, opened when the runtime starts) plus
 * up to {@link #MAX_TABS} - 1 additional shell or command tabs. Every tab —
 * including the initial one — is closable, and a tab whose session ends
 * cleanly ({@link TerminalSessionState#EXITED}, a guest {@code exit} or a
 * finished command) removes itself automatically. Closing the last tab leaves
 * the set empty; the presentation layer renders the no-tab empty state and the
 * ⋮ menu still offers "New terminal". Each tab's session is a plain
 * {@link TerminalSessionController} — the same state machine and
 * stale-transport guard as the single session of ADR-0033 — so a dropped or
 * failed tab is still explicit and re-attaches only through that tab's
 * {@link TerminalSessionPort#reconnect()}, never silently, while a clean end
 * is not offered a Reconnect at all.</p>
 *
 * <p>The runtime lifecycle rule is ADR-0033's, applied to the whole set: a new
 * runtime session id replaces every tab with a fresh shell tab; a republished
 * {@code RUNNING} for the same session id leaves the tabs alone (each tab
 * reconciles itself) and must not silently re-open a tab when the set is
 * empty; a runtime that stops running closes every tab and publishes an empty
 * snapshot. Every mutation publishes a fresh {@link TerminalTabsSnapshot}
 * through the publisher.</p>
 *
 * <p>Tab ids: the initial root is {@value #SHELL_TAB_ID}; other tabs get
 * monotonic {@code tab-N} ids that are never reused, so a stale reference can
 * never name a different live session. Display ordinals
 * ({@link TerminalTabSnapshot#getDisplayOrdinal()}) are display counters scoped
 * to the current tab set, one per tab kind: the initial root is 1 and each
 * additional shell tab takes the next shell ordinal, never reusing one, so the
 * terminal's menu — which shows the shell tabs only (ADR-0054, isolation
 * amendment) — numbers them without gaps. Command tabs count on their own
 * sequence; no surface displays it. Both counters restart when a new runtime
 * session replaces the whole set.</p>
 *
 * <p>Threading mirrors {@link TerminalSessionController}: all internal state
 * is mutated on the main thread, so the controller is not thread-safe by
 * design. {@link #onRuntimeStatus}, {@link #select}, {@link #openShell},
 * {@link #openOrSelectCommand}, {@link #close(String)}, {@link #snapshot()},
 * {@link #tab(String)}, and {@link #isFull()} must be called on the main
 * thread (the service and the views both do). {@link #close()} (teardown)
 * marshals onto the injected executor so it is safe from any thread. Per-tab
 * session callbacks marshal onto the same executor, so a tab's transport
 * events and the tab model can never interleave.</p>
 */
public final class TerminalTabsController implements TerminalTabsPort {

    /**
     * Id of the initial root shell tab, opened when a runtime session first
     * reaches {@code RUNNING}. It is a stable id for that first tab only: the
     * tab is closable like any other, and a later "New terminal" creates a
     * {@code tab-N} id instead.
     */
    public static final String SHELL_TAB_ID = "shell";

    /**
     * Maximum live tabs. Every tab owns a WebView running xterm, so the cap
     * exists to bound renderer memory rather than a protocol limit.
     */
    public static final int MAX_TABS = 5;

    private final TerminalTransportFactory transportFactory;
    private final Consumer<TerminalTabsSnapshot> publisher;
    private final Executor mainExecutor;

    // All of the following is touched on the main thread only.
    private final Map<String, Tab> tabs = new LinkedHashMap<>();
    private String selectedTabId;
    private String runtimeSessionId;
    private HostRuntimeStatus lastRuntimeStatus;
    private int nextTabSeq = 1;
    /**
     * Display counters, one per tab kind. Shell ordinals are what the
     * terminal's own menu shows, so they count shell tabs only: a launcher
     * command app's tab lives in a surface of its own and must not leave a gap
     * in that numbering (ADR-0054, isolation amendment). Command ordinals stay
     * monotonic for the same reason tabs never reuse one; no surface shows
     * them.
     */
    private int nextShellDisplayOrdinal = 2;
    private int nextCommandDisplayOrdinal = 1;

    /**
     * @param transportFactory source of one fresh SSH transport per tab session
     * @param publisher        sink for tabs snapshots (production: the
     *                         {@link TerminalTabsBus}); called on the main thread
     * @param mainExecutor     serializes per-tab session callbacks; production
     *                         passes a main-thread executor, tests a direct one
     */
    public TerminalTabsController(
            TerminalTransportFactory transportFactory,
            Consumer<TerminalTabsSnapshot> publisher,
            Executor mainExecutor) {
        if (transportFactory == null) {
            throw new IllegalArgumentException("transportFactory must not be null");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
        if (mainExecutor == null) {
            throw new IllegalArgumentException("mainExecutor must not be null");
        }
        this.transportFactory = transportFactory;
        this.publisher = publisher;
        this.mainExecutor = mainExecutor;
    }

    /**
     * Follows the runtime session, exactly like the single-session controller
     * of ADR-0033 did. Called on the main thread for every runtime status
     * transition: a new session identity replaces every tab with a fresh shell
     * tab, the same identity reconciles each live tab, and a stopped runtime
     * closes every tab and publishes an empty snapshot.
     */
    public void onRuntimeStatus(HostRuntimeStatus status) {
        lastRuntimeStatus = status;
        if (status.isRuntimeRunning() && status.getSnapshot() != null) {
            String sessionId = status.getSnapshot().getSessionId();
            if (!sessionId.equals(runtimeSessionId)) {
                // A new runtime session never inherits a tab: its shell must
                // not silently run against a different guest session.
                closeAllTabs();
                runtimeSessionId = sessionId;
                openRootShellTab();
            } else {
                // Same running session: each tab reconciles itself (a live tab
                // is untouched; a tab whose session never opened re-attaches).
                for (Tab tab : new ArrayList<>(tabs.values())) {
                    tab.session.onRuntimeStatus(status);
                }
            }
        } else {
            runtimeSessionId = null;
            closeAllTabs();
            publishSnapshot();
        }
    }

    /** Current tab set. Only meaningful when called on the main thread. */
    @Override
    public TerminalTabsSnapshot snapshot() {
        List<TerminalTabSnapshot> list = new ArrayList<>(tabs.size());
        for (Tab tab : tabs.values()) {
            list.add(tab.snapshot());
        }
        return new TerminalTabsSnapshot(list, selectedTabId);
    }

    /**
     * The session port of one tab, or {@code null} when the id is unknown.
     * Only meaningful when called on the main thread; the returned port's own
     * methods keep {@link TerminalSessionController}'s threading contract
     * (input/resize from any thread, callbacks marshalled to main).
     */
    @Override
    public TerminalSessionPort tab(String tabId) {
        Tab tab = tabId == null ? null : tabs.get(tabId);
        return tab == null ? null : tab.session;
    }

    @Override
    public void select(String tabId) {
        if (!tabs.containsKey(tabId) || tabId.equals(selectedTabId)) {
            return;
        }
        selectedTabId = tabId;
        publishSnapshot();
    }

    @Override
    public TerminalTabSnapshot openShell() throws TerminalTabException {
        requireRunning();
        if (isFull()) {
            throw new TerminalTabException(
                    TerminalTabException.Reason.TAB_LIMIT,
                    "at most " + MAX_TABS + " terminal tabs");
        }
        Tab tab = addTab(newTabId(), TerminalTabKind.SHELL, null, null,
                nextShellDisplayOrdinal++, true);
        select(tab.id);
        return tab.snapshot();
    }

    @Override
    public TerminalTabSnapshot openOrSelectCommand(String commandAppId, String command)
            throws TerminalTabException {
        if (commandAppId == null || commandAppId.isEmpty()) {
            throw new IllegalArgumentException("commandAppId must not be blank");
        }
        TerminalCommand validated;
        try {
            validated = TerminalCommand.of(command);
        } catch (IllegalArgumentException e) {
            throw new TerminalTabException(
                    TerminalTabException.Reason.INVALID_COMMAND, e.getMessage());
        }
        requireRunning();
        // One live tab per command app: re-opening selects it instead of
        // spending another slot and another SSH session.
        List<Tab> dead = null;
        for (Tab tab : tabs.values()) {
            if (tab.kind == TerminalTabKind.COMMAND && commandAppId.equals(tab.commandAppId)) {
                TerminalSessionState state = tab.session.status().getState();
                if (state != TerminalSessionState.DROPPED
                        && state != TerminalSessionState.FAILED
                        && state != TerminalSessionState.EXITED) {
                    select(tab.id);
                    return tab.snapshot();
                }
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(tab);
            }
        }
        // A dead tab for the same app is closed first so it cannot block the
        // cap with a session that no longer exists.
        if (dead != null) {
            for (Tab tab : dead) {
                removeTab(tab.id);
            }
            publishSnapshot();
        }
        if (isFull()) {
            throw new TerminalTabException(
                    TerminalTabException.Reason.TAB_LIMIT,
                    "at most " + MAX_TABS + " terminal tabs");
        }
        Tab tab = addTab(newTabId(), TerminalTabKind.COMMAND, commandAppId,
                validated.value(), nextCommandDisplayOrdinal++, true);
        select(tab.id);
        return tab.snapshot();
    }

    @Override
    public void close(String tabId) {
        if (tabId == null || !tabs.containsKey(tabId)) {
            return; // unknown id
        }
        removeTab(tabId);
        publishSnapshot();
    }

    @Override
    public boolean isFull() {
        return tabs.size() >= MAX_TABS;
    }

    /**
     * Release every tab's session for good (service destroy). The runtime
     * session remains unaffected; the runtime host owns that separately.
     */
    public void close() {
        mainExecutor.execute(() -> {
            runtimeSessionId = null;
            closeAllTabs();
            publishSnapshot();
        });
    }

    private void requireRunning() throws TerminalTabException {
        if (runtimeSessionId == null) {
            throw new TerminalTabException(
                    TerminalTabException.Reason.NOT_RUNNING,
                    "no runtime session is running");
        }
    }

    /** The initial root tab: id {@value #SHELL_TAB_ID}, display ordinal 1. */
    private void openRootShellTab() {
        // Display ordinals are counters scoped to this tab set: a new runtime
        // session restarts them because every tab was just replaced.
        nextShellDisplayOrdinal = 2;
        nextCommandDisplayOrdinal = 1;
        Tab tab = addTab(SHELL_TAB_ID, TerminalTabKind.SHELL, null, null, 1, true);
        selectedTabId = tab.id;
        publishSnapshot();
    }

    private String newTabId() {
        return "tab-" + (nextTabSeq++);
    }

    private Tab addTab(String id, TerminalTabKind kind, String commandAppId,
            String command, int displayOrdinal, boolean closable) {
        TerminalSessionController session = new TerminalSessionController(
                transportFactory, command,
                status -> onTabSessionStatus(id), mainExecutor);
        Tab tab = new Tab(id, kind, commandAppId, command, displayOrdinal, closable, session);
        tabs.put(id, tab);
        // Attach to the running runtime immediately; when none runs the
        // session stays NOT_STARTED until the runtime reaches it (callers only
        // create tabs while running, so this is the attach path).
        if (lastRuntimeStatus != null) {
            session.onRuntimeStatus(lastRuntimeStatus);
        }
        return tab;
    }

    private void onTabSessionStatus(String tabId) {
        Tab tab = tabId == null ? null : tabs.get(tabId);
        if (tab == null) {
            return; // a removed tab's late session transition changes nothing
        }
        if (tab.session.status().getState() == TerminalSessionState.EXITED) {
            // A clean remote channel end ({@code exit}, a finished command)
            // closes exactly this tab; DROPPED/FAILED stay because they need
            // an explicit reconnect, never an automatic close. removeTab
            // removes the tab before closing the session, so the session's
            // own late publishes below cannot re-enter removal.
            removeTab(tabId);
        }
        publishSnapshot();
    }

    /**
     * Remove one tab and release its session. The session is closed after the
     * tab leaves the model so its final status publish is inert. Closing the
     * selected tab selects the previous tab in insertion order.
     */
    private void removeTab(String tabId) {
        List<String> order = new ArrayList<>(tabs.keySet());
        int index = order.indexOf(tabId);
        if (index < 0) {
            return;
        }
        Tab removed = tabs.remove(tabId);
        if (tabId.equals(selectedTabId)) {
            if (tabs.isEmpty()) {
                selectedTabId = null;
            } else {
                List<String> remaining = new ArrayList<>(tabs.keySet());
                int previous = index - 1;
                if (previous < 0) {
                    previous = 0;
                }
                if (previous >= remaining.size()) {
                    previous = remaining.size() - 1;
                }
                selectedTabId = remaining.get(previous);
            }
        }
        removed.session.close();
    }

    /** Close every tab's session and clear the model. Does not publish. */
    private void closeAllTabs() {
        if (tabs.isEmpty()) {
            return;
        }
        List<Tab> closing = new ArrayList<>(tabs.values());
        tabs.clear();
        selectedTabId = null;
        for (Tab tab : closing) {
            tab.session.close();
        }
    }

    private void publishSnapshot() {
        publisher.accept(snapshot());
    }

    /**
     * One tab: its stable metadata plus the {@link TerminalSessionController}
     * that owns the SSH session. The tab's published status is always read
     * live from the session so a snapshot can never disagree with it.
     */
    private static final class Tab {
        final String id;
        final TerminalTabKind kind;
        final String commandAppId;
        final String command;
        final int displayOrdinal;
        final boolean closable;
        final TerminalSessionController session;

        Tab(String id, TerminalTabKind kind, String commandAppId, String command,
                int displayOrdinal, boolean closable, TerminalSessionController session) {
            this.id = id;
            this.kind = kind;
            this.commandAppId = commandAppId;
            this.command = command;
            this.displayOrdinal = displayOrdinal;
            this.closable = closable;
            this.session = session;
        }

        TerminalTabSnapshot snapshot() {
            return new TerminalTabSnapshot(
                    id, kind, commandAppId, command, displayOrdinal, closable,
                    session.status());
        }
    }
}
