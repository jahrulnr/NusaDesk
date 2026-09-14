package gh.nusashell.nusadesk.application.runtime;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

/** Converts interrupted install phases into an honest retryable failure. */
public final class RuntimeSnapshotReconciler {
    private RuntimeSnapshotReconciler() {
    }

    public static RuntimeSnapshot reconcile(RuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        switch (snapshot.getState()) {
            case DOWNLOADING:
            case VERIFYING:
            case EXTRACTING:
                return new RuntimeSnapshot(
                        snapshot.getAppId(),
                        RuntimeState.FAILED,
                        "The previous installation was interrupted. Retry installation.",
                        0,
                        System.currentTimeMillis());
            default:
                return snapshot;
        }
    }
}
