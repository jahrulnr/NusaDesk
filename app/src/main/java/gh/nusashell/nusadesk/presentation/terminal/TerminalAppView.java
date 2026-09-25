package gh.nusashell.nusadesk.presentation.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.TerminalOutputListener;
import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;
import gh.nusashell.nusadesk.application.terminal.TerminalTabsPort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabSnapshot;
import gh.nusashell.nusadesk.domain.terminal.TerminalTabsSnapshot;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsBus;
import gh.nusashell.nusadesk.infrastructure.service.TerminalTabsRegistry;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.widget.TerminalBridgeView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Terminal — the multi-tab in-app shell, opened as a maximized surface.
 *
 * <p>The terminal sessions themselves live in the host service (ADR-0033,
 * extended to tabs by ADR-0054): the service opens one SSH session per tab to
 * the fixed loopback endpoint once Linux is running, keeps them across
 * Activity recreation, and re-attaches one only on an explicit reconnect. This
 * surface is a <em>consumer</em> of that tab set: it renders the
 * {@link TerminalTabsSnapshot}s delivered by {@link TerminalTabsBus}, forwards
 * input and geometry through each tab's own {@link TerminalSessionPort}
 * (resolved via {@link TerminalTabsRegistry}), and never opens or closes an
 * SSH connection itself — detaching the view leaves every tab's shell
 * running.</p>
 *
 * <p>One {@link TerminalBridgeView} is kept per tab inside the
 * {@code terminal_tab_host} container: only the selected tab is visible, and a
 * live tab keeps streaming into its own bridge while hidden, so switching tabs
 * is a visibility change and no host-side replay buffer exists. All tabs share
 * the single container geometry — a resize reported by the visible bridge is
 * forwarded to every live session, and the surface's last known size is
 * replayed to a tab the moment its session reaches {@code RUNNING}.</p>
 *
 * <p>Linux is background infrastructure: it starts from an app launch, so this
 * surface has no session control. While the runtime is not running — or no tab
 * exists yet — it says so and offers a way back to the launcher, never a
 * second start button. A selected tab whose shell dropped or failed while
 * Linux stayed up shows the reconnect banner; the same action is offered by
 * the host notification.</p>
 *
 * <p>The terminal itself is a real WebView host ({@link TerminalBridgeView})
 * running the packaged xterm bundle over an owned origin. Terminal I/O crosses
 * the WebView boundary through a {@code WebMessagePort} only — no
 * {@code addJavascriptInterface}.</p>
 */
