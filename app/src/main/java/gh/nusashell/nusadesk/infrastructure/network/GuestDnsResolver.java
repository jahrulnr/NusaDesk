package gh.nusashell.nusadesk.infrastructure.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.FileObserver;
import android.util.Log;

import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges Android's active-network DNS into the guest as a bound
 * {@code /etc/resolv.conf}, keeps it current while the runtime runs, and
 * repairs guest/package-manager mutations of the host-owned source.
 *
 * <p>This is the Android adapter the PRoot launcher uses. It owns one fixed,
 * persistent app-private resolver file under {@code filesDir}, writes it from
 * {@link AndroidActiveNetworkDns} via {@link GuestResolvConfWriter}, and
 * returns a {@link ProotBindMount} of that file over the guest
 * {@code /etc/resolv.conf}. When Android has no valid DNS, it returns
 * {@link Optional#empty()} so the launcher binds nothing and the guest keeps
 * its own resolver — the runtime still works, just without DNS.</p>
 *
 * <p>Lifecycle refresh: while the supervised runtime is running,
 * {@link #startRefresh()} registers a default-network callback and starts one
 * bounded doctor scheduler. The scheduler observes the resolver source's
 * parent directory for replacement events and also runs a periodic check;
 * either signal repairs only this product-owned source file. The observer is
 * an early trigger, not the correctness boundary: Android can kill the
 * process, inotify events can be lost, and PRoot may virtualise a guest
 * rename. {@link #stopRefresh()} unregisters and stops both mechanisms.</p>
 *
 * <p>Security: the file is app-private and contains only validated literal
 * nameserver lines (no secrets, no hostnames, no injected directives). It is
 * bound over the guest {@code /etc/resolv.conf} only; no host filesystem is
 * exposed broadly and the active rootfs is never mutated by the doctor.</p>
 */
public final class GuestDnsResolver {

    private static final String TAG = "GuestDnsResolver";

    /** Fixed persistent state directory below the app's files directory. */
    static final String RESOLVER_STATE_DIR = "linux-wrapper/state";
    /** Fixed app-private resolver file, written and rewritten in place. */
    static final String RESOLV_CONF_NAME = "resolv.conf";
    /** Guest path the file is bound over. */
    public static final String GUEST_RESOLV_PATH = "/etc/resolv.conf";
    /** Doctor tick: frequent enough to repair package-manager mutations, bounded for battery. */
    private static final long DOCTOR_PERIOD_SECONDS = 30L;
    /** Debounce window for an atomic rename/write burst. */
    private static final long DOCTOR_DEBOUNCE_MILLIS = 1_000L;

    private final Context context;
    private final Path resolvFile;
    private final AndroidActiveNetworkDns source;
    private final GuestResolverDoctor doctor;
    private final Executor executor;
    private final AtomicBoolean repairQueued = new AtomicBoolean(false);
    private volatile ConnectivityManager.NetworkCallback callback;
    private volatile FileObserver resolverObserver;
    private volatile ScheduledExecutorService doctorExecutor;

    public GuestDnsResolver(Context context, Executor executor) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.resolvFile = this.context.getFilesDir().toPath()
                .resolve(RESOLVER_STATE_DIR).resolve(RESOLV_CONF_NAME);
        this.source = new AndroidActiveNetworkDns(this.context);
        this.doctor = new GuestResolverDoctor(resolvFile, source::activeDnsServers);
    }

    /**
     * Check and repair the current resolver source, then return the guest bind.
     * Safe to call on every PRoot launch: it re-reads Android's current DNS and
     * atomically restores only the product-owned source file.
     */
    public Optional<ProotBindMount> resolverBind() {
        try {
            GuestResolverDoctor.Report report = doctor.repair();
            if (!report.hasUsableResolver()) {
                if (report.getStatus() != GuestResolverDoctor.Status.NO_ACTIVE_DNS) {
                    Log.w(TAG, "guest resolver source is not usable: " + report);
                }
                return Optional.empty();
            }
            return Optional.of(ProotBindMount.of(resolvFile.toString(), GUEST_RESOLV_PATH));
        } catch (Exception e) {
            Log.w(TAG, "could not prepare guest resolv.conf; the guest keeps its own resolver", e);
            return Optional.empty();
        }
    }

    /**
     * Start network refresh plus the bounded resolver doctor. Idempotent.
     * Registration failures are logged; the periodic/source guard still runs.
     */
    public synchronized void startRefresh() {
        startDoctor();
        if (callback != null) {
            return;
        }
        ConnectivityManager cm = connectivityManager();
        if (cm == null) {
            return;
        }
        try {
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    rewrite();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    rewrite();
                }
            };
            cm.registerDefaultNetworkCallback(cb);
            callback = cb;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not register default network callback for DNS refresh", e);
        }
    }

    /** Stop the network refresh and resolver doctor. Idempotent. */
    public synchronized void stopRefresh() {
        ConnectivityManager.NetworkCallback cb = callback;
        callback = null;
        if (cb != null) {
            ConnectivityManager cm = connectivityManager();
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(cb);
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not unregister default network callback", e);
                }
            }
        }
        stopDoctor();
    }

    private void startDoctor() {
        if (doctorExecutor != null) {
            return;
        }
        try {
            Files.createDirectories(resolvFile.getParent());
        } catch (Exception e) {
            Log.w(TAG, "could not create resolver state directory", e);
        }
        ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "guest-resolver-doctor");
            thread.setDaemon(true);
            return thread;
        });
        doctorExecutor = scheduled;
        try {
            FileObserver observer = new FileObserver(
                    resolvFile.getParent().toFile(),
                    FileObserver.CLOSE_WRITE
                            | FileObserver.CREATE
                            | FileObserver.DELETE
                            | FileObserver.MOVED_FROM
                            | FileObserver.MOVED_TO
                            | FileObserver.ATTRIB) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null || RESOLV_CONF_NAME.equals(path)) {
                        queueRepair();
                    }
                }
            };
            resolverObserver = observer;
            observer.startWatching();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start resolver file observer", e);
        }
        scheduled.scheduleWithFixedDelay(this::runDoctor, DOCTOR_PERIOD_SECONDS,
                DOCTOR_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void stopDoctor() {
        FileObserver observer = resolverObserver;
        resolverObserver = null;
        if (observer != null) {
            try {
                observer.stopWatching();
            } catch (RuntimeException e) {
                Log.w(TAG, "could not stop resolver file observer", e);
            }
        }
        ScheduledExecutorService scheduled = doctorExecutor;
        doctorExecutor = null;
        repairQueued.set(false);
        if (scheduled != null) {
            scheduled.shutdownNow();
        }
    }

    private void queueRepair() {
        ScheduledExecutorService scheduled = doctorExecutor;
        if (scheduled == null || !repairQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduled.schedule(() -> {
                repairQueued.set(false);
                runDoctor();
            }, DOCTOR_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            repairQueued.set(false);
        }
    }

    private void runDoctor() {
        try {
            GuestResolverDoctor.Report report = doctor.repair();
            if (report.getStatus() != GuestResolverDoctor.Status.OK) {
                Log.i(TAG, "resolver doctor: " + report);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "resolver doctor failed", e);
        }
    }

    private void rewrite() {
        Executor ex = executor;
        if (ex == null) {
            runDoctor();
            return;
        }
        try {
            ex.execute(this::runDoctor);
        } catch (RejectedExecutionException ree) {
            // Executor shutting down: use the doctor thread as a fallback.
            runDoctor();
        }
    }

    private ConnectivityManager connectivityManager() {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
}
