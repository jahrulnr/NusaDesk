package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Foreground location grant check, kept Android-free for testing.
 *
 * <p>Implementations resolve the current grant per request and must never
 * request background location, never open a permission activity, and never
 * claim a grant they did not observe.</p>
 */
public interface LocationPermissionChecker {
    /** Resolve the current foreground location grant. */
    LocationGrant check();
}
