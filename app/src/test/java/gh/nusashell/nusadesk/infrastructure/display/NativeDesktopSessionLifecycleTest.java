package gh.nusashell.nusadesk.infrastructure.display;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ServiceConnection;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import gh.nusashell.nusadesk.application.display.NativeDesktopPort;
import gh.nusashell.nusadesk.domain.display.DesktopTasks;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class NativeDesktopSessionLifecycleTest {
    private Object previousRuntimeStatus;
    @Before public void isolateRuntimeStatus() throws Exception {
        Object bus = gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus.getInstance();
        previousRuntimeStatus = get(bus, "lastStatus");
        put(bus, "lastStatus", null);
    }
    @After public void restoreRuntimeStatus() throws Exception {
        ShadowLooper.idleMainLooper();
        put(gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus.getInstance(), "lastStatus", previousRuntimeStatus);
    }
    private static final class Work extends AbstractExecutorService {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        public void execute(Runnable action) { queue.add(action); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long time, TimeUnit unit) { return true; }
        void drain() throws Exception {
            while (!queue.isEmpty()) CompletableFuture.runAsync(queue.remove()).get(3, TimeUnit.SECONDS);
        }
    }
    private static final class BindingContext extends ContextWrapper {
        int unbinds;
        boolean rejectUnbind;
        BindingContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public void unbindService(ServiceConnection connection) {
            unbinds++;
            if (rejectUnbind) throw new IllegalArgumentException("stale binding");
        }
    }
    private static void put(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static ServiceConnection bind(NativeDesktopSession session) throws Exception {
        Method method = NativeDesktopSession.class.getDeclaredMethod("newConnection", int.class, CountDownLatch.class);
        method.setAccessible(true);
        ServiceConnection connection = (ServiceConnection) method.invoke(session, 0, new CountDownLatch(0));
        put(session, "connection", connection); put(session, "bound", true);
        return connection;
    }

    @Test public void disconnectQueuesTeardownAndPublishesFailureOnlyAfterWorkerCleanup() throws Exception {
        Work work = new Work(); BindingContext context = new BindingContext();
        NativeDesktopSession session = new NativeDesktopSession(context, work);
        ServiceConnection connection = bind(session);
        connection.onServiceDisconnected(null);
        assertEquals(1, work.queue.size()); assertEquals(0, context.unbinds);
        work.drain(); ShadowLooper.idleMainLooper();
        assertEquals(1, context.unbinds); assertEquals(false, get(session, "bound"));
        assertEquals(NativeDesktopPort.State.FAILED, get(session, "state"));
    }

    @Test public void binderShutdownFailureAlwaysClearsBindingAndEndpoint() throws Exception {
        Work work = new Work(); BindingContext context = new BindingContext();
        NativeDesktopSession session = new NativeDesktopSession(context, work); bind(session);
        put(session, "server", new IX11Server.Stub() {
            public ParcelFileDescriptor connectRenderer() { return null; }
            public void shutdown() throws RemoteException { throw new RemoteException("test shutdown failure"); }
        });
        CompletableFuture.runAsync(() -> {
            try { session.stopAndAwait(); fail("expected display-specific shutdown error"); }
            catch (NativeDisplayShutdownException expected) { assertTrue(expected.getMessage().contains("display shutdown")); }
            catch (Exception unexpected) { throw new AssertionError(unexpected); }
        }).get(3, TimeUnit.SECONDS);
        assertEquals(1, context.unbinds); assertEquals(false, get(session, "bound"));
        assertNull(get(session, "server")); assertNull(get(session, "connection"));
    }

    @Test public void staleServiceBindingIsLoggedNotEscalatedSoTeardownStillFinishes() throws Exception {
        Work work = new Work(); BindingContext context = new BindingContext();
        context.rejectUnbind = true;
        NativeDesktopSession session = new NativeDesktopSession(context, work); bind(session);
        CompletableFuture.runAsync(() -> {
            try { session.stopAndAwait(); }
            catch (Exception error) { throw new AssertionError(error); }
        }).get(3, TimeUnit.SECONDS);
        assertEquals(1, context.unbinds); assertEquals(false, get(session, "bound"));
        assertNull(get(session, "server")); assertNull(get(session, "connection"));
        assertEquals(false, get(session, "opening"));
        ShadowLooper.idleMainLooper();
        assertEquals(NativeDesktopPort.State.STOPPED, get(session, "state"));
    }

    @Test public void interruptedColdBindingStillUnbindsAndDoesNotLeaveRetryStuck() throws Exception {
        Work work = new Work(); BindingContext context = new BindingContext();
        NativeDesktopSession session = new NativeDesktopSession(context, work); bind(session);
        put(session, "bindingComplete", new CountDownLatch(1));
        CompletableFuture.runAsync(() -> {
            Thread.currentThread().interrupt();
            try { session.stopAndAwait(); fail("expected interrupt"); }
            catch (NativeDisplayShutdownException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
            catch (Exception unexpected) { throw new AssertionError(unexpected); }
            finally { Thread.interrupted(); }
        }).get(3, TimeUnit.SECONDS);
        assertEquals(false, get(session, "bound")); assertEquals(1, context.unbinds);
    }

    @Test public void retryReconnectsRendererInsteadOfReportingAnUnconnectedDisplayRunning() throws Exception {
        Work work = new Work(); NativeDesktopSession session = new NativeDesktopSession(new BindingContext(), work);
        DesktopTasks tasks = (DesktopTasks) get(session, "tasks");
        tasks.select(new LinuxDesktopApp("editor.desktop", "Editor", "/usr/share/applications/editor.desktop", null));
        tasks.ready("editor.desktop");
        put(session, "bound", true); put(session, "wmReady", true);
        AtomicInteger connections = new AtomicInteger();
        put(session, "server", new IX11Server.Stub() {
            public ParcelFileDescriptor connectRenderer() {
                connections.incrementAndGet();
                try { ParcelFileDescriptor[] pair = ParcelFileDescriptor.createPipe(); pair[1].close(); return pair[0]; }
                catch (Exception error) { throw new IllegalStateException(error); }
            }
            public void shutdown() { }
        });
        session.open(); work.drain(); ShadowLooper.idleMainLooper();
        assertEquals(1, connections.get()); assertEquals(false, get(session, "displayReady"));
        assertEquals(NativeDesktopPort.State.STARTING, get(session, "state"));
    }

    @Test public void rendererDisconnectInvalidatesReadinessWithoutStoppingTheBoundServer() throws Exception {
        Work work = new Work(); NativeDesktopSession session = new NativeDesktopSession(new BindingContext(), work);
        AtomicInteger connections = new AtomicInteger();
        DesktopTasks tasks = (DesktopTasks) get(session, "tasks");
        tasks.select(new LinuxDesktopApp("editor.desktop", "Editor", "/usr/share/applications/editor.desktop", null));
        tasks.ready("editor.desktop");
        IX11Server endpoint = new IX11Server.Stub() {
            public ParcelFileDescriptor connectRenderer() {
                connections.incrementAndGet();
                try {
                    ParcelFileDescriptor[] pair = ParcelFileDescriptor.createPipe();
                    pair[1].close();
                    return pair[0];
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }
            public void shutdown() { }
        };
        put(session, "bound", true); put(session, "wmReady", true);
        put(session, "rendererConnected", true); put(session, "displayReady", true);
        put(session, "server", endpoint);
        put(session, "state", NativeDesktopPort.State.RUNNING);
        // Execute the same callback installed by attach(), without loading ARM JNI in Robolectric.
        session.rendererDisconnectCallback(null).run();
        ShadowLooper.idleMainLooper();
        assertEquals(false, get(session, "rendererConnected"));
        assertEquals(false, get(session, "displayReady"));
        assertEquals(NativeDesktopPort.State.FAILED, get(session, "state"));
        assertEquals(true, get(session, "bound"));
        assertSame(endpoint, get(session, "server"));
        assertEquals(0, work.queue.size());
        session.open(); work.drain(); ShadowLooper.idleMainLooper();
        assertEquals(1, connections.get());
        assertEquals(NativeDesktopPort.State.STARTING, get(session, "state"));
        assertEquals(true, get(session, "bound"));
        assertSame(endpoint, get(session, "server"));
    }
}
