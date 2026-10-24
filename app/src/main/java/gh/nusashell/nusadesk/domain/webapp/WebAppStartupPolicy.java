package gh.nusashell.nusadesk.domain.webapp;

/** Startup grace belongs only to apps that launch a server command. */
public final class WebAppStartupPolicy {
    public static final long WAIT_MILLIS = 30_000;
    public static final long RETRY_MILLIS = 200;
    private WebAppStartupPolicy() { }
    public static boolean shouldRetry(boolean launchesCommand, boolean reachable, long elapsedMillis) {
        return launchesCommand && !reachable && elapsedMillis < WAIT_MILLIS;
    }
}
