package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Typed pipeline start failure, Android-free.
 *
 * <p>The capture/encode pipeline throws this with the matching
 * {@link LiveMediaError} instead of leaking platform exceptions; the service
 * maps it to the bounded failed status the bridge reports.</p>
 */
public final class LiveMediaStartException extends Exception {
    private final LiveMediaError error;

    public LiveMediaStartException(LiveMediaError error, String detail) {
        super(detail);
        if (error == null) {
            throw new IllegalArgumentException("error must not be null");
        }
        this.error = error;
    }

    public LiveMediaError getError() {
        return error;
    }
}
