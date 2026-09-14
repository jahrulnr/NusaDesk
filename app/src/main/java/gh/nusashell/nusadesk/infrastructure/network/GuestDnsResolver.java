package gh.nusashell.nusadesk.infrastructure.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bridges Android's active-network DNS into the guest as a bound
 * {@code /etc/resolv.conf}, and keeps it current while the runtime runs.
 *
 * <p>This is the Android adapter the PRoot launcher uses. It owns one fixed
 * app-private resolver file (under the app cache dir), writes it from
 * {@link AndroidActiveNetworkDns} via {@link GuestResolvConfWriter}, and
 * returns a {@link ProotBindMount} of that file over the guest
 * {@code /etc/resolv.conf}. When Android has no valid DNS, it returns
 * {@link Optional#empty()} so the launcher binds nothing and the guest keeps
 * its own resolver — the runtime still works, just without DNS.</p>
 *
 * <p>Lifecycle refresh: while the supervised runtime is running,
 * {@link #startRefresh()} registers a default-network callback that rewrites
 * the same file when the active network or its DNS changes. Because the file
 * is bind-mounted (a reference, not a copy), glibc re-reads the updated
 * resolver in place. {@link #stopRefresh()} unregisters the callback. Both are
 * idempotent and defensive: a failure to register/unregister is logged and
 * never crashes the runtime — the worst case is a stale resolver until the
 * next guest start.</p>
 *
 * <p>Security: the file is app-private and contains only validated literal
 * nameserver lines (no secrets, no hostnames, no injected directives). It is
 * bound over the guest {@code /etc/resolv.conf} only; no host filesystem is
 * exposed broadly and the active rootfs is never mutated.</p>
 */
public final class GuestDnsResolver {

    private static final String TAG = "GuestDnsResolver";

    /** Fixed app-private resolver file, written and rewritten in place. */
    static final String RESOLV_CONF_NAME = "resolv.conf";
    /** Guest path the file is bound over. */
    public static final String GUEST_RESOLV_PATH = "/etc/resolv.conf";

    private final Context context;
    private final Path resolvFile;
    private final AndroidActiveNetworkDns source;
    private final Executor executor;
    private volatile ConnectivityManager.NetworkCallback callback;

    public GuestDnsResolver(Context context, Executor executor) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.resolvFile = this.context.getCacheDir().toPath().resolve(RESOLV_CONF_NAME);
        this.source = new AndroidActiveNetworkDns(this.context);
    }

    /**
     * Write the current active-network DNS to the fixed file and return the
     * guest bind, or {@code Optional#empty()} when there is no valid resolver.
     *
     * <p>Safe to call on every PRoot launch: it re-reads Android's current DNS
     * and atomically rewrites the file, so the setup, daemon, and generic
     * command paths all use a consistent, fresh resolver.</p>
     */
    public Optional<ProotBindMount> resolverBind() {
        try {
            List<String> servers = source.activeDnsServers();
            Path written = GuestResolvConfWriter.write(resolvFile, servers);
            if (written == null) {
                return Optional.empty();
            }
            return Optional.of(ProotBindMount.of(written.toString(), GUEST_RESOLV_PATH));
        } catch (Exception e) {
            Log.w(TAG, "could not write guest resolv.conf; the guest keeps its own resolver", e);
            return Optional.empty();
        }
    }

    /**
     * Start rewriting the resolver file when the active network changes.
     * Idempotent. Best-effort: a registration failure is logged and the runtime
     * continues with the resolver written at start time.
     */
    public synchronized void startRefresh() {
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

    /** Stop the network-change refresh. Idempotent. */
    public synchronized void stopRefresh() {
        ConnectivityManager.NetworkCallback cb = callback;
        callback = null;
        if (cb == null) {
            return;
        }
        ConnectivityManager cm = connectivityManager();
        if (cm == null) {
            return;
        }
        try {
            cm.unregisterNetworkCallback(cb);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not unregister default network callback", e);
        }
    }

    private void rewrite() {
        Executor ex = executor;
        if (ex == null) {
            tryWrite();
            return;
        }
        try {
            ex.execute(this::tryWrite);
        } catch (RejectedExecutionException ree) {
            // Executor shutting down: write on the callback thread as a fallback.
            tryWrite();
        }
    }

    private void tryWrite() {
        try {
            GuestResolvConfWriter.write(resolvFile, source.activeDnsServers());
        } catch (Exception e) {
            Log.w(TAG, "could not refresh guest resolv.conf", e);
        }
    }

    private ConnectivityManager connectivityManager() {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
}
