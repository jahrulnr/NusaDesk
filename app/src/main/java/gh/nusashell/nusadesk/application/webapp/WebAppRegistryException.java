package gh.nusashell.nusadesk.application.webapp;

/**
 * Expected failure at the launcher web-app boundary.
 *
 * <p>The reason is machine-readable so the add/edit form can attach the message
 * to the field the user has to fix instead of parsing English text. Every reason
 * is reachable from a user action today.</p>
 */
public class WebAppRegistryException extends Exception {
    /** What the caller has to correct. */
    public enum Reason {
        /** The display name is missing or longer than the accepted maximum. */
        INVALID_NAME,
        /** The icon token is present but is not a usable {@code content://} URI. */
        INVALID_ICON_URI,
        /** The port is not a TCP port number. */
        INVALID_PORT,
        /** The port belongs to a guest service, not to a user-defined web app. */
        PORT_RESERVED,
        /** Another registered web app already owns the port. */
        PORT_IN_USE,
        /** The id does not match any registered web app. */
        UNKNOWN_APP,
        /** The record could not be written to app-private storage. */
        STORAGE_FAILURE
    }

    private final Reason reason;

    public WebAppRegistryException(Reason reason, String message) {
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
