package gh.nusashell.nusadesk.infrastructure.ssh;

/**
 * Immutable, bounded reconnect policy for {@link SshClientBridge}.
 *
 * <p>Reconnect is intentionally bounded: a finite maximum number of attempts
 * with exponential backoff capped at {@code maxDelayMillis}. This prevents
 * unbounded retry loops that would drain battery or spin forever against an
 * unreachable host. The bridge consults {@link #shouldRetry(int)} before each
 * attempt and {@link #delayForAttempt(int)} to sleep.</p>
 *
 * <p>Attempt numbering starts at 1. The first failed session maps to attempt 1.
 * Backoff is {@code baseDelayMillis * 2^(attempt-1)}, capped at
 * {@code maxDelayMillis}.</p>
 */
public final class SshReconnectPolicy {
    /** A conservative default: 5 attempts, 500ms base, 8s cap. */
    public static final SshReconnectPolicy DEFAULT = new SshReconnectPolicy(5, 500L, 8_000L);

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public SshReconnectPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must not be negative");
        }
        if (baseDelayMillis < 0) {
            throw new IllegalArgumentException("baseDelayMillis must not be negative");
        }
        if (maxDelayMillis < 0) {
            throw new IllegalArgumentException("maxDelayMillis must not be negative");
        }
        if (maxDelayMillis < baseDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must not be less than baseDelayMillis");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getBaseDelayMillis() {
        return baseDelayMillis;
    }

    public long getMaxDelayMillis() {
        return maxDelayMillis;
    }

    /**
     * Whether a reconnect attempt should be made after {@code attempt} prior
     * failures. Attempts are 1-based; an attempt equal to {@code maxAttempts}
     * is the last allowed try.
     */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }

    /**
     * Delay to wait before the next attempt, given {@code attempt} prior
     * failures (1-based). Exponential backoff capped at {@code maxDelayMillis}.
     * A base delay of zero yields zero delay (used by tests).
     */
    public long delayForAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        if (baseDelayMillis == 0L) {
            return 0L;
        }
        long shift = attempt - 1;
        if (shift >= 30) {
            return maxDelayMillis;
        }
        long raw = baseDelayMillis << shift;
        return Math.min(raw, maxDelayMillis);
    }
}
