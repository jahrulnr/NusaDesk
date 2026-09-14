package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * A runtime install snapshot tagged with the component it belongs to, so the
 * single setup pipeline can render the rootfs and the guest-SSH add-on as one
 * append-only terminal log without persisting an add-on snapshot as base
 * runtime state.
 *
 * <p>Pure Java with no Android imports: it wraps a domain {@link RuntimeSnapshot}
 * and a {@link Component}, so the phase-to-line mapping, deduplication, and
 * new-attempt detection stay testable in plain JUnit.</p>
 */
public final class InstallPhaseSnapshot {

    /** Which curated component a setup snapshot describes. */
    public enum Component { ROOTFS, ADDON }

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
