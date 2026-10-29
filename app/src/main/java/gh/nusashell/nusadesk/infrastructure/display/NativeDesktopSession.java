package gh.nusashell.nusadesk.infrastructure.display;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.json.JSONObject;

import gh.nusashell.nusadesk.application.display.NativeDesktopPort;
import gh.nusashell.nusadesk.domain.display.DesktopTask;
import gh.nusashell.nusadesk.domain.display.DesktopTasks;
import gh.nusashell.nusadesk.domain.display.LinuxDesktopApp;
import gh.nusashell.nusadesk.domain.runtime.CuratedRuntimeCatalog;
import gh.nusashell.nusadesk.infrastructure.proot.ProotPaths;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.infrastructure.session.SharedPreferencesHostKeyTrustStore;
import gh.nusashell.nusadesk.infrastructure.ssh.KeystoreVaultCredentialProvider;
import gh.nusashell.nusadesk.infrastructure.ssh.LocalSshSessionFactory;
import gh.nusashell.nusadesk.infrastructure.ssh.SshClientBridge;
import gh.nusashell.nusadesk.infrastructure.ssh.SshReconnectPolicy;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionListener;
import gh.nusashell.nusadesk.infrastructure.ssh.SshSessionState;

/** One display for the existing guest. Binding survives view recreation, never a guest session stop. */
public final class NativeDesktopSession implements NativeDesktopPort {
    private static NativeDesktopSession instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile IX11Server server;
    private volatile CountDownLatch serverGone = new CountDownLatch(0);
    private volatile SshClientBridge guest;
    private volatile boolean displayReady;
    private volatile State state = State.STOPPED;
    private volatile String detail = "Open a Linux GUI application";
    private volatile int generation;
    private volatile boolean wmReady, rendererConnected;
    private volatile boolean bound;
    private volatile boolean opening;
    private volatile LinuxDesktopApp selectedApp;
    private final AtomicLong foregroundVersion = new AtomicLong();
    private final DesktopTasks tasks = new DesktopTasks();
    /** Channels are main-confined; the map monitor only serializes puts against teardown's clear. */
    private final Map<String, AppChannel> apps = new ConcurrentHashMap<>();
    private final Set<String> ownedBusAddresses = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Consumer<List<DesktopTask>>> taskListeners = new CopyOnWriteArrayList<>();
    private static final class AppChannel {
        final LinuxDesktopApp app;
        final SshClientBridge ssh;
        final StringBuilder output = new StringBuilder();
        boolean ready, failed, sawClosed, sawFailed;
        AppChannel(LinuxDesktopApp app, SshClientBridge ssh) { this.app = app; this.ssh = ssh; }
    }
    private volatile CountDownLatch bindingComplete = new CountDownLatch(0);
    private X11Surface surface;
    private final StringBuilder guestOutput = new StringBuilder();

