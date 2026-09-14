package gh.nusashell.nusadesk.infrastructure.proot;

/**
 * Typed failure to build a PRoot launch specification or start the PRoot process.
 *
 * <p>Carries a short, secret-free message; it never includes credentials,
 * tokens, host keys, or full environment contents. Callers convert this into
 * a {@link gh.nusashell.nusadesk.domain.runtime.RuntimeState#FAILED}
 * state with a non-sensitive detail string.</p>
 */
public final class ProotLaunchException extends Exception {
    public ProotLaunchException(String message) {
        super(message);
    }

    public ProotLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
