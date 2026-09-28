package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Combined setup phase policy for the single setup pipeline: given the rootfs
 * install snapshot and the latest add-on phase snapshot, it derives the one
 * install phase the unified surface should display and the one action it
 * should offer.
 *
 * <p>The launcher unlocks only after the rootfs, guest SSH, and the required
 * service/Python and base-extras overlays are active. USB/ADB, Termux, and
 * the D-Bus face are independent options and do not affect core readiness.</p>
 *
 * <p>Pure Java with no Android imports so the combined policy is unit-tested
 * in plain JUnit.</p>
 */
public final class SetupPhasePolicy {

    private SetupPhasePolicy() {
    }

    /**
     * The install phase the unified surface should render.
     *
     * @param rootfs the curated rootfs install snapshot; {@code null} means
     *               nothing is installed yet
     * @param addon  the latest add-on install phase snapshot, or {@code null}
     *               when the add-on has not started (rootfs ready, add-on
     *               pending)
     */
    public static InstallPhaseSnapshot phaseFor(
            RuntimeSnapshot rootfs, InstallPhaseSnapshot addon) {
        RuntimeState rootfsState = rootfs == null
                ? RuntimeState.NOT_INSTALLED : rootfs.getState();
        if (rootfsState != RuntimeState.READY) {
            return rootfs == null
                    ? InstallPhaseSnapshot.rootfs(notInstalled())
                    : InstallPhaseSnapshot.rootfs(rootfs);
        }
        // Rootfs is active: the add-on phase owns the surface. A missing add-on
        // snapshot is the pending gap before the auto-continued install posts
        // its first progress snapshot.
        return addon != null ? addon : InstallPhaseSnapshot.addon(notInstalled());
    }

    /**
     * The single action the unified surface should offer.
     *
     * @param rootfs        the curated rootfs install snapshot; {@code null}
     *                      means nothing is installed yet
     * @param addonInstalled whether a usable guest-SSH add-on is active on disk
     * @param addonFailed    whether the add-on install has failed
     */
    public static SetupAction actionFor(
            RuntimeSnapshot rootfs, boolean addonInstalled, boolean addonFailed) {
        RuntimeState rootfsState = rootfs == null
                ? RuntimeState.NOT_INSTALLED : rootfs.getState();
        boolean rootfsReady = rootfsState == RuntimeState.READY;
        if (rootfsReady && addonInstalled) {
            return SetupAction.HIDDEN;
        }
        if (rootfsState == RuntimeState.FAILED || addonFailed) {
            return SetupAction.RETRY;
        }
        if (rootfsState == RuntimeState.NOT_INSTALLED) {
            return SetupAction.START;
        }
        return SetupAction.INSTALLING;
    }

    /**
     * Like {@link #phaseFor(RuntimeSnapshot, InstallPhaseSnapshot)}, but
     * names the required service overlay when SSH is already available and
     * its own first snapshot has not arrived yet.
     */
    public static InstallPhaseSnapshot phaseFor(
            RuntimeSnapshot rootfs, InstallPhaseSnapshot addon, boolean sshInstalled) {
        RuntimeState rootfsState = rootfs == null
                ? RuntimeState.NOT_INSTALLED : rootfs.getState();
        if (rootfsState != RuntimeState.READY) {
            return phaseFor(rootfs, addon);
        }
        if (addon != null) {
            return addon;
        }
        return sshInstalled
                ? InstallPhaseSnapshot.serviceAddon(notInstalled())
                : InstallPhaseSnapshot.addon(notInstalled());
    }

    /**
     * The action for all mandatory installation components. Missing optional
     * USB/ADB and Termux tooling never blocks the launcher.
     */
    public static SetupAction actionFor(RuntimeSnapshot rootfs,
            boolean sshInstalled, boolean sshFailed,
            boolean serviceInstalled, boolean serviceFailed) {
        RuntimeState rootfsState = rootfs == null
                ? RuntimeState.NOT_INSTALLED : rootfs.getState();
        if (rootfsState == RuntimeState.READY && sshInstalled && serviceInstalled) {
            return SetupAction.HIDDEN;
        }
        if (rootfsState == RuntimeState.FAILED || sshFailed || serviceFailed) {
            return SetupAction.RETRY;
        }
        if (rootfsState == RuntimeState.NOT_INSTALLED) {
            return SetupAction.START;
        }
        return SetupAction.INSTALLING;
    }

    private static RuntimeSnapshot notInstalled() {
        return new RuntimeSnapshot("setup", RuntimeState.NOT_INSTALLED, "", 0, 0L);
    }
}
