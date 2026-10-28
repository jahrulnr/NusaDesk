package gh.nusashell.nusadesk.infrastructure.service;

import android.content.Intent;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class RuntimeRestartFromFailedTest {
    @After public void cleanup() { RuntimeWorkloadRegistry.getInstance().unregister(); }

    @Test public void explicitRestartStartsANewAttemptAfterAnOrdinaryFailure() {
        var lifecycle = Robolectric.buildService(RuntimeHostService.class).create();
        RuntimeHostService service = lifecycle.get();
        int[] starts = {0};
        RuntimeWorkloadRegistry.getInstance().register(new RuntimeWorkload() {
            public String workloadId() { return "test"; }
            public void start(SessionSnapshot session, WorkloadListener listener) {
                starts[0]++;
                listener.onFailed("guest OpenSSH sshd exited unexpectedly (exit code 1)");
            }
            public void stop(WorkloadListener listener) { listener.onStopped(); }
        });
        try {
            service.onStartCommand(new Intent().setAction(RuntimeHostService.ACTION_ENSURE_RUNNING), 0, 1);
            ShadowLooper.idleMainLooper();
            assertEquals(1, starts[0]);
            assertEquals(SessionState.FAILED, RuntimeStatusBus.getInstance().current().getState());
            service.onStartCommand(new Intent().setAction(RuntimeHostService.ACTION_RESTART), 0, 2);
            ShadowLooper.idleMainLooper();
            assertEquals("Restart from FAILED must actually start a new attempt", 2, starts[0]);
            assertEquals("a failed retry stays failed, without a restart loop", SessionState.FAILED,
                    RuntimeStatusBus.getInstance().current().getState());
        } finally { lifecycle.destroy(); }
    }
}
