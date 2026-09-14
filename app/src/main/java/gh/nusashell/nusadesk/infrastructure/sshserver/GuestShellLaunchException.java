package gh.nusashell.nusadesk.infrastructure.sshserver;

/**
 * Typed failure to launch the fixed curated guest shell.
 *
 * <p>Carries no secret material. The message is safe to surface as a non-{@code
 * RUNNING} detail; it must never include credentials, host-key bytes, or
 * process arguments that could leak a secret (AGENTS.md).</p>
 */
public final class GuestShellLaunchException extends Exception {

    public GuestShellLaunchException(String message) {
        super(message);
    }

    public GuestShellLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
