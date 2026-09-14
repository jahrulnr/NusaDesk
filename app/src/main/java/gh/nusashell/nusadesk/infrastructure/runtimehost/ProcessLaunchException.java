package gh.nusashell.nusadesk.infrastructure.runtimehost;

/**
 * Typed failure to start the guest process.
 *
 * <p>Carries a short, secret-free message; it never includes credentials,
 * tokens, or full environment contents. The supervisor converts this into a
 * {@link gh.nusashell.nusadesk.domain.runtime.RuntimeState#FAILED} state
 * with a non-sensitive detail string.</p>
 */
public final class ProcessLaunchException extends Exception {
    public ProcessLaunchException(String message) {
        super(message);
    }

    public ProcessLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
