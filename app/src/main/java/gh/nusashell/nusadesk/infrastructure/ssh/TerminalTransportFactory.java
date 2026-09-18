package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * Creates one {@link TerminalTransport} per terminal session.
 *
 * <p>A fresh transport per session keeps the bounded reconnect budget and the
 * credential/host-key state clean when the runtime session changes identity:
 * the old transport is closed and the new one starts from scratch (ADR-0033).</p>
 */
public interface TerminalTransportFactory {

    /** @return a new, not-yet-started transport for one terminal session. */
    TerminalTransport create();
}
