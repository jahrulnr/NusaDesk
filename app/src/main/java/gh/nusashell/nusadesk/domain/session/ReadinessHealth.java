package gh.nusashell.nusadesk.domain.session;

/**
 * Health reported by the guest runtime inside a readiness frame.
 *
 * <p>Readiness means the advertised health check succeeds, so only
 * {@link #HEALTHY} authorises the host to expose the WebView. A structurally
 * valid frame can still be non-ready when the guest reports degradation.</p>
 */
public enum ReadinessHealth {
    HEALTHY,
    DEGRADED,
    UNHEALTHY
}
