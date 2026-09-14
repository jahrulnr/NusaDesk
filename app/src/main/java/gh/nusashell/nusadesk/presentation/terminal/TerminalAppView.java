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
import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridge;
import gh.nusashell.nusadesk.infrastructure.ssh.SshCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionConfig;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.widget.TerminalBridgeView;

import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Terminal — the in-app shell, opened as a maximized surface.
 *
 * <p>It is not an SSH client and has no target to choose. The only endpoint it
 * can dial is the fixed loopback address of the Linux this app started, and it
 * gets it from {@link LocalSshSessionFactory}, which exposes no host, port, or
 * credential parameter at all (ADR-0013). A key this app did not pin is refused
 * rather than trusted on first contact.</p>
 *
 * <p>Linux is background infrastructure: it starts from an app launch, so this
 * surface has no session control. While the session is starting it waits
 * passively, and when the session is not running it says so and offers a way
 * back to the launcher — never a second start button.</p>
 *
 * <p>The terminal itself is a real WebView host ({@link TerminalBridgeView})
 * running the packaged xterm bundle over an owned origin. Terminal I/O crosses
 * the WebView boundary through a {@code WebMessagePort} only — no
 * {@code addJavascriptInterface}.</p>
 */
public final class TerminalAppView extends FrameLayout
        implements SshSessionListener, TerminalBridgeView.Listener, SessionStatusAware {

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

    private SshCredentialProvider credentialProvider;
    private SshReconnectPolicy reconnectPolicy;
    private HostKeyTrustStore trustStore;
    private LongSupplier clock = System::currentTimeMillis;

    private SshClientBridge sshBridge;
    private UiState state = UiState.IDLE;
    private String lastDetail;
    private HostRuntimeStatus hostStatus;
    /** Session whose shell dropped; the terminal waits for an explicit reconnect. */
    private String droppedSessionId;
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
     * Provides the SSH dependencies the surface needs for real connections. All
     * arguments are required; without a production trust store or credential
     * provider the surface reports the failure rather than faking a session.
     */
    public void setSshDependencies(
            SshCredentialProvider credentialProvider,
            SshReconnectPolicy reconnectPolicy,
            HostKeyTrustStore trustStore,
            LongSupplier clock) {
        this.credentialProvider = credentialProvider;
        this.reconnectPolicy = reconnectPolicy;
        this.trustStore = trustStore;
        if (clock != null) {
            this.clock = clock;
        }
        updateUi();
    }

    // ---- Session status ----

    /**
     * {@link RuntimeStatusBus} delivery, always on the main thread. Attaches to a
     * healthy local session exactly once per session, and detaches when the
     * session it was attached to ends.
     */
    @Override
    public void renderSessionStatus(HostRuntimeStatus status) {
        hostStatus = status;
        SessionSnapshot snapshot = status.getSnapshot();
        if (status.isRuntimeRunning() && snapshot != null) {
            maybeConnectLocal(snapshot);
        } else if (snapshot != null && isTerminalState(status.getState())) {
            reconcileEndedSession(status);
        }
        updateUi();
    }

    private void reconcileEndedSession(HostRuntimeStatus status) {
        if (sshBridge != null) {
            closeSshBridge();
            setState(status.getState() == SessionState.FAILED
                    ? UiState.FAILED : UiState.IDLE, status.getFailureReason());
        }
        // A dropped shell has already released its bridge and remembered the
        // session it belonged to; the attach prompt explains the rest.
    }

    private void maybeConnectLocal(SessionSnapshot snapshot) {
        if (sshBridge != null) {
            return; // already connecting, running, or reconnecting
        }
        if (snapshot.getSessionId().equals(droppedSessionId)) {
            return; // the shell dropped: wait for the user's explicit reconnect
        }
        connectLocal();
    }

    /** Explicit re-attach after the shell dropped while the session stayed up. */
    private void reconnectLocal() {
        SessionSnapshot snapshot = hostStatus == null ? null : hostStatus.getSnapshot();
        if (snapshot == null || hostStatus.getState() != SessionState.RUNNING) {
            return;
        }
        droppedSessionId = null;
        connectLocal();
    }

    private static boolean isTerminalState(SessionState state) {
        return state == SessionState.STOPPED || state == SessionState.FAILED
                || state == SessionState.CANCELLED || state == SessionState.NOT_STARTED;
    }

    // ---- Local connection ----

    /**
     * The single connection path. The endpoint and the identity come from
     * {@link LocalSshSessionFactory}: the fixed loopback host and port, the
     * app-managed Keystore credential, and the pinned-host-key-only trust policy.
     * Nothing here can be pointed at another host.
     */
    private void connectLocal() {
        if (trustStore == null || credentialProvider == null) {
            setState(UiState.FAILED, getContext().getString(R.string.terminal_failed_unknown));
            return;
        }
        SshReconnectPolicy policy = reconnectPolicy != null
                ? reconnectPolicy : SshReconnectPolicy.DEFAULT;
        closeSshBridge();
        sshBridge = new SshClientBridge(credentialProvider, trustStore,
                LocalSshSessionFactory.pinnedHostKeyOnly(), policy, clock);
        SshSessionConfig config = LocalSshSessionFactory.create(lastCols, lastRows);
        setState(UiState.CONNECTING);
        // The previous bridge's close() posts a deferred CLOSED that would
        // otherwise land after this connect and stomp the new session's state;
        // ignore callbacks that arrive from a stale bridge.
        final SshClientBridge created = sshBridge;
        sshBridge.start(config, new SshSessionListener() {
            @Override
            public void onState(SshSessionState sshState, String detail) {
                if (sshBridge == created) {
                    TerminalAppView.this.onState(sshState, detail);
                }
            }

            @Override
            public void onStdout(byte[] data, int len) {
                if (sshBridge == created) {
                    TerminalAppView.this.onStdout(data, len);
                }
            }

            @Override
            public void onStderr(byte[] data, int len) {
                if (sshBridge == created) {
                    TerminalAppView.this.onStderr(data, len);
                }
            }

            @Override
            public void onClosed(String reason) {
                if (sshBridge == created) {
                    TerminalAppView.this.onClosed(reason);
                }
            }
        });
    }

    private void closeSshBridge() {
        SshClientBridge b = sshBridge;
        sshBridge = null;
        if (b != null) {
            b.close();
        }
    }

    // ---- SshSessionListener (called on background threads) ----

    @Override
    public void onState(SshSessionState sshState, String detail) {
        mainHandler.post(() -> onSessionStateUi(sshState, detail));
    }

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

    @Override
    public void onClosed(String reason) {
        mainHandler.post(() -> {
            if (state == UiState.FAILED) {
                return;
            }
            // The shell exited or the channel dropped. Remember this session so
            // the terminal does not silently re-attach to it; the banner offers
            // an explicit reconnect while Linux stays running.
            droppedSessionId = currentSessionId();
            closeSshBridge();
            setState(UiState.IDLE);
        });
    }

    private String currentSessionId() {
        SessionSnapshot snapshot = hostStatus == null ? null : hostStatus.getSnapshot();
        return snapshot == null ? null : snapshot.getSessionId();
    }

    private boolean isSessionRunning() {
        SessionState session = sessionState();
        return session == SessionState.RUNNING || session == SessionState.RECONNECTING;
    }

    private SessionState sessionState() {
        return hostStatus == null ? SessionState.NOT_STARTED : hostStatus.getState();
    }

    private void onSessionStateUi(SshSessionState sshState, String detail) {
        android.util.Log.i("TerminalAppView",
                "ssh state " + sshState + (detail != null ? " (" + detail + ")" : ""));
        switch (sshState) {
            case CONNECTING:
            case HOST_KEY_PENDING:
            case AUTHENTICATING:
                setState(UiState.CONNECTING);
                break;
            case RUNNING:
                setState(UiState.RUNNING);
                // The session surface now owns input; move view focus off any
                // previously focused control onto the terminal, and align the
                // freshly opened PTY with the size the page already has.
                bridge.focus();
                SshClientBridge running = sshBridge;
                if (running != null) {
                    running.resize(lastCols, lastRows);
                }
                bridge.fit();
                break;
            case RECONNECTING:
                setState(UiState.RECONNECTING);
                break;
            case FAILED:
                setState(UiState.FAILED, detail);
                break;
            case CLOSED:
            default:
                if (state != UiState.FAILED) {
                    setState(UiState.IDLE);
                }
                break;
        }
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
        SshClientBridge b = sshBridge;
        if (b != null && data != null && !data.isEmpty()) {
            b.write(data.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Forward the terminal's real size to the open SSH channel. The guest runs
     * a real {@code sshd} that allocates a real PTY, so {@code window-change} is
     * meaningful; the size is cached as well because the page can resize before a
     * channel exists (and a channel can open after the page already fitted).
     */
    @Override
    public void onTerminalResize(int cols, int rows) {
        lastCols = cols;
        lastRows = rows;
        SshClientBridge b = sshBridge;
        if (b != null) {
            b.resize(cols, rows);
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
        boolean localActive = sshBridge != null
                && (state == UiState.RUNNING || state == UiState.CONNECTING
                    || state == UiState.RECONNECTING);

        boolean showAttach = !localActive && !sessionRunning;
        attachPanel.setVisibility(showAttach ? VISIBLE : GONE);
        if (showAttach) {
            renderAttachPanel(session);
        }

        banner.setVisibility(
                droppedSessionId != null && sessionRunning && !localActive ? VISIBLE : GONE);

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
    }

    @Override
    protected void onDetachedFromWindow() {
        RuntimeStatusBus.getInstance().unregister(statusListener);
        closeSshBridge();
        bridge.setListener(null);
        bridge.release();
        super.onDetachedFromWindow();
    }
}
