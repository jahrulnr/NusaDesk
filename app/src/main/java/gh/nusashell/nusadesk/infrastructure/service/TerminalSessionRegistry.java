package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.application.terminal.TerminalSessionPort;

/**
 * In-process holder of the current host-owned terminal session.
 *
 * <p>The session lives in {@link RuntimeHostService}, which registers its
 * {@link TerminalSessionController} here on creation and clears it on
 * destruction; the Activity reads {@link #port()} when building the terminal
 * surface (ADR-0033). Before the service exists — or after it was destroyed —
 * {@link #port()} returns {@code null} and the surface renders the honest
 * "no runtime session" state. Mirrors the singleton pattern of
 * {@link RuntimeStatusBus} and {@link RuntimeWorkloadRegistry}: the service
 * and the Activity share one process, so no binder boundary is needed.</p>
 */
public final class TerminalSessionRegistry {

    private static final TerminalSessionRegistry INSTANCE = new TerminalSessionRegistry();

    public static TerminalSessionRegistry getInstance() {
        return INSTANCE;
    }

    private volatile TerminalSessionController controller;

    private TerminalSessionRegistry() {
    }

    /** Called by the host service when it creates its terminal session. */
    public void register(TerminalSessionController controller) {
        this.controller = controller;
    }

    /** Called by the host service when it tears its terminal session down. */
    public void clear() {
        this.controller = null;
    }

    /** @return the presentation port of the current terminal session, or null. */
    public TerminalSessionPort port() {
        return controller;
    }
}
