package gh.nusashell.nusadesk.application.terminal;

/**
 * Expected failure at the launcher terminal-command-app boundary.
 *
 * <p>The reason is machine-readable so the add/edit form can attach the message
 * to the field the user has to fix instead of parsing English text. Every reason
 * is reachable from a user action today.</p>
 */
public final class TerminalCommandRegistryException extends Exception {
    /** What the caller has to correct. */
    public enum Reason {
        /** The display name is missing or longer than the accepted maximum. */
        INVALID_NAME,
        /** The icon token is present but is not a usable {@code content://} URI. */
        INVALID_ICON_URI,
        /** The command is missing, too long, or contains a control character. */
        INVALID_COMMAND,
        /** The id does not match any registered terminal-command app. */
        UNKNOWN_APP,
        /** The record could not be written to app-private storage. */
        STORAGE_FAILURE
    }

    private final Reason reason;

    public TerminalCommandRegistryException(Reason reason, String message) {
        super(message);
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
