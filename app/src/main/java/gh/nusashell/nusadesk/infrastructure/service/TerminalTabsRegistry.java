package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.application.terminal.TerminalTabsPort;

/**
 * In-process holder of the current host-owned terminal tab set.
 *
 * <p>The tabs live in {@link RuntimeHostService}, which registers its
 * {@link TerminalTabsController} here on creation and clears it on
 * destruction; the Activity reads {@link #port()} when building the terminal
 * surface (ADR-0054, the multi-tab form of ADR-0033's registry). Before the
 * service exists — or after it was destroyed — {@link #port()} returns
 * {@code null} and the surface renders the honest "no runtime session" state.
 * Mirrors the singleton pattern of {@link RuntimeStatusBus} and
 * {@link RuntimeWorkloadRegistry}: the service and the Activity share one
 * process, so no binder boundary is needed.</p>
 */
public final class TerminalTabsRegistry {

    private static final TerminalTabsRegistry INSTANCE = new TerminalTabsRegistry();

    public static TerminalTabsRegistry getInstance() {
        return INSTANCE;
    }

    private volatile TerminalTabsController controller;

    private TerminalTabsRegistry() {
    }

    /** Called by the host service when it creates its terminal tab set. */
    public void register(TerminalTabsController controller) {
        this.controller = controller;
    }

    /** Called by the host service when it tears its terminal tab set down. */
    public void clear() {
        this.controller = null;
    }

    /** @return the presentation port of the current terminal tab set, or null. */
    public TerminalTabsPort port() {
        return controller;
    }
}
