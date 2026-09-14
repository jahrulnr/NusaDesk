package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/**
 * Combined setup phase policy for the single setup pipeline: given the rootfs
 * install snapshot and the latest add-on phase snapshot, it derives the one
 * install phase the unified surface should display and the one action it
 * should offer.
 *
 * <p>The rule is the product contract: the launcher unlocks only when both the
 * curated rootfs and the guest-SSH add-on are active, so the setup surface
 * stays visible while either is missing, never fakes a base rootfs READY for
 * launcher unlock, and never offers a second "Next: add terminal component"
 * action. Retry after an add-on failure runs only the add-on because a valid
 * active rootfs is never re-downloaded.</p>
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

    private static RuntimeSnapshot notInstalled() {
        return new RuntimeSnapshot("setup", RuntimeState.NOT_INSTALLED, "", 0, 0L);
    }
}
