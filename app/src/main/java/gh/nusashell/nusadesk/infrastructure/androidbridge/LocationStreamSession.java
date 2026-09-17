package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Host-owned, foreground-only continuous location stream, kept Android-free
 * for testing.
 *
 * <p>A session is started with a bounded {@link LocationStreamRequest},
 * produces a bounded sequence of {@link LocationStreamEvent}s, and is stopped
 * explicitly by the host. The contract never claims background location,
 * never starts a foreground service, never opens a permission activity, and
 * never exposes arbitrary listener callbacks: the only consumption paths are
 * {@link #poll(long)} and {@link #latestReading()}, and both are bounded.</p>
 *
 * <p>Lifecycle: {@link #start(LocationStreamRequest)} begins a session
 * (failures — missing/denied grant, unavailable provider, platform rejection —
 * surface as typed events with the session in {@link State#STOPPED}, never as
 * a fabricated fix). {@link #stop()} ends a running session and pushes a
 * terminal {@link LocationStreamEvent.State#STOPPED} event; it is idempotent
 * and a stopped session may be started again. {@link #close()} is terminal
 * and idempotent: it unregisters the listener, stops the delivery thread, and
 * makes further {@code start} calls fail with
 * {@link IllegalStateException}. Every terminal path unregisters the
 * platform listener, so no listener outlives the session.</p>
 */
public interface LocationStreamSession extends AutoCloseable {
    enum State {
        /** No session is running and none has run since construction/close. */
        IDLE,
        /** A session is running and delivering fixes to the bounded buffer. */
        STREAMING,
        /** The last session ended (stop, failure, or provider loss); restart is allowed. */
        STOPPED,
        /** The session was closed; it cannot be started again. */
        CLOSED
    }

    /**
     * Start a stream session with the given bounded request.
     *
     * <p>Any prior events are discarded, the foreground grant is checked, the
     * provider is chosen from the request accuracy, and failures are pushed
     * as typed events with the session ending in {@link State#STOPPED}. When
     * the session starts, {@link State#STREAMING} is observable and fixes
     * arrive as {@link LocationStreamEvent.State#READING} events.</p>
     *
     * @throws IllegalArgumentException when the request is {@code null} or
     *                                  carries no accuracy
     * @throws IllegalStateException    when the session is already streaming
     *                                  or has been closed
     */
    void start(LocationStreamRequest request);

    /**
     * Stop a running session: unregister the platform listener and push a
     * terminal {@link LocationStreamEvent.State#STOPPED} event. Idempotent;
     * a stopped session may be started again.
     */
    void stop();

    /**
     * Terminate the session permanently: stop any running stream, unregister
     * the platform listener, and stop the delivery thread. Idempotent; after
     * close, {@link #start(LocationStreamRequest)} throws
     * {@link IllegalStateException}. Buffered events remain drainable.
     */
    @Override
    void close();

    /** Current session state. */
    State state();

    /**
     * Next stream event, blocking up to {@code timeoutMillis} when none is
     * buffered.
     *
     * <p>When the deadline passes with no buffered event and no fix arrived
     * within the session's bounded fix-silence window, the poll returns a
     * non-terminal {@link LocationStreamEvent.State#TIMEOUT} event (at most
     * one per window). The evaluation is pull-based: no background timer
     * runs while the stream is idle.</p>
     *
     * @param timeoutMillis maximum wait; {@code 0} returns immediately
     * @return the oldest buffered event, a timeout event, or {@code null}
     *         when the deadline passed with neither
     */
    LocationStreamEvent poll(long timeoutMillis);

    /**
     * Most recent validated fix of the current session, or {@code null}
     * before the first reading. The latest-value view never grows.
     */
    LocationSnapshot latestReading();
}
