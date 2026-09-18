package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.application.terminal.TerminalOutputListener;
import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionState;
import gh.nusashell.nusadesk.domain.terminal.TerminalSessionStatus;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionConfig;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransport;
import gh.nusashell.nusadesk.infrastructure.ssh.TerminalTransportFactory;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Host-owned terminal SSH session for the running runtime session.
 *
 * <p>The session lives here — in the host service — instead of in the terminal
 * view, so its lifetime follows the <em>runtime session</em> (ADR-0033): it
 * opens when the runtime reaches {@code RUNNING}, closes when the runtime
 * leaves it, and survives Activity recreation. A shell that drops or fails
 * while the runtime stays up becomes {@code DROPPED}/{@code FAILED} and is
 * re-attached only by an explicit {@link #reconnect()} — from the terminal
 * banner or the notification action — never silently against a session the
 * user may have stopped.</p>
 *
 * <p>All internal state is mutated on the injected main executor (the service
 * calls in on the main thread; bridge callbacks are marshalled there too), so
 * the controller is not thread-safe by design. {@link #write} and
 * {@link #resize} are forwarded to the transport directly, which is
 * thread-safe.</p>
 */
public final class TerminalSessionController implements TerminalSessionPort {

    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;

    private final TerminalTransportFactory transportFactory;
    private final Consumer<TerminalSessionStatus> publisher;
    private final Executor mainExecutor;

    // Read by write/resize from any caller thread; mutated on the main executor.
    private volatile TerminalTransport transport;
    private String runtimeSessionId;
    private TerminalSessionState state = TerminalSessionState.NOT_STARTED;
    private String detail = "";
    // Reported from any thread, read on the main executor when a shell opens.
    private volatile int lastCols = DEFAULT_COLS;
    private volatile int lastRows = DEFAULT_ROWS;
    // Read on transport pump threads; written when surfaces attach/detach.
    private volatile TerminalOutputListener outputListener;

    /**
     * @param transportFactory source of one fresh SSH transport per session
     * @param publisher        sink for status updates (production: the
     *                         {@link TerminalSessionBus}); called on the main executor
     * @param mainExecutor     serializes state transitions; production passes a
     *                         main-thread executor, tests a direct one
     */
    public TerminalSessionController(
            TerminalTransportFactory transportFactory,
            Consumer<TerminalSessionStatus> publisher,
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
     * Follows the runtime session. Called on the main thread for every runtime
     * status transition: opens the terminal session once the runtime is
     * running (fresh transport per runtime session identity) and closes it the
     * moment the runtime is not running, so a dead or stopped guest never
     * keeps a fake attached shell.
     */
    public void onRuntimeStatus(HostRuntimeStatus status) {
        if (status.isRuntimeRunning() && status.getSnapshot() != null) {
            String sessionId = status.getSnapshot().getSessionId();
            if (!sessionId.equals(runtimeSessionId)) {
                closeCurrent();
                runtimeSessionId = sessionId;
                openTransport();
            } else if (state == TerminalSessionState.NOT_STARTED && transport == null) {
                // The runtime recovered (or re-published) with the same
                // identity; the terminal was closed with it, so re-attach.
                openTransport();
            }
            // Same running session with an open or dropped/failed shell: leave
            // it alone; a dropped/failed shell needs an explicit reconnect.
        } else {
            closeCurrent();
            runtimeSessionId = null;
            setState(TerminalSessionState.NOT_STARTED, "");
        }
    }

    /** Current session status. Only meaningful when called on the main thread. */
    public TerminalSessionStatus status() {
        return new TerminalSessionStatus(state, detail);
    }

    /**
     * Explicit re-attach to the running runtime session. A no-op when no
     * runtime session is running; the guest daemon is still up, so a fresh
     * shell opens (ADR-0013).
     */
    @Override
    public void reconnect() {
        mainExecutor.execute(() -> {
            if (runtimeSessionId == null) {
                return;
            }
            closeCurrent();
            openTransport();
        });
    }

    @Override
    public void write(byte[] data) {
        TerminalTransport current = transport;
        if (current != null && data != null) {
            current.write(data);
        }
    }

    @Override
    public void resize(int cols, int rows) {
        TerminalTransport current = transport;
        lastCols = cols;
        lastRows = rows;
        if (current != null) {
            current.resize(cols, rows);
        }
    }

    @Override
    public void setOutputListener(TerminalOutputListener listener) {
        outputListener = listener;
    }

    /**
     * Release the terminal session for good (service destroy). The runtime
     * session remains unaffected; the runtime host owns that separately.
     */
    public void close() {
        mainExecutor.execute(() -> {
            closeCurrent();
            runtimeSessionId = null;
            setState(TerminalSessionState.NOT_STARTED, "");
        });
    }

    private void openTransport() {
        TerminalTransport created = transportFactory.create();
        transport = created;
        setState(TerminalSessionState.CONNECTING, "");
        SshSessionConfig config = LocalSshSessionFactory.create(lastCols, lastRows);
        created.start(config, new SshSessionListener() {
            @Override
            public void onState(SshSessionState sshState, String detail) {
                TerminalSessionController.this.onBridgeState(created, sshState, detail);
            }

            @Override
            public void onStdout(byte[] data, int len) {
                TerminalOutputListener sink = outputListener;
                if (sink != null) {
                    sink.onStdout(data, len);
                }
            }

            @Override
            public void onStderr(byte[] data, int len) {
                TerminalOutputListener sink = outputListener;
                if (sink != null) {
                    sink.onStderr(data, len);
                }
            }

            @Override
            public void onClosed(String reason) {
                TerminalSessionController.this.onBridgeClosed(created, reason);
            }
        });
    }

    private void onBridgeState(TerminalTransport source, SshSessionState sshState, String sshDetail) {
        mainExecutor.execute(() -> {
            if (source != transport) {
                return; // stale transport's callback; a newer session owns the state
            }
            String reason = sshDetail == null ? "" : sshDetail;
            switch (sshState) {
                case CONNECTING:
                case HOST_KEY_PENDING:
                case AUTHENTICATING:
                    setState(TerminalSessionState.CONNECTING, reason);
                    break;
                case RUNNING:
                    setState(TerminalSessionState.RUNNING, reason);
                    break;
                case RECONNECTING:
                    setState(TerminalSessionState.RECONNECTING, reason);
                    break;
                case FAILED:
                    setState(TerminalSessionState.FAILED, reason);
                    break;
                case CLOSED:
                default:
                    // The terminal close is reported through onBridgeClosed.
                    break;
            }
        });
    }

    private void onBridgeClosed(TerminalTransport source, String reason) {
        mainExecutor.execute(() -> {
            if (source != transport) {
                // A close we initiated (runtime stopped, session replaced,
                // service teardown) nulls the transport field first, so the
                // asynchronous CLOSED from the closed transport is stale here.
                return;
            }
            transport = null;
            if (state == TerminalSessionState.FAILED) {
                return; // FAILED already reported the terminal end; keep its reason
            }
            setState(TerminalSessionState.DROPPED, reason == null ? "" : reason);
        });
    }

    private void closeCurrent() {
        TerminalTransport current = transport;
        transport = null;
        if (current != null) {
            current.close();
        }
    }

    private void setState(TerminalSessionState next, String nextDetail) {
        state = next;
        detail = nextDetail;
        publisher.accept(new TerminalSessionStatus(state, detail));
    }
}