public final class TerminalAppView extends FrameLayout
        implements TerminalTabsBus.Listener, SessionStatusAware {

    /**
     * Snapshot observer for the host (the options menu rebuilds its tab list
     * from every delivery). Called on the main thread after each snapshot is
     * rendered, whether or not this surface is currently visible.
     */
    public interface TabsListener {
        void onTabsChanged(TerminalTabsSnapshot snapshot);
    }

    private static final int INITIAL_COLS = 80;
    private static final int INITIAL_ROWS = 24;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** One bridge per live tab, in the snapshot's insertion order. */
    private final Map<String, TerminalBridgeView> bridges = new LinkedHashMap<>();

    private FrameLayout tabHost;
    private TerminalKeyRowView keyRow;
    private View attachPanel;
    private TextView attachTitle;
    private TextView attachBody;
    private Button attachAction;
    private View banner;
    private TextView bannerText;
    private Button bannerAction;

    private TerminalTabsRegistry tabsRegistry;
    private TerminalTabsBus tabsBus;
    private TabsListener tabsListener;

    private TerminalTabsSnapshot tabsSnapshot = TerminalTabsSnapshot.empty();
    private HostRuntimeStatus hostStatus;
    /** Last size reported by the visible terminal page; replayed to every tab. */
    private int lastCols = INITIAL_COLS;
    private int lastRows = INITIAL_ROWS;

    private final RuntimeStatusBus.Listener statusListener = this::renderSessionStatus;

    public TerminalAppView(Context context) {
        super(context);
        LayoutInflater.from(context).inflate(R.layout.widget_terminal_screen, this, true);
        initViews();
    }

    public TerminalAppView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.widget_terminal_screen, this, true);
        initViews();
    }

    /**
     * Binds the inflated views and wires listeners. Called from the constructor
     * (not {@link #onFinishInflate()}) because this surface's layout root is
     * {@code <merge>}, and Android does not invoke {@code onFinishInflate} on the
     * attach root when a {@code <merge>} layout is self-inflated via
     * {@code inflate(resource, this, true)}.
     */
    private void initViews() {
        tabHost = findViewById(R.id.terminal_tab_host);
        keyRow = findViewById(R.id.terminal_key_row);
        attachPanel = findViewById(R.id.terminal_attach_panel);
        attachTitle = findViewById(R.id.attach_title);
        attachBody = findViewById(R.id.attach_body);
        attachAction = findViewById(R.id.attach_action);
        banner = findViewById(R.id.terminal_banner);
        bannerText = findViewById(R.id.banner_text);
        bannerAction = findViewById(R.id.banner_action);

        keyRow.setListener(sequence -> sendInput(tabsSnapshot.getSelectedTabId(), sequence));
        bannerAction.setOnClickListener(view -> reconnectSelectedTab());
        updateUi();
    }

    /** Wires the attach prompt's "Go to all apps" action to host navigation. */
    public void setOnGoToDesktopListener(OnClickListener listener) {
        attachAction.setOnClickListener(listener);
    }

    /**
     * Provides the host-owned terminal tab set this surface consumes. Both are
     * singletons (ADR-0054): the registry resolves the current tabs port and
     * the bus delivers tab snapshots, replayed on registration so a surface
     * created after a transition renders the retained snapshot.
     */
    public void setTerminalDependencies(
            TerminalTabsRegistry tabsRegistry, TerminalTabsBus tabsBus) {
        this.tabsRegistry = tabsRegistry;
        this.tabsBus = tabsBus;
        updateUi();
    }

    /**
     * Sets the observer called with every rendered snapshot (nullable). The
     * host uses it to rebuild the terminal's options menu; the callback fires
     * on the main thread whether or not this surface is currently visible.
     */
    public void setOnTabsChangedListener(TabsListener listener) {
        this.tabsListener = listener;
    }

    // ---- Host runtime status (RuntimeStatusBus delivery, main thread) ----

    /**
     * The runtime and the terminal tabs are separate concerns: the host service
     * opens and closes the whole tab set as the runtime moves through
     * {@code RUNNING}, so this surface only keeps the latest runtime status and
     * re-renders — the tab snapshot is what reconciles the bridges.
     */
    @Override
    public void renderSessionStatus(HostRuntimeStatus status) {
        hostStatus = status;
        updateUi();
    }

    // ---- Tab snapshots (TerminalTabsBus.Listener, main thread) ----

    @Override
    public void onTerminalTabs(TerminalTabsSnapshot snapshot) {
        TerminalTabsSnapshot previous = tabsSnapshot;
        tabsSnapshot = snapshot;
        reconcileBridges(previous, snapshot);
        updateUi();
        TabsListener listener = tabsListener;
        if (listener != null) {
            listener.onTabsChanged(snapshot);
        }
    }

    /**
     * Reconciles the bridge set with the latest snapshot: a bridge is created
     * for a tab that just appeared, released for one that disappeared, and the
     * selected tab is the only visible one. Also applies the per-tab effects a
     * snapshot carries — replaying the surface geometry to a tab whose session
     * just reached {@code RUNNING}, and fitting/focusing the newly selected
     * bridge so typed input lands on the visible terminal.
     */
    private void reconcileBridges(
            TerminalTabsSnapshot previous, TerminalTabsSnapshot next) {
        Iterator<Map.Entry<String, TerminalBridgeView>> it =
                bridges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TerminalBridgeView> entry = it.next();
            if (next.tab(entry.getKey()) == null) {
                TerminalSessionPort port = tabPort(entry.getKey());
                if (port != null) {
                    port.setOutputListener(null);
                }
                tabHost.removeView(entry.getValue());
                entry.getValue().release();
                it.remove();
            }
        }

        List<String> newBridges = new ArrayList<>();
        for (TerminalTabSnapshot tab : next.getTabs()) {
            if (!bridges.containsKey(tab.getId())) {
                TerminalBridgeView bridge = new TerminalBridgeView(getContext());
                bridge.setListener(new TabBridgeListener(tab.getId()));
                tabHost.addView(bridge, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                bridges.put(tab.getId(), bridge);
                newBridges.add(tab.getId());
            }
            // Re-registered on every snapshot, not only at creation: a
            // recreated host service can publish the same tab ids ("shell" is a
            // constant) over a fresh controller, and only the current port must
            // ever hold this surface's listener.
            TerminalSessionPort port = tabPort(tab.getId());
            if (port != null) {
                port.setOutputListener(new TabOutputListener(tab.getId()));
            }
        }

        String selectedId = next.getSelectedTabId();
        boolean selectionChanged =
                !Objects.equals(selectedId, previous.getSelectedTabId());
        for (TerminalTabSnapshot tab : next.getTabs()) {
            TerminalBridgeView bridge = bridges.get(tab.getId());
            boolean selected = tab.getId().equals(selectedId);
            bridge.setVisibility(selected ? VISIBLE : GONE);

            TerminalTabSnapshot before = previous.tab(tab.getId());
            boolean running =
                    tab.getStatus().getState() == TerminalSessionState.RUNNING;
            // The PTY must agree with the page's real size: replay the cached
            // size when a tab's session reaches RUNNING, and also when its
            // bridge was just created for an already-running session — the
            // bus's retained snapshot on a re-attach carries no transition.
            boolean replay =
                    running && (newBridges.contains(tab.getId())
                            || before == null
                            || before.getStatus().getState()
                                    != TerminalSessionState.RUNNING);
            if (replay) {
                TerminalSessionPort port = tabPort(tab.getId());
                if (port != null) {
                    port.resize(lastCols, lastRows);
                }
            }
            if (selected && (replay || selectionChanged)) {
                bridge.fit();
                bridge.focus();
            }
        }
    }

    // ---- Local session access ----

    /**
     * The session port of one tab, or {@code null} before the host service has
     * created its terminal tab set. Resolved fresh on every use so a recreated
     * service is picked up without re-wiring.
     */
    private TerminalSessionPort tabPort(String tabId) {
        TerminalTabsRegistry registry = tabsRegistry;
        if (registry == null || tabId == null) {
            return null;
        }
        TerminalTabsPort port = registry.port();
        return port == null ? null : port.tab(tabId);
    }

    /**
     * Explicit re-attach of the selected tab after its shell dropped while the
     * runtime stayed up. A no-op when the runtime is not actually running.
     */
    private void reconnectSelectedTab() {
        SessionSnapshot snapshot = hostStatus == null ? null : hostStatus.getSnapshot();
        if (snapshot == null || hostStatus.getState() != SessionState.RUNNING) {
            return;
        }
        TerminalSessionPort port = tabPort(tabsSnapshot.getSelectedTabId());
        if (port != null) {
            port.reconnect();
        }
    }

    private boolean isSessionRunning() {
        SessionState session = sessionState();
        return session == SessionState.RUNNING || session == SessionState.RECONNECTING;
    }

    private SessionState sessionState() {
        return hostStatus == null ? SessionState.NOT_STARTED : hostStatus.getState();
    }

    /**
     * The single stdin path of this surface: both a page's {@code INPUT}
     * messages and the native accessory key row arrive here, each already
     * bound to the tab they belong to, so the key row cannot reach the guest
     * any other way.
     */
    private void sendInput(String tabId, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        TerminalSessionPort port = tabPort(tabId);
        if (port != null) {
            port.write(data.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            // A hidden view loses focus, and the container may have changed
            // size while this surface was hidden: every tab shares this one
            // geometry, so replay it to all live sessions, then refit and
            // refocus the visible bridge so xterm and its PTY agree again and
            // a hardware Enter reaches the terminal rather than whichever
            // control was focused last.
            for (TerminalTabSnapshot tab : tabsSnapshot.getTabs()) {
                TerminalSessionPort port = tabPort(tab.getId());
                if (port != null) {
                    port.resize(lastCols, lastRows);
                }
            }
            TerminalBridgeView selected = bridges.get(tabsSnapshot.getSelectedTabId());
            if (selected != null) {
                selected.fit();
                selected.focus();
            }
        }
    }

    // ---- UI state rendering ----

    /**
     * Renders the honest state from the two inputs this surface owns nothing
     * of: the host runtime status and the latest tab snapshot. The attach
     * panel shows whenever the runtime is not running or no tab exists yet;
     * the banner shows when the selected tab's shell is gone while the runtime
     * stays up; the key row is live only while the selected tab runs; and each
     * bridge's overlay mirrors its own tab's connection state.
     */
    private void updateUi() {
        SessionState session = sessionState();
        boolean sessionRunning = isSessionRunning();
        TerminalTabSnapshot selected = tabsSnapshot.selected();
        TerminalSessionState selectedState =
                selected == null ? null : selected.getStatus().getState();

        boolean showAttach = !sessionRunning || tabsSnapshot.isEmpty();
        attachPanel.setVisibility(showAttach ? VISIBLE : GONE);
        if (showAttach) {
            if (sessionRunning && tabsSnapshot.isEmpty()) {
                attachTitle.setText(R.string.terminal_no_tabs_title);
                attachBody.setText(R.string.terminal_no_tabs_body);
                attachAction.setVisibility(GONE);
            } else {
                renderAttachPanel(session);
            }
        }

        // The selected shell dropped or failed while Linux stayed up: offer
        // the explicit reconnect here and in the host notification.
        boolean shellNeedsReconnect = selectedState == TerminalSessionState.DROPPED
                || selectedState == TerminalSessionState.FAILED;
        banner.setVisibility(
                shellNeedsReconnect && sessionRunning ? VISIBLE : GONE);
        bannerText.setText(R.string.terminal_dropped_banner);
        bannerAction.setText(R.string.terminal_reconnect);

        // The accessory keys are inert without a live shell; they stay visible
        // so the row does not appear and disappear with the session.
        keyRow.setShellAttached(selectedState == TerminalSessionState.RUNNING);

        renderBridgeOverlays();
    }

    /**
     * Honest waiting prompt. It explains what Linux is doing and points at the
     * launcher; it never offers a second start/stop control of its own.
     */
    private void renderAttachPanel(SessionState session) {
        switch (session) {
            case FAILED:
                String reason = hostStatus == null ? "" : hostStatus.getFailureReason();
                attachTitle.setText(R.string.session_failed);
                attachBody.setText(getContext().getString(
                        R.string.terminal_attach_failed_body,
                        reason == null || reason.trim().isEmpty()
                                ? getContext().getString(R.string.session_failed_no_reason)
                                : reason));
                attachAction.setVisibility(VISIBLE);
                break;
            case STARTING:
            case RECOVERING:
                attachTitle.setText(R.string.session_starting);
                attachBody.setText(R.string.session_starting_detail);
                attachAction.setVisibility(GONE);
                break;
            case STOPPING:
                attachTitle.setText(R.string.session_stopping);
                attachBody.setText(R.string.session_stopping_detail);
                attachAction.setVisibility(GONE);
                break;
            default:
                attachTitle.setText(R.string.terminal_attach_stopped_title);
                attachBody.setText(R.string.terminal_attach_stopped_body);
                attachAction.setVisibility(VISIBLE);
                break;
        }
    }

    /**
     * Per-tab state overlay over each terminal. A hidden tab's overlay is kept
     * current as well so switching back never shows a stale state; only real
     * connection states are drawn — never to fake a live terminal.
     */
    private void renderBridgeOverlays() {
        for (TerminalTabSnapshot tab : tabsSnapshot.getTabs()) {
            TerminalBridgeView bridge = bridges.get(tab.getId());
            if (bridge == null) {
                continue;
            }
            switch (tab.getStatus().getState()) {
                case CONNECTING:
                    bridge.showOverlay(
                            getContext().getString(R.string.terminal_connecting));
                    break;
                case RECONNECTING:
                    bridge.showOverlay(
                            getContext().getString(R.string.terminal_reconnecting));
                    break;
                case FAILED:
                    String detail = tab.getStatus().getDetail();
                    bridge.showOverlay(getContext().getString(R.string.terminal_failed,
                            detail == null || detail.trim().isEmpty()
                                    ? getContext().getString(R.string.terminal_failed_unknown)
                                    : detail));
                    break;
                default:
                    bridge.showOverlay(null);
                    break;
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RuntimeStatusBus.getInstance().register(statusListener);
        if (tabsBus != null) {
            tabsBus.register(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        RuntimeStatusBus.getInstance().unregister(statusListener);
        if (tabsBus != null) {
            tabsBus.unregister(this);
        }
        // The sessions belong to the host service, not this view: detaching
        // (recreation, rotation, a trip back to the launcher) must leave every
        // tab's shell running and only stop streaming to this surface. The
        // bridges are WebViews bound to a window, so they are released here; a
        // re-attach recreates them from the bus's retained snapshot.
        for (Map.Entry<String, TerminalBridgeView> entry : bridges.entrySet()) {
            TerminalSessionPort port = tabPort(entry.getKey());
            if (port != null) {
                port.setOutputListener(null);
            }
            entry.getValue().release();
        }
        bridges.clear();
        tabHost.removeAllViews();
        super.onDetachedFromWindow();
    }

    /**
     * One tab's {@link TerminalBridgeView.Listener}: binds the bridge's
     * ready/input/resize events to the tab they came from.
     *
     * <p>Ready and input only act for the selected tab — a hidden bridge must
     * not steal focus, and its soft-keyboard modifiers are owned by the
     * visible one. A resize is accepted only from the selected (visible)
     * bridge and is broadcast to every live session, because all tabs share
     * this surface's single container geometry.</p>
     */
    private final class TabBridgeListener implements TerminalBridgeView.Listener {
        private final String tabId;

        TabBridgeListener(String tabId) {
            this.tabId = tabId;
        }

        @Override
        public void onBridgeReady() {
            // Let the page's fit addon decide the size for the current
            // container and report it back through onTerminalResize; forcing a
            // fixed 80x24 here would undo the fit and leave the guest PTY
            // disagreeing with xterm. Hidden tabs skip this: they have no
            // geometry and must not take focus.
            if (!tabId.equals(tabsSnapshot.getSelectedTabId())) {
                return;
            }
            TerminalBridgeView bridge = bridges.get(tabId);
            if (bridge != null) {
                bridge.fit();
                bridge.focus();
            }
        }

        @Override
        public void onInput(String data) {
            // Typed input first passes the sticky CTRL/ALT modifiers the
            // accessory key row may have armed; the row clears them once used.
            // Only the visible tab consumes them — a hidden bridge cannot be
            // the one the user was typing into.
            boolean selected = tabId.equals(tabsSnapshot.getSelectedTabId());
            sendInput(tabId, selected && keyRow != null
                    ? keyRow.applyPendingModifiers(data)
                    : data);
        }

        @Override
        public void onTerminalResize(int cols, int rows) {
            // Only the visible bridge reports meaningful geometry. All tabs
            // share this surface's one container size, so the new size is
            // applied to every live session and cached for tabs that open a
            // PTY later.
            if (!tabId.equals(tabsSnapshot.getSelectedTabId())) {
                return;
            }
            lastCols = cols;
            lastRows = rows;
            for (TerminalTabSnapshot tab : tabsSnapshot.getTabs()) {
                TerminalSessionPort port = tabPort(tab.getId());
                if (port != null) {
                    port.resize(cols, rows);
                }
            }
        }
    }

    /**
     * One tab's {@link TerminalOutputListener}: decodes the session's output
     * and posts it into that tab's own bridge on the main thread, whether or
     * not the tab is currently visible — a live tab keeps filling its own
     * xterm scrollback while hidden. A write for a tab whose bridge is gone is
     * dropped (the session itself is being closed by the host anyway).
     */
    private final class TabOutputListener implements TerminalOutputListener {
        private final String tabId;

        TabOutputListener(String tabId) {
            this.tabId = tabId;
        }

        @Override
        public void onStdout(byte[] data, int len) {
            if (data == null || len <= 0) {
                return;
            }
            String text = new String(data, 0, len, StandardCharsets.UTF_8);
            mainHandler.post(() -> {
                TerminalBridgeView bridge = bridges.get(tabId);
                if (bridge != null) {
                    bridge.writeStdout(text);
                }
            });
        }

        @Override
        public void onStderr(byte[] data, int len) {
            if (data == null || len <= 0) {
                return;
            }
            String text = new String(data, 0, len, StandardCharsets.UTF_8);
            mainHandler.post(() -> {
                TerminalBridgeView bridge = bridges.get(tabId);
                if (bridge != null) {
                    bridge.writeStderr(text);
                }
            });
        }
    }
}
