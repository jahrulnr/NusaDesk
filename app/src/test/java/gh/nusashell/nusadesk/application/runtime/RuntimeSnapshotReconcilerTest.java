package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RuntimeSnapshotReconcilerTest {
    @Test
    public void interruptedExtractionBecomesRetryableFailure() {
        RuntimeSnapshot interrupted = new RuntimeSnapshot(
                "ubuntu-base-arm64",
                RuntimeState.EXTRACTING,
                "Preparing the private runtime files",
                0,
                10L);

        RuntimeSnapshot reconciled = RuntimeSnapshotReconciler.reconcile(interrupted);

        assertEquals(RuntimeState.FAILED, reconciled.getState());
        assertEquals("The previous installation was interrupted. Retry installation.",
                reconciled.getDetail());
        assertEquals(0, reconciled.getPort());
    }

    @Test
    public void stableStateIsNotChanged() {
        RuntimeSnapshot ready = new RuntimeSnapshot(
                "ubuntu-base-arm64",
                RuntimeState.READY,
                "installed",
                0,
                10L);

        assertSame(ready, RuntimeSnapshotReconciler.reconcile(ready));
    }
}
