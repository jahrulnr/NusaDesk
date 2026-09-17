package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one Android location snapshot, kept Android-free for testing.
 *
 * <p>Implementations must return within their bounded read window and must
 * never fabricate a {@link LocationSnapshot.State#READING} fix: a missing
 * grant, provider, or fix is reported as its explicit state. The source
 * checks the foreground location grant per read and never requests background
 * location. The session owner closes the concrete adapter (its
 * {@code close()} path) when the bridge session ends.</p>
 */
public interface LocationSource {
    /** Read one bounded one-shot location snapshot. */
    LocationSnapshot read();
}
