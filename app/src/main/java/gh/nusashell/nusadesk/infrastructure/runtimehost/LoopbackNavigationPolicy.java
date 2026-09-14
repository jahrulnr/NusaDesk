package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure-Java navigation policy that classifies a URL against one exact owned
 * loopback origin.
 *
 * <p>The Android {@code WebViewClient} delegates to this so the navigation
 * decision (the security-relevant part) is deterministic and unit-testable
 * without an Android runtime. The policy enforces the loopback WebView
 * boundary from {@code AGENTS.md} and the {@code android-webview-hosting}
 * skill:</p>
 *
 * <ul>
 *   <li>Only the exact owned origin (scheme + host + port) loads in the WebView.</li>
 *   <li>A different loopback port is a different origin and is blocked, not sent
 *       to the system browser, so an unowned local listener cannot be reached.</li>
 *   <li>External HTTP(S) links (non-loopback hosts) leave the WebView for the
 *       system browser.</li>
 *   <li>A small documented set of intent schemes (mailto, tel, sms, geo, market,
 *       intent) is routed externally so the runtime UI can hand off to other apps.</li>
 *   <li>{@code file}, {@code content}, {@code javascript}, {@code blob},
 *       {@code data} and other {@code about:*} URIs are blocked.</li>
 *   <li>{@code about:blank} is allowed so the WebView's own initial blank page is
 *       not rejected.</li>
 * </ul>
 *
 * <p>This class has no Android dependency and performs no I/O.</p>
 */
public final class LoopbackNavigationPolicy {
    /** Host classification result returned by {@link #classify(String)}. */
    public enum Decision {
        /** Load inside the WebView (the exact owned origin or about:blank). */
        OWNED,
        /** Hand the URL to the system browser / external app. */
        EXTERNAL,
        /** Do not load and do not hand off; report a blocked navigation. */
        BLOCKED
    }

    private static final Set<String> LOOPBACK_HOSTS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "127.0.0.1", "localhost", "::1")));

    private static final Set<String> EXTERNAL_INTENT_SCHEMES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "mailto", "tel", "sms", "geo", "market", "intent")));

    private final String ownedScheme;
    private final String ownedHost;
    private final int ownedPort;
    private final String ownedOrigin;

    /**
     * @param ownedOriginUrl absolute {@code http(s)://loopbackHost:port} URL that the
     *                       WebView is allowed to load; must be loopback with an
     *                       explicit port
     */
    public LoopbackNavigationPolicy(String ownedOriginUrl) {
        if (ownedOriginUrl == null) {
            throw new IllegalArgumentException("ownedOriginUrl must not be null");
        }
        URI uri = parseQuietly(ownedOriginUrl);
        if (uri == null || uri.getScheme() == null
                || uri.getHost() == null || uri.getPort() == -1) {
            throw new IllegalArgumentException(
                    "owned origin must be an absolute http(s) loopback URL with explicit port: "
                            + ownedOriginUrl);
        }
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("owned origin scheme must be http or https");
        }
        String host = normalizeHost(uri.getHost());
        if (!isLoopback(host)) {
            throw new IllegalArgumentException("owned origin must be a loopback host");
        }
        this.ownedScheme = scheme;
        this.ownedHost = host;
        this.ownedPort = uri.getPort();
        this.ownedOrigin = scheme + "://" + host + ":" + ownedPort;
    }

    /** The exact origin string this policy allows inside the WebView. */
    public String getOwnedOrigin() {
        return ownedOrigin;
    }

    /** Classify a raw URL string. Never throws; unparseable URLs are {@link Decision#BLOCKED}. */
    public Decision classify(String url) {
        if (url == null) {
            return Decision.BLOCKED;
        }
        URI uri = parseQuietly(url);
        if (uri == null || uri.getScheme() == null) {
            return Decision.BLOCKED;
        }
        String scheme = uri.getScheme();

        if ("about".equals(scheme)) {
            return "blank".equals(uri.getSchemeSpecificPart()) ? Decision.OWNED : Decision.BLOCKED;
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = normalizeHost(uri.getHost());
            if (host != null && isLoopback(host)) {
                if (scheme.equals(ownedScheme) && host.equals(ownedHost)
                        && uri.getPort() == ownedPort) {
                    return Decision.OWNED;
                }
                // A loopback origin we do not own: never hand it to the system browser.
                return Decision.BLOCKED;
            }
            return Decision.EXTERNAL;
        }
        if (EXTERNAL_INTENT_SCHEMES.contains(scheme)) {
            return Decision.EXTERNAL;
        }
        return Decision.BLOCKED;
    }

    private static boolean isLoopback(String host) {
        return host != null && LOOPBACK_HOSTS.contains(host);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static URI parseQuietly(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
