package gh.nusashell.nusadesk.presentation.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.TerminalOutputListener;
import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.service.TerminalSessionBus;
import gh.nusashell.nusadesk.infrastructure.service.TerminalSessionRegistry;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.widget.TerminalBridgeView;

import java.nio.charset.StandardCharsets;

/**
 * Terminal — the in-app shell, opened as a maximized surface.
 *
 * <p>The terminal session itself lives in the host service (ADR-0033): the
 * service opens the SSH session to the fixed loopback endpoint once Linux is
 * running, keeps it across Activity recreation, and re-attaches it from the
 * notification when the shell drops. This surface is a <em>consumer</em> of
 * that session: it renders the session's state from
 * {@link TerminalSessionBus}, forwards input and geometry through
 * {@link TerminalSessionPort}, and never opens or closes the SSH connection
 * itself — detaching the view leaves the shell running.</p>
 *
 * <p>Linux is background infrastructure: it starts from an app launch, so this
 * surface has no session control. While the session is starting it waits
 * passively, and when the session is not running it says so and offers a way
 * back to the launcher — never a second start button. A shell that dropped or
 * failed while Linux stayed up shows the reconnect banner; the same action is
 * offered by the host notification.</p>
 *
 * <p>The terminal itself is a real WebView host ({@link TerminalBridgeView})
 * running the packaged xterm bundle over an owned origin. Terminal I/O crosses
 * the WebView boundary through a {@code WebMessagePort} only — no
 * {@code addJavascriptInterface}.</p>
 */
