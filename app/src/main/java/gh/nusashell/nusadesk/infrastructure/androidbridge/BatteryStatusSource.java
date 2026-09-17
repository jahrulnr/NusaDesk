package gh.nusashell.nusadesk.infrastructure.androidbridge;

/** Source of one Android capability snapshot, kept Android-free for testing. */
public interface BatteryStatusSource {
    /** Read the current battery state; return {@link BatteryStatus#unavailable()} when absent. */
    BatteryStatus read();
}
