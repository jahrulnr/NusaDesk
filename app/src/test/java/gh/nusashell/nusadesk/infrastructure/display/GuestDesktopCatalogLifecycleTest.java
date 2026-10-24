package gh.nusashell.nusadesk.infrastructure.display;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.domain.session.SessionSnapshot;
import gh.nusashell.nusadesk.domain.session.SessionState;
import gh.nusashell.nusadesk.domain.network.RuntimePort;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class GuestDesktopCatalogLifecycleTest {
    private static final class Work extends AbstractExecutorService {
        final List<Runnable> work = new ArrayList<>();
        public void execute(Runnable task) { work.add(task); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long value, TimeUnit unit) { return true; }
    }
    @Test public void timeoutCompletesAndBusyRequestsReceiveOneFreshFollowupRatherThanBeingDropped() {
        RuntimeStatusBus.getInstance().publish(new HostRuntimeStatus(new SessionSnapshot("catalog", "ubuntu-base-arm64", "1",
                SessionState.RUNNING, new RuntimePort("127.0.0.1", 22022), 1, 1, "", 0), true, "ssh"));
        Work work = new Work(); GuestDesktopCatalog catalog = new GuestDesktopCatalog(RuntimeEnvironment.getApplication(), work);
        AtomicInteger first = new AtomicInteger(), followup = new AtomicInteger();
        catalog.refresh((apps, error) -> { assertTrue(error.contains("timed out")); first.incrementAndGet(); });
        catalog.refresh((apps, error) -> followup.incrementAndGet());
        assertEquals(1, work.work.size());
        ShadowLooper.idleMainLooper(15, TimeUnit.SECONDS);
        assertEquals(1, first.get()); assertEquals(0, followup.get()); assertEquals(2, work.work.size());
        ShadowLooper.idleMainLooper(15, TimeUnit.SECONDS);
        assertEquals(1, followup.get());
        catalog.close();
    }
}
