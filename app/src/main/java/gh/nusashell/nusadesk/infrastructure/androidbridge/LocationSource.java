package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one Android location snapshot, kept Android-free for testing.
 *
 * <p>Implementations must return within their bounded read window and must
 * never fabricate a {@link LocationSnapshot.State#READING} fix: a missing
 * grant, provider, or fix is reported as its explicit state. A read may
 * answer a real last-known platform fix marked {@link
 * LocationSnapshot#isStale() stale} when no fresh fix arrived — cached
 * platform data, never invented coordinates. The source checks the
 * foreground location grant per read and never requests background
 * location. The session owner closes the concrete adapter (its
 * {@code close()} path) when the bridge session ends.</p>
 */
public interface LocationSource {
    /** Read one bounded one-shot location snapshot. */
    LocationSnapshot read();

    /**
     * Read one snapshot honoring an explicit provider and request mode:
     * {@code provider} is {@code gps}, {@code network}, or {@code passive}
     * to force that provider, or {@code null} for automatic selection;
     * {@code lastOnly} answers the freshest last-known fix the grant covers
     * without registering for a fresh one. The default delegates to
     * {@link #read()} so a source that cannot honor the hint still answers a
     * bounded read.
     */
    default LocationSnapshot read(String provider, boolean lastOnly) {
        return read();
    }
}
