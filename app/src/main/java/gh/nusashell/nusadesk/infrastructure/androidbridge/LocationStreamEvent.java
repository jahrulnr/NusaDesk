package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.Objects;

/**
 * Immutable event of the continuous location stream, kept Android-free for
 * testing.
 *
 * <p>Only {@link State#READING} carries a snapshot — a validated, finite
 * {@link LocationSnapshot} — so a consumer can never mistake a failure state
 * for a real fix. Permission, unavailability, timeout, and error states are
 * explicit value objects; {@link State#STOPPED} is the terminal event pushed
 * when a running session stops or closes, after which the session emits
 * nothing else until a new {@code start}. No coordinates are logged or
 * exposed beyond the reading snapshot itself.</p>
 */
public final class LocationStreamEvent {
    public enum State {
        /** A finite, complete platform fix was delivered. */
        READING,
        /** Location permission is absent and no denial is recorded. */
        PERMISSION_REQUIRED,
        /** Location permission is absent and the user previously denied it. */
        PERMISSION_DENIED,
        /** The location manager, provider, or device location switch is absent/disabled. */
        UNAVAILABLE,
        /** A poll observed no fix within the bounded fix-silence window (stream keeps listening). */
        TIMEOUT,
        /** The platform delivered an invalid fix or rejected the stream. */
        ERROR,
        /** The session was stopped or closed; terminal for the current session. */
        STOPPED
    }

    private final State state;
    private final LocationSnapshot snapshot;

    private LocationStreamEvent(State state, LocationSnapshot snapshot) {
        this.state = state;
        this.snapshot = snapshot;
    }

    /**
     * A validated finite fix.
     *
     * @param snapshot a {@link LocationSnapshot.State#READING} snapshot
     * @throws IllegalArgumentException when the snapshot is {@code null} or
     *                                  not a reading
     */
    public static LocationStreamEvent reading(LocationSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (snapshot.getState() != LocationSnapshot.State.READING) {
            throw new IllegalArgumentException("only a reading snapshot can be streamed");
        }
        return new LocationStreamEvent(State.READING, snapshot);
    }

    public static LocationStreamEvent permissionRequired() {
        return new LocationStreamEvent(State.PERMISSION_REQUIRED, null);
    }

    public static LocationStreamEvent permissionDenied() {
        return new LocationStreamEvent(State.PERMISSION_DENIED, null);
    }

    public static LocationStreamEvent unavailable() {
        return new LocationStreamEvent(State.UNAVAILABLE, null);
    }

    public static LocationStreamEvent timeout() {
        return new LocationStreamEvent(State.TIMEOUT, null);
    }

    public static LocationStreamEvent error() {
        return new LocationStreamEvent(State.ERROR, null);
    }

    public static LocationStreamEvent stopped() {
        return new LocationStreamEvent(State.STOPPED, null);
    }

    public State getState() {
        return state;
    }

    /** The validated fix, or {@code null} unless {@link State#READING}. */
    public LocationSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LocationStreamEvent)) {
            return false;
        }
        LocationStreamEvent that = (LocationStreamEvent) other;
        return state == that.state && Objects.equals(snapshot, that.snapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, snapshot);
    }
}
