package gh.nusashell.nusadesk.presentation.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/** Truth table for one optional toolkit row (ADR-0057). */
public class ServicesExtraStateTest {

    private static RuntimeSnapshot snapshot(RuntimeState state) {
        return new RuntimeSnapshot("guest-usb-adb", state, "detail", 0, 1_000L);
    }

    @Test
    public void installedOnDiskWinsOverAnySnapshot() {
        assertEquals(ServicesExtraState.Kind.INSTALLED,
                ServicesExtraState.of(null, true, false).getKind());
        assertEquals(ServicesExtraState.Kind.INSTALLED,
                ServicesExtraState.of(snapshot(RuntimeState.FAILED), true, false).getKind());
        assertFalse(ServicesExtraState.of(null, true, false).isActionEnabled());
    }

    @Test
    public void absentAndNeverAttemptedOffersInstall() {
        ServicesExtraState state = ServicesExtraState.of(null, false, false);
        assertEquals(ServicesExtraState.Kind.AVAILABLE, state.getKind());
        assertTrue(state.isActionEnabled());
        assertNull(state.getDetail());
    }

    @Test
    public void inProgressIsInstallingAndDisablesTheAction() {
        for (RuntimeState progress : new RuntimeState[]{
                RuntimeState.DOWNLOADING, RuntimeState.VERIFYING,
                RuntimeState.EXTRACTING}) {
            ServicesExtraState state = ServicesExtraState.of(snapshot(progress), false, true);
            assertEquals(progress.name(), ServicesExtraState.Kind.INSTALLING, state.getKind());
            assertEquals(progress.name(), "detail", state.getDetail());
            assertFalse(progress.name(), state.isActionEnabled());
        }
    }

    @Test
    public void failureOffersRetryWhenNotBusy() {
        ServicesExtraState state = ServicesExtraState.of(snapshot(RuntimeState.FAILED), false, false);
        assertEquals(ServicesExtraState.Kind.FAILED, state.getKind());
        assertEquals("detail", state.getDetail());
        assertTrue(state.isActionEnabled());
    }

    @Test
    public void busyPipelineDisablesTheAction() {
        assertFalse(ServicesExtraState.of(null, false, true).isActionEnabled());
        assertFalse(ServicesExtraState.of(snapshot(RuntimeState.FAILED), false, true)
                .isActionEnabled());
    }

    @Test
    public void unrelatedRuntimeStatesStayAvailable() {
        for (RuntimeState other : new RuntimeState[]{
                RuntimeState.NOT_INSTALLED, RuntimeState.READY,
                RuntimeState.RUNNING, RuntimeState.STOPPED}) {
            assertEquals(other.name(), ServicesExtraState.Kind.AVAILABLE,
                    ServicesExtraState.of(snapshot(other), false, false).getKind());
        }
    }
}
