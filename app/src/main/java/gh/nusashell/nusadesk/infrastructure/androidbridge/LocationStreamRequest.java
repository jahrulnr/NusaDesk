package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.Objects;

/**
 * Immutable configuration of one continuous location stream session, kept
 * Android-free for testing.
 *
 * <p>The interval is configurable but capped: the constructor clamps it with
 * {@link LocationStreamIntervalPolicy#clampInterval(long)} and
 * {@link #getIntervalMillis()} returns the effective, clamped value. The
 * accuracy is an explicit foreground selection ({@link Accuracy#FINE} or
 * {@link Accuracy#COARSE}); the adapter never assumes a level the user did
 * not request, never requests background location, and never upgrades a
 * coarse grant. There is no provider, URI, or arbitrary string input: the
 * adapter maps the requested accuracy onto its fixed, bounded providers.</p>
 */
public final class LocationStreamRequest {
    public enum Accuracy {
        /** Best available foreground fix (GPS preferred, network fallback). */
        FINE,
        /** Foreground fix from the network provider only. */
        COARSE
    }

    private final long intervalMillis;
    private final Accuracy accuracy;

    private LocationStreamRequest(long intervalMillis, Accuracy accuracy) {
        this.intervalMillis = intervalMillis;
        this.accuracy = accuracy;
    }

    /**
     * Build a bounded stream request.
     *
     * @param intervalMillis requested update interval; clamped into the
     *                       policy bounds
     * @param accuracy       explicit foreground accuracy selection
     * @throws IllegalArgumentException when {@code accuracy} is {@code null}
     */
    public static LocationStreamRequest create(long intervalMillis, Accuracy accuracy) {
        if (accuracy == null) {
            throw new IllegalArgumentException("accuracy must not be null");
        }
        return new LocationStreamRequest(
                LocationStreamIntervalPolicy.clampInterval(intervalMillis), accuracy);
    }

    /** Effective update interval, clamped into the policy bounds. */
    public long getIntervalMillis() {
        return intervalMillis;
    }

    /** Explicit foreground accuracy selection. */
    public Accuracy getAccuracy() {
        return accuracy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LocationStreamRequest)) {
            return false;
        }
        LocationStreamRequest that = (LocationStreamRequest) other;
        return intervalMillis == that.intervalMillis && accuracy == that.accuracy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(intervalMillis, accuracy);
    }
}