    private NativeDesktopSession(Context context) {
        this(context, Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "native-desktop"); thread.setDaemon(true); return thread;
        }));
    }

    NativeDesktopSession(Context context, ExecutorService worker) {
        this.context = context.getApplicationContext();
        this.worker = worker;
    }

    public static synchronized NativeDesktopSession get(Context context) {
        if (instance == null) instance = new NativeDesktopSession(context);
        return instance;
    }

    public LinuxDesktopApp selectedApp() { return selectedApp; }

    public void subscribeTasks(Consumer<List<DesktopTask>> listener) {
        taskListeners.addIfAbsent(listener); listener.accept(tasks.snapshot());
    }
    public void unsubscribeTasks(Consumer<List<DesktopTask>> listener) { taskListeners.remove(listener); }
    private void tasksChanged() {
        List<DesktopTask> snapshot = tasks.snapshot();
        for (Consumer<List<DesktopTask>> listener : taskListeners) listener.accept(snapshot);
    }

    /** Select an existing live task or schedule a new app; other tasks keep running. */
    public void selectApp(LinuxDesktopApp app) {
        if (surface != null) surface.prepareTaskSwitch();
        AppChannel previous = apps.get(app.getId());
        if (previous != null && previous.failed) { apps.remove(app.getId()); worker.execute(previous.ssh::close); }
        selectedApp = app;
        tasks.select(app);
        if (previous != null && previous.ready && !previous.failed) tasks.ready(app.getId());
        foreground(app.getId());
        tasksChanged();
        if (displayReady) { ensureApps(); renderSelected(); }
    }

    /** Minimize windows while retaining the app channel, PID and in-memory document. */
    public void minimize() {
        if (surface != null) surface.prepareTaskSwitch();
        tasks.minimize(); foreground(""); tasksChanged();
    }

    public void closeSelected() {
        DesktopTask selected = tasks.selected();
        if (selected == null) return;
        if (selected.getState() == DesktopTask.State.FAILED) {
            // A display/foreground error can fail a task while its channel
            // remains live. Detach it before closing so late callbacks cannot
            // resurrect the task or affect a new launch of the same app.
            String id = selected.getApp().getId();
            AppChannel channel = apps.remove(id);
            if (channel != null) worker.execute(channel.ssh::close);
            tasks.remove(id);
            tasksChanged();
            renderSelected();
            return;
        }
        foreground(selected.getApp().getId(), true);
    }

    private void foreground(String id) { foreground(id, false); }
    private void foreground(String id, boolean close) {
        long requestVersion = foregroundVersion.incrementAndGet();
        worker.execute(() -> {
            synchronized (NativeDesktopSession.this) {
                HostRuntimeStatus runtime = RuntimeStatusBus.getInstance().current();
                if (requestVersion != foregroundVersion.get() || runtime == null
                        || !runtime.isRuntimeRunning()) return;
                try {
                    Path tmp = rootfs().resolve("tmp");
                    Path temporary = Files.createTempFile(tmp, ".foreground-", ".json");
                    try {
                        JSONObject request = new JSONObject(); request.put("id", id); request.put("token", UUID.randomUUID().toString());
                        if (close) request.put("close", UUID.randomUUID().toString());
                        Files.write(temporary, request.toString().getBytes(StandardCharsets.UTF_8));
                        Files.move(temporary, tmp.resolve("nusadesk-foreground.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                } catch (Exception error) { fail(generation, error); }
            }
        });
    }

    public void attach(X11Surface view) {
        if (surface != null && surface != view) surface.release();
        surface = view;
        view.setOnDisconnected(rendererDisconnectCallback(view));
        if (server != null && displayReady) connectView(server, generation);
    }

    Runnable rendererDisconnectCallback(X11Surface view) {
        return () -> main.post(() -> {
            if (surface != view) return;
            rendererConnected = false; displayReady = false; opening = false;
            publish(State.FAILED, "Linux display disconnected. Open again to retry.");
        });
    }

    public void detach(X11Surface view) {
        if (surface == view) { view.release(); surface = null; }
    }

    /**
     * Drop the renderer connection only. Keeps the display process, WM, and
     * guest apps; publishes FAILED so Retry can reconnect to the same server.
     */
    public void disconnectRenderer() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::disconnectRenderer);
            return;
        }
        X11Surface view = surface;
        if (view == null) return;
        view.disconnectRenderer();
    }

    private Path rootfs() {
        return ProotPaths.activeRootfsPath(context.getFilesDir().toPath(),
                CuratedRuntimeCatalog.ubuntuBaseArm64().getAppId());
    }

    @Override public void open() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::open); return; }
        if (tasks.selected() == null) {
            // Test-only default GUI app; product flow always selects an installed desktop entry.
            selectApp(selectedApp == null ? new LinuxDesktopApp("org.xfce.mousepad.desktop", "Mousepad",
                    "/usr/share/applications/org.xfce.mousepad.desktop", null) : selectedApp);
        }
        if (tasks.selected() != null && tasks.selected().getState() == DesktopTask.State.FAILED) selectApp(tasks.selected().getApp());
        if (displayReady) { ensureApps(); renderSelected(); return; }
        if (opening) return;
        if (bound && wmReady && server != null) {
            opening = true;
            publish(State.STARTING, "Reconnecting Linux display…");
            connectView(server, generation);
            return;
        }
        HostRuntimeStatus runtime = RuntimeStatusBus.getInstance().current();
        if (runtime == null || !runtime.isRuntimeRunning()) {
            String message = "Linux is not running. Return to the launcher and wait for it.";
            if (tasks.selectedId() != null) tasks.fail(tasks.selectedId(), message);
            tasksChanged(); publish(State.STOPPED, message);
            return;
        }
        // Guest file checks stay off the UI thread; the verified continuation runs openAfterChecks.
        opening = true;
        publish(State.STARTING, "Starting Linux display…");
        int attempt = generation;
        worker.execute(() -> {
            boolean missing = guiComponentsMissing(rootfs());
            main.post(() -> {
                if (generation != attempt) return;
                opening = false;
                if (missing) {
                    String message = "The Linux GUI needs openbox, xkb-data and x11-utils, plus Python 3, GLib/GIO and dbus-run-session for desktop entries.";
                    if (tasks.selectedId() != null) tasks.fail(tasks.selectedId(), message);
                    tasksChanged(); publish(State.MISSING_COMPONENTS, message);
                    return;
                }
                openAfterChecks();
            });
        });
        return;
    }

    private static boolean guiComponentsMissing(Path root) {
        return !Files.isDirectory(root.resolve("usr/share/X11/xkb"))
                || !Files.isRegularFile(root.resolve("usr/bin/openbox"))
                || !Files.exists(root.resolve("usr/bin/python3"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(root.resolve("usr/bin/xdpyinfo"))
                || !Files.isRegularFile(root.resolve("usr/bin/xprop"))
                || !Files.isRegularFile(root.resolve("usr/bin/dbus-run-session"));
    }

    private void openAfterChecks() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::openAfterChecks); return; }
        if (displayReady) { ensureApps(); renderSelected(); return; }
        if (opening) return;
        // The runtime may have stopped while the component check was on the worker.
        HostRuntimeStatus runtime = RuntimeStatusBus.getInstance().current();
        if (runtime == null || !runtime.isRuntimeRunning()) {
            String message = "Linux is not running. Return to the launcher and wait for it.";
            if (tasks.selectedId() != null) tasks.fail(tasks.selectedId(), message);
            tasksChanged(); publish(State.STOPPED, message);
            return;
        }
        if (bound) {
            opening = true;
            worker.execute(() -> {
                try { stopAndAwait(); main.post(this::open); }
                catch (IOException error) { fail(generation, error); }
            });
            return;
        }
        opening = true;
        generation++;
        publish(State.STARTING, "Starting Linux display…");
        bindingComplete = new CountDownLatch(1);
        connection = newConnection(generation, bindingComplete);
        bound = context.bindService(new Intent(context, X11ServerService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            bindingComplete.countDown();
            opening = false;
            fail(generation, new IOException("Android could not start the Linux display."));
        }
    }

    private volatile ServiceConnection connection;
    private ServiceConnection newConnection(int attempt, CountDownLatch connected) {
        return new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            IX11Server endpoint = IX11Server.Stub.asInterface(binder);
            if (connection != this) {
                connected.countDown();
                worker.execute(() -> { try { endpoint.shutdown(); } catch (RemoteException ignored) { } });
                return;
            }
            server = endpoint;
            CountDownLatch gone = new CountDownLatch(1);
            serverGone = gone;
            try { binder.linkToDeath(gone::countDown, 0); }
            catch (RemoteException error) { gone.countDown(); }
            connected.countDown();
            if (generation != attempt) return;
            worker.execute(() -> {
                try {
                    GuestDesktopCatalog.stage(context);
                    waitForX11(rootfs().resolve("tmp/.X11-unix/X1"));
                    connectView(endpoint, attempt);
                    main.post(() -> {
                        if (generation == attempt) { foreground(tasks.selectedId() == null ? "" : tasks.selectedId()); launchGuest(attempt); }
                    });
                } catch (Exception error) { fail(attempt, error); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            connected.countDown();
            // A delayed callback from an unbound service must not acknowledge
            // the death of the replacement server before its /tmp is cleaned.
            if (connection != this) return;
            serverGone.countDown();
            if (generation == attempt) {
                stopAsync("Linux display stopped. Open again to retry.");
            }
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
        };
    }

    private void stopAsync(String message) {
        displayReady = false; wmReady = false; rendererConnected = false; opening = true;
        generation++; // invalidate queued app and WM callbacks before the worker closes channels
        worker.execute(() -> {
            String result = message;
            try { stopAndAwait(); }
            catch (IOException error) { result += " " + error.getMessage(); }
            String failure = result;
            main.post(() -> publish(State.FAILED, failure));
        });
    }

    private void connectView(IX11Server endpoint, int attempt) {
        worker.execute(() -> {
            try {
                ParcelFileDescriptor fd = endpoint.connectRenderer();
                main.post(() -> {
                    try (ParcelFileDescriptor descriptor = fd) {
                        if (generation == attempt && surface != null) {
                            surface.attachConnection(descriptor.detachFd());
                            rendererConnected = true;
                            displayReady = wmReady;
                            if (displayReady) { opening = false; ensureApps(); renderSelected(); }
                        }
                    } catch (Exception error) { fail(attempt, error); }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (generation != attempt) return;
                    wmReady = false; rendererConnected = false; displayReady = false;
                    fail(attempt, error);
                });
            }
        });
    }

    private SshClientBridge newChannel() {
        return new SshClientBridge(new KeystoreVaultCredentialProvider(context),
                new SharedPreferencesHostKeyTrustStore(context), LocalSshSessionFactory.pinnedHostKeyOnly(),
                new SshReconnectPolicy(1, 0, 0), System::currentTimeMillis);
    }

    private void launchGuest(int attempt) {
        publish(State.STARTING, "Starting Linux window manager…");
        worker.execute(() -> {
            try {
                // The openbox config write is file I/O; keep it off the main thread.
                Files.write(rootfs().resolve("tmp/nusadesk-openbox.xml"), (
                        "<?xml version=\"1.0\"?><openbox_config xmlns=\"http://openbox.org/3.4/rc\">"
                        + "<applications><application type=\"normal\"><decor>no</decor>"
                        + "<maximized>yes</maximized></application></applications></openbox_config>").getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { fail(attempt, error); return; }
            main.post(() -> { if (generation == attempt) startWindowManager(attempt); });
        });
    }

    private void startWindowManager(int attempt) {
        try {
            synchronized (guestOutput) { guestOutput.setLength(0); }
            guest = newChannel();
            guest.start(LocalSshSessionFactory.create(80, 24), GuestDesktopWorkload.windowManagerCommand(), new SshSessionListener() {
                public void onState(SshSessionState next, String detail) {
                    if (next == SshSessionState.FAILED) fail(attempt, new IOException(detail));
                }
                public void onStdout(byte[] data, int length) {
                    logGuest(data, length);
                    synchronized (guestOutput) {
                        guestOutput.append(new String(data, 0, length, StandardCharsets.UTF_8));
                        if (guestOutput.indexOf(GuestDesktopWorkload.WM_READY_MARKER) >= 0) main.post(() -> {
                            if (generation == attempt && !displayReady) {
                                wmReady = true; displayReady = rendererConnected;
                                if (displayReady) { opening = false; ensureApps(); renderSelected(); }
                            }
                        });
                        if (guestOutput.length() > 4096) guestOutput.delete(0, guestOutput.length() - 128);
                    }
                }
                public void onStderr(byte[] data, int length) { logGuest(data, length); }
                public void onClosed(String reason) { main.post(() -> {
                    if (generation == attempt) {
                        stopAsync("Linux window manager closed: " + reason);
                    }
                }); }
            });
        } catch (Exception error) { fail(attempt, error); }
    }

    private void ensureApps() {
        synchronized (apps) {
            if (!displayReady) return;
            for (DesktopTask task : tasks.snapshot()) {
                String id = task.getApp().getId();
                if (task.getState() != DesktopTask.State.STARTING || apps.containsKey(id)) continue;
                // stopAndAwait clears displayReady under this monitor: recheck so a put cannot outlive closeApps.
                if (!displayReady) return;
                AppChannel channel = new AppChannel(task.getApp(), newChannel());
                apps.put(id, channel);
                int attempt = generation;
                String busAddress = "unix:abstract=nusadesk-display-" + UUID.randomUUID();
                ownedBusAddresses.add(busAddress);
                channel.ssh.start(LocalSshSessionFactory.create(80, 24), GuestDesktopWorkload.command(id, busAddress), new SshSessionListener() {
                    public void onState(SshSessionState next, String detail) {
                        if (next == SshSessionState.FAILED) main.post(() -> {
                            if (generation == attempt && apps.get(id) == channel) {
                                channel.failed = true; tasks.fail(id, detail); tasksChanged(); renderSelected();
                            }
                        });
                    }
                    public void onStdout(byte[] data, int length) {
                        logGuest(data, length);
                        synchronized (channel.output) {
                            channel.output.append(new String(data, 0, length, StandardCharsets.UTF_8));
                            // Latch the verdict markers: the bounded buffer can trim them away before close.
                            if (channel.output.indexOf(DesktopAppResult.CLOSED) >= 0) channel.sawClosed = true;
                            if (channel.output.indexOf(DesktopAppResult.FAILED) >= 0) channel.sawFailed = true;
                            if (channel.output.indexOf(GuestDesktopWorkload.READY_MARKER) >= 0) main.post(() -> {
                                if (generation == attempt && apps.get(id) == channel && !channel.ready) {
                                    channel.ready = true; tasks.ready(id); tasksChanged(); renderSelected();
                                }
                            });
                            if (channel.output.length() > 4096) channel.output.delete(0, channel.output.length() - 128);
                        }
                    }
                    public void onStderr(byte[] data, int length) {
                        logGuest(data, length);
                        synchronized (channel.output) {
                            channel.output.append(new String(data, 0, length, StandardCharsets.UTF_8));
                            if (channel.output.indexOf(DesktopAppResult.CLOSED) >= 0) channel.sawClosed = true;
                            if (channel.output.indexOf(DesktopAppResult.FAILED) >= 0) channel.sawFailed = true;
                            if (channel.output.length() > 4096) channel.output.delete(0, channel.output.length() - 2048);
                        }
                    }
                    public void onClosed(String reason) { main.post(() -> {
                        if (generation != attempt || apps.get(id) != channel) return;
                        apps.remove(id);
                        worker.execute(channel.ssh::close);
                        String output; boolean sawClosed, sawFailed;
                        synchronized (channel.output) {
                            output = channel.output.toString().trim();
                            sawClosed = channel.sawClosed; sawFailed = channel.sawFailed;
                        }
                        if (DesktopAppResult.closed(sawClosed, sawFailed, channel.failed)) tasks.remove(id);
                        else tasks.fail(id, "Linux application failed: " + DesktopAppResult.failure(output, reason));
                        tasksChanged(); renderSelected();
                    }); }
                });
            }
        }
    }
    private void renderSelected() {
        DesktopTask task = tasks.selected();
        if (task == null) { publish(State.APP_CLOSED, "Linux application closed."); return; }
        switch (task.getState()) {
            case RUNNING: publish(State.RUNNING, ""); break;
            case FAILED: publish(State.FAILED, task.getDetail()); break;
            default: publish(State.STARTING, "Waiting for " + task.getApp().getName() + "…");
        }
    }

    private List<SshClientBridge> closeApps() {
        List<AppChannel> closing;
        synchronized (apps) {
            closing = new ArrayList<>(apps.values()); apps.clear();
        }
        List<SshClientBridge> result = new ArrayList<>();
        for (AppChannel channel : closing) { channel.ssh.close(); result.add(channel.ssh); }
        return result;
    }

    private void logGuest(byte[] data, int length) {
        Log.d("NusaDeskGUI", new String(data, 0, length, StandardCharsets.UTF_8));
    }

    private void fail(int attempt, Exception error) {
        Log.e("NusaDeskGUI", "native desktop failed", error);
        main.post(() -> {
            if (generation == attempt) {
                opening = false;
                String message = "Linux display failed: " + (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
                if (tasks.selectedId() != null) tasks.fail(tasks.selectedId(), message);
                tasksChanged(); publish(State.FAILED, message);
            }
        });
    }

    private static void waitForX11(Path path) throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        IOException last = null;
        do {
            try (LocalSocket socket = new LocalSocket()) {
                X11SocketProbe.exchange(socket, new LocalSocketAddress(
                        path.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
                return;
            } catch (IOException error) { last = error; }
            Thread.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IOException("X11 did not answer its protocol probe", last);
    }

    @Override public void subscribe(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.onStatus(state, detail);
    }
    @Override public void unsubscribe(Listener listener) { listeners.remove(listener); }
    private void publish(State next, String message) {
        state = next; detail = message;
        main.post(() -> { for (Listener listener : listeners) listener.onStatus(next, message); });
    }

    /** Kill only buses recorded by this owner; abstract socket names die with their process. */
    private void reapPrivateSessionBuses() throws IOException {
        Path proc = Paths.get("/proc");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!ownedBusAddresses.isEmpty()) {
            // PID reuse is accepted: each pass re-reads cmdline, so a recycled PID only
            // matches while the live process still carries this owner's exact address.
            List<Long> remaining = GuestDisplayCleanup.privateSessionBusPids(proc, ownedBusAddresses);
            if (remaining.isEmpty()) { ownedBusAddresses.clear(); return; }
            for (long pid : remaining) {
                try { Os.kill((int) pid, OsConstants.SIGKILL); }
                catch (ErrnoException error) {
                    if (error.errno != OsConstants.ESRCH) throw new NativeDisplayShutdownException("Could not stop an owned display bus", error);
                }
            }
            if (System.nanoTime() >= deadline) throw new NativeDisplayShutdownException("Owned display buses did not stop within 2 seconds");
            try { Thread.sleep(25); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new NativeDisplayShutdownException("Display bus cleanup interrupted", error);
            }
        }
    }

    /** Called on the workload worker before guest /tmp cleanup. Wait for actual process death. */
    public static void stopBeforeTmpCleanup() throws IOException {
        NativeDesktopSession current;
        synchronized (NativeDesktopSession.class) { current = instance; }
        if (current == null) return;
        // Raise the open() guard before teardown so a racing main-thread open()
        // cannot bind mid-stop; stopAndAwait owns and releases it from here.
        current.opening = true;
        current.stopAndAwait();
    }

    synchronized void stopAndAwait() throws IOException {
        foregroundVersion.incrementAndGet();
        generation++;
        opening = true;
        // Under the apps monitor so an ensureApps put can never land after closeApps' clear.
        synchronized (apps) { displayReady = false; }
        wmReady = false; rendererConnected = false;
        ServiceConnection owned = connection;
        try {
            List<SshClientBridge> closing = closeApps();
            SshClientBridge old = guest; guest = null;
            if (old != null) { old.close(); closing.add(old); }
            if (bound && !bindingComplete.await(30, TimeUnit.SECONDS)) {
                throw new NativeDisplayShutdownException("Linux display startup did not finish within 30 seconds while stopping");
            }
            IX11Server endpoint = server;
            if (endpoint != null) {
                try {
                    endpoint.shutdown();
                    if (!serverGone.await(10, TimeUnit.SECONDS)) {
                        throw new NativeDisplayShutdownException("Linux display process did not stop within 10 seconds");
                    }
                } catch (RemoteException error) {
                    if (endpoint.asBinder().isBinderAlive()) throw new NativeDisplayShutdownException("Linux display shutdown failed", error);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (SshClientBridge channel : closing) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0 || !channel.awaitClosed(remaining)) {
                    throw new NativeDisplayShutdownException("Linux display SSH channels did not finish stopping");
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new NativeDisplayShutdownException("Linux display shutdown wait interrupted", error);
        } finally {
            try {
                reapPrivateSessionBuses();
            } catch (Exception error) {
                // Secondary cleanup must not mask a primary shutdown failure.
                Log.w("NusaDeskGUI", "could not reap owned display buses", error);
            }
            try {
                // A timed-out or still-binding server may still own this socket.
                if (server != null && !server.asBinder().isBinderAlive()) GuestDisplayCleanup.unlinkServerSocket(rootfs());
            } catch (Exception error) {
                Log.w("NusaDeskGUI", "could not unlink the display socket", error);
            }
            server = null; connection = null;
            boolean wasBound = bound; bound = false;
            try {
                try {
                    if (wasBound && owned != null) context.unbindService(owned);
                } catch (IllegalArgumentException error) {
                    // A binding the framework already forgot must not escape teardown as a crash.
                    Log.w("NusaDeskGUI", "Linux display binding was already gone", error);
                }
            } finally {
                opening = false;
                main.post(() -> { tasks.clear(); tasksChanged(); publish(State.STOPPED, "Linux display stopped."); });
            }
        }
    }
}
