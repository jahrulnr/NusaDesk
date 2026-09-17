package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Source of one Android sensor snapshot, kept Android-free for testing.
 *
 * <p>Implementations must return within their bounded read window and must
 * never fabricate a {@link SensorReading.State#READING} snapshot: a missing
 * sensor, timeout, or platform failure is reported as its explicit state.</p>
 */
public interface SensorReadingSource {
    /** Read one bounded snapshot of the requested sensor kind. */
    SensorReading read(SensorKind kind);
}
