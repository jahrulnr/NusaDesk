package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistryException;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistryException;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;

/**
 * Where an add/edit app failure belongs on the form.
 *
 * <p>Both registries publish a machine-readable reason, so the form can attach
 * the message to the field the user has to fix instead of printing one line at
 * the bottom and making them guess. The two kinds share the name and image
 * fields, so their reasons map onto the same strings; the kind-specific field
 * (port or command) and the form-level reasons each use their own kind's
 * wording. This mapping is pure so it can be asserted without a device, and it
 * is exhaustive over both {@code Reason} enums so a new reason cannot silently
 * render nowhere.</p>
 */
public final class AppFormError {

    /** The form field a failure belongs to. {@code FORM} means "not one field". */
    public enum Field { NAME, IMAGE, PORT, COMMAND, FORM }

    private final Field field;
    private final int messageRes;
    private final int intArgument;
    private final String detail;

    private AppFormError(Field field, int messageRes, int intArgument, String detail) {
        this.field = field;
        this.messageRes = messageRes;
        this.intArgument = intArgument;
        this.detail = detail;
    }

    /**
     * Maps a web-app registry failure to a field and a message.
     *
     * @param failure   the expected failure the registry threw
     * @param typedPort the port the user typed, or any sentinel when the form had
     *                  no port to report; used only for the port-in-use message
     */
    public static AppFormError from(WebAppRegistryException failure, int typedPort) {
        switch (failure.getReason()) {
            case INVALID_NAME:
                return name();
            case INVALID_ICON_URI:
                return image();
            case INVALID_PORT:
                return new AppFormError(Field.PORT, R.string.webapp_error_port, -1, null);
            case PORT_RESERVED:
                return new AppFormError(
                        Field.PORT, R.string.webapp_error_port_reserved, -1, null);
            case PORT_IN_USE:
                return new AppFormError(
                        Field.PORT, R.string.webapp_error_port_in_use, typedPort, null);
            case UNKNOWN_APP:
                return new AppFormError(
                        Field.FORM, R.string.webapp_error_unknown_app, -1, null);
            case STORAGE_FAILURE:
            default:
                return storage(failure.getMessage(), R.string.webapp_error_storage);
        }
    }

    /**
     * Maps a terminal-command registry failure to a field and a message. No port
     * argument exists here; the one message that needs a number reports the
     * domain's command-length limit, not anything the user typed.
     */
    public static AppFormError from(TerminalCommandRegistryException failure) {
        switch (failure.getReason()) {
            case INVALID_NAME:
                return name();
            case INVALID_ICON_URI:
                return image();
            case INVALID_COMMAND:
                return new AppFormError(Field.COMMAND, R.string.terminal_error_command,
                        TerminalCommand.MAX_LENGTH, null);
            case UNKNOWN_APP:
                return new AppFormError(
                        Field.FORM, R.string.terminal_error_unknown_app, -1, null);
            case STORAGE_FAILURE:
            default:
                return storage(failure.getMessage(), R.string.terminal_error_storage);
        }
    }

    // The name and image rules are identical for both kinds, so their failures
    // share one mapping each.
    private static AppFormError name() {
        return new AppFormError(Field.NAME, R.string.webapp_error_name, -1, null);
    }

    private static AppFormError image() {
        return new AppFormError(Field.IMAGE, R.string.webapp_error_image, -1, null);
    }

    private static AppFormError storage(String reason, int messageRes) {
        return new AppFormError(Field.FORM, messageRes, -1,
                reason == null || reason.trim().isEmpty() ? "" : reason);
    }

    public Field getField() {
        return field;
    }

    public int getMessageRes() {
        return messageRes;
    }

    /**
     * True when the message needs a {@code %1$d} argument: the port the user
     * typed for port-in-use, or the command-length limit for invalid-command.
     */
    public boolean hasIntArgument() {
        return intArgument >= 0;
    }

    public int getIntArgument() {
        return intArgument;
    }

    /** True when the message needs the registry's own non-secret detail. */
    public boolean hasDetail() {
        return detail != null;
    }

    public String getDetail() {
        return detail;
    }
}
