package gh.nusashell.nusadesk.infrastructure.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the active network's DNS servers from Android
 * {@link ConnectivityManager#getLinkProperties(Network)}.
 *
 * <p>This is the only class that touches the Android connectivity API for the
 * resolver. It returns raw IP-literal candidates (validated later by
 * {@link gh.nusashell.nusadesk.domain.network.ResolvConf}); it never
 * hardcodes a public DNS fallback, so the guest uses the network Android
 * actually routes on. When there is no active network, no LinkProperties, or
 * no DNS servers, it returns an empty list and the guest keeps its own
 * resolver (graceful, never a fake endpoint).</p>
 *
 * <p>Requires {@code ACCESS_NETWORK_STATE} (a normal, non-dangerous permission)
 * to read the active network and its LinkProperties. It performs no network
 * I/O of its own.</p>
 */
public final class AndroidActiveNetworkDns {

    private final ConnectivityManager connectivity;

    public AndroidActiveNetworkDns(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.connectivity = (ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * @return the active network's DNS server IP literals (scope IDs
     *         stripped), possibly empty; never null
     */
    public List<String> activeDnsServers() {
        if (connectivity == null) {
            return Collections.emptyList();
        }
        try {
            Network active = connectivity.getActiveNetwork();
            if (active == null) {
                return Collections.emptyList();
            }
            LinkProperties properties = connectivity.getLinkProperties(active);
            if (properties == null) {
                return Collections.emptyList();
            }
            List<InetAddress> servers = properties.getDnsServers();
            if (servers == null || servers.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> literals = new ArrayList<>(servers.size());
            for (InetAddress server : servers) {
                if (server == null) {
                    continue;
                }
                String hostAddress = server.getHostAddress();
                if (hostAddress == null) {
                    continue;
                }
                // Inet6Address.getHostAddress() may append a scope id
                // ("fe80::1%wlan0"); a resolver file cannot carry a scope, so
                // strip it. ResolvConf re-validates the literal.
                int scope = hostAddress.indexOf('%');
                if (scope >= 0) {
                    hostAddress = hostAddress.substring(0, scope);
                }
                literals.add(hostAddress);
            }
            return literals;
        } catch (RuntimeException e) {
            // ACCESS_NETWORK_STATE missing or the platform refused: degrade
            // gracefully rather than crash the runtime start.
            return Collections.emptyList();
        }
    }
}
