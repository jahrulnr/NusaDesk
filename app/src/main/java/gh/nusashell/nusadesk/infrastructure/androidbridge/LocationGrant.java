package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Foreground location grant level resolved per request, kept Android-free for
 * testing.
 *
 * <p>Only foreground state is modeled: this capability never requests,
 * claims, or tracks background location. {@link #DENIED} records that the
 * user previously refused the grant, while {@link #REQUIRED} means no grant
 * exists and no denial is recorded (the later user-visible consent slice may
 * ask).</p>
 */
public enum LocationGrant {
    /** Fine (and therefore coarse) location is granted. */
    FINE,
    /** Only coarse location is granted. */
    COARSE_ONLY,
    /** No grant exists and the user previously denied it. */
    DENIED,
    /** No grant exists and no denial is recorded; a consent flow may ask. */
    REQUIRED
}
