package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * A runtime install snapshot tagged with the component it belongs to, so the
 * single setup pipeline can render the rootfs, the guest-SSH add-on, the
 * required service/Python overlay, and the two optional toolkits as one
 * append-only terminal log without persisting an add-on snapshot as base
 * runtime state.
 *
 * <p>Pure Java with no Android imports: it wraps a domain {@link RuntimeSnapshot}
 * and a {@link Component}, so the phase-to-line mapping, deduplication, and
 * new-attempt detection stay testable in plain JUnit.</p>
 */
public final class InstallPhaseSnapshot {

    /**
     * Which curated component a setup snapshot describes. {@code ROOTFS} and
     * {@code ADDON} are the essential core (Linux + the terminal component);
     * {@code SERVICES} is the required Python/systemctl overlay; the two
     * toolkits are separate opt-in choices (ADR-0057).
     */
    public enum Component { ROOTFS, ADDON, SERVICES, USB_ADB, TERMUX }

    private final Component component;
    private final RuntimeSnapshot snapshot;

    public InstallPhaseSnapshot(Component component, RuntimeSnapshot snapshot) {
        if (component == null) {
            throw new IllegalArgumentException("component must not be null");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        this.component = component;
        this.snapshot = snapshot;
    }

    /** Tags a rootfs install snapshot. */
    public static InstallPhaseSnapshot rootfs(RuntimeSnapshot snapshot) {
        return new InstallPhaseSnapshot(Component.ROOTFS, snapshot);
    }

    /** Tags a guest-SSH add-on install snapshot. */
    public static InstallPhaseSnapshot addon(RuntimeSnapshot snapshot) {
        return new InstallPhaseSnapshot(Component.ADDON, snapshot);
    }

    /** Tags the required service/Python overlay phase. */
    public static InstallPhaseSnapshot serviceAddon(RuntimeSnapshot snapshot) {
        return new InstallPhaseSnapshot(Component.SERVICES, snapshot);
    }

    /** Tags optional USB/ADB provisioning. */
    public static InstallPhaseSnapshot usbAdb(RuntimeSnapshot snapshot) {
        return new InstallPhaseSnapshot(Component.USB_ADB, snapshot);
    }

    /** Tags optional Termux command provisioning. */
    public static InstallPhaseSnapshot termux(RuntimeSnapshot snapshot) {
        return new InstallPhaseSnapshot(Component.TERMUX, snapshot);
    }

    public Component getComponent() {
        return component;
    }

    public RuntimeSnapshot getSnapshot() {
        return snapshot;
    }

    public RuntimeState getState() {
        return snapshot.getState();
    }

    public String getDetail() {
        return snapshot.getDetail();
    }
}
