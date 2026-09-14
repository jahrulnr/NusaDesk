package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pure tests for the combined setup phase policy: the one install phase the
 * unified surface renders and the one action it offers, derived from the
 * rootfs snapshot, add-on phase, and add-on presence/failure. No Android
 * dependencies — plain JUnit.
 */
public class SetupPhasePolicyTest {

    private static RuntimeSnapshot rootfs(RuntimeState state) {
        return new RuntimeSnapshot("ubuntu-base-arm64", state, "detail", 0, 1_000L);
    }

    private static InstallPhaseSnapshot addon(RuntimeState state) {
        return InstallPhaseSnapshot.addon(
                new RuntimeSnapshot("guest-ssh", state, "detail", 0, 1_000L));
    }

    // ---- phase ----

    @Test
    public void rootfsNotInstalledShowsRootfsPhase() {
        InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(
                rootfs(RuntimeState.NOT_INSTALLED), null);
        assertEquals(InstallPhaseSnapshot.Component.ROOTFS, phase.getComponent());
        assertEquals(RuntimeState.NOT_INSTALLED, phase.getState());
    }

    @Test
    public void rootfsNullShowsRootfsNotInstalledPhase() {
        InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(null, null);
        assertEquals(InstallPhaseSnapshot.Component.ROOTFS, phase.getComponent());
        assertEquals(RuntimeState.NOT_INSTALLED, phase.getState());
    }

    @Test
    public void rootfsInProgressShowsRootfsPhase() {
        for (RuntimeState state : new RuntimeState[]{
                RuntimeState.DOWNLOADING, RuntimeState.VERIFYING,
                RuntimeState.EXTRACTING, RuntimeState.FAILED}) {
            InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(rootfs(state), null);
            assertEquals(state.name(), InstallPhaseSnapshot.Component.ROOTFS, phase.getComponent());
            assertEquals(state.name(), state, phase.getState());
        }
    }

    @Test
    public void rootfsReadyWithAddonInProgressShowsAddonPhase() {
        for (RuntimeState state : new RuntimeState[]{
                RuntimeState.DOWNLOADING, RuntimeState.VERIFYING,
                RuntimeState.EXTRACTING, RuntimeState.FAILED}) {
            InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(
                    rootfs(RuntimeState.READY), addon(state));
            assertEquals(state.name(), InstallPhaseSnapshot.Component.ADDON, phase.getComponent());
            assertEquals(state.name(), state, phase.getState());
        }
    }

    @Test
    public void rootfsReadyWithoutAddonShowsPendingAddonPhase() {
        InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(
                rootfs(RuntimeState.READY), null);
        assertEquals(InstallPhaseSnapshot.Component.ADDON, phase.getComponent());
        assertEquals(RuntimeState.NOT_INSTALLED, phase.getState());
    }

    @Test
    public void rootfsReadyWithAddonReadyShowsAddonReadyPhase() {
        InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(
                rootfs(RuntimeState.READY), addon(RuntimeState.READY));
        assertEquals(InstallPhaseSnapshot.Component.ADDON, phase.getComponent());
        assertEquals(RuntimeState.READY, phase.getState());
    }

    // ---- action ----

    @Test
    public void rootfsNotInstalledOffersStart() {
        assertEquals(SetupAction.START,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.NOT_INSTALLED), false, false));
    }

    @Test
    public void rootfsNullOffersStart() {
        assertEquals(SetupAction.START, SetupPhasePolicy.actionFor(null, false, false));
    }

    @Test
    public void rootfsInProgressIsInstalling() {
        for (RuntimeState state : new RuntimeState[]{
                RuntimeState.DOWNLOADING, RuntimeState.VERIFYING,
                RuntimeState.EXTRACTING}) {
            assertEquals(state.name(), SetupAction.INSTALLING,
                    SetupPhasePolicy.actionFor(rootfs(state), false, false));
        }
    }

    @Test
    public void rootfsFailedOffersRetry() {
        assertEquals(SetupAction.RETRY,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.FAILED), false, false));
    }

    @Test
    public void addonFailedOffersRetryEvenWithRootfsReady() {
        assertEquals(SetupAction.RETRY,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.READY), false, true));
    }

    @Test
    public void rootfsReadyAddonMissingIsInstalling() {
        // The add-on auto-continues; the single action is disabled.
        assertEquals(SetupAction.INSTALLING,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.READY), false, false));
    }

    @Test
    public void rootfsReadyAddonInstallingIsInstalling() {
        assertEquals(SetupAction.INSTALLING,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.READY), false, false));
    }

    @Test
    public void bothReadyHidesAction() {
        assertEquals(SetupAction.HIDDEN,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.READY), true, false));
    }

    @Test
    public void rootfsFailedBeatsAddonReadyForAction() {
        // A failed rootfs is still a retry even if the add-on somehow reports
        // installed (defensive: the pipeline never reaches the add-on then).
        assertEquals(SetupAction.RETRY,
                SetupPhasePolicy.actionFor(rootfs(RuntimeState.FAILED), true, false));
    }

    // ---- regression: one action, no second button ----

    @Test
    public void everyActionIsSingleAndNonNull() {
        for (RuntimeState rootfsState : RuntimeState.values()) {
            for (boolean installed : new boolean[]{true, false}) {
                for (boolean failed : new boolean[]{true, false}) {
                    SetupAction action = SetupPhasePolicy.actionFor(
                            rootfs(rootfsState), installed, failed);
                    assertNotNull(rootfsState.name(), action);
                }
            }
        }
    }
}