public final class TerminalAppView extends FrameLayout
        implements TerminalSessionBus.Listener, TerminalOutputListener,
        TerminalBridgeView.Listener, SessionStatusAware {

    private enum UiState { IDLE, CONNECTING, RUNNING, RECONNECTING, FAILED }

    private static final int INITIAL_COLS = 80;
    private static final int INITIAL_ROWS = 24;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TerminalBridgeView bridge;
    private TerminalKeyRowView keyRow;
    private View attachPanel;
    private TextView attachTitle;
    private TextView attachBody;
    private Button attachAction;
    private View banner;

    private TerminalSessionRegistry terminalRegistry;
    private TerminalSessionBus terminalBus;
    private TerminalSessionState terminalState = TerminalSessionState.NOT_STARTED;
    private UiState state = UiState.IDLE;
    private String lastDetail;
    private HostRuntimeStatus hostStatus;
    /** Last size reported by the terminal page; replayed to a newly opened PTY. */
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
        bridge = findViewById(R.id.terminal_bridge);
        keyRow = findViewById(R.id.terminal_key_row);
        attachPanel = findViewById(R.id.terminal_attach_panel);
        attachTitle = findViewById(R.id.attach_title);
        attachBody = findViewById(R.id.attach_body);
        attachAction = findViewById(R.id.attach_action);
        banner = findViewById(R.id.terminal_banner);

        bridge.setListener(this);
        keyRow.setListener(this::sendInput);
        findViewById(R.id.banner_action).setOnClickListener(view -> reconnectLocal());
        updateUi();
    }

    /** Wires the attach prompt's "Go to all apps" action to host navigation. */
    public void setOnGoToDesktopListener(OnClickListener listener) {
        attachAction.setOnClickListener(listener);
    }

    /**
     * Provides the host-owned terminal session this surface consumes. Both are
     * singletons (ADR-0033): the registry resolves the current session port and
     * the bus delivers its state, replayed on registration so a surface created
     * after a transition renders the retained status.
     */
    public void setTerminalDependencies(
            TerminalSessionRegistry terminalRegistry, TerminalSessionBus terminalBus) {
        this.terminalRegistry = terminalRegistry;
        this.terminalBus = terminalBus;
        updateUi();
    }

    // ---- Session status ----

    /**
     * {@link RuntimeStatusBus} delivery, always on the main thread. The runtime
     * and the terminal session are separate concerns: the host service opens
     * and closes the terminal session as the runtime moves through
     * {@code RUNNING}, so this surface only reconciles its own UI state when
     * the runtime session ends.
     */
    @Override
    public void renderSessionStatus(HostRuntimeStatus status) {
        hostStatus = status;
        SessionSnapshot snapshot = status.getSnapshot();
        if (snapshot != null && isTerminalState(status.getState())) {
            reconcileEndedSession(status);
        }
        updateUi();
    }

    private void reconcileEndedSession(HostRuntimeStatus status) {
        // The host service closes the terminal session with the runtime; the
        // terminal bus already delivered NOT_STARTED. This surface only drops
        // any local connection state so the attach panel renders the truth.
        setState(UiState.IDLE);
    }

    private static boolean isTerminalState(SessionState state) {
        return state == SessionState.STOPPED || state == SessionState.FAILED
                || state == SessionState.CANCELLED || state == SessionState.NOT_STARTED;
    }

    // ---- Terminal session state (TerminalSessionBus.Listener, main thread) ----

    @Override
    public void onTerminalStatus(TerminalSessionStatus status) {
        terminalState = status.getState();
        switch (status.getState()) {
            case CONNECTING:
                setState(UiState.CONNECTING, status.getDetail());
                break;
            case RUNNING:
                setState(UiState.RUNNING, status.getDetail());
                // The freshly opened PTY must agree with the page's real size;
                // the cached size is replayed whether or not it changed.
                TerminalSessionPort port = currentPort();
                if (port != null) {
                    port.resize(lastCols, lastRows);
                }
                bridge.focus();
                bridge.fit();
                break;
            case RECONNECTING:
                setState(UiState.RECONNECTING, status.getDetail());
                break;
            case FAILED:
                setState(UiState.FAILED, status.getDetail());
                break;
            case DROPPED:
                setState(UiState.IDLE, status.getDetail());
                break;
            case NOT_STARTED:
            default:
                setState(UiState.IDLE, status.getDetail());
                break;
        }
    }

    // ---- TerminalOutputListener (may arrive on background threads) ----

    @Override
    public void onStdout(byte[] data, int len) {
        if (data == null || len <= 0) {
            return;
        }
        String text = new String(data, 0, len, StandardCharsets.UTF_8);
        mainHandler.post(() -> bridge.writeStdout(text));
    }

    @Override
    public void onStderr(byte[] data, int len) {
        if (data == null || len <= 0) {
            return;
        }
        String text = new String(data, 0, len, StandardCharsets.UTF_8);
        mainHandler.post(() -> bridge.writeStderr(text));
    }

    // ---- Local session access ----

    /**
     * The session port for the current host session, or {@code null} before
     * the host service has created its terminal session. Resolved fresh on
     * every use so a recreated service is picked up without re-wiring.
     */
    private TerminalSessionPort currentPort() {
        return terminalRegistry == null ? null : terminalRegistry.port();
    }

    /** Explicit re-attach after the shell dropped while the session stayed up. */
    private void reconnectLocal() {
        SessionSnapshot snapshot = hostStatus == null ? null : hostStatus.getSnapshot();
        if (snapshot == null || hostStatus.getState() != SessionState.RUNNING) {
            return;
        }
        TerminalSessionPort port = currentPort();
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

    // ---- TerminalBridgeView.Listener (called on the UI thread) ----

    @Override
    public void onBridgeReady() {
        // Let the page's fit addon decide the size for the current container and
        // report it back through onTerminalResize; forcing a fixed 80x24 here
        // would undo the fit and leave the guest PTY disagreeing with xterm.
        bridge.fit();
        bridge.focus();
    }

    @Override
    public void onInput(String data) {
        // Typed input first passes the sticky CTRL/ALT modifiers the accessory
        // key row may have armed; the row clears them once they are used.
        sendInput(keyRow == null ? data : keyRow.applyPendingModifiers(data));
    }

    /**
     * The single stdin path of this surface: both the page's {@code INPUT}
     * messages and the native accessory key row arrive here, so the key row
     * cannot reach the guest any other way.
     */
    private void sendInput(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        TerminalSessionPort port = currentPort();
        if (port != null) {
            port.write(data.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Forward the terminal's real size to the host session. The guest runs a
     * real {@code sshd} that allocates a real PTY, so {@code window-change} is
     * meaningful; the size is cached as well because the page can resize before
     * a shell exists (and a shell can open after the page already fitted).
     */
    @Override
    public void onTerminalResize(int cols, int rows) {
        lastCols = cols;
        lastRows = rows;
        TerminalSessionPort port = currentPort();
        if (port != null) {
            port.resize(cols, rows);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            // A hidden view loses focus, and the container changed size while
            // this surface was hidden: refit so xterm and the guest PTY agree
            // again, and hand input focus back to the terminal. Without the
            // focus re-assert a hardware Enter after a surface switch would press
            // whichever control was focused last.
            TerminalSessionPort port = currentPort();
            if (port != null) {
                port.resize(lastCols, lastRows);
            }
            bridge.fit();
            bridge.focus();
        }
    }

    // ---- UI state rendering ----

    private void setState(UiState next) {
        setState(next, null);
    }

    private void setState(UiState next, String detail) {
        state = next;
        lastDetail = detail;
        updateUi();
    }

    private void updateUi() {
        SessionState session = sessionState();
        boolean sessionRunning = isSessionRunning();
        boolean localActive = state == UiState.RUNNING
                || state == UiState.CONNECTING || state == UiState.RECONNECTING;

        boolean showAttach = !localActive && !sessionRunning;
        attachPanel.setVisibility(showAttach ? VISIBLE : GONE);
        if (showAttach) {
            renderAttachPanel(session);
        }

        // The shell dropped or failed while Linux stayed up: offer the explicit
        // reconnect here and in the host notification.
        boolean shellNeedsReconnect = terminalState == TerminalSessionState.DROPPED
                || terminalState == TerminalSessionState.FAILED;
        banner.setVisibility(
                shellNeedsReconnect && sessionRunning && !localActive ? VISIBLE : GONE);

        // The accessory keys are inert without a live shell; they stay visible
        // so the row does not appear and disappear with the session.
        keyRow.setShellAttached(state == UiState.RUNNING);

        renderBridgeOverlay();
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

    /** State overlay over the terminal itself; only for a real connection state. */
    private void renderBridgeOverlay() {
        switch (state) {
            case CONNECTING:
                bridge.showOverlay(getContext().getString(R.string.terminal_connecting));
                break;
            case RECONNECTING:
                bridge.showOverlay(getContext().getString(R.string.terminal_reconnecting));
                break;
            case FAILED:
                bridge.showOverlay(getContext().getString(R.string.terminal_failed,
                        lastDetail == null || lastDetail.trim().isEmpty()
                                ? getContext().getString(R.string.terminal_failed_unknown)
                                : lastDetail));
                break;
            default:
                bridge.showOverlay(null);
                break;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RuntimeStatusBus.getInstance().register(statusListener);
        if (terminalBus != null) {
            terminalBus.register(this);
        }
        TerminalSessionPort port = currentPort();
        if (port != null) {
            port.setOutputListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        RuntimeStatusBus.getInstance().unregister(statusListener);
        if (terminalBus != null) {
            terminalBus.unregister(this);
        }
        // The session belongs to the host service, not this view: detaching
        // (recreation, rotation, a trip back to the launcher) must leave the
        // shell running and only stop streaming to this surface.
        TerminalSessionPort port = currentPort();
        if (port != null) {
            port.setOutputListener(null);
        }
        bridge.setListener(null);
        bridge.release();
        super.onDetachedFromWindow();
    }
}
