package gh.nusashell.nusadesk.presentation.webapp;
import android.app.Activity;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.infrastructure.webapp.WebAppReadinessObserver;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.IOException;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppProbeGenerationTest {
    private static final class Work extends AbstractExecutorService {
        final List<Runnable> queue = new ArrayList<>();
        public void execute(Runnable task) { queue.add(task); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long time, TimeUnit unit) { return true; }
    }
    @Test public void reloadSupersedesQueuedProbesAndReleaseCancelsDelayedRetry() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        WebAppSurfaceView surface = new WebAppSurfaceView(activity);
        AtomicInteger calls = new AtomicInteger();
        WebAppReadinessObserver observer = new WebAppReadinessObserver((scheme, host, port, path, connect, read) -> {
            calls.incrementAndGet(); throw new IOException("server not ready");
        }, 1, 1);
        java.lang.reflect.Field field = WebAppSurfaceView.class.getDeclaredField("observer");
        field.setAccessible(true); field.set(surface, observer);
        Work work = new Work();
        surface.bind(new WebAppDefinition(WebAppId.of("server"), "Server", null, 4050, 1, 1, 0, "serve"), work,
                (host, realm, answer) -> answer.cancel());
        surface.reload();
        work.queue.remove(0).run(); assertEquals(0, calls.get());
        work.queue.remove(0).run(); ShadowLooper.idleMainLooper(); assertEquals(1, calls.get());
        surface.release(); ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS);
        assertTrue(work.queue.isEmpty());
    }
}
